//! Spotify sign-in with Authorization Code + PKCE.
//!
//! The browser handles the password; this process only receives the one-time
//! code Spotify redirects to a loopback listener, then trades it for an
//! access token. The playback grant uses Spotify's desktop client identity,
//! the one librespot streams with, whose registered redirect is fixed.

use std::net::SocketAddr;
use std::time::Duration;

use anyhow::{Context, Result, anyhow, bail};
use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use rand::RngCore;
use serde::Deserialize;
use sha2::{Digest, Sha256};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::TcpListener;

pub const PLAYBACK_CLIENT_ID: &str = "65b708073fc0480ea92a077233ca87bd";
pub const PLAYBACK_REDIRECT_PORT: u16 = 8898;
pub const PLAYBACK_SCOPES: &[&str] = &["streaming"];

/// The public Web API app shared by several open-source players.
/// Registered before Spotify's November 2024 restrictions, so it can still
/// read Spotify-owned playlists.
pub const SHARED_WEB_CLIENT_ID: &str = "d420a117a32841c2b3474932e49fb54b";
/// Both Web API apps (shared and the user's personal one) register this.
pub const WEB_REDIRECT_PORT: u16 = 8989;
pub const WEB_SCOPES: &[&str] = &[
    "playlist-read-private",
    "playlist-read-collaborative",
    "playlist-modify-private",
    "playlist-modify-public",
    "user-library-read",
    "user-library-modify",
    "user-follow-read",
    "user-follow-modify",
    "user-read-private",
    "user-read-recently-played",
    "user-top-read",
];

const REDIRECT_PATH: &str = "/login";
const AUTHORIZE_URL: &str = "https://accounts.spotify.com/authorize";
const TOKEN_URL: &str = "https://accounts.spotify.com/api/token";
const LOGIN_TIMEOUT: Duration = Duration::from_secs(600);

const DONE_PAGE: &str = "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'>\
<body style='font-family:sans-serif;background:#111;color:#eee;display:grid;place-items:center;height:90vh'>\
<p>Pronto. Pode voltar para o Sonata.</p>";

#[derive(Debug, Clone)]
pub struct Grant {
    pub client_id: String,
    pub port: u16,
    pub scopes: &'static [&'static str],
}

impl Grant {
    pub fn playback() -> Self {
        Self {
            client_id: PLAYBACK_CLIENT_ID.to_owned(),
            port: PLAYBACK_REDIRECT_PORT,
            scopes: PLAYBACK_SCOPES,
        }
    }

    pub fn web(client_id: &str) -> Self {
        Self {
            client_id: client_id.to_owned(),
            port: WEB_REDIRECT_PORT,
            scopes: WEB_SCOPES,
        }
    }

    fn redirect_uri(&self) -> String {
        format!("http://127.0.0.1:{}{REDIRECT_PATH}", self.port)
    }
}

/// One sign-in attempt: the URL to open and the secrets to finish it.
pub struct Flow {
    pub url: String,
    verifier: String,
    state: String,
}

#[derive(Debug, Deserialize)]
pub struct TokenResponse {
    pub access_token: String,
    /// PKCE refresh tokens rotate: each refresh may return a new one.
    #[serde(default)]
    pub refresh_token: Option<String>,
    #[serde(default = "default_expiry")]
    pub expires_in: u64,
}

fn default_expiry() -> u64 {
    3600
}

fn random_token(bytes: usize) -> String {
    let mut buffer = vec![0u8; bytes];
    rand::rng().fill_bytes(&mut buffer);
    URL_SAFE_NO_PAD.encode(buffer)
}

pub fn begin(grant: &Grant) -> Flow {
    let verifier = random_token(48);
    let challenge = URL_SAFE_NO_PAD.encode(Sha256::digest(verifier.as_bytes()));
    let state = random_token(18);
    let url = format!(
        "{AUTHORIZE_URL}?client_id={}&response_type=code&redirect_uri={}&code_challenge_method=S256&code_challenge={challenge}&state={state}&scope={}",
        grant.client_id,
        urlencoding::encode(&grant.redirect_uri()),
        urlencoding::encode(&grant.scopes.join(" ")),
    );
    Flow { url, verifier, state }
}

/// Bind the callback port before the browser opens, so a busy port fails
/// early with a clear message instead of a dead redirect.
pub fn listen(grant: &Grant) -> Result<TcpListener> {
    let address: SocketAddr = ([127, 0, 0, 1], grant.port).into();
    let listener = std::net::TcpListener::bind(address)
        .with_context(|| format!("porta {} ocupada; feche o app que a usa", grant.port))?;
    listener.set_nonblocking(true)?;
    Ok(TcpListener::from_std(listener)?)
}

/// Wait for Spotify's redirect and return the authorization code.
pub async fn wait_for_code(listener: TcpListener, flow: &Flow) -> Result<String> {
    tokio::time::timeout(LOGIN_TIMEOUT, async {
        loop {
            let (stream, _) = listener.accept().await?;
            match answer_redirect(stream, &flow.state).await {
                Ok(code) => return Ok(code),
                // A favicon request or a stale tab is not the redirect.
                Err(error) => log::debug!("ignored request on redirect listener: {error}"),
            }
        }
    })
    .await
    .map_err(|_| anyhow!("tempo esgotado esperando o login"))?
}

async fn answer_redirect(mut stream: tokio::net::TcpStream, expected_state: &str) -> Result<String> {
    let mut request_line = String::new();
    BufReader::new(&mut stream).read_line(&mut request_line).await?;
    let target = request_line
        .split_whitespace()
        .nth(1)
        .ok_or_else(|| anyhow!("malformed request"))?;
    let (path, query) = target.split_once('?').unwrap_or((target, ""));
    if path != REDIRECT_PATH {
        bail!("unexpected path {path}");
    }

    let mut code = None;
    let mut state = None;
    let mut error = None;
    for pair in query.split('&') {
        let (key, value) = pair.split_once('=').unwrap_or((pair, ""));
        let value = urlencoding::decode(value)?.into_owned();
        match key {
            "code" => code = Some(value),
            "state" => state = Some(value),
            "error" => error = Some(value),
            _ => {}
        }
    }

    let response = format!(
        "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{DONE_PAGE}",
        DONE_PAGE.len()
    );
    let _ = stream.write_all(response.as_bytes()).await;

    if state.as_deref() != Some(expected_state) {
        bail!("state mismatch");
    }
    if let Some(error) = error {
        bail!("Spotify recusou o login: {error}");
    }
    code.ok_or_else(|| anyhow!("Spotify não devolveu o código"))
}

pub async fn exchange_code(grant: &Grant, flow: &Flow, code: &str) -> Result<TokenResponse> {
    token_request(&[
        ("client_id", grant.client_id.as_str()),
        ("grant_type", "authorization_code"),
        ("code", code),
        ("redirect_uri", grant.redirect_uri().as_str()),
        ("code_verifier", flow.verifier.as_str()),
    ])
    .await
}

pub async fn refresh(client_id: &str, refresh_token: &str) -> Result<TokenResponse> {
    token_request(&[
        ("client_id", client_id),
        ("grant_type", "refresh_token"),
        ("refresh_token", refresh_token),
    ])
    .await
}

async fn token_request(form: &[(&str, &str)]) -> Result<TokenResponse> {
    let response = reqwest::Client::new().post(TOKEN_URL).form(form).send().await?;
    let status = response.status();
    if !status.is_success() {
        let detail = response.text().await.unwrap_or_default();
        bail!("pedido de token falhou ({status}): {detail}");
    }
    Ok(response.json().await?)
}
