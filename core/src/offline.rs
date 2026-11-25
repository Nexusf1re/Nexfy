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
    /// When the last audio key was asked, and until when none is: Spotify
    /// refuses keys asked in a burst.
    keys: Mutex<KeyPace>,
    /// Songs with no file this account can play (removed, or not in its
    /// country), and when that was seen: not asked again for a while, and
    /// not counted as missing from a kept list.
    unavailable: Mutex<HashMap<String, u64>>,
}

#[derive(Default)]
struct KeyPace {
    last: Option<std::time::Instant>,
    refused_until: Option<std::time::Instant>,
}

impl Offline {
    pub fn new(cache_dir: &Path, quality: AudioQuality) -> Self {
        let kept = read_json(&cache_dir.join(KEPT));
        Self {
            dir: cache_dir.to_owned(),
            quality: Mutex::new(quality.downloading()),
            files: Mutex::new(load_files(cache_dir, quality)),
            kept: Mutex::new(kept),
            keys: Mutex::default(),
            unavailable: Mutex::new(read_json(&cache_dir.join(UNAVAILABLE))),
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

    /// How many of `uris` no file can play for this account: a kept list
    /// is complete without them.
    pub fn unavailable_count(&self, uris: &[String]) -> u32 {
        let unavailable = self.unavailable.lock().unwrap();
        uris.iter().filter(|uri| unavailable.contains_key(*uri)).count() as u32
    }

    /// Whether `uri` was found unplayable lately: not asked again yet.
    fn known_unavailable(&self, uri: &str) -> bool {
        let seen = self.unavailable.lock().unwrap().get(uri).copied();
        seen.is_some_and(|at| now_secs().saturating_sub(at) < UNAVAILABLE_RECHECK.as_secs())
    }

    fn mark_unavailable(&self, uri: &str, unavailable: bool) {
        let mut all = self.unavailable.lock().unwrap();
        let changed = if unavailable {
            all.insert(uri.to_owned(), now_secs());
            true
        } else {
            all.remove(uri).is_some()
        };
        if changed {
            if let Ok(json) = serde_json::to_vec(&*all) {
                let _ = std::fs::write(self.dir.join(UNAVAILABLE), json);
            }
        }
    }

    /// The file `uri` plays from and the song it belongs to, asking Spotify
    /// the first time; None when no file can play for this account. A song
    /// relinked to another release plays that one's file, as the player
    /// does. Reading the songs' metadata keeps it in the cache, for playing
    /// them offline.
    async fn file_for(&self, session: &Session, uri: &SpotifyUri) -> Result<Option<(FileId, SpotifyUri)>> {
        let key = uri.to_uri();
        let cache = session.cache();
        let kept_meta = cache.is_some_and(|cache| cache.metadata(&key).is_some());
        if let Some(file) = self.known_file(&key).filter(|_| kept_meta) {
            // With its key there, nothing else is needed; without, the song
            // the file belongs to is found again below, for asking the key.
            if cache.is_some_and(|cache| cache.audio_key(file).is_some()) {
                return Ok(Some((file, uri.clone())));
            }
        }
        let item = match AudioItem::get_file(session, uri.clone()).await {
            Ok(item) => item,
            Err(e) if e.kind == librespot_core::error::ErrorKind::Unavailable => return Ok(None),
            Err(e) => return Err(anyhow!("{e}")),
        };
        let quality = *self.quality.lock().unwrap();
        let pick = |item: &AudioItem| quality.formats().iter().find_map(|format| item.files.get(format).copied());
        let mut found = pick(&item).filter(|_| item.availability.is_ok()).map(|file| (file, uri.clone()));
        if found.is_none() && item.availability.is_ok() {
            for alternative in item.alternatives.iter().flat_map(|alternatives| alternatives.0.iter()) {
                let Ok(other) = AudioItem::get_file(session, alternative.clone()).await else { continue };
                if other.availability.is_ok() {
                    if let Some(file) = pick(&other) {
                        found = Some((file, alternative.clone()));
                        break;
                    }
                }
            }
        }
        if let Some((file, _)) = &found {
            self.remember(&key, *file);
        }
        Ok(found)
    }

    /// Put `uri` in the audio cache, with what playing it offline needs (its
    /// metadata and audio key); true if the audio was downloaded now.
    pub async fn download(&self, session: &Session, uri: &str) -> Result<bool> {
        if self.known_unavailable(uri) {
            return Ok(false);
        }
        let parsed = SpotifyUri::from_uri(uri).map_err(|e| anyhow!("{e}"))?;
        let Some((file, owner)) = self.file_for(session, &parsed).await? else {
            self.mark_unavailable(uri, true);
            return Err(anyhow!("{uri} does not play for this account"));
        };
        self.mark_unavailable(uri, false);
        let cache = session.cache().ok_or_else(|| anyhow!("sem cache de áudio"))?;
        // The key belongs to the song whose file it is: a relinked one's
        // comes from the release it plays.
        if let SpotifyUri::Track { id } = &owner {
            if cache.audio_key(file).is_none() {
                self.fetch_key(session, *id, file, uri).await;
            }
        }
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

impl Offline {
    /// Ask a song's audio key, for playing it offline: kept on disk by the key
    /// manager once fetched. Spaced out, and none for a while after Spotify
    /// refuses one: asked in a burst they are all refused. A song without one
    /// still downloads, and gets it when played online.
    async fn fetch_key(&self, session: &Session, id: librespot_core::SpotifyId, file: FileId, uri: &str) {
        let wait = {
            let pace = self.keys.lock().unwrap();
            if pace.refused_until.is_some_and(|until| std::time::Instant::now() < until) {
                return;
            }
            pace.last.map(|last| KEY_SPACING.saturating_sub(last.elapsed())).unwrap_or_default()
        };
        tokio::time::sleep(wait).await;
        let result = session.audio_key().request(id, file).await;
        let mut pace = self.keys.lock().unwrap();
        pace.last = Some(std::time::Instant::now());
        if let Err(error) = result {
            log::info!("no audio key yet for {uri}: {error}");
            pace.refused_until = Some(std::time::Instant::now() + KEY_PAUSE);
        }
    }
}

/// Between two audio keys asked ahead, and the pause after Spotify refuses one.
const KEY_SPACING: Duration = Duration::from_millis(1500);
const KEY_PAUSE: Duration = Duration::from_secs(10 * 60);

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
    let here = |files: &librespot_metadata::audio::AudioFiles| {
        files.values().any(|&file| {
            cache.audio_key(file).is_some() && cache.file_path(file).is_some_and(|path| path.exists())
        })
    };
    if here(&track.files) {
        return true;
    }
    // A song relinked to another release plays that one's file.
    for alternative in &track.alternatives.0 {
        if let Ok(other) = librespot_metadata::Track::get(session, alternative).await {
            if here(&other.files) {
                return true;
            }
        }
    }
    false
}

/// Where the kept songs are listed.
const KEPT: &str = "kept_songs.json";

/// Where the songs found unplayable are listed, and how long until one is
/// asked again (it may come back, or the account move country).
const UNAVAILABLE: &str = "unavailable_songs.json";
const UNAVAILABLE_RECHECK: Duration = Duration::from_secs(7 * 24 * 60 * 60);

fn now_secs() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|elapsed| elapsed.as_secs())
        .unwrap_or_default()
}

fn read_json<T: serde::de::DeserializeOwned + Default>(path: &Path) -> T {
    std::fs::read(path)
        .ok()
        .and_then(|bytes| serde_json::from_slice(&bytes).ok())
        .unwrap_or_default()
}

fn load_files(dir: &Path, quality: AudioQuality) -> HashMap<String, String> {
    read_json(&dir.join(files_name(quality)))
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
