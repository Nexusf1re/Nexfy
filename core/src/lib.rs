//! Sonata's Spotify core: sign-in, the librespot session, the play queue and
//! local playback, exported to Kotlin through UniFFI.

mod auth;
#[cfg(target_os = "android")]
mod android;
mod queue;
mod webapi;

use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, Weak};

use librespot_core::authentication::Credentials;
use librespot_core::cache::Cache;
use librespot_core::config::SessionConfig;
use librespot_core::session::Session;
use librespot_core::{SpotifyId, SpotifyUri};
use librespot_metadata::audio::{AudioItem, UniqueFields};
use librespot_playback::audio_backend;
use librespot_playback::config::{AudioFormat, PlayerConfig};
use librespot_playback::mixer::VolumeGetter;
use librespot_playback::player::{Player, PlayerEvent, PlayerEventChannel};
use tokio::net::TcpListener;

pub use queue::RepeatMode;
use queue::Queue;
pub use webapi::{
    Album, AlbumDetail, Artist, ArtistDetail, Playlist, PlaylistDetail, Profile, SearchResults,
    Track, TrackPage, WebApp, WebStatus,
};
use webapi::WebApi;

uniffi::setup_scaffolding!();

/// Volume while another app's sound has focus (navigation, notifications).
const DUCKED_VOLUME: f64 = 0.2;

#[derive(Debug, thiserror::Error, uniffi::Error)]
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

#[derive(Debug, Clone, uniffi::Record)]
pub struct QueueState {
    /// Track URIs in play order.
    pub uris: Vec<String>,
    /// Play-order index of the current track.
    pub position: u32,
    pub shuffle: bool,
    pub repeat: RepeatMode,
}

#[derive(Debug, Clone, uniffi::Enum)]
pub enum CoreEvent {
    TrackChanged { track: TrackInfo },
    /// Playing from `position_ms` as of now; also sent after seeks.
    Playing { position_ms: u32 },
    Paused { position_ms: u32 },
    Loading,
    /// The queue finished.
    Ended,
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
}

/// Software gain the player applies to every sample, for ducking.
struct Gain(Arc<AtomicU64>);

impl VolumeGetter for Gain {
    fn attenuation_factor(&self) -> f64 {
        f64::from_bits(self.0.load(Ordering::Relaxed))
    }
}

struct Inner {
    cache_dir: PathBuf,
    pending_login: Mutex<Option<PendingLogin>>,
    web: WebApi,
    connected: Mutex<Option<Connected>>,
    queue: Mutex<Queue>,
    listener: Mutex<Option<Arc<dyn CoreListener>>>,
    playing: AtomicBool,
    gain: Arc<AtomicU64>,
}

#[derive(uniffi::Object)]
pub struct SonataCore {
    inner: Arc<Inner>,
}

