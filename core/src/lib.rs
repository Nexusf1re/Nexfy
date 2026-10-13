//! Nexfy's Spotify core: sign-in, the librespot session, Spotify Connect
//! playback and the Web API, exported to Kotlin through UniFFI.
//!
//! Playback runs through Spirc, Spotify's Connect state machine: the phone
//! is a Connect device of the account, Spotify keeps the queue and context
//! (and autoplay after it), and other devices can see and control it.

mod auth;
#[cfg(target_os = "android")]
mod android;
mod lyrics;
mod offline;
mod remote;
mod session_reads;
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
use librespot_metadata::audio::{AudioItem, UniqueFields};
use librespot_playback::audio_backend;
use librespot_playback::config::{AudioFormat, PlayerConfig};
use librespot_playback::mixer::{Mixer, MixerConfig, VolumeGetter};
use librespot_playback::player::{Player, PlayerEvent, PlayerEventChannel, QueueTrack};
use tokio::net::TcpListener;

pub use lyrics::{Lyrics, LyricsLine};
pub use webapi::{
    Album, AlbumDetail, Artist, ArtistDetail, Playlist, PlaylistDetail, Profile, RecentPlay,
    SearchResults, Track, TrackPage, WebApp, WebStatus,
};
use webapi::WebApi;
pub use offline::CacheInfo;
pub use remote::{Device, RemoteCommand, RemoteState};

uniffi::setup_scaffolding!();

/// Volume while another app's sound has focus (navigation, notifications).
const DUCKED_VOLUME: f64 = 0.2;

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
/// ducking gain (another app speaking). The phone's own volume keys still
/// work on top, through Android's stream volume.
#[derive(Clone)]
struct NexfyMixer {
    volume: Arc<AtomicU64>,
    duck: Arc<AtomicU64>,
}

impl NexfyMixer {
    fn new(duck: Arc<AtomicU64>) -> Self {
        Self {
            volume: Arc::new(AtomicU64::new(1.0f64.to_bits())),
            duck,
        }
    }
}

impl Mixer for NexfyMixer {
    fn open(_: MixerConfig) -> Result<Self, LibrespotError> {
        Ok(Self::new(Arc::new(AtomicU64::new(1.0f64.to_bits()))))
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
            duck: self.duck.clone(),
        })
    }
}

struct Gain {
    volume: Arc<AtomicU64>,
    duck: Arc<AtomicU64>,
}

impl VolumeGetter for Gain {
    fn attenuation_factor(&self) -> f64 {
        // Squared: Connect sliders feel linear to the ear this way.
        let volume = f64::from_bits(self.volume.load(Ordering::Relaxed));
        volume * volume * f64::from_bits(self.duck.load(Ordering::Relaxed))
    }
}

