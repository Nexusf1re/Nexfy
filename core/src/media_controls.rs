//! The desktop's counterpart of the phone's media session: Windows' System
//! Media Transport Controls. What plays shows in the volume flyout, on the
//! lock screen and in the quick settings, with its cover, and the media keys
//! (a keyboard's, a headset's) reach the app with its window in the
//! background. The phone has Media3 for this; the core never sees it there.

use std::sync::{Arc, Mutex};

use crate::CoreError;

/// A button pressed in the system's controls, or a key on the keyboard.
#[derive(Debug, Clone, Copy, uniffi::Enum)]
pub enum MediaButton {
    Play,
    Pause,
    Toggle,
    Next,
    Previous,
    /// Rewind or fast forward: this many milliseconds back or ahead.
    SeekBy { delta_ms: i64 },
    /// The flyout's timeline dragged to a place.
    SeekTo { position_ms: u64 },
}

/// Implemented in Kotlin. Called from a system thread, never the main thread.
#[uniffi::export(with_foreign)]
pub trait MediaButtonListener: Send + Sync {
    fn pressed(&self, button: MediaButton);
}

/// How far the flyout's rewind and fast forward jump.
#[cfg(windows)]
const SKIP_MS: i64 = 10_000;

#[derive(uniffi::Object)]
pub struct SystemMediaControls {
    #[cfg(windows)]
    controls: Mutex<souvlaki::MediaControls>,
    #[cfg(not(windows))]
    _none: Mutex<()>,
}

#[uniffi::export]
impl SystemMediaControls {
    /// The controls of the window [window_handle] (an HWND), which they are
    /// tied to: the system names the app after it. Fails where the platform
    /// has none, and the app then goes without.
    #[uniffi::constructor]
    pub fn new(window_handle: u64, listener: Arc<dyn MediaButtonListener>) -> Result<Arc<Self>, CoreError> {
        #[cfg(windows)]
        {
            use souvlaki::{MediaControlEvent, PlatformConfig, SeekDirection};
            let config = PlatformConfig {
                display_name: "Nexfy",
                dbus_name: "nexfy",
                hwnd: Some(window_handle as usize as *mut std::ffi::c_void),
            };
            let error = |e: souvlaki::Error| CoreError::Playback(format!("controles de mídia: {e:?}"));
            let mut controls = souvlaki::MediaControls::new(config).map_err(error)?;
            controls
                .attach(move |event| {
                    let button = match event {
                        MediaControlEvent::Play => MediaButton::Play,
                        MediaControlEvent::Pause | MediaControlEvent::Stop => MediaButton::Pause,
                        MediaControlEvent::Toggle => MediaButton::Toggle,
                        MediaControlEvent::Next => MediaButton::Next,
                        MediaControlEvent::Previous => MediaButton::Previous,
                        MediaControlEvent::Seek(SeekDirection::Forward) => MediaButton::SeekBy { delta_ms: SKIP_MS },
                        MediaControlEvent::Seek(SeekDirection::Backward) => MediaButton::SeekBy { delta_ms: -SKIP_MS },
                        MediaControlEvent::SetPosition(p) => MediaButton::SeekTo { position_ms: p.0.as_millis() as u64 },
                        _ => return,
                    };
                    listener.pressed(button);
                })
                .map_err(error)?;
            Ok(Arc::new(Self { controls: Mutex::new(controls) }))
        }
        #[cfg(not(windows))]
        {
            let _ = (window_handle, listener);
            Err(CoreError::Playback("controles de mídia: não há neste sistema".into()))
        }
    }

    /// The song shown: its names, its cover (an https link the system fetches
    /// itself) and its length.
    pub fn show(&self, title: String, artist: String, album: String, cover_url: Option<String>, duration_ms: u64) {
        #[cfg(windows)]
        {
            let metadata = souvlaki::MediaMetadata {
                title: Some(&title),
                artist: Some(&artist),
                album: Some(album.as_str()).filter(|a| !a.is_empty()),
                cover_url: cover_url.as_deref(),
                duration: Some(std::time::Duration::from_millis(duration_ms)),
            };
            if let Err(e) = self.controls.lock().unwrap().set_metadata(metadata) {
                log::info!("media controls metadata: {e:?}");
            }
        }
        #[cfg(not(windows))]
        let _ = (title, artist, album, cover_url, duration_ms);
    }

    /// Whether it plays, and where it was at that moment: the flyout moves
    /// its timeline on by itself from there.
    pub fn set_playing(&self, playing: bool, position_ms: u64) {
        #[cfg(windows)]
        {
            use souvlaki::{MediaPlayback, MediaPosition};
            let progress = Some(MediaPosition(std::time::Duration::from_millis(position_ms)));
            let playback = if playing { MediaPlayback::Playing { progress } } else { MediaPlayback::Paused { progress } };
            if let Err(e) = self.controls.lock().unwrap().set_playback(playback) {
                log::info!("media controls playback: {e:?}");
            }
        }
        #[cfg(not(windows))]
        let _ = (playing, position_ms);
    }

    /// Nothing to show: the controls leave the flyout.
    pub fn clear(&self) {
        #[cfg(windows)]
        if let Err(e) = self.controls.lock().unwrap().set_playback(souvlaki::MediaPlayback::Stopped) {
            log::info!("media controls stop: {e:?}");
        }
    }
}
