//! Sonata's Spotify core: sign-in, the librespot session and local playback,
//! exported to Kotlin through UniFFI.

mod auth;
#[cfg(target_os = "android")]
mod android;

use std::path::PathBuf;
use std::sync::{Arc, Mutex};

use librespot_core::authentication::Credentials;
use librespot_core::cache::Cache;
use librespot_core::config::SessionConfig;
use librespot_core::session::Session;
use librespot_core::{SpotifyId, SpotifyUri};
use librespot_playback::audio_backend;
use librespot_playback::config::{AudioFormat, PlayerConfig};
use librespot_playback::mixer::NoOpVolume;
use librespot_playback::player::Player;
use tokio::net::TcpListener;

uniffi::setup_scaffolding!();

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum CoreError {
    #[error("login: {0}")]
    Login(String),
    #[error("sessão: {0}")]
    Session(String),
    #[error("reprodução: {0}")]
    Playback(String),
}

struct Connected {
    // Not read yet; Web API calls and Spotify Connect will use it.
    #[allow(dead_code)]
    session: Session,
    player: Arc<Player>,
}

#[derive(uniffi::Object)]
pub struct SonataCore {
    cache_dir: PathBuf,
    pending_login: Mutex<Option<(auth::Flow, TcpListener)>>,
    connected: Mutex<Option<Connected>>,
}

#[uniffi::export(async_runtime = "tokio")]
impl SonataCore {
    /// `cache_dir` is app-private storage; librespot keeps the reusable
    /// credential there so later launches skip the browser.
    #[uniffi::constructor]
    pub fn new(cache_dir: String) -> Arc<Self> {
        Arc::new(Self {
            cache_dir: PathBuf::from(cache_dir),
            pending_login: Mutex::new(None),
            connected: Mutex::new(None),
        })
    }

    /// Start a sign-in: reserves the redirect port and returns the URL the
    /// app opens in the browser. Must be called inside the runtime because
    /// the listener registers with Tokio.
    pub async fn begin_login(&self) -> Result<String, CoreError> {
        let grant = &auth::Grant::PLAYBACK;
        let listener = auth::listen(grant).map_err(|e| CoreError::Login(format!("{e:#}")))?;
        let flow = auth::begin(grant);
        let url = flow.url.clone();
        *self.pending_login.lock().unwrap() = Some((flow, listener));
        Ok(url)
    }

    /// Wait for the browser redirect, then open the Spotify session.
    /// Returns the account's username.
    pub async fn finish_login(&self) -> Result<String, CoreError> {
        let (flow, listener) = self
            .pending_login
            .lock()
            .unwrap()
            .take()
            .ok_or_else(|| CoreError::Login("nenhum login em andamento".into()))?;
        let grant = &auth::Grant::PLAYBACK;
        let code = auth::wait_for_code(listener, &flow)
            .await
            .map_err(|e| CoreError::Login(format!("{e:#}")))?;
        let token = auth::exchange_code(grant, &flow, &code)
            .await
            .map_err(|e| CoreError::Login(format!("{e:#}")))?;
        self.connect(Credentials::with_access_token(token.access_token))
            .await
    }

    /// Reconnect with the credential saved by an earlier sign-in.
    /// Returns the username, or None when no credential is stored.
    pub async fn restore_session(&self) -> Result<Option<String>, CoreError> {
        let Some(credentials) = self.cache()?.credentials() else {
            return Ok(None);
        };
        self.connect(credentials).await.map(Some)
    }

    /// Play a track from a `spotify:track:…` URI or an open.spotify.com link.
    pub fn play_track(&self, uri: String) -> Result<(), CoreError> {
        let track = parse_track(&uri)?;
        self.with_player(|player| player.load(track, true, 0))
    }

    pub fn pause(&self) -> Result<(), CoreError> {
        self.with_player(|player| player.pause())
    }

    pub fn resume(&self) -> Result<(), CoreError> {
        self.with_player(|player| player.play())
    }

    pub fn is_connected(&self) -> bool {
        self.connected.lock().unwrap().is_some()
    }
}

impl SonataCore {
    fn cache(&self) -> Result<Cache, CoreError> {
        let credentials = self.cache_dir.join("credentials");
        let audio = self.cache_dir.join("audio");
        Cache::new(Some(credentials), None, Some(audio), Some(512 * 1024 * 1024))
            .map_err(|e| CoreError::Session(e.to_string()))
    }

    async fn connect(&self, credentials: Credentials) -> Result<String, CoreError> {
        // On Android librespot defaults to Spotify's Android client id, but
        // our token comes from the desktop (keymaster) client; login5 rejects
        // the mismatch with INVALID_CREDENTIALS unless the session uses it too.
        let config = SessionConfig {
            client_id: auth::PLAYBACK_CLIENT_ID.to_owned(),
            ..SessionConfig::default()
        };
        let session = Session::new(config, Some(self.cache()?));
        session
            .connect(credentials, true)
            .await
            .map_err(|e| CoreError::Session(e.to_string()))?;

        let backend = audio_backend::find(None)
            .ok_or_else(|| CoreError::Playback("nenhuma saída de áudio".into()))?;
        let player = Player::new(
            PlayerConfig::default(),
            session.clone(),
            Box::new(NoOpVolume),
            move || backend(None, AudioFormat::default()),
        );

        let username = session.username();
        *self.connected.lock().unwrap() = Some(Connected { session, player });
        log::info!("connected as {username}");
        Ok(username)
    }

    fn with_player(&self, action: impl FnOnce(&Player)) -> Result<(), CoreError> {
        let connected = self.connected.lock().unwrap();
        let connected = connected
            .as_ref()
            .ok_or_else(|| CoreError::Playback("não conectado".into()))?;
        action(&connected.player);
        Ok(())
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
