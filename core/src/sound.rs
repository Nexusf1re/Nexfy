//! Sound settings (stream quality, volume normalisation), kept in the
//! core's app-private dir, and the gain stages the player applies on top of
//! Connect's volume.

use std::path::Path;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};

use librespot_metadata::audio::AudioFileFormat;
use librespot_playback::config::{Bitrate, NormalisationMethod, PlayerConfig};

/// Volume while another app's sound has focus (navigation, notifications).
const DUCKED_VOLUME: f64 = 0.2;

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum AudioQuality {
    /// 160 kbps.
    Normal,
    /// 320 kbps, Premium only.
    High,
    /// High on Wi-Fi, Normal on mobile data; downloads are High.
    Auto,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SoundSettings {
    /// The quality chosen.
    pub quality: AudioQuality,
    /// The quality streaming now: the choice, or what Auto picked for the
    /// network. A new choice applies from the next song.
    pub active_quality: AudioQuality,
    pub normalize: bool,
}

impl AudioQuality {
    /// The user's choice; High unless they picked Normal.
    pub fn load(dir: &Path) -> Self {
        match std::fs::read_to_string(dir.join("audio_quality")) {
            Ok(text) if text.trim() == "160" => Self::Normal,
            Ok(text) if text.trim() == "auto" => Self::Auto,
            _ => Self::High,
        }
    }

    pub fn save(self, dir: &Path) {
        let text = match self {
            Self::Normal => "160",
            Self::High => "320",
            Self::Auto => "auto",
        };
        let _ = std::fs::write(dir.join("audio_quality"), text);
    }

    /// What streams on this network: Auto saves mobile data.
    pub fn streaming(self, metered: bool) -> Self {
        match self {
            Self::Auto if metered => Self::Normal,
            Self::Auto => Self::High,
            quality => quality,
        }
    }

    /// What downloads keep: songs kept are heard later, often on mobile data.
    pub fn downloading(self) -> Self {
        self.streaming(false)
    }

    pub fn bitrate(self) -> Bitrate {
        match self.downloading() {
            Self::Normal => Bitrate::Bitrate160,
            _ => Bitrate::Bitrate320,
        }
    }

    /// The formats the player tries at this quality, in its order (see
    /// librespot's player.rs): prefetching any other file would not be the
    /// one that plays.
    pub fn formats(self) -> [AudioFileFormat; 7] {
        match self.downloading() {
            Self::Normal => [
                AudioFileFormat::OGG_VORBIS_160,
                AudioFileFormat::MP3_160,
                AudioFileFormat::OGG_VORBIS_96,
                AudioFileFormat::MP3_96,
                AudioFileFormat::MP3_256,
                AudioFileFormat::OGG_VORBIS_320,
                AudioFileFormat::MP3_320,
            ],
            _ => [
                AudioFileFormat::OGG_VORBIS_320,
                AudioFileFormat::MP3_320,
                AudioFileFormat::MP3_256,
                AudioFileFormat::OGG_VORBIS_160,
                AudioFileFormat::MP3_160,
                AudioFileFormat::OGG_VORBIS_96,
                AudioFileFormat::MP3_96,
            ],
        }
    }
}

/// Gains read for every audio packet, so changes are heard at once.
pub struct Levels {
    duck: AtomicU64,
    /// The sleep timer's fade out.
    fade: AtomicU64,
    normalize: AtomicBool,
    /// The normalisation factor librespot applies to the song playing.
    factor: Arc<AtomicU64>,
}

impl Levels {
    pub fn new(normalize: bool) -> Self {
        Self {
            duck: AtomicU64::new(1.0f64.to_bits()),
            fade: AtomicU64::new(1.0f64.to_bits()),
            normalize: AtomicBool::new(normalize),
            factor: Arc::new(AtomicU64::new(1.0f64.to_bits())),
        }
    }

    pub fn set_ducked(&self, ducked: bool) {
        let gain = if ducked { DUCKED_VOLUME } else { 1.0 };
        self.duck.store(gain.to_bits(), Ordering::Relaxed);
    }

    pub fn set_fade(&self, gain: f64) {
        self.fade.store(gain.clamp(0.0, 1.0).to_bits(), Ordering::Relaxed);
    }

    pub fn normalize(&self) -> bool {
        self.normalize.load(Ordering::Relaxed)
    }

    pub fn set_normalize(&self, on: bool) {
        self.normalize.store(on, Ordering::Relaxed);
    }

    /// What multiplies Connect's volume. With normalisation off this
    /// divides the song's factor back out: librespot's Basic method
    /// multiplies by exactly that factor, so the result is the raw song.
    pub fn gain(&self) -> f64 {
        let load = |value: &AtomicU64| f64::from_bits(value.load(Ordering::Relaxed));
        let factor = load(&self.factor);
        let undo = if self.normalize() || factor <= 0.0 { 1.0 } else { 1.0 / factor };
        load(&self.duck) * load(&self.fade) * undo
    }
}

/// librespot fixes its config when the player is built, so normalisation
/// is always on there (Basic: a plain gain, never above 0 dB, no limiter)
/// and [Levels::gain] undoes it live when the user turns it off.
pub fn player_config(quality: AudioQuality, levels: &Levels) -> PlayerConfig {
    PlayerConfig {
        bitrate: quality.bitrate(),
        normalisation: true,
        normalisation_method: NormalisationMethod::Basic,
        normalisation_report: Some(levels.factor.clone()),
        ..PlayerConfig::default()
    }
}

/// Off until the user turns it on: no file means it was never chosen.
pub fn load_normalize(dir: &Path) -> bool {
    std::fs::read_to_string(dir.join("normalize")).is_ok_and(|text| text.trim() == "1")
}

pub fn save_normalize(dir: &Path, on: bool) {
    let _ = std::fs::write(dir.join("normalize"), if on { "1" } else { "0" });
}
