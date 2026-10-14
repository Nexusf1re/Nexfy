//! Nexfy's Spotify core: sign-in, the librespot session, Spotify Connect
//! playback and the Web API, exported to Kotlin through UniFFI.
//!
//! Playback runs through Spirc, Spotify's Connect state machine: the phone
//! is a Connect device of the account, Spotify keeps the queue and context
//! (and autoplay after it), and other devices can see and control it.

mod auth;
#[cfg(target_os = "android")]
mod android;
mod collection;
mod lyrics;
mod offline;
mod playlist_edit;
mod remote;
mod session_reads;
mod sound;
mod webapi;

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, Weak};

use librespot_connect::{
    ConnectConfig, LoadContextOptions, LoadRequest, LoadRequestOptions, Options, PlayingTrack, Spirc,
};
use librespot_core::authentication::Credentials;
use librespot_core::cache::Cache;
use librespot_core::config::{DeviceType, SessionConfig};
use librespot_core::session::Session;
use librespot_core::{Error as LibrespotError, SpotifyId, SpotifyUri};
use librespot_metadata::Metadata;
use librespot_metadata::audio::{AudioItem, UniqueFields};
use librespot_playback::audio_backend;
use librespot_playback::config::AudioFormat;
use librespot_playback::mixer::{Mixer, MixerConfig, VolumeGetter};
use librespot_playback::player::{Player, PlayerEvent, PlayerEventChannel, QueueTrack};
use tokio::net::TcpListener;

pub use lyrics::{Lyrics, LyricsLine, LyricsWord};
pub use webapi::{
    Album, AlbumDetail, Artist, ArtistDetail, Episode, Playlist, PlaylistDetail, Profile, RecentPlay,
    SearchResults, Show, ShowDetail, Track, TrackPage, WebApp, WebStatus,
};
use webapi::WebApi;
pub use offline::CacheInfo;
pub use remote::{Device, RemoteCommand, RemoteState};
pub use sound::{AudioQuality, SoundSettings};

uniffi::setup_scaffolding!();

/// Flat: Kotlin sees each variant with its Display text as the message.
#[derive(Debug, thiserror::Error, uniffi::Error)]
#[uniffi(flat_error)]
pub enum CoreError {
    #[error("login: {0}")]
    Login(String),
    #[error("sessão: {0}")]
    Session(String),
    #[error("reprodução: {0}")]
    Playback(String),
    #[error("{0}")]
    Web(String),
}

fn web_error(error: anyhow::Error) -> CoreError {
    CoreError::Web(format!("{error:#}"))
}

/// Which grant a pending browser sign-in is for.
enum LoginTarget {
    Playback,
    Web(WebApp),
}

