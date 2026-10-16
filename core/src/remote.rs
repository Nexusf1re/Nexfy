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

/// What plays next on the active device (Spotify's queue, user-queued songs
/// first, then the context), up to 20 songs.
pub async fn queue(tokens: &[String]) -> Result<Vec<Track>> {
    #[derive(Deserialize)]
    struct Queue {
        #[serde(default)]
        queue: Vec<serde_json::Value>,
    }
    let Some(json) = send(tokens, Method::GET, "/queue", None).await? else {
        return Ok(Vec::new());
    };
    let queue: Queue = serde_json::from_value(json)?;
    Ok(queue.queue.into_iter().filter_map(webapi::parse_track).collect())
}

// ------------------------------------------------- over the session --
//
// The dealer keeps this phone up to date with what every device of the
// account plays (the Connect cluster), and Spotify's own clients command
// other devices through spclient. Neither spends the Web API's daily
// quota, so these come first; the Web API above is the fallback.

/// "SMARTPHONE", "CAST_AUDIO"... as the Web API names kinds ("castaudio").
fn kind_name(kind: librespot_protocol::devices::DeviceType) -> String {
    format!("{kind:?}").replace('_', "").to_lowercase()
}

fn cluster_devices(own: &str, cluster: &librespot_protocol::connect::Cluster) -> Vec<Device> {
    // Earlier sessions of this phone linger under its name until Spotify
    // drops them: only the current one is this phone.
    let own_name = cluster.device.get(own).map(|info| info.name.as_str());
    let mut devices: Vec<Device> = cluster
        .device
        .iter()
        .filter(|(_, info)| !info.is_offline)
        .filter(|(id, info)| {
            id.as_str() == own || **id == cluster.active_device_id || Some(info.name.as_str()) != own_name
        })
        .map(|(id, info)| Device {
            id: id.clone(),
            name: info.name.clone(),
            kind: kind_name(info.device_type.enum_value_or_default()),
            active: *id == cluster.active_device_id,
            is_self: id == own,
            volume: info
                .capabilities
                .as_ref()
                .filter(|caps| !caps.disable_volume)
                .map(|_| (info.volume as u64 * 100 / 65_535) as u32),
            restricted: !info.can_play,
        })
        .collect();
    devices.sort_by(|a, b| b.is_self.cmp(&a.is_self).then(a.name.cmp(&b.name)));
    devices
}

/// The account's devices from the cluster, None before the dealer sent one.
pub fn session_devices(session: &Session) -> Option<Vec<Device>> {
    let cluster = librespot_connect::cluster_cache::latest()?;
    Some(cluster_devices(&session.device_id(), &cluster))
}

fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or_default()
}

/// Song metadata for the cluster's track, kept so a poll costs nothing.
static TRACKS: std::sync::Mutex<Option<(String, Track)>> = std::sync::Mutex::new(None);

async fn track_of(session: &Session, uri: &str) -> Option<Track> {
    if let Some((known, track)) = TRACKS.lock().unwrap().as_ref() {
        if known == uri {
            return Some(track.clone());
        }
    }
    let track = crate::session_reads::tracks(session, &[uri.to_owned()]).await.into_iter().next()?;
    *TRACKS.lock().unwrap() = Some((uri.to_owned(), track.clone()));
    Some(track)
}

/// What another device plays, from the cluster: Some(None) when nothing
/// plays anywhere, None before the dealer sent a cluster.
pub async fn session_state(session: &Session) -> Option<Option<RemoteState>> {
    let cluster = librespot_connect::cluster_cache::latest()?;
    let own = session.device_id().to_owned();
    if cluster.active_device_id.is_empty() {
        return Some(None);
    }
    let device = cluster_devices(&own, &cluster).into_iter().find(|d| d.active)?;
    let player = cluster.player_state.as_ref()?;
    let playing = player.is_playing && !player.is_paused;
    let mut progress = player.position_as_of_timestamp;
    if playing && player.timestamp > 0 {
        let speed = if player.playback_speed > 0.0 { player.playback_speed.min(4.0) } else { 1.0 };
        progress += ((now_ms() - player.timestamp).max(0) as f64 * speed) as i64;
    }
    if player.duration > 0 {
        progress = progress.min(player.duration);
    }
    let uri = player.track.as_ref().map(|t| t.uri.clone()).filter(|uri| !uri.is_empty());
    let track = match &uri {
        Some(uri) => track_of(session, uri).await,
        None => None,
    };
    let options = player.options.as_ref();
    Some(Some(RemoteState {
        device,
        playing,
        progress_ms: progress.max(0) as u32,
        track,
        shuffle: options.is_some_and(|o| o.shuffling_context),
        repeat: match options {
            Some(o) if o.repeating_track => RepeatMode::One,
            Some(o) if o.repeating_context => RepeatMode::All,
            _ => RepeatMode::Off,
        },
        context_uri: Some(player.context_uri.clone()).filter(|uri| !uri.is_empty()),
    }))
}

/// What plays next on the active device, as the cluster lists it.
/// The next songs of the device playing elsewhere, with who queued each (a
/// Jam guest) when Spotify says.
pub fn session_queue() -> Option<Vec<(String, Option<String>)>> {
    let cluster = librespot_connect::cluster_cache::latest()?;
    let player = cluster.player_state.as_ref()?;
    Some(
        player
            .next_tracks
            .iter()
            .filter(|t| t.uri.starts_with("spotify:track:") || t.uri.starts_with("spotify:episode:"))
            .inspect(|t| {
                // Which keys a queued song carries, to learn how Spotify names its adder.
                if t.provider == "queue" {
                    log::info!("queued {} metadata keys: {:?}", t.uri, t.metadata.keys().collect::<Vec<_>>());
                }
            })
            .map(|t| (t.uri.clone(), librespot_connect::queued_by(t)))
            .take(50)
            .collect(),
    )
}