#[uniffi::export(async_runtime = "tokio")]
impl SonataCore {
    /// `cache_dir` is app-private storage; librespot keeps the reusable
    /// credential and the audio cache there.
    #[uniffi::constructor]
    pub fn new(cache_dir: String) -> Arc<Self> {
        let cache_dir = PathBuf::from(cache_dir);
        Arc::new(Self {
            inner: Arc::new(Inner {
                web: WebApi::new(&cache_dir),
                cache_dir,
                pending_login: Mutex::new(None),
                connected: Mutex::new(None),
                queue: Mutex::new(Queue::default()),
                listener: Mutex::new(None),
                playing: AtomicBool::new(false),
                gain: Arc::new(AtomicU64::new(1.0f64.to_bits())),
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

    pub async fn playlist(&self, id: String, refresh: bool) -> Result<PlaylistDetail, CoreError> {
        self.inner.web.playlist(&id, refresh).await.map_err(web_error)
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

    /// Replace the queue with `uris` (track URIs or open.spotify.com links)
    /// and play from `start_index`.
    pub async fn play_queue(&self, uris: Vec<String>, start_index: u32) -> Result<(), CoreError> {
        let tracks = uris
            .iter()
            .map(|uri| parse_track(uri))
            .collect::<Result<Vec<_>, _>>()?;
        if tracks.is_empty() {
            return Err(CoreError::Playback("fila vazia".into()));
        }
        let first = {
            let mut queue = self.inner.queue.lock().unwrap();
            queue.set(tracks, start_index as usize);
            queue.current()
        };
        self.inner.emit_queue();
        match first {
            Some(track) => self.inner.load(track).await,
            None => Ok(()),
        }
    }

    pub async fn play_track(&self, uri: String) -> Result<(), CoreError> {
        self.play_queue(vec![uri], 0).await
    }

    /// Jump to a play-order index of the queue.
    pub async fn skip_to(&self, position: u32) -> Result<(), CoreError> {
        let track = self.inner.queue.lock().unwrap().skip_to(position as usize);
        let track = track.ok_or_else(|| CoreError::Playback("posição fora da fila".into()))?;
        self.inner.emit_queue();
        self.inner.load(track).await
    }

    pub fn seek(&self, position_ms: u32) -> Result<(), CoreError> {
        self.inner.with_player(|player| player.seek(position_ms))
    }

    pub fn pause(&self) -> Result<(), CoreError> {
        self.inner.with_player(|player| player.pause())
    }

    pub async fn resume(&self) -> Result<(), CoreError> {
        self.inner.ensure_session().await?;
        self.inner.with_player(|player| player.play())
    }

    pub fn stop(&self) -> Result<(), CoreError> {
        self.inner.with_player(|player| player.stop())
    }

    pub fn set_shuffle(&self, shuffle: bool) {
        self.inner.queue.lock().unwrap().set_shuffle(shuffle);
        self.inner.emit_queue();
    }

    pub fn set_repeat(&self, repeat: RepeatMode) {
        self.inner.queue.lock().unwrap().set_repeat(repeat);
        self.inner.emit_queue();
    }

    /// Lower the volume while another app speaks (audio focus "can duck").
    pub fn set_ducked(&self, ducked: bool) {
        let gain = if ducked { DUCKED_VOLUME } else { 1.0 };
        self.inner.gain.store(gain.to_bits(), Ordering::Relaxed);
    }

    pub fn queue_state(&self) -> QueueState {
        self.inner.queue_state()
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
        Cache::new(Some(credentials), None, Some(audio), Some(512 * 1024 * 1024))
            .map_err(|e| CoreError::Session(e.to_string()))
    }

    fn emit(&self, event: CoreEvent) {
        let listener = self.listener.lock().unwrap().clone();
        if let Some(listener) = listener {
            listener.on_event(event);
        }
    }

    fn queue_state(&self) -> QueueState {
        let queue = self.queue.lock().unwrap();
        QueueState {
            uris: queue.in_play_order().iter().map(SpotifyUri::to_uri).collect(),
            position: queue.position() as u32,
            shuffle: queue.shuffle(),
            repeat: queue.repeat(),
        }
    }

    fn emit_queue(&self) {
        self.emit(CoreEvent::QueueChanged {
            queue: self.queue_state(),
        });
    }

    /// A new session signed in with `credentials`. librespot does not
    /// reconnect a dropped session, so each connection gets a fresh one.
    async fn open_session(&self, credentials: Credentials) -> Result<Session, CoreError> {
        // Our token comes from the desktop (keymaster) client; the session
        // must use the same id or login5 rejects it.
        let config = SessionConfig {
            client_id: auth::PLAYBACK_CLIENT_ID.to_owned(),
            ..SessionConfig::default()
        };
        let session = Session::new(config, Some(self.cache()?));
        session
            .connect(credentials, true)
            .await
            .map_err(|e| CoreError::Session(e.to_string()))?;
        Ok(session)
    }

    async fn connect(self: &Arc<Self>, credentials: Credentials) -> Result<String, CoreError> {
        let session = self.open_session(credentials).await?;

        let backend = audio_backend::find(None)
            .ok_or_else(|| CoreError::Playback("nenhuma saída de áudio".into()))?;
        let player = Player::new(
            PlayerConfig::default(),
            session.clone(),
            Box::new(Gain(self.gain.clone())),
            move || backend(None, AudioFormat::default()),
        );
        tokio::spawn(run_events(
            Arc::downgrade(self),
            player.get_player_event_channel(),
        ));

        let username = session.username();
        *self.connected.lock().unwrap() = Some(Connected { session, player });
        log::info!("connected as {username}");
        Ok(username)
    }

    /// Replace a session the server closed (backgrounded app, Wi-Fi power
    /// saving) before using it; a dead one fails key requests and leaves the
    /// player decoding encrypted audio.
    async fn ensure_session(&self) -> Result<(), CoreError> {
        let player = {
            let connected = self.connected.lock().unwrap();
            let connected = connected
                .as_ref()
                .ok_or_else(|| CoreError::Session("não conectado".into()))?;
            if !connected.session.is_invalid() {
                return Ok(());
            }
            connected.player.clone()
        };

        log::warn!("session closed by the server, reconnecting");
        let credentials = self
            .cache()?
            .credentials()
            .ok_or_else(|| CoreError::Session("credencial salva não encontrada".into()))?;
        let session = self.open_session(credentials).await?;
        player.set_session(session.clone());
        if let Some(connected) = self.connected.lock().unwrap().as_mut() {
            connected.session = session;
        }
        Ok(())
    }

    async fn load(&self, track: SpotifyUri) -> Result<(), CoreError> {
        self.ensure_session().await?;
        self.with_player(|player| player.load(track, true, 0))
    }

    fn with_player(&self, action: impl FnOnce(&Player)) -> Result<(), CoreError> {
        let connected = self.connected.lock().unwrap();
        let connected = connected
            .as_ref()
            .ok_or_else(|| CoreError::Playback("não conectado".into()))?;
        action(&connected.player);
        Ok(())
    }

    fn with_session<T>(&self, action: impl FnOnce(&Session) -> T) -> Option<T> {
        self.connected
            .lock()
            .unwrap()
            .as_ref()
            .map(|connected| action(&connected.session))
    }

    /// The current track ended or could not play: move on through the queue.
    async fn advance(&self, failed: bool) {
        let next = {
            let mut queue = self.queue.lock().unwrap();
            if failed {
                // Skip a broken track even in repeat-one, or it would loop.
                let pos = queue.position() + 1;
                queue.skip_to(pos)
            } else {
                queue.advance_auto()
            }
        };
        match next {
            Some(track) => {
                self.emit_queue();
                if let Err(error) = self.load(track).await {
                    self.emit(CoreEvent::Error {
                        message: error.to_string(),
                    });
                }
            }
            None => {
                self.playing.store(false, Ordering::Relaxed);
                self.emit(CoreEvent::Ended);
            }
        }
    }
}

/// Forward librespot's player events to Kotlin and drive the queue.
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
            PlayerEvent::TimeToPreloadNextTrack { .. } => {
                let next = inner.queue.lock().unwrap().peek_auto_next();
                if let Some(next) = next {
                    let _ = inner.with_player(|player| player.preload(next));
                }
            }
            PlayerEvent::EndOfTrack { .. } => inner.advance(false).await,
            PlayerEvent::Unavailable { track_id, .. } => {
                inner.emit(CoreEvent::Error {
                    message: format!("faixa indisponível: {}", track_id.to_uri()),
                });
                inner.advance(true).await;
            }
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