struct PendingLogin {
    target: LoginTarget,
    grant: auth::Grant,
    flow: auth::Flow,
    listener: TcpListener,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct TrackInfo {
    pub uri: String,
    pub title: String,
    pub artists: String,
    pub album: String,
    pub cover_url: Option<String>,
    pub duration_ms: u32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum RepeatMode {
    Off,
    All,
    One,
}

/// A queue entry; `queued` marks songs the user added ("Na fila"), which
/// play before the rest of the context.
#[derive(Debug, Clone, uniffi::Record)]
pub struct QueueEntry {
    pub uri: String,
    pub queued: bool,
}

/// Spotify's queue for this device, as Spirc last reported it.
#[derive(Debug, Clone, uniffi::Record)]
pub struct QueueState {
    /// The playlist/album/… playing, if any.
    pub context_uri: Option<String>,
    pub previous: Vec<QueueEntry>,
    pub current: Option<QueueEntry>,
    pub next: Vec<QueueEntry>,
    pub shuffle: bool,
    pub repeat: RepeatMode,
}

impl Default for QueueState {
    fn default() -> Self {
        Self {
            context_uri: None,
            previous: Vec::new(),
            current: None,
            next: Vec::new(),
            shuffle: false,
            repeat: RepeatMode::Off,
        }
    }
}

#[derive(Debug, Clone, uniffi::Enum)]
pub enum CoreEvent {
    TrackChanged { track: TrackInfo },
    /// Playing from `position_ms` as of now; also sent after seeks.
    Playing { position_ms: u32 },
    Paused { position_ms: u32 },
    Loading,
    /// Playback stopped here: the context ended or another device took over.
    Stopped,
    QueueChanged { queue: QueueState },
    Error { message: String },
}

/// Implemented in Kotlin. Called from core threads, never the main thread.
#[uniffi::export(with_foreign)]
pub trait CoreListener: Send + Sync {
    fn on_event(&self, event: CoreEvent);
}

struct Connected {
    session: Session,
    player: Arc<Player>,
    spirc: Arc<Spirc>,
}

/// Software volume: Connect's volume (other devices can set it) times the
/// sound levels (ducking while another app speaks, the sleep timer's fade,
/// normalisation). The phone's own volume keys still work on top, through
/// Android's stream volume.
#[derive(Clone)]
struct NexfyMixer {
    volume: Arc<AtomicU64>,
    levels: Arc<sound::Levels>,
}

impl NexfyMixer {
    fn new(levels: Arc<sound::Levels>) -> Self {
        Self {
            volume: Arc::new(AtomicU64::new(1.0f64.to_bits())),
            levels,
        }
    }
}

impl Mixer for NexfyMixer {
    fn open(_: MixerConfig) -> Result<Self, LibrespotError> {
        Ok(Self::new(Arc::new(sound::Levels::new(true))))
    }

    fn volume(&self) -> u16 {
        (f64::from_bits(self.volume.load(Ordering::Relaxed)) * f64::from(u16::MAX)) as u16
    }

    fn set_volume(&self, volume: u16) {
        let level = f64::from(volume) / f64::from(u16::MAX);
        self.volume.store(level.to_bits(), Ordering::Relaxed);
    }

    fn get_soft_volume(&self) -> Box<dyn VolumeGetter + Send> {
        Box::new(Gain {
            volume: self.volume.clone(),
            levels: self.levels.clone(),
        })
    }
}

struct Gain {
    volume: Arc<AtomicU64>,
    levels: Arc<sound::Levels>,
}

impl VolumeGetter for Gain {
    fn attenuation_factor(&self) -> f64 {
        // Squared: Connect sliders feel linear to the ear this way.
        let volume = f64::from_bits(self.volume.load(Ordering::Relaxed));
        volume * volume * self.levels.gain()
    }
}

struct Inner {
    cache_dir: PathBuf,
    /// How this phone appears in Spotify's device list.
    device_name: String,
    pending_login: Mutex<Option<PendingLogin>>,
    web: Arc<WebApi>,
    offline: offline::Offline,
    connected: Mutex<Option<Connected>>,
    queue: Mutex<QueueState>,
    /// Metadata for queue entries, fetched once per track.
    track_cache: Mutex<HashMap<String, Track>>,
    listener: Mutex<Option<Arc<dyn CoreListener>>>,
    playing: AtomicBool,
    levels: Arc<sound::Levels>,
    /// Read at start: the player is built with it.
    quality: AudioQuality,
}

#[derive(uniffi::Object)]
pub struct NexfyCore {
    inner: Arc<Inner>,
}

#[uniffi::export(async_runtime = "tokio")]
impl NexfyCore {
    /// `cache_dir` is app-private storage; librespot keeps the reusable
    /// credential and the audio cache there. `device_name` is how the phone
    /// shows up in other Spotify apps' device lists.
    #[uniffi::constructor]
    pub fn new(cache_dir: String, device_name: String) -> Arc<Self> {
        let cache_dir = PathBuf::from(cache_dir);
        let tracks = load_track_cache(&cache_dir);
        let quality = AudioQuality::load(&cache_dir);
        Arc::new(Self {
            inner: Arc::new(Inner {
                web: Arc::new(WebApi::new(&cache_dir)),
                offline: offline::Offline::new(&cache_dir, quality),
                levels: Arc::new(sound::Levels::new(sound::load_normalize(&cache_dir))),
                quality,
                cache_dir,
                device_name,
                pending_login: Mutex::new(None),
                connected: Mutex::new(None),
                queue: Mutex::new(QueueState::default()),
                track_cache: Mutex::new(tracks),
                listener: Mutex::new(None),
                playing: AtomicBool::new(false),
            }),
        })
    }

    pub fn set_listener(&self, listener: Arc<dyn CoreListener>) {
        *self.inner.listener.lock().unwrap() = Some(listener);
    }

    /// Start the playback sign-in: reserves the redirect port and returns
    /// the URL the app opens in the browser. Async so the listener binds
    /// inside Tokio.
    pub async fn begin_login(&self) -> Result<String, CoreError> {
        self.inner.begin(LoginTarget::Playback, auth::Grant::playback())
    }

    /// Wait for the browser redirect, then open the Spotify session.
    /// Returns the account's username.
    pub async fn finish_login(&self) -> Result<String, CoreError> {
        let token = self.inner.finish(|t| matches!(t, LoginTarget::Playback)).await?;
        self.inner
            .connect(Credentials::with_access_token(token.access_token))
            .await
    }

    // -------------------------------------------------- Web API --

    pub async fn web_status(&self) -> WebStatus {
        self.inner.web.status().await
    }

    /// Set (or clear with None) the user's own Spotify developer app.
    pub async fn set_personal_client_id(&self, client_id: Option<String>) {
        self.inner.web.set_personal_client_id(client_id).await
    }

    /// Sign one Web API app in through the browser, like [Self::begin_login].
    pub async fn begin_web_login(&self, app: WebApp) -> Result<String, CoreError> {
        let grant = self.inner.web.login_grant(app).await.map_err(web_error)?;
        self.inner.begin(LoginTarget::Web(app), grant)
    }

    pub async fn finish_web_login(&self, app: WebApp) -> Result<(), CoreError> {
        let token = self
            .inner
            .finish(|t| matches!(t, LoginTarget::Web(a) if *a == app))
            .await?;
        self.inner.web.finish_login(app, token).await;
        Ok(())
    }

    pub async fn web_sign_out(&self, app: WebApp) {
        self.inner.web.sign_out(app).await
    }

    /// The session's profile service first (no Web API quota), the Web API
    /// as the fallback. `refresh` skips the cache.
    pub async fn profile(&self, refresh: bool) -> Result<Profile, CoreError> {
        const KEY: &str = "session:profile";
        let web = &self.inner.web;
        if !refresh {
            if let Some(profile) = web.load_local::<Profile>(KEY, std::time::Duration::from_secs(86_400)) {
                return Ok(profile);
            }
        }
        let read = async {
            let session = self.inner.live_session().await?;
            let me = session.username();
            let face = session_reads::user(&session, &me).await.map_err(web_error)?;
            let product = session.get_user_attribute("type").unwrap_or_default();
            Ok::<_, CoreError>(Profile { id: me, display_name: face.name, image_url: face.image_url, product })
        };
        match read.await {
            Ok(profile) => {
                web.save_local(KEY, &profile);
                Ok(profile)
            }
            Err(error) => {
                log::info!("profile via Web API: {error}");
                web.profile(refresh).await.map_err(web_error)
            }
        }
    }

    /// The session's rootlist first (no Web API quota), with the owners'
    /// names from the profile service; the Web API as the fallback.
    pub async fn my_playlists(&self, refresh: bool) -> Result<Vec<Playlist>, CoreError> {
        const KEY: &str = "session:rootlist";
        let web = &self.inner.web;
        if !refresh {
            if let Some(fresh) = web.load_local::<Vec<Playlist>>(KEY, std::time::Duration::from_secs(600)) {
                return Ok(fresh);
            }
        }
        let read = async {
            let session = self.inner.live_session().await?;
            session_reads::rootlist(&session).await.map_err(web_error)
        };
        match read.await {
            Ok(mut playlists) if !playlists.is_empty() => {
                let me = self.inner.with_session(|s| s.username()).unwrap_or_default();
                let owners: Vec<String> = playlists
                    .iter()
                    .map(|p| p.owner_id.clone())
                    .filter(|owner| !owner.is_empty() && *owner != me)
                    .collect::<std::collections::HashSet<_>>()
                    .into_iter()
                    .collect();
                let names: HashMap<String, String> =
                    self.users(owners).await.into_iter().map(|face| (face.id, face.name)).collect();
                let my_name = self.profile(false).await.map(|p| p.display_name).ok();
                for playlist in &mut playlists {
                    if playlist.owner_id == me {
                        if let Some(name) = &my_name {
                            playlist.owner = name.clone();
                        }
                    } else if let Some(name) = names.get(&playlist.owner_id) {
                        playlist.owner = name.clone();
                    }
                }
                let playlists = self.fill_covers(playlists).await;
                web.save_local(KEY, &playlists);
                Ok(playlists)
            }
            other => {
                if let Err(error) = &other {
                    log::info!("playlists via Web API: {error}");
                }
                match web.my_playlists(refresh).await {
                    Ok(playlists) => Ok(playlists),
                    Err(error) => match web.load_local::<Vec<Playlist>>(KEY, std::time::Duration::from_secs(30 * 86_400)) {
                        Some(saved) => Ok(saved),
                        None => match error.downcast::<webapi::Cut<Playlist>>() {
                            Ok(cut) => Ok(cut.0),
                            Err(error) => Err(web_error(error)),
                        },
                    },
                }
            }
        }
    }

    /// Playlists without a picture of their own (Spotify draws a mosaic the
    /// rootlist does not carry) take their first song's cover, as the
    /// playlist page does: the last copy of that page, else one small read.
    async fn fill_covers(&self, mut playlists: Vec<Playlist>) -> Vec<Playlist> {
        use futures_util::{StreamExt, stream};
        let web = &self.inner.web;
        let Ok(session) = self.inner.live_session().await else {
            return playlists;
        };
        let bare: Vec<(usize, String)> = playlists
            .iter()
            .enumerate()
            .filter(|(_, p)| p.image_url.is_none())
            .map(|(i, p)| (i, p.id.clone()))
            .collect();
        let covers: Vec<(usize, Option<String>)> = stream::iter(bare)
            .map(|(i, id)| {
                let session = session.clone();
                async move {
                    let saved = web
                        .load_local::<PlaylistDetail>(&format!("session:playlist5:{id}"), std::time::Duration::from_secs(30 * 86_400))
                        .and_then(|detail| detail.playlist.image_url);
                    if saved.is_some() {
                        return (i, saved);
                    }
                    let first = session_reads::first_uris(&session, &id, 3).await.unwrap_or_default();
                    let cover = match first.into_iter().next() {
                        Some(uri) => self.tracks_info(vec![uri]).await.ok().and_then(|t| t.into_iter().next()).and_then(|t| t.image_url),
                        None => None,
                    };
                    (i, cover)
                }
            })
            .buffer_unordered(4)
            .collect()
            .await;
        for (i, cover) in covers {
            if cover.is_some() {
                playlists[i].image_url = cover;
            }
        }
        playlists
    }

    /// The playlist service first (no Web API quota): its rows in one
    /// request, the songs from the track cache. The Web API is the fallback.
    pub async fn playlist(&self, id: String, refresh: bool) -> Result<PlaylistDetail, CoreError> {
        let web = &self.inner.web;
        let key = format!("session:playlist5:{id}");
        if !refresh {
            if let Some(fresh) = web.load_local::<PlaylistDetail>(&key, std::time::Duration::from_secs(20 * 60)) {
                return Ok(fresh);
            }
        }
        match self.session_playlist(&id).await {
            Ok(detail) => {
                web.save_local(&key, &detail);
                Ok(detail)
            }
            Err(error) => {
                log::info!("playlist {id} via Web API: {error}");
                if let Some(saved) = web.load_local::<PlaylistDetail>(&key, std::time::Duration::from_secs(30 * 86_400)) {
                    return Ok(saved);
                }
                self.web_playlist(&id, refresh).await
            }
        }
    }

    async fn session_playlist(&self, id: &str) -> Result<PlaylistDetail, CoreError> {
        let session = self.inner.live_session().await?;
        let list = session_reads::playlist_entries(&session, id).await.map_err(web_error)?;
        let uris: Vec<String> = list
            .entries
            .iter()
            .map(|entry| entry.uri.clone())
            .filter(|uri| uri.starts_with("spotify:track:"))
            .collect();
        let known: HashMap<String, Track> =
            self.tracks_info(uris).await?.into_iter().map(|track| (track.uri.clone(), track)).collect();
        let tracks: Vec<Track> = list
            .entries
            .iter()
            .filter_map(|entry| {
                known.get(&entry.uri).map(|track| Track {
                    added_at_ms: entry.added_at_ms,
                    added_by: entry.added_by.clone(),
                    ..track.clone()
                })
            })
            .collect();
        if tracks.len() < list.entries.len() {
            let missing: Vec<&str> =
                list.entries.iter().map(|e| e.uri.as_str()).filter(|uri| !known.contains_key(*uri)).take(10).collect();
            log::warn!("playlist {id}: {} of {} rows resolved; missing {missing:?}", tracks.len(), list.entries.len());
        }
        let me = session.username();
        let owner = if list.owner_username.is_empty() {
            String::new()
        } else if list.owner_username == me {
            self.profile(false).await.map(|p| p.display_name).unwrap_or_else(|_| me.clone())
        } else {
            self.users(vec![list.owner_username.clone()])
                .await
                .into_iter()
                .next()
                .map(|face| face.name)
                .unwrap_or_else(|| list.owner_username.clone())
        };
        let followers = self.inner.web.cached_playlist_meta(id).map(|p| p.followers).unwrap_or_default();
        let image_url = list.image_url.or_else(|| tracks.first().and_then(|t| t.image_url.clone()));
        Ok(PlaylistDetail {
            playlist: Playlist {
                id: id.to_owned(),
                uri: format!("spotify:playlist:{id}"),
                name: list.name,
                description: list.description,
                owner,
                owner_id: list.owner_username,
                image_url,
                track_count: tracks.len() as u32,
                followers,
            },
            tracks,
        })
    }

    /// The Web API read of a playlist, for when the session cannot answer.
    async fn web_playlist(&self, id: &str, refresh: bool) -> Result<PlaylistDetail, CoreError> {
        let web = &self.inner.web;
        let meta = web.playlist_meta(id, refresh).await;
        let tracks = match web.playlist_items(id, refresh).await {
            Ok(tracks) => tracks,
            Err(error) => match error.downcast::<webapi::Cut<Track>>() {
                Ok(cut) => cut.0,
                Err(error) => return Err(web_error(error)),
            },
        };
        let mut playlist = match meta {
            Ok(playlist) => playlist,
            Err(error) => match self.known_playlist(id).await {
                Some(known) => known,
                None => return Err(web_error(error)),
            },
        };
        if playlist.image_url.is_none() {
            playlist.image_url = tracks.first().and_then(|t| t.image_url.clone());
        }
        if playlist.track_count == 0 {
            playlist.track_count = tracks.len() as u32;
        }
        Ok(PlaylistDetail { playlist, tracks })
    }

    /// A playlist's details without the Web API: the library's rootlist
    /// copy, else its attributes over the session.
    async fn known_playlist(&self, id: &str) -> Option<Playlist> {
        let saved = self
            .inner
            .web
            .load_local::<Vec<Playlist>>("session:rootlist", std::time::Duration::from_secs(30 * 86_400))
            .and_then(|all| all.into_iter().find(|p| p.id == id));
        if saved.is_some() {
            return saved;
        }
        let session = self.inner.live_session().await.ok()?;
        session_reads::playlist_info(&session, id).await.ok()
    }

    /// The session's collection first (no Web API quota); the Web API when
    /// the session cannot read it. An empty collection asks the Web API
    /// too, in case Spotify moved the songs to another set.
    pub async fn liked_tracks(&self, offset: u32, limit: u32, refresh: bool) -> Result<TrackPage, CoreError> {
        let web = &self.inner.web;
        let read = async {
            let session = self.inner.live_session().await?;
            collection::liked_tracks(web, &session, offset, limit, refresh).await.map_err(web_error)
        };
        match read.await {
            Ok(page) if page.total > 0 => Ok(page),
            Ok(page) => Ok(web.liked_tracks(offset, limit, refresh).await.unwrap_or(page)),
            Err(error) => {
                log::info!("liked songs via the Web API: {error}");
                web.liked_tracks(offset, limit, refresh).await.map_err(web_error)
            }
        }
    }

    /// Session first, like [Self::liked_tracks].
    pub async fn saved_albums(&self, refresh: bool) -> Result<Vec<Album>, CoreError> {
        let web = &self.inner.web;
        let read = async {
            let session = self.inner.live_session().await?;
            collection::saved_albums(web, &session, refresh).await.map_err(web_error)
        };
        match read.await {
            Ok(albums) if !albums.is_empty() => Ok(albums),
            Ok(albums) => Ok(web.saved_albums(refresh).await.unwrap_or(albums)),
            Err(error) => {
                log::info!("saved albums via the Web API: {error}");
                web.saved_albums(refresh).await.map_err(web_error)
            }
        }
    }

    /// Over the session first (no Web API quota; kept a week, an album does
    /// not change); the Web API as the fallback.
    pub async fn album(&self, id: String, refresh: bool) -> Result<AlbumDetail, CoreError> {
        let web = &self.inner.web;
        let key = format!("session:album:{id}");
        if !refresh {
            if let Some(detail) = web.load_local::<AlbumDetail>(&key, std::time::Duration::from_secs(7 * 86_400)) {
                return Ok(detail);
            }
        }
        let read = async {
            let session = self.inner.live_session().await?;
            session_reads::album(&session, &id).await.map_err(web_error)
        };
        match read.await {
            Ok(detail) => {
                web.save_local(&key, &detail);
                Ok(detail)
            }
            Err(error) => {
                log::info!("album {id} via Web API: {error}");
                web.album(&id, refresh).await.map_err(web_error)
            }
        }
    }

    /// Session first, like [Self::liked_tracks].
    pub async fn saved_shows(&self, refresh: bool) -> Result<Vec<Show>, CoreError> {
        let web = &self.inner.web;
        let read = async {
            let session = self.inner.live_session().await?;
            collection::saved_shows(web, &session, refresh).await.map_err(web_error)
        };
        match read.await {
            Ok(shows) if !shows.is_empty() => Ok(shows),
            Ok(shows) => Ok(web.saved_shows(refresh).await.unwrap_or(shows)),
            Err(error) => {
                log::info!("saved shows via the Web API: {error}");
                web.saved_shows(refresh).await.map_err(web_error)
            }
        }
    }

    /// A podcast and its latest 50 episodes, newest first.
    /// Over the session first (no Web API quota; kept an hour), with the
    /// resume points the Web API last gave; the Web API as the fallback.
    pub async fn show(&self, id: String, refresh: bool) -> Result<ShowDetail, CoreError> {
        let web = &self.inner.web;
        let key = format!("session:show:{id}");
        let fresh = if refresh {
            None
        } else {
            web.load_local::<ShowDetail>(&key, std::time::Duration::from_secs(3600))
        };
        let read = async {
            if let Some(detail) = fresh {
                return Ok(detail);
            }
            let session = self.inner.live_session().await?;
            let detail = session_reads::show(&session, &id).await.map_err(web_error)?;
            web.save_local(&key, &detail);
            Ok::<_, CoreError>(detail)
        };
        match read.await {
            Ok(mut detail) => {
                let progress = web.cached_show_progress(&id);
                for episode in &mut detail.episodes {
                    if let Some((at, done)) = progress.get(&episode.uri) {
                        episode.resume_position_ms = *at;
                        episode.fully_played = *done;
                    }
                }
                Ok(detail)
            }
            Err(error) => {
                log::info!("show {id} via Web API: {error}");
                web.show(&id, refresh).await.map_err(web_error)
            }
        }
    }

    /// Over the session first (no Web API quota, and Spotify refuses
    /// development-mode apps an artist's top tracks; cached a day); the
    /// Web API as the fallback.
    pub async fn artist(&self, id: String, refresh: bool) -> Result<ArtistDetail, CoreError> {
        let web = &self.inner.web;
        let key = format!("session:artist:{id}");
        if !refresh {
            if let Some(detail) = web.load_local::<ArtistDetail>(&key, std::time::Duration::from_secs(86_400)) {
                return Ok(detail);
            }
        }
        let read = async {
            let session = self.inner.live_session().await?;
            session_reads::artist(&session, &id).await.map_err(web_error)
        };
        match read.await {
            Ok(detail) => {
                web.save_local(&key, &detail);
                Ok(detail)
            }
            Err(error) => {
                log::info!("artist {id} via Web API: {error}");
                web.artist(&id, refresh).await.map_err(web_error)
            }
        }
    }

    // ------------------------------------------------ library writes --

    /// Save (or with `on` false, remove) songs, albums, podcasts or playlists,
    /// over the session (the collection and the rootlist); what it refuses
    /// goes through the Web API.
    pub async fn set_saved(&self, uris: Vec<String>, on: bool) -> Result<(), CoreError> {
        // Playlists: the session's rootlist first, the Web API if that fails.
        let (playlists, mut uris): (Vec<String>, Vec<String>) =
            uris.into_iter().partition(|uri| uri.starts_with("spotify:playlist:"));
        if !playlists.is_empty() {
            let edit = async {
                let session = self.inner.live_session().await?;
                playlist_edit::set_followed(&session, &playlists, on).await.map_err(web_error)
            };
            match edit.await {
                Ok(()) => playlist_edit::forget_rootlist(&self.inner.web),
                Err(error) => {
                    log::info!("follow playlists via Web API: {error}");
                    uris.extend(playlists);
                }
            }
        }
        if uris.is_empty() {
            return Ok(());
        }
        // Songs, albums and shows: the session's collection; the Web API
        // takes what it could not write.
        let web = &self.inner.web;
        let rest = match self.inner.live_session().await {
            Ok(session) => {
                let rest = collection::set_saved(web, &session, &uris, on).await;
                if rest.len() < uris.len() {
                    // Its saved reads are behind now.
                    web.forget_library();
                }
                rest
            }
            Err(error) => {
                log::info!("library write via the Web API: {error}");
                uris
            }
        };
        if rest.is_empty() {
            return Ok(());
        }
        web.set_saved(&rest, on).await.map_err(web_error)
    }

    /// Whether each URI is in the library, in order: songs, albums and
    /// podcasts from the session's collection, the rest from the Web API.
    pub async fn saved(&self, uris: Vec<String>) -> Result<Vec<bool>, CoreError> {
        let web = &self.inner.web;
        let known = match self.inner.live_session().await {
            Ok(session) => collection::contains(web, &session, &uris).await.unwrap_or_else(|error| {
                log::info!("saved via the Web API: {error:#}");
                vec![None; uris.len()]
            }),
            Err(_) => vec![None; uris.len()],
        };
        let rest: Vec<String> = uris.iter().zip(&known).filter(|(_, k)| k.is_none()).map(|(uri, _)| uri.clone()).collect();
        let mut answers = if rest.is_empty() {
            Vec::new()
        } else {
            web.saved(&rest).await.map_err(web_error)?
        }
        .into_iter();
        Ok(known.into_iter().map(|k| k.unwrap_or_else(|| answers.next().unwrap_or(false))).collect())
    }

    // Playlist writes go over the session first, which has no Web API quota
    // to run out; the Web API is the fallback.

    pub async fn create_playlist(&self, name: String, description: String) -> Result<Playlist, CoreError> {
        let edit = async {
            let session = self.inner.live_session().await?;
            playlist_edit::create(&session, &name, &description).await.map_err(web_error)
        };
        match edit.await {
            Ok(playlist) => {
                playlist_edit::forget_rootlist(&self.inner.web);
                Ok(playlist)
            }
            Err(error) => {
                log::info!("create playlist via Web API: {error}");
                self.inner.web.create_playlist(&name, &description).await.map_err(web_error)
            }
        }
    }

    pub async fn add_to_playlist(&self, id: String, uris: Vec<String>, position: Option<u32>) -> Result<(), CoreError> {
        let edit = async {
            let session = self.inner.live_session().await?;
            playlist_edit::add(&session, &id, &uris, position).await.map_err(web_error)
        };
        match edit.await {
            Ok(()) => {
                playlist_edit::forget_playlist(&self.inner.web, &id);
                Ok(())
            }
            Err(error) => {
                log::info!("add to playlist {id} via Web API: {error}");
                self.inner.web.add_to_playlist(&id, &uris, position).await.map_err(web_error)
            }
        }
    }

    pub async fn remove_from_playlist(&self, id: String, uris: Vec<String>) -> Result<(), CoreError> {
        let edit = async {
            let session = self.inner.live_session().await?;
            playlist_edit::remove(&session, &id, &uris).await.map_err(web_error)
        };
        match edit.await {
            Ok(()) => {
                playlist_edit::forget_playlist(&self.inner.web, &id);
                Ok(())
            }
            Err(error) => {
                log::info!("remove from playlist {id} via Web API: {error}");
                self.inner.web.remove_from_playlist(&id, &uris).await.map_err(web_error)
            }
        }
    }

    pub async fn edit_playlist(&self, id: String, name: String, description: String) -> Result<(), CoreError> {
        let edit = async {
            let session = self.inner.live_session().await?;
            playlist_edit::edit(&session, &id, &name, &description).await.map_err(web_error)
        };
        match edit.await {
            Ok(()) => {
                playlist_edit::forget_playlist(&self.inner.web, &id);
                Ok(())
            }
            Err(error) => {
                log::info!("edit playlist {id} via Web API: {error}");
                self.inner.web.edit_playlist(&id, &name, &description).await.map_err(web_error)
            }
        }
    }

    /// New albums and singles (last 90 days) from the artists the user
    /// listens to most and lately. Cached 12 h; an older copy answers at
    /// once while a fresh one is read in the background.
    pub async fn new_releases(&self, refresh: bool) -> Result<Vec<Album>, CoreError> {
        const KEY: &str = "session:releases";
        let web = &self.inner.web;
        if !refresh {
            if let Some(fresh) = web.load_local::<Vec<Album>>(KEY, std::time::Duration::from_secs(12 * 3600)) {
                return Ok(fresh);
            }
            if let Some(stale) = web.load_local::<Vec<Album>>(KEY, std::time::Duration::from_secs(30 * 86_400)) {
                let inner = self.inner.clone();
                tokio::spawn(async move {
                    if let Err(error) = read_releases(&inner).await {
                        log::info!("new releases: {error:#}");
                    }
                });
                return Ok(stale);
            }
        }
        read_releases(&self.inner).await
    }

    /// Names and photos of playlist contributors, cached a week; users
    /// that fail to load are left out.
    pub async fn users(&self, ids: Vec<String>) -> Vec<session_reads::UserFace> {
        use futures_util::{StreamExt, stream};
        let web = &self.inner.web;
        let ttl = std::time::Duration::from_secs(7 * 86_400);
        let mut faces = Vec::new();
        let mut missing = Vec::new();
        for id in ids {
            match web.load_local::<session_reads::UserFace>(&format!("user:{id}"), ttl) {
                Some(face) => faces.push(face),
                None => missing.push(id),
            }
        }
        if missing.is_empty() {
            return faces;
        }
        let Ok(session) = self.inner.live_session().await else {
            return faces;
        };
        let fetched: Vec<_> = stream::iter(missing)
            .map(|id| {
                let session = session.clone();
                async move { session_reads::user(&session, &id).await }
            })
            .buffer_unordered(4)
            .collect()
            .await;
        for result in fetched {
            match result {
                Ok(face) => {
                    web.save_local(&format!("user:{}", face.id), &face);
                    faces.push(face);
                }
                Err(error) => log::info!("user profile: {error:#}"),
            }
        }
        faces
    }

    /// What was played lately, from the session (one row per playlist,
    /// album or artist, cached 5 min); the Web API when the session cannot
    /// answer, and a saved copy when neither does.
    pub async fn recently_played(&self, refresh: bool) -> Result<Vec<RecentPlay>, CoreError> {
        const KEY: &str = "session:recent";
        let web = &self.inner.web;
        if !refresh {
            if let Some(plays) = web.load_local::<Vec<RecentPlay>>(KEY, std::time::Duration::from_secs(300)) {
                return Ok(plays);
            }
        }
        let read = async {
            let session = self.inner.live_session().await?;
            collection::recently_played(&session).await.map_err(web_error)
        };
        match read.await {
            Ok(plays) if !plays.is_empty() => {
                web.save_local(KEY, &plays);
                return Ok(plays);
            }
            Ok(_) => {}
            Err(error) => log::info!("recently played via the Web API: {error}"),
        }
        match web.recently_played(refresh).await {
            Ok(plays) => Ok(plays),
            Err(error) => web
                .load_local::<Vec<RecentPlay>>(KEY, std::time::Duration::from_secs(30 * 86_400))
                .ok_or_else(|| web_error(error)),
        }
    }

    pub async fn top_tracks(&self, refresh: bool) -> Result<Vec<Track>, CoreError> {
        self.inner.web.top_tracks(refresh).await.map_err(web_error)
    }

    pub async fn top_artists(&self, refresh: bool) -> Result<Vec<Artist>, CoreError> {
        self.inner.web.top_artists(refresh).await.map_err(web_error)
    }

    /// Lyrics for a track: Spotify's, else LRCLIB's; None when nobody has them.
    pub async fn lyrics(
        &self,
        track_uri: String,
        title: String,
        artists: String,
        album: String,
        duration_ms: u32,
    ) -> Result<Option<Lyrics>, CoreError> {
        // A dead session only costs the Spotify source; LRCLIB still answers.
        let _ = self.inner.ensure_session().await;
        let session = self.inner.with_session(Session::clone);
        let query = lyrics::Query {
            track_uri: &track_uri,
            title: &title,
            artist: &artists,
            album: &album,
            duration_ms,
        };
        lyrics::fetch(session.as_ref(), &self.inner.cache_dir, &query)
            .await
            .map_err(web_error)
    }

    pub async fn search(&self, query: String) -> Result<SearchResults, CoreError> {
        self.inner.web.search(&query).await.map_err(web_error)
    }

    // ------------------------------------------------- playback --

    /// Reconnect with the credential saved by an earlier sign-in.
    /// Returns the username, or None when no credential is stored.
    pub async fn restore_session(&self) -> Result<Option<String>, CoreError> {
        if self.is_connected() {
            return Ok(self.inner.with_session(|s| s.username()));
        }
        let Some(credentials) = self.inner.cache()?.credentials() else {
            return Ok(None);
        };
        self.inner.connect(credentials).await.map(Some)
    }

    pub fn is_connected(&self) -> bool {
        self.inner.connected.lock().unwrap().is_some()
    }

    /// The signed-in account's username (for its liked-songs context URI).
    pub fn username(&self) -> Option<String> {
        self.inner.with_session(|s| s.username())
    }

    /// Play a playlist, album, artist or liked-songs context. Spotify
    /// resolves its tracks, keeps the queue and continues with similar
    /// songs (autoplay) after the end. Starts at `track_uri` if given.
    pub async fn play_context(
        &self,
        context_uri: String,
        track_uri: Option<String>,
        shuffle: bool,
    ) -> Result<(), CoreError> {
        let options = self.inner.load_options(track_uri.map(PlayingTrack::Uri), shuffle);
        self.inner
            .load(LoadRequest::from_context_uri(context_uri, options))
            .await
    }

    /// Play an explicit list (a filtered or sorted view, search results),
    /// starting at `start_index`. Links and URIs are both accepted.
    pub async fn play_tracks(&self, uris: Vec<String>, start_index: u32, shuffle: bool) -> Result<(), CoreError> {
        let uris = uris
            .iter()
            .map(|uri| parse_track(uri).map(|t| t.to_uri()))
            .collect::<Result<Vec<_>, _>>()?;
        if uris.is_empty() {
            return Err(CoreError::Playback("fila vazia".into()));
        }
        let options = self.inner.load_options(Some(PlayingTrack::Index(start_index)), shuffle);
        self.inner.load(LoadRequest::from_tracks(uris, options)).await
    }

    /// Play an episode with its show as the context (the next episode
    /// follows), from `position_ms`. librespot streams only audio Spotify
    /// hosts itself, so an episode served from the publisher's own feed is
    /// refused here with a clear reason instead of failing in the player.
    pub async fn play_episode(&self, show_id: String, episode_uri: String, position_ms: u32) -> Result<(), CoreError> {
        let uri = SpotifyUri::from_uri(&episode_uri)
            .ok()
            .filter(|uri| matches!(uri, SpotifyUri::Episode { .. }))
            .ok_or_else(|| CoreError::Playback(format!("episódio inválido: {episode_uri}")))?;
        let session = self.inner.live_session().await?;
        match librespot_metadata::Episode::get(&session, &uri).await {
            Ok(episode) if episode.audio.is_empty() => {
                return Err(CoreError::Playback(
                    if episode.external_url.is_empty() {
                        "este episódio não tem áudio que o Nexfy consiga tocar"
                    } else {
                        "este episódio fica hospedado fora do Spotify e o Nexfy não consegue tocá-lo"
                    }
                    .into(),
                ));
            }
            Ok(_) => {}
            // The player reports it if it really cannot load.
            Err(error) => log::info!("episode {episode_uri} metadata: {error}"),
        }
        let mut options = self.inner.load_options(Some(PlayingTrack::Uri(episode_uri)), false);
        options.seek_to = position_ms;
        self.inner
            .load(LoadRequest::from_context_uri(format!("spotify:show:{show_id}"), options))
            .await
    }

    /// The radio of a song or artist (`spotify:track:` / `spotify:artist:`),
    /// for playing it on whichever device plays. The Web API refuses
    /// recommendations to development-mode apps; the session reads the
    /// radio Spotify's own clients use. Cached a day.
    pub async fn radio(&self, seed_uri: String) -> Result<session_reads::Radio, CoreError> {
        let web = &self.inner.web;
        let key = format!("session:radio:{seed_uri}");
        if let Some(radio) = web.load_local::<session_reads::Radio>(&key, std::time::Duration::from_secs(86_400)) {
            return Ok(radio);
        }
        let session = self.inner.live_session().await?;
        let radio = session_reads::radio(&session, &seed_uri).await.map_err(web_error)?;
        web.save_local(&key, &radio);
        Ok(radio)
    }

    /// Play the radio of a song or artist on this phone.
    pub async fn start_radio(&self, seed_uri: String) -> Result<(), CoreError> {
        let radio = self.radio(seed_uri).await?;
        match radio.context_uri {
            Some(context) => self.play_context(context, None, false).await,
            None => self.play_tracks(radio.track_uris, 0, false).await,
        }
    }

    /// Jump to a song of the current context (from the queue view).
    pub async fn skip_to(&self, track_uri: String) -> Result<(), CoreError> {
        let context = self.inner.queue.lock().unwrap().context_uri.clone();
        let context = context.ok_or_else(|| CoreError::Playback("nada tocando".into()))?;
        let shuffle = self.inner.queue.lock().unwrap().shuffle;
        self.play_context(context, Some(track_uri), shuffle).await
    }

    /// Add a song to Spotify's queue ("Na fila"): it plays before the rest
    /// of the context, on whichever device is playing this session.
    pub async fn add_to_queue(&self, track_uri: String) -> Result<(), CoreError> {
        let uri = parse_track(&track_uri)?;
        self.inner.ensure_session().await?;
        self.inner.with_spirc(|spirc| spirc.add_to_queue(uri))
    }

    pub fn seek(&self, position_ms: u32) -> Result<(), CoreError> {
        self.inner.with_spirc(|spirc| spirc.set_position_ms(position_ms))
    }

    pub async fn pause(&self) -> Result<(), CoreError> {
        self.inner.ensure_session().await?;
        log::info!("pause");
        self.inner.with_spirc(|spirc| spirc.pause())
    }

    /// Activates this device first: Spirc drops commands while another
    /// device holds the session, and the song here would not resume.
    pub async fn resume(&self) -> Result<(), CoreError> {
        self.inner.ensure_session().await?;
        log::info!("resume");
        self.inner.with_spirc(|spirc| spirc.activate().and_then(|_| spirc.play()))
    }

    /// Toggles from what Spirc is doing, not what the screen last showed.
    pub async fn play_pause(&self) -> Result<(), CoreError> {
        self.inner.ensure_session().await?;
        log::info!("play/pause");
        self.inner.with_spirc(|spirc| spirc.activate().and_then(|_| spirc.play_pause()))
    }

    pub async fn next(&self) -> Result<(), CoreError> {
        self.inner.ensure_session().await?;
        self.inner.with_spirc(|spirc| spirc.next())
    }

    pub async fn previous(&self) -> Result<(), CoreError> {
        self.inner.ensure_session().await?;
        self.inner.with_spirc(|spirc| spirc.prev())
    }

    pub fn set_shuffle(&self, shuffle: bool) -> Result<(), CoreError> {
        self.inner.with_spirc(|spirc| spirc.shuffle(shuffle))
    }

    pub fn set_repeat(&self, repeat: RepeatMode) -> Result<(), CoreError> {
        self.inner.with_spirc(|spirc| match repeat {
            RepeatMode::Off => spirc.repeat_track(false).and_then(|_| spirc.repeat(false)),
            RepeatMode::All => spirc.repeat_track(false).and_then(|_| spirc.repeat(true)),
            RepeatMode::One => spirc.repeat(false).and_then(|_| spirc.repeat_track(true)),
        })
    }

    /// Lower the volume while another app speaks (audio focus "can duck").
    pub fn set_ducked(&self, ducked: bool) {
        self.inner.levels.set_ducked(ducked)
    }

    // ---------------------------------------------------------- sound --

    pub fn sound_settings(&self) -> SoundSettings {
        SoundSettings {
            quality: AudioQuality::load(&self.inner.cache_dir),
            active_quality: self.inner.quality,
            normalize: self.inner.levels.normalize(),
        }
    }

    /// Takes effect on the next app start, with the player built anew.
    pub fn set_audio_quality(&self, quality: AudioQuality) {
        quality.save(&self.inner.cache_dir)
    }

    /// Heard at once, even mid-song.
    pub fn set_normalize(&self, on: bool) {
        self.inner.levels.set_normalize(on);
        sound::save_normalize(&self.inner.cache_dir, on);
    }

    /// Extra gain from 0 to 1 over everything else, for the sleep timer's
    /// fade out; the caller sets it back to 1.
    pub fn set_fade(&self, gain: f64) {
        self.inner.levels.set_fade(gain)
    }

    pub fn queue_state(&self) -> QueueState {
        self.inner.queue.lock().unwrap().clone()
    }

    /// Metadata for queue entries (cached per track), in the given order.
    pub async fn tracks_info(&self, uris: Vec<String>) -> Result<Vec<Track>, CoreError> {
        let missing: Vec<String> = {
            let cache = self.inner.track_cache.lock().unwrap();
            uris.iter().filter(|uri| !cache.contains_key(*uri)).cloned().collect()
        };
        if !missing.is_empty() {
            self.inner.ensure_session().await?;
            let session = self
                .inner
                .with_session(Session::clone)
                .ok_or_else(|| CoreError::Session("não conectado".into()))?;
            let fetched = session_reads::tracks(&session, &missing).await;
            let mut cache = self.inner.track_cache.lock().unwrap();
            if cache.len() > TRACK_CACHE_MAX {
                cache.clear();
            }
            for track in fetched {
                cache.insert(track.uri.clone(), track);
            }
            save_track_cache(&self.inner.cache_dir, &cache);
        }
        let cache = self.inner.track_cache.lock().unwrap();
        Ok(uris.iter().filter_map(|uri| cache.get(uri).cloned()).collect())
    }


    // ---------------------------------------------- songs on the phone --

    /// How much the audio cache holds and may hold.
    pub fn cache_info(&self) -> CacheInfo {
        self.inner.offline.info()
    }

    /// Audio cache size; takes effect on the next app start.
    pub fn set_cache_limit(&self, bytes: u64) {
        self.inner.offline.set_limit(bytes)
    }

    pub fn clear_audio_cache(&self) {
        self.inner.offline.clear()
    }

    /// How many of these songs are on the phone already.
    pub fn cached_count(&self, uris: Vec<String>) -> u32 {
        self.inner
            .with_session(|session| self.inner.offline.cached_count(session, &uris))
            .unwrap_or(0)
    }

    /// Download songs into the audio cache, one at a time with a pause
    /// between downloads, like a listener would fetch them. Returns how
    /// many were downloaded now.
    pub async fn cache_tracks(&self, uris: Vec<String>) -> Result<u32, CoreError> {
        let session = self.inner.live_session().await?;
        let mut downloaded = 0;
        for uri in uris {
            match self.inner.offline.download(&session, &uri).await {
                Ok(true) => {
                    downloaded += 1;
                    tokio::time::sleep(std::time::Duration::from_secs(2)).await;
                }
                Ok(false) => {}
                Err(error) => log::info!("could not cache {uri}: {error:#}"),
            }
        }
        Ok(downloaded)
    }
    // ------------------------------------------------- other devices --

    /// The account's devices: from the Connect cluster the session keeps,
    /// the Web API only before the first cluster arrives.
    pub async fn devices(&self) -> Result<Vec<Device>, CoreError> {
        let session = self.inner.live_session().await?;
        if let Some(devices) = remote::session_devices(&session) {
            log::info!("devices from the cluster: {:?}", devices.iter().map(|d| d.name.as_str()).collect::<Vec<_>>());
            return Ok(devices);
        }
        remote::devices(&session, &self.inner.web.player_tokens().await).await.map_err(web_error)
    }

    /// What is playing on any device; None when nothing is. Read from the
    /// cluster, so polling it costs no Web API quota.
    pub async fn remote_state(&self) -> Result<Option<RemoteState>, CoreError> {
        let session = self.inner.live_session().await?;
        if let Some(state) = remote::session_state(&session).await {
            return Ok(state);
        }
        remote::state(&session, &self.inner.web.player_tokens().await).await.map_err(web_error)
    }

    /// Move playback to another device (or back to this phone).
    pub async fn transfer(&self, device_id: String, play: bool) -> Result<(), CoreError> {
        let session = self.inner.live_session().await?;
        if let Some(Ok(())) = remote::session_transfer(&session, &device_id).await {
            return Ok(());
        }
        remote::transfer(&self.inner.web.player_tokens().await, &device_id, play).await.map_err(web_error)
    }

    /// What plays next on the device playing elsewhere.
    pub async fn remote_queue(&self) -> Result<Vec<Track>, CoreError> {
        let session = self.inner.live_session().await?;
        if let Some(uris) = remote::session_queue_uris() {
            return Ok(session_reads::tracks(&session, &uris).await);
        }
        remote::queue(&self.inner.web.player_tokens().await).await.map_err(web_error)
    }

    /// Control the device that is playing: through spclient like Spotify's
    /// apps, the Web API when that is not possible.
    pub async fn remote_command(&self, command: RemoteCommand) -> Result<(), CoreError> {
        let session = self.inner.live_session().await?;
        if let Some(Ok(())) = remote::session_command(&session, &command).await {
            return Ok(());
        }
        remote::command(&self.inner.web.player_tokens().await, command).await.map_err(web_error)
    }
}

impl Inner {
    fn begin(&self, target: LoginTarget, grant: auth::Grant) -> Result<String, CoreError> {
        let listener = auth::listen(&grant).map_err(|e| CoreError::Login(format!("{e:#}")))?;
        let flow = auth::begin(&grant);
        let url = flow.url.clone();
        *self.pending_login.lock().unwrap() = Some(PendingLogin {
            target,
            grant,
            flow,
            listener,
        });
        Ok(url)
    }

    /// Wait for the redirect of the pending sign-in `expected` matches and
    /// trade its code for a token.
    async fn finish(
        &self,
        expected: impl Fn(&LoginTarget) -> bool,
    ) -> Result<auth::TokenResponse, CoreError> {
        let pending = self
            .pending_login
            .lock()
            .unwrap()
            .take()
            .filter(|p| expected(&p.target))
            .ok_or_else(|| CoreError::Login("nenhum login em andamento".into()))?;
        let code = auth::wait_for_code(pending.listener, &pending.flow)
            .await
            .map_err(|e| CoreError::Login(format!("{e:#}")))?;
        auth::exchange_code(&pending.grant, &pending.flow, &code)
            .await
            .map_err(|e| CoreError::Login(format!("{e:#}")))
    }

    fn cache(&self) -> Result<Cache, CoreError> {
        let credentials = self.cache_dir.join("credentials");
        let audio = self.cache_dir.join("audio");
        Cache::new(Some(credentials), None, Some(audio), Some(self.offline.limit()))
            .map_err(|e| CoreError::Session(e.to_string()))
    }

    fn emit(&self, event: CoreEvent) {
        let listener = self.listener.lock().unwrap().clone();
        if let Some(listener) = listener {
            listener.on_event(event);
        }
    }

    fn update_queue(&self, change: impl FnOnce(&mut QueueState)) {
        let queue = {
            let mut queue = self.queue.lock().unwrap();
            change(&mut queue);
            queue.clone()
        };
        self.emit(CoreEvent::QueueChanged { queue });
    }

    /// Load options keeping the current repeat mode.
    fn load_options(&self, playing_track: Option<PlayingTrack>, shuffle: bool) -> LoadRequestOptions {
        let repeat = self.queue.lock().unwrap().repeat;
        LoadRequestOptions {
            start_playing: true,
            seek_to: 0,
            context_options: Some(LoadContextOptions::Options(Options {
                shuffle,
                repeat: repeat == RepeatMode::All,
                repeat_track: repeat == RepeatMode::One,
            })),
            playing_track,
        }
    }

    async fn load(&self, request: LoadRequest) -> Result<(), CoreError> {
        self.ensure_session().await?;
        self.with_spirc(|spirc| spirc.activate().and_then(|_| spirc.load(request)))
    }

    /// A new, not yet connected session: Spirc signs it in itself.
    /// librespot does not reconnect a dropped session, so each connection
    /// gets a fresh one.
    fn new_session(&self) -> Result<Session, CoreError> {
        // Our token comes from the desktop (keymaster) client; the session
        // must use the same id or login5 rejects it.
        let config = SessionConfig {
            client_id: auth::PLAYBACK_CLIENT_ID.to_owned(),
            ..SessionConfig::default()
        };
        Ok(Session::new(config, Some(self.cache()?)))
    }

    /// The reusable credential librespot stored at sign-in.
    fn stored_credentials(&self) -> Result<Credentials, CoreError> {
        self.cache()?
            .credentials()
            .ok_or_else(|| CoreError::Session("credencial salva não encontrada".into()))
    }

    /// Sign `session` in with `credentials` (storing a reusable credential)
    /// and register this phone as a Connect device driving `player`.
    async fn start_spirc(
        &self,
        session: &Session,
        player: Arc<Player>,
        credentials: Credentials,
    ) -> Result<Arc<Spirc>, CoreError> {
        let config = ConnectConfig {
            name: self.device_name.clone(),
            device_type: DeviceType::Smartphone,
            initial_volume: u16::MAX,
            emit_set_queue_events: true,
            ..ConnectConfig::default()
        };
        let mixer: Arc<dyn Mixer> = Arc::new(NexfyMixer::new(self.levels.clone()));
        let (spirc, task) = Spirc::new(config, session.clone(), credentials, player, mixer)
            .await
            .map_err(|e| CoreError::Session(e.to_string()))?;
        tokio::spawn(task);
        Ok(Arc::new(spirc))
    }

    async fn connect(self: &Arc<Self>, credentials: Credentials) -> Result<String, CoreError> {
        let session = self.new_session()?;

        let backend = audio_backend::find(None)
            .ok_or_else(|| CoreError::Playback("nenhuma saída de áudio".into()))?;
        let mixer = NexfyMixer::new(self.levels.clone());
        let player = Player::new(
            sound::player_config(self.quality, &self.levels),
            session.clone(),
            mixer.get_soft_volume(),
            move || backend(None, AudioFormat::default()),
        );
        tokio::spawn(run_events(
            Arc::downgrade(self),
            player.get_player_event_channel(),
        ));
        let spirc = self.start_spirc(&session, player.clone(), credentials).await?;

        let username = session.username();
        *self.connected.lock().unwrap() = Some(Connected { session, player, spirc });
        log::info!("connected as {username}, Connect device \"{}\"", self.device_name);
        Ok(username)
    }

    /// Replace a session the server closed (backgrounded app, Wi-Fi power
    /// saving) before using it. The Connect device is registered again on
    /// the new session and what was playing resumes where it was.
    async fn ensure_session(&self) -> Result<(), CoreError> {
        let (player, old_spirc) = {
            let connected = self.connected.lock().unwrap();
            let connected = connected
                .as_ref()
                .ok_or_else(|| CoreError::Session("não conectado".into()))?;
            if !connected.session.is_invalid() {
                return Ok(());
            }
            (connected.player.clone(), connected.spirc.clone())
        };

        log::warn!("session closed by the server, reconnecting");
        let snapshot = old_spirc.disconnected_playback();
        let _ = old_spirc.shutdown();
        let session = self.new_session()?;
        player.set_session(session.clone());
        let spirc = self.start_spirc(&session, player, self.stored_credentials()?).await?;
        if let Some(snapshot) = snapshot {
            if let Err(error) = spirc.restore_playback(snapshot) {
                log::warn!("could not restore playback: {error}");
            }
        }
        if let Some(connected) = self.connected.lock().unwrap().as_mut() {
            connected.session = session;
            connected.spirc = spirc;
        }
        Ok(())
    }

    fn with_spirc(&self, action: impl FnOnce(&Spirc) -> Result<(), LibrespotError>) -> Result<(), CoreError> {
        let spirc = self
            .connected
            .lock()
            .unwrap()
            .as_ref()
            .map(|connected| connected.spirc.clone())
            .ok_or_else(|| CoreError::Playback("não conectado".into()))?;
        action(&spirc).map_err(|e| CoreError::Playback(e.to_string()))
    }

    /// A connected session, reconnecting first if it dropped.
    async fn live_session(&self) -> Result<Session, CoreError> {
        // At launch the screens ask before the saved login reconnects:
        // wait for it briefly rather than fall back to the Web API.
        for _ in 0..40 {
            if self.connected.lock().unwrap().is_some() {
                break;
            }
            tokio::time::sleep(std::time::Duration::from_millis(250)).await;
        }
        self.ensure_session().await?;
        self.with_session(Session::clone)
            .ok_or_else(|| CoreError::Session("não conectado".into()))
    }

    fn with_session<T>(&self, action: impl FnOnce(&Session) -> T) -> Option<T> {
        self.connected
            .lock()
            .unwrap()
            .as_ref()
            .map(|connected| action(&connected.session))
    }

}

/// How many upcoming and past queue entries Kotlin gets.
const QUEUE_AHEAD: usize = 100;
const QUEUE_BEHIND: usize = 20;

fn entry(track: QueueTrack) -> QueueEntry {
    QueueEntry {
        // Spotify's own queue ("Na fila") reports this provider.
        queued: track.provider == "queue",
        uri: track.uri,
    }
}

/// Forward librespot's player and Connect events to Kotlin. Spirc drives
/// the queue itself (next track, autoplay, remote commands).
/// Reads the new releases over the session and keeps them for [`Core::new_releases`].
async fn read_releases(inner: &Arc<Inner>) -> Result<Vec<Album>, CoreError> {
    let web = &inner.web;
    let mut ids: Vec<String> = web.top_artists(false).await.map(|artists| artists.into_iter().map(|a| a.id).collect()).unwrap_or_default();
    if let Ok(recent) = web.recently_played(false).await {
        for play in recent {
            for id in play.track.artist_ids {
                if !ids.contains(&id) {
                    ids.push(id);
                }
            }
        }
    }
    if ids.len() < 8 {
        // The Web API could not say who the user listens to: the artists of
        // the songs the app knows (the queue and playlists opened) instead.
        let cache = inner.track_cache.lock().unwrap();
        let mut counts: HashMap<&String, usize> = HashMap::new();
        for id in cache.values().flat_map(|track| track.artist_ids.iter()) {
            *counts.entry(id).or_default() += 1;
        }
        let mut known: Vec<(&String, usize)> = counts.into_iter().collect();
        known.sort_by(|a, b| b.1.cmp(&a.1));
        for (id, _) in known.into_iter().take(30) {
            if !ids.contains(id) {
                ids.push(id.clone());
            }
        }
    }
    let session = inner.live_session().await?;
    let albums = session_reads::releases(&session, &ids, 90, 20).await;
    if !albums.is_empty() {
        web.save_local("session:releases", &albums);
    }
    Ok(albums)
}

async fn run_events(inner: Weak<Inner>, mut events: PlayerEventChannel) {
    while let Some(event) = events.recv().await {
        let Some(inner) = inner.upgrade() else { break };
        match event {
            PlayerEvent::TrackChanged { audio_item } => inner.emit(CoreEvent::TrackChanged {
                track: track_info(&audio_item),
            }),
            PlayerEvent::Playing { position_ms, .. } => {
                inner.playing.store(true, Ordering::Relaxed);
                inner.emit(CoreEvent::Playing { position_ms });
            }
            PlayerEvent::Paused { position_ms, .. } => {
                inner.playing.store(false, Ordering::Relaxed);
                inner.emit(CoreEvent::Paused { position_ms });
            }
            PlayerEvent::Seeked { position_ms, .. }
            | PlayerEvent::PositionCorrection { position_ms, .. } => {
                inner.emit(if inner.playing.load(Ordering::Relaxed) {
                    CoreEvent::Playing { position_ms }
                } else {
                    CoreEvent::Paused { position_ms }
                })
            }
            PlayerEvent::Loading { .. } => inner.emit(CoreEvent::Loading),
            PlayerEvent::Stopped { .. } => {
                inner.playing.store(false, Ordering::Relaxed);
                inner.emit(CoreEvent::Stopped);
            }
            PlayerEvent::Unavailable { track_id, .. } => inner.emit(CoreEvent::Error {
                message: match track_id {
                    // Most often one hosted outside Spotify, which librespot cannot stream.
                    SpotifyUri::Episode { .. } => format!("episódio indisponível no Nexfy: {}", track_id.to_uri()),
                    _ => format!("faixa indisponível: {}", track_id.to_uri()),
                },
            }),
            PlayerEvent::SetQueue {
                context_uri,
                current_track,
                next_tracks,
                prev_tracks,
            } => inner.update_queue(|queue| {
                queue.context_uri = Some(context_uri).filter(|uri| !uri.is_empty());
                queue.current = current_track.map(entry);
                queue.next = next_tracks.into_iter().take(QUEUE_AHEAD).map(entry).collect();
                let skip = prev_tracks.len().saturating_sub(QUEUE_BEHIND);
                queue.previous = prev_tracks.into_iter().skip(skip).map(entry).collect();
            }),
            PlayerEvent::ShuffleChanged { shuffle } => inner.update_queue(|queue| queue.shuffle = shuffle),
            PlayerEvent::RepeatChanged { context, track } => inner.update_queue(|queue| {
                queue.repeat = if track {
                    RepeatMode::One
                } else if context {
                    RepeatMode::All
                } else {
                    RepeatMode::Off
                }
            }),
            _ => {}
        }
    }
}

fn track_info(item: &AudioItem) -> TrackInfo {
    let (artists, album) = match &item.unique_fields {
        UniqueFields::Track { artists, album, .. } => (
            artists
                .iter()
                .map(|artist| artist.name.as_str())
                .collect::<Vec<_>>()
                .join(", "),
            album.clone(),
        ),
        UniqueFields::Episode { show_name, .. } => (show_name.clone(), String::new()),
        UniqueFields::Local { artists, album, .. } => (
            artists.clone().unwrap_or_default(),
            album.clone().unwrap_or_default(),
        ),
    };
    TrackInfo {
        uri: item.uri.clone(),
        title: item.name.clone(),
        artists,
        album,
        cover_url: item
            .covers
            .iter()
            .max_by_key(|cover| cover.width)
            .map(|cover| cover.url.clone()),
        duration_ms: item.duration_ms,
    }
}

fn parse_track(input: &str) -> Result<SpotifyUri, CoreError> {
    let input = input.trim();
    let id = if let Some(rest) = input.strip_prefix("spotify:track:") {
        rest
    } else if let Some(rest) = input.split("/track/").nth(1) {
        rest.split(['?', '/']).next().unwrap_or(rest)
    } else {
        input
    };
    let id = SpotifyId::from_base62(id)
        .map_err(|_| CoreError::Playback(format!("faixa inválida: {input}")))?;
    Ok(SpotifyUri::Track { id })
}

/// Most songs whose metadata is kept for the queue; past it the cache starts over.
const TRACK_CACHE_MAX: usize = 20_000;

/// Queue metadata kept on disk, so the queue shows complete right after a restart.
fn load_track_cache(dir: &std::path::Path) -> HashMap<String, Track> {
    std::fs::read(dir.join("tracks.json"))
        .ok()
        .and_then(|bytes| serde_json::from_slice(&bytes).ok())
        .unwrap_or_default()
}

fn save_track_cache(dir: &std::path::Path, cache: &HashMap<String, Track>) {
    if let Ok(json) = serde_json::to_vec(cache) {
        let _ = std::fs::write(dir.join("tracks.json"), json);
    }
}