/// A fresh id per command, as Spotify's clients send.
pub(crate) fn command_id() -> String {
    use std::hash::{BuildHasher, Hasher};
    let mut a = std::collections::hash_map::RandomState::new().build_hasher();
    a.write_i64(now_ms());
    let b = std::collections::hash_map::RandomState::new().build_hasher();
    format!("{:016x}{:016x}", a.finish(), b.finish())
}

/// Sends `cmd` to the device playing now. None when there is no cluster or
/// no active device (the caller then tries the Web API).
pub async fn session_command(session: &Session, cmd: &RemoteCommand) -> Option<Result<()>> {
    let cluster = librespot_connect::cluster_cache::latest()?;
    let target = cluster.active_device_id.clone();
    if target.is_empty() {
        return None;
    }
    let own = session.device_id().to_owned();
    let spclient = session.spclient();
    let logging = serde_json::json!({ "command_id": command_id() });
    let command = match cmd {
        RemoteCommand::Volume { percent } => {
            let volume = ((*percent).min(100) as u64 * 65_535 / 100) as u16;
            return Some(spclient.connect_volume(&own, &target, volume).await.map(|_| ()).map_err(|e| {
                log::warn!("connect volume to {target}: {e}");
                anyhow!("o outro dispositivo não aceitou o volume")
            }));
        }
        RemoteCommand::Play => serde_json::json!({ "endpoint": "resume", "logging_params": logging }),
        RemoteCommand::Pause => serde_json::json!({ "endpoint": "pause", "logging_params": logging }),
        RemoteCommand::Next => serde_json::json!({ "endpoint": "skip_next", "logging_params": logging }),
        RemoteCommand::Previous => serde_json::json!({ "endpoint": "skip_prev", "logging_params": logging }),
        RemoteCommand::Seek { position_ms } => {
            serde_json::json!({ "endpoint": "seek_to", "value": position_ms, "logging_params": logging })
        }
        RemoteCommand::Shuffle { on } => {
            serde_json::json!({ "endpoint": "set_shuffling_context", "value": on, "logging_params": logging })
        }
        RemoteCommand::Repeat { mode } => serde_json::json!({
            "endpoint": "set_options",
            "repeating_context": !matches!(mode, RepeatMode::Off),
            "repeating_track": matches!(mode, RepeatMode::One),
            "logging_params": logging,
        }),
        RemoteCommand::Queue { uri } => serde_json::json!({
            "endpoint": "add_to_queue",
            "track": { "uri": uri, "metadata": { "is_queued": "true" }, "provider": "queue" },
            "logging_params": logging,
        }),
        RemoteCommand::PlayContext { context_uri, track_uri } => {
            let mut options = serde_json::json!({ "license": "on-demand", "player_options_override": {} });
            if let Some(uri) = track_uri {
                options["skip_to"] = serde_json::json!({ "track_uri": uri });
            }
            serde_json::json!({
                "endpoint": "play",
                "context": { "uri": context_uri, "url": format!("context://{context_uri}"), "metadata": {} },
                "options": options,
                "logging_params": logging,
            })
        }
        RemoteCommand::PlayTracks { uris, index } => {
            let index = (*index as usize).min(uris.len().saturating_sub(1));
            let start = index.saturating_sub(MAX_URIS / 2).min(uris.len().saturating_sub(MAX_URIS));
            let tracks: Vec<serde_json::Value> =
                uris.iter().skip(start).take(MAX_URIS).map(|uri| serde_json::json!({ "uri": uri, "uid": "" })).collect();
            let first = uris.get(index).cloned().unwrap_or_default();
            serde_json::json!({
                "endpoint": "play",
                "context": { "uri": first, "url": "", "metadata": {}, "pages": [{ "tracks": tracks }] },
                "options": {
                    "license": "on-demand",
                    "skip_to": { "track_index": index - start },
                    "player_options_override": {},
                },
                "logging_params": logging,
            })
        }
    };
    Some(spclient.connect_command(&own, &target, &command).await.map(|_| ()).map_err(|e| {
        log::warn!("connect command to {target}: {e}");
        anyhow!("o outro dispositivo não respondeu ao comando")
    }))
}

/// Moves playback to `device_id` through spclient, like Spotify's apps.
pub async fn session_transfer(session: &Session, device_id: &str) -> Option<Result<()>> {
    let cluster = librespot_connect::cluster_cache::latest()?;
    let from = if cluster.active_device_id.is_empty() { device_id.to_owned() } else { cluster.active_device_id.clone() };
    Some(session.spclient().transfer(&from, device_id, None).await.map(|_| ()).map_err(|e| {
        log::warn!("connect transfer to {device_id}: {e}");
        anyhow!("não deu para passar a música para esse dispositivo")
    }))
}

/// The songs either side of the one playing on the active device, as the
/// cluster lists them: what a skip there shows before the device confirms.
pub fn session_neighbours() -> Option<(Option<String>, Option<String>)> {
    let cluster = librespot_connect::cluster_cache::latest()?;
    let player = cluster.player_state.as_ref()?;
    let song = |uri: &&String| uri.starts_with("spotify:track:") || uri.starts_with("spotify:episode:");
    let previous = player.prev_tracks.iter().map(|t| &t.uri).filter(song).last().cloned();
    let next = player.next_tracks.iter().map(|t| &t.uri).find(song).cloned();
    Some((previous, next))
}
