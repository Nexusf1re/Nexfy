//! Songs kept on the phone: librespot's encrypted audio cache, sized by the
//! user and filled ahead of time, so songs start at once and keep playing
//! on weak connections. Playing still needs the network for each song's
//! key (a few bytes), so this is not an airplane mode.

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::Duration;

use anyhow::{anyhow, Result};
use librespot_audio::AudioFile;
use librespot_core::session::Session;
use librespot_core::{FileId, SpotifyUri};
use librespot_metadata::audio::{AudioFileFormat, AudioItem};

/// Audio cache size when the user has not chosen one.
pub const DEFAULT_LIMIT: u64 = 2 * 1024 * 1024 * 1024;

/// The formats the player picks at its default 160 kbps, in its order:
/// prefetching any other file would not be the one that plays.
const FORMATS: [AudioFileFormat; 7] = [
    AudioFileFormat::OGG_VORBIS_160,
    AudioFileFormat::MP3_160,
    AudioFileFormat::OGG_VORBIS_96,
    AudioFileFormat::MP3_96,
    AudioFileFormat::MP3_256,
    AudioFileFormat::OGG_VORBIS_320,
    AudioFileFormat::MP3_320,
];

/// How much the audio cache holds and may hold.
#[derive(Debug, Clone, uniffi::Record)]
pub struct CacheInfo {
    pub used_bytes: u64,
    pub limit_bytes: u64,
}

pub struct Offline {
    dir: PathBuf,
    /// Song URI → the audio file it plays from (base16), so checking what
    /// is on the phone needs no network.
    files: Mutex<HashMap<String, String>>,
}

impl Offline {
    pub fn new(cache_dir: &Path) -> Self {
        let files = std::fs::read(cache_dir.join("audio_files.json"))
            .ok()
            .and_then(|bytes| serde_json::from_slice(&bytes).ok())
            .unwrap_or_default();
        Self {
            dir: cache_dir.to_owned(),
            files: Mutex::new(files),
        }
    }

    pub fn audio_dir(&self) -> PathBuf {
        self.dir.join("audio")
    }

    pub fn limit(&self) -> u64 {
        std::fs::read_to_string(self.dir.join("audio_limit"))
            .ok()
            .and_then(|text| text.trim().parse().ok())
            .unwrap_or(DEFAULT_LIMIT)
    }

    /// Takes effect when the session is next created (the next app start).
    pub fn set_limit(&self, bytes: u64) {
        let _ = std::fs::write(self.dir.join("audio_limit"), bytes.to_string());
    }

    pub fn info(&self) -> CacheInfo {
        CacheInfo {
            used_bytes: dir_size(&self.audio_dir()),
            limit_bytes: self.limit(),
        }
    }

    pub fn clear(&self) {
        let dir = self.audio_dir();
        let _ = std::fs::remove_dir_all(&dir);
        let _ = std::fs::create_dir_all(&dir);
    }

    fn known_file(&self, uri: &str) -> Option<FileId> {
        let hex = self.files.lock().unwrap().get(uri).cloned()?;
        file_id(&hex)
    }

    fn remember(&self, uri: &str, file: FileId) {
        let mut files = self.files.lock().unwrap();
        files.insert(uri.to_owned(), file.to_base16());
        if let Ok(json) = serde_json::to_vec(&*files) {
            let _ = std::fs::write(self.dir.join("audio_files.json"), json);
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

    /// The file `uri` plays from, asking Spotify the first time.
    async fn file_for(&self, session: &Session, uri: &str) -> Result<FileId> {
        if let Some(file) = self.known_file(uri) {
            return Ok(file);
        }
        let parsed = SpotifyUri::from_uri(uri).map_err(|e| anyhow!("{e}"))?;
        let item = AudioItem::get_file(session, parsed).await.map_err(|e| anyhow!("{e}"))?;
        let file = FORMATS
            .iter()
            .find_map(|format| item.files.get(format).copied())
            .ok_or_else(|| anyhow!("sem arquivo de áudio para {uri}"))?;
        self.remember(uri, file);
        Ok(file)
    }

    /// Put `uri` in the audio cache; true if it was downloaded now.
    pub async fn download(&self, session: &Session, uri: &str) -> Result<bool> {
        let file = self.file_for(session, uri).await?;
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
