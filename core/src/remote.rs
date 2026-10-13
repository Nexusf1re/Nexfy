//! Spotify Connect remote control: the account's devices, transferring
//! playback between them and driving whichever one is playing.
//!
//! Goes through the Web API player endpoints with the signed-in apps'
//! tokens (personal first), which carry the player scopes. The playback
//! session's own token is not used: its client is shared by every librespot
//! user and the Web API rate limits it almost constantly.

use std::sync::OnceLock;

use anyhow::{anyhow, bail, Result};
use librespot_core::session::Session;
use reqwest::Method;
use serde::Deserialize;

use crate::webapi::{self, Track};
use crate::RepeatMode;

const PLAYER: &str = "https://api.spotify.com/v1/me/player";

/// A Spotify Connect device of the account.
#[derive(Debug, Clone, uniffi::Record)]
pub struct Device {
    pub id: String,
    pub name: String,
    /// Spotify's device type: "Computer", "Smartphone", "Speaker", "TV"...
    pub kind: String,
    pub active: bool,
    /// This phone (Nexfy's own Connect device).
    pub is_self: bool,
    pub volume: Option<u32>,
    /// Cannot be controlled through the Web API (some speakers and TVs).
    pub restricted: bool,
}

/// What the active device is playing.
#[derive(Debug, Clone, uniffi::Record)]
pub struct RemoteState {
    pub device: Device,
    pub playing: bool,
    pub progress_ms: u32,
    pub track: Option<Track>,
    pub shuffle: bool,
    pub repeat: RepeatMode,
    pub context_uri: Option<String>,
}

/// A command for the active device.
#[derive(Debug, Clone, uniffi::Enum)]
pub enum RemoteCommand {
    Play,
    Pause,
    Next,
    Previous,
    Seek { position_ms: u32 },
    Volume { percent: u32 },
    Shuffle { on: bool },
    Repeat { mode: RepeatMode },
    /// Play a playlist, album or liked songs, from `track_uri` when given.
    PlayContext { context_uri: String, track_uri: Option<String> },
    /// Play exactly these songs, from `index`.
    PlayTracks { uris: Vec<String>, index: u32 },
    /// Add a song to the queue.
    Queue { uri: String },
}

#[derive(Deserialize)]
struct RawDevice {
    id: Option<String>,
    name: String,
    #[serde(rename = "type", default)]
    kind: String,
    #[serde(default)]
    is_active: bool,
    #[serde(default)]
    is_restricted: bool,
    volume_percent: Option<u32>,
}

impl RawDevice {
    fn into_device(self, own_id: &str) -> Option<Device> {
        let id = self.id?;
        Some(Device {
            is_self: id == own_id,
            id,
            name: self.name,
            kind: self.kind,
            active: self.is_active,
            volume: self.volume_percent,
            restricted: self.is_restricted,
        })
    }
}

#[derive(Deserialize)]
struct RawState {
    device: RawDevice,
    #[serde(default)]
    is_playing: bool,
    progress_ms: Option<u32>,
    item: Option<serde_json::Value>,
    #[serde(default)]
    shuffle_state: bool,
    #[serde(default)]
    repeat_state: String,
    context: Option<RawContext>,
}

#[derive(Deserialize)]
struct RawContext {
    uri: Option<String>,
}

fn http() -> &'static reqwest::Client {
    static CLIENT: OnceLock<reqwest::Client> = OnceLock::new();
    CLIENT.get_or_init(reqwest::Client::new)
}

/// Send a player request; `Ok(None)` for an empty answer (204). Tries the
/// apps in turn while one is refused or rate limited.
async fn send(
    tokens: &[String],
    method: Method,
    path: &str,
    body: Option<serde_json::Value>,
) -> Result<Option<serde_json::Value>> {
    let url = format!("{PLAYER}{path}");
    let mut last = anyhow!("entre com o app pessoal em Ajustes para controlar outros dispositivos");
    for (i, token) in tokens.iter().enumerate() {
        let mut request = http().request(method.clone(), &url).bearer_auth(token);
        request = match &body {
            Some(json) => request.json(json),
            // Spotify answers 411 to bodiless PUTs and POSTs without a length.
            None => request.header(reqwest::header::CONTENT_LENGTH, "0"),
        };
        let response = request.send().await?;
        let status = response.status().as_u16();
        if (200..300).contains(&status) {
            if status == 204 {
                return Ok(None);
            }
            let text = response.text().await.unwrap_or_default();
            return Ok(if text.trim().is_empty() { None } else { Some(serde_json::from_str(&text)?) });
        }
        let detail = response.text().await.unwrap_or_default();
        log::warn!("player {method} {path} via app {i}: HTTP {status}: {}", detail.split_whitespace().collect::<Vec<_>>().join(" ").chars().take(240).collect::<String>());
        last = match status {
            404 => bail!("nenhum dispositivo ativo agora"),
            // Grants from before the player scopes were added need a new sign-in.
            401 | 403 if detail.contains("scope") => {
                anyhow!("entre de novo com o app pessoal em Ajustes para liberar o controle de dispositivos")
            }
            401 | 403 => anyhow!("esse dispositivo não aceita esse comando"),
            429 => anyhow!("muitos comandos seguidos, tente de novo em instantes"),
            _ => bail!("o Spotify recusou o comando ({status})"),
        };
    }
    Err(last)
}