struct Inner {
    cache_dir: PathBuf,
    /// How this phone appears in Spotify's device list.
    device_name: String,
    pending_login: Mutex<Option<PendingLogin>>,
    web: WebApi,
    offline: offline::Offline,
    connected: Mutex<Option<Connected>>,
    queue: Mutex<QueueState>,
    /// Metadata for queue entries, fetched once per track.
    track_cache: Mutex<HashMap<String, Track>>,
    listener: Mutex<Option<Arc<dyn CoreListener>>>,
    playing: AtomicBool,
    duck: Arc<AtomicU64>,
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
        Arc::new(Self {
            inner: Arc::new(Inner {
                web: WebApi::new(&cache_dir),
                offline: offline::Offline::new(&cache_dir),
                cache_dir,
                device_name,
                pending_login: Mutex::new(None),
                connected: Mutex::new(None),
                queue: Mutex::new(QueueState::default()),
                track_cache: Mutex::new(HashMap::new()),
                listener: Mutex::new(None),
                playing: AtomicBool::new(false),
                duck: Arc::new(AtomicU64::new(1.0f64.to_bits())),
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

    /// `refresh` skips the cache; otherwise recent results come from disk.
    pub async fn profile(&self, refresh: bool) -> Result<Profile, CoreError> {
        self.inner.web.profile(refresh).await.map_err(web_error)
    }

    pub async fn my_playlists(&self, refresh: bool) -> Result<Vec<Playlist>, CoreError> {
        self.inner.web.my_playlists(refresh).await.map_err(web_error)
    }

    /// Web API first; playlists it refuses (other people's, for
    /// development-mode apps) are read over the playback session.
    pub async fn playlist(&self, id: String, refresh: bool) -> Result<PlaylistDetail, CoreError> {
        let web = &self.inner.web;
        let meta = web.playlist_meta(&id, refresh).await;
        let tracks = match web.playlist_items(&id, refresh).await {
            Ok(tracks) => tracks,
            Err(error) => {
                log::info!("playlist {id} via session: {error:#}");
                let key = format!("session:playlist:{id}");
                let ttl = std::time::Duration::from_secs(600);
                match web.load_local::<Vec<Track>>(&key, ttl).filter(|_| !refresh) {
                    Some(tracks) => tracks,
                    None => {
                        self.inner.ensure_session().await?;
                        let session = self
                            .inner
                            .with_session(Session::clone)
                            .ok_or_else(|| CoreError::Session("não conectado".into()))?;
                        let data = session_reads::playlist(&session, &id).await.map_err(web_error)?;
                        web.save_local(&key, &data.tracks);
                        if meta.is_err() {
                            return Ok(PlaylistDetail {
                                playlist: Playlist {
                                    id: id.clone(),
                                    uri: format!("spotify:playlist:{id}"),
                                    name: data.name,
                                    description: data.description,
                                    owner: String::new(),
                                    owner_id: String::new(),
                                    image_url: data.tracks.first().and_then(|t| t.image_url.clone()),
                                    track_count: data.tracks.len() as u32,
                                },
                                tracks: data.tracks,
                            });
                        }
                        data.tracks
                    }
                }
            }
        };
        let mut playlist = meta.map_err(web_error)?;
        if playlist.image_url.is_none() {
            playlist.image_url = tracks.first().and_then(|t| t.image_url.clone());
        }
        Ok(PlaylistDetail { playlist, tracks })
    }

    pub async fn liked_tracks(&self, offset: u32, limit: u32, refresh: bool) -> Result<TrackPage, CoreError> {
        self.inner.web.liked_tracks(offset, limit, refresh).await.map_err(web_error)
    }

    pub async fn saved_albums(&self, refresh: bool) -> Result<Vec<Album>, CoreError> {
        self.inner.web.saved_albums(refresh).await.map_err(web_error)
    }

    pub async fn album(&self, id: String, refresh: bool) -> Result<AlbumDetail, CoreError> {
        self.inner.web.album(&id, refresh).await.map_err(web_error)
    }

    pub async fn artist(&self, id: String, refresh: bool) -> Result<ArtistDetail, CoreError> {
        self.inner.web.artist(&id, refresh).await.map_err(web_error)
    }

    pub async fn recently_played(&self, refresh: bool) -> Result<Vec<RecentPlay>, CoreError> {
        self.inner.web.recently_played(refresh).await.map_err(web_error)
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

    pub fn pause(&self) -> Result<(), CoreError> {
        self.inner.with_spirc(|spirc| spirc.pause())
    }

    pub async fn resume(&self) -> Result<(), CoreError> {
        self.inner.ensure_session().await?;
        self.inner.with_spirc(|spirc| spirc.play())
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
        let gain = if ducked { DUCKED_VOLUME } else { 1.0 };
        self.inner.duck.store(gain.to_bits(), Ordering::Relaxed);
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
            for track in fetched {
                cache.insert(track.uri.clone(), track);
            }
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

    /// The account's Spotify Connect devices, this phone included.
    pub async fn devices(&self) -> Result<Vec<Device>, CoreError> {
        let session = self.inner.live_session().await?;
        remote::devices(&session, &self.inner.web.player_tokens().await).await.map_err(web_error)
    }

    /// What is playing on any device; None when nothing is.
    pub async fn remote_state(&self) -> Result<Option<RemoteState>, CoreError> {
        let session = self.inner.live_session().await?;
        remote::state(&session, &self.inner.web.player_tokens().await).await.map_err(web_error)
    }

    /// Move playback to another device (or back to this phone).
    pub async fn transfer(&self, device_id: String, play: bool) -> Result<(), CoreError> {
        remote::transfer(&self.inner.web.player_tokens().await, &device_id, play).await.map_err(web_error)
    }

    /// Control the device that is playing.
    pub async fn remote_command(&self, command: RemoteCommand) -> Result<(), CoreError> {
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
        let mixer: Arc<dyn Mixer> = Arc::new(NexfyMixer::new(self.duck.clone()));
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
        let mixer = NexfyMixer::new(self.duck.clone());
        let player = Player::new(
            PlayerConfig::default(),
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
                message: format!("faixa indisponível: {}", track_id.to_uri()),
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
