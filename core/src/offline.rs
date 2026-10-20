//! Songs kept on the phone: librespot's encrypted audio cache, sized by the
//! user and filled ahead of time, so songs start at once and keep playing
//! on weak connections. Each one's metadata and audio key are kept beside
//! it, so a song on the phone plays with no connection at all.

use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::Duration;

use anyhow::{anyhow, Result};
use librespot_audio::AudioFile;
use librespot_core::cache::Cache;
use librespot_core::session::Session;
use librespot_core::{FileId, SpotifyUri};
use librespot_metadata::Metadata;
use librespot_metadata::audio::AudioItem;

use crate::sound::AudioQuality;

/// Audio cache size when the user has not chosen one.
pub const DEFAULT_LIMIT: u64 = 2 * 1024 * 1024 * 1024;

/// How much the audio cache holds and may hold.
#[derive(Debug, Clone, uniffi::Record)]
pub struct CacheInfo {
    pub used_bytes: u64,
    pub limit_bytes: u64,
    /// Of `used_bytes`, the songs of kept playlists and albums: never pruned.
    pub kept_bytes: u64,
    /// The pages' data kept for showing them at once and offline.
    pub data_bytes: u64,
}

pub struct Offline {
    dir: PathBuf,
    /// The quality downloads pick, so a kept song is the file that plays.
    quality: Mutex<AudioQuality>,
    /// Song URI → the audio file it plays from (base16), for that quality,
    /// so checking what is on the phone needs no network.
    files: Mutex<HashMap<String, String>>,
    /// The songs of kept playlists and albums: pinned in the audio cache.
    kept: Mutex<HashSet<String>>,
}

impl Offline {
    pub fn new(cache_dir: &Path, quality: AudioQuality) -> Self {
        let kept = std::fs::read(cache_dir.join(KEPT))
            .ok()
            .and_then(|bytes| serde_json::from_slice(&bytes).ok())
            .unwrap_or_default();
        Self {
            dir: cache_dir.to_owned(),
            quality: Mutex::new(quality.downloading()),
            files: Mutex::new(load_files(cache_dir, quality)),
            kept: Mutex::new(kept),
        }
    }

    pub fn audio_dir(&self) -> PathBuf {
        self.dir.join("audio")
    }

    /// Song metadata and audio keys, for playing kept songs offline.
    pub fn data_dir(&self) -> PathBuf {
        self.dir.join("offline")
    }

    pub fn limit(&self) -> u64 {
        std::fs::read_to_string(self.dir.join("audio_limit"))
            .ok()
            .and_then(|text| text.trim().parse().ok())
            .unwrap_or(DEFAULT_LIMIT)
    }

    /// Kept for the next start; [Cache::set_size_limit] applies it now.
    pub fn set_limit(&self, bytes: u64) {
        let _ = std::fs::write(self.dir.join("audio_limit"), bytes.to_string());
    }

    /// Downloads from now on pick this quality's files.
    pub fn set_quality(&self, quality: AudioQuality) {
        *self.quality.lock().unwrap() = quality.downloading();
        *self.files.lock().unwrap() = load_files(&self.dir, quality);
    }

    pub fn info(&self, cache: Option<&Cache>) -> CacheInfo {
        let (used_bytes, kept_bytes) = cache
            .and_then(Cache::audio_usage)
            .unwrap_or_else(|| (dir_size(&self.audio_dir()), 0));
        CacheInfo {
            used_bytes,
            limit_bytes: self.limit(),
            kept_bytes,
            data_bytes: dir_size(&self.dir.join("webapi").join("cache")),
        }
    }

    pub fn clear(&self, cache: Option<&Cache>) {
        match cache {
            Some(cache) => {
                if let Err(e) = cache.clear_audio() {
                    log::warn!("audio cache not cleared: {e}");
                }
            }
            None => {
                let dir = self.audio_dir();
                let _ = std::fs::remove_dir_all(&dir);
                let _ = std::fs::create_dir_all(&dir);
            }
        }
    }

    /// The songs to keep on the phone whatever the size limit.
    pub fn set_kept(&self, uris: Vec<String>) {
        let kept: HashSet<String> = uris.into_iter().collect();
        if let Ok(json) = serde_json::to_vec(&kept) {
            let _ = std::fs::write(self.dir.join(KEPT), json);
        }
        *self.kept.lock().unwrap() = kept;
    }

    /// The files of the kept songs, in either quality: one downloaded before
    /// the quality changed is still a kept song.
    pub fn kept_files(&self) -> Vec<FileId> {
        let kept = self.kept.lock().unwrap();
        [AudioQuality::Normal, AudioQuality::High]
            .into_iter()
            .flat_map(|quality| load_files(&self.dir, quality))
            .filter(|(uri, _)| kept.contains(uri))
            .filter_map(|(_, hex)| file_id(&hex))
            .collect()
    }

    fn known_file(&self, uri: &str) -> Option<FileId> {
        let hex = self.files.lock().unwrap().get(uri).cloned()?;
        file_id(&hex)
    }