pub async fn devices(session: &Session, tokens: &[String]) -> Result<Vec<Device>> {
    #[derive(Deserialize)]
    struct Devices {
        #[serde(default)]
        devices: Vec<RawDevice>,
    }
    let own = session.device_id().to_owned();
    let Some(json) = send(tokens, Method::GET, "/devices", None).await? else {
        return Ok(Vec::new());
    };
    let list: Devices = serde_json::from_value(json)?;
    Ok(list.devices.into_iter().filter_map(|d| d.into_device(&own)).collect())
}

/// What is playing now, on whichever device; None when nothing is.
pub async fn state(session: &Session, tokens: &[String]) -> Result<Option<RemoteState>> {
    let own = session.device_id().to_owned();
    let Some(json) = send(tokens, Method::GET, "", None).await? else {
        return Ok(None);
    };
    let raw: RawState = serde_json::from_value(json)?;
    let Some(device) = raw.device.into_device(&own) else {
        return Ok(None);
    };
    Ok(Some(RemoteState {
        device,
        playing: raw.is_playing,
        progress_ms: raw.progress_ms.unwrap_or(0),
        track: raw.item.and_then(webapi::parse_track),
        shuffle: raw.shuffle_state,
        repeat: match raw.repeat_state.as_str() {
            "track" => RepeatMode::One,
            "context" => RepeatMode::All,
            _ => RepeatMode::Off,
        },
        context_uri: raw.context.and_then(|c| c.uri),
    }))
}

/// Move playback to `device_id`, playing or keeping it paused.
pub async fn transfer(tokens: &[String], device_id: &str, play: bool) -> Result<()> {
    let body = serde_json::json!({ "device_ids": [device_id], "play": play });
    send(tokens, Method::PUT, "", Some(body)).await?;
    Ok(())
}

/// Most songs sent with an explicit list: the Web API rejects huge bodies.
const MAX_URIS: usize = 300;

pub async fn command(tokens: &[String], command: RemoteCommand) -> Result<()> {
    let mut body = None;
    let (method, path) = match command {
        RemoteCommand::Play => (Method::PUT, "/play".to_owned()),
        RemoteCommand::Pause => (Method::PUT, "/pause".to_owned()),
        RemoteCommand::Next => (Method::POST, "/next".to_owned()),
        RemoteCommand::Previous => (Method::POST, "/previous".to_owned()),
        RemoteCommand::Seek { position_ms } => (Method::PUT, format!("/seek?position_ms={position_ms}")),
        RemoteCommand::Volume { percent } => (Method::PUT, format!("/volume?volume_percent={}", percent.min(100))),
        RemoteCommand::Shuffle { on } => (Method::PUT, format!("/shuffle?state={on}")),
        RemoteCommand::Repeat { mode } => {
            let state = match mode {
                RepeatMode::Off => "off",
                RepeatMode::All => "context",
                RepeatMode::One => "track",
            };
            (Method::PUT, format!("/repeat?state={state}"))
        }
        RemoteCommand::PlayContext { context_uri, track_uri } => {
            let mut json = serde_json::json!({ "context_uri": context_uri });
            if let Some(uri) = track_uri {
                json["offset"] = serde_json::json!({ "uri": uri });
            }
            body = Some(json);
            (Method::PUT, "/play".to_owned())
        }
        RemoteCommand::PlayTracks { uris, index } => {
            // Keep a window around the chosen song when the list is long.
            let index = (index as usize).min(uris.len().saturating_sub(1));
            let start = index.saturating_sub(MAX_URIS / 2).min(uris.len().saturating_sub(MAX_URIS));
            let window: Vec<&String> = uris.iter().skip(start).take(MAX_URIS).collect();
            body = Some(serde_json::json!({ "uris": window, "offset": { "position": index - start } }));
            (Method::PUT, "/play".to_owned())
        }
        RemoteCommand::Queue { uri } => (Method::POST, format!("/queue?uri={}", urlencoding::encode(&uri))),
    };
    send(tokens, method, &path, body).await?;
    Ok(())
}