    fn remember(&self, uri: &str, file: FileId) {
        let quality = *self.quality.lock().unwrap();
        let mut files = self.files.lock().unwrap();
        files.insert(uri.to_owned(), file.to_base16());
        if let Ok(json) = serde_json::to_vec(&*files) {
            let _ = std::fs::write(self.dir.join(files_name(quality)), json);
        }
    }

    /// How many of `uris` are on the phone, from what is known locally.
    pub fn cached_count(&self, session: &Session, uris: &[String]) -> u32 {
        let Some(cache) = session.cache() else { return 0 };
        uris.iter()
            .filter_map(|uri| self.known_file(uri))
            .filter(|&file| cache.file_path(file).is_some_and(|path| path.exists()))
            .count() as u32
    }

    /// The file `uri` plays from, asking Spotify the first time. Reading the
    /// song's metadata keeps it in the cache, for playing it offline.
    async fn file_for(&self, session: &Session, uri: &SpotifyUri) -> Result<FileId> {
        let key = uri.to_uri();
        let kept_meta = session.cache().is_some_and(|cache| cache.metadata(&key).is_some());
        if let Some(file) = self.known_file(&key).filter(|_| kept_meta) {
            return Ok(file);
        }
        let item = AudioItem::get_file(session, uri.clone()).await.map_err(|e| anyhow!("{e}"))?;
        let quality = *self.quality.lock().unwrap();
        let file = quality
            .formats()
            .iter()
            .find_map(|format| item.files.get(format).copied())
            .ok_or_else(|| anyhow!("sem arquivo de áudio para {key}"))?;
        self.remember(&key, file);
        Ok(file)
    }

    /// Put `uri` in the audio cache, with what playing it offline needs (its
    /// metadata and audio key); true if the audio was downloaded now.
    pub async fn download(&self, session: &Session, uri: &str) -> Result<bool> {
        let parsed = SpotifyUri::from_uri(uri).map_err(|e| anyhow!("{e}"))?;
        let file = self.file_for(session, &parsed).await?;
        if let SpotifyUri::Track { id } = &parsed {
            // Kept on disk by the key manager: asked once per song.
            session.audio_key().request(*id, file).await.map_err(|e| anyhow!("{e}"))?;
        }
        let cache = session.cache().ok_or_else(|| anyhow!("sem cache de áudio"))?;
        let path = cache.file_path(file).ok_or_else(|| anyhow!("sem cache de áudio"))?;
        if path.exists() {
            return Ok(false);
        }
        let mut audio = AudioFile::open(session, file, 40 * 1024).await.map_err(|e| anyhow!("{e}"))?;
        if audio.is_cached() {
            return Ok(false);
        }
        // Reading it all completes the download, and librespot then saves
        // the (still encrypted) file to the cache.
        tokio::task::spawn_blocking(move || std::io::copy(&mut audio, &mut std::io::sink())).await??;
        for _ in 0..50 {
            if path.exists() {
                return Ok(true);
            }
            tokio::time::sleep(Duration::from_millis(200)).await;
        }
        Err(anyhow!("download de {uri} não foi salvo"))
    }
}

/// Whether `uri` can play with no connection: its metadata, one of its
/// audio files and that file's key are all on the phone. `session` is one
/// that never connected, so the metadata comes from the cache.
pub async fn playable_offline(session: &Session, uri: &str) -> bool {
    let (Some(cache), Ok(parsed)) = (session.cache(), SpotifyUri::from_uri(uri)) else {
        return false;
    };
    let Ok(track) = librespot_metadata::Track::get(session, &parsed).await else {
        return false;
    };
    track.files.values().any(|&file| {
        cache.audio_key(file).is_some() && cache.file_path(file).is_some_and(|path| path.exists())
    })
}

/// Where the kept songs are listed.
const KEPT: &str = "kept_songs.json";

fn load_files(dir: &Path, quality: AudioQuality) -> HashMap<String, String> {
    std::fs::read(dir.join(files_name(quality)))
        .ok()
        .and_then(|bytes| serde_json::from_slice(&bytes).ok())
        .unwrap_or_default()
}

/// Each quality plays other files, so each keeps its own song → file map.
fn files_name(quality: AudioQuality) -> &'static str {
    match quality.downloading() {
        AudioQuality::Normal => "audio_files.json",
        _ => "audio_files_320.json",
    }
}

fn file_id(hex: &str) -> Option<FileId> {
    if hex.len() != 40 {
        return None;
    }
    let bytes: Option<Vec<u8>> = (0..40)
        .step_by(2)
        .map(|i| u8::from_str_radix(&hex[i..i + 2], 16).ok())
        .collect();
    Some(FileId::from(bytes?.as_slice()))
}

fn dir_size(dir: &Path) -> u64 {
    let Ok(entries) = std::fs::read_dir(dir) else { return 0 };
    entries
        .flatten()
        .map(|entry| match entry.metadata() {
            Ok(meta) if meta.is_dir() => dir_size(&entry.path()),
            Ok(meta) => meta.len(),
            Err(_) => 0,
        })
        .sum()
}
