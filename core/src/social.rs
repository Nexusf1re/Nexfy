//! Social features over the session: what friends are listening to (the
//! desktop client's buddy list) and Jams (Spotify's "social sessions").
//!
//! Neither is in the Web API. Both are spclient services Spotify's own
//! clients call with the session's login5 token, so requests are sent by
//! hand here: librespot's spclient helper drops the body of a refused
//! answer, and these endpoints are undocumented, so every call logs its
//! status and the start of the body for checking on the phone
//! (`logcat -s NexfyCore:I`, look for "social").

use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant};

use anyhow::{Result, anyhow, bail};
use librespot_core::session::Session;
use reqwest::Method;
use serde_json::Value;

/// Where buddy list and Jam calls go when the session's access point
/// refuses them: the host Spotify's desktop and web clients use.
const FALLBACK_BASE: &str = "https://guc-spclient.spotify.com";

/// A friend counts as listening now when their song started this recently.
const LISTENING_NOW_MS: i64 = 10 * 60 * 1000;

/// How long a buddy list read is reused: friends change songs every few minutes.
const FRIENDS_TTL: Duration = Duration::from_secs(60);

/// What a friend is playing or last played.
#[derive(Debug, Clone, uniffi::Record)]
pub struct FriendActivity {
    pub user_id: String,
    pub name: String,
    pub image_url: Option<String>,
    pub track_uri: String,
    pub title: String,
    pub artists: String,
    pub cover_url: Option<String>,
    /// The playlist, album or artist it plays from, when Spotify says.
    pub context_uri: Option<String>,
    pub context_name: Option<String>,
    /// When the song started, ms since the epoch.
    pub at_ms: i64,
    pub listening_now: bool,
}

/// Someone in a Jam.
#[derive(Debug, Clone, uniffi::Record)]
pub struct JamMember {
    pub id: String,
    pub name: String,
    pub image_url: Option<String>,
    pub is_host: bool,
    pub is_me: bool,
}

/// A Jam this account is in.
#[derive(Debug, Clone, uniffi::Record)]
pub struct JamSession {
    pub id: String,
    /// What others join with; the share link is built from it.
    pub join_token: String,
    pub share_url: String,
    pub is_host: bool,
    pub members: Vec<JamMember>,
    /// The device the Jam plays on, where queued songs are sent.
    pub host_device_id: Option<String>,
}

fn http() -> &'static reqwest::Client {
    static CLIENT: OnceLock<reqwest::Client> = OnceLock::new();
    CLIENT.get_or_init(|| {
        reqwest::Client::builder()
            .timeout(Duration::from_secs(15))
            .build()
            .unwrap_or_default()
    })
}

fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or_default()
}

/// One line of an answer for the log.
fn excerpt(text: &str) -> String {
    text.split_whitespace().collect::<Vec<_>>().join(" ").chars().take(300).collect()
}

/// An answer: the status and the body as text.
struct Answer {
    status: u16,
    body: String,
}

impl Answer {
    fn ok(&self) -> bool {
        (200..300).contains(&self.status)
    }

    fn json(&self) -> Result<Value> {
        if self.body.trim().is_empty() {
            return Ok(Value::Null);
        }
        Ok(serde_json::from_str(&self.body)?)
    }
}

/// Sends a request to spclient as Spotify's clients do: the session's
/// login5 token and the client token. `base` None is the session's access
/// point. Network errors are errors; any HTTP status is an answer.
async fn send(session: &Session, base: Option<&str>, method: Method, path: &str, body: Option<&Value>) -> Result<Answer> {
    let base = match base {
        Some(base) => base.to_owned(),
        None => session.spclient().base_url().await?,
    };
    let token = session.login5().auth_token().await?;
    let user_agent = format!(
        "Spotify/{} Android/0 (Nexfy)",
        librespot_core::version::spotify_version()
    );
    let mut request = http()
        .request(method.clone(), format!("{base}{path}"))
        .header(reqwest::header::AUTHORIZATION, format!("{} {}", token.token_type, token.access_token))
        .header(reqwest::header::ACCEPT, "application/json")
        .header(reqwest::header::USER_AGENT, user_agent)
        .header("app-platform", "Android");
    match session.spclient().client_token().await {
        Ok(client_token) => request = request.header("client-token", client_token),
        Err(error) => log::info!("social: no client token ({error}), trying without"),
    }
    request = match body {
        Some(json) => request.json(json),
        // Spotify answers 411 to bodiless POSTs without a length.
        None if method != Method::GET => request.header(reqwest::header::CONTENT_LENGTH, "0"),
        None => request,
    };
    let response = request.send().await?;
    let status = response.status().as_u16();
    let body = response.text().await.unwrap_or_default();
    Ok(Answer { status, body })
}

/// [send] on the access point, then on [FALLBACK_BASE] when the access
/// point does not know the endpoint. Logs every attempt.
async fn call(session: &Session, what: &str, method: Method, path: &str, body: Option<&Value>) -> Result<Answer> {
    let mut answer = send(session, None, method.clone(), path, body).await?;
    log::info!("social {what}: {method} {path}: HTTP {}: {}", answer.status, excerpt(&answer.body));
    if matches!(answer.status, 404 | 405 | 501 | 502 | 503) {
        match send(session, Some(FALLBACK_BASE), method.clone(), path, body).await {
            Ok(second) => {
                log::info!("social {what} via {FALLBACK_BASE}: HTTP {}: {}", second.status, excerpt(&second.body));
                // Keep the first answer when the fallback knows no better.
                if second.ok() || second.status != 404 {
                    answer = second;
                }
            }
            Err(error) => log::warn!("social {what} via {FALLBACK_BASE}: {error:#}"),
        }
    }
    Ok(answer)
}

// ------------------------------------------------------------ friends --

/// The text at the first of `keys` that holds a non-empty string.
fn text(value: &Value, keys: &[&str]) -> Option<String> {
    keys.iter()
        .filter_map(|key| value.get(*key))
        .filter_map(|v| v.as_str())
        .map(str::trim)
        .find(|s| !s.is_empty())
        .map(str::to_owned)
}

fn number(value: &Value, keys: &[&str]) -> Option<i64> {
    keys.iter().filter_map(|key| value.get(*key)).find_map(|v| {
        v.as_i64().or_else(|| v.as_f64().map(|f| f as i64)).or_else(|| v.as_str().and_then(|s| s.parse().ok()))
    })
}

/// Spotify still hands out some image links over plain http, which
/// Android refuses to load.
fn https(url: Option<String>) -> Option<String> {
    url.map(|u| match u.strip_prefix("http://") {
        Some(rest) => format!("https://{rest}"),
        None => u,
    })
}

fn parse_friends(json: &Value, now: i64) -> Vec<FriendActivity> {
    let Some(friends) = json.get("friends").and_then(Value::as_array) else {
        return Vec::new();
    };
    let mut list: Vec<FriendActivity> = friends
        .iter()
        .filter_map(|friend| {
            let user = friend.get("user")?;
            let track = friend.get("track")?;
            let user_uri = text(user, &["uri"])?;
            let user_id = user_uri.strip_prefix("spotify:user:").unwrap_or(&user_uri).to_owned();
            let track_uri = text(track, &["uri"])?;
            let at_ms = number(friend, &["timestamp"]).unwrap_or_default();
            let context = track.get("context");
            // The buddy list names one artist; some answers carry a list.
            let artists = match track.get("artists").and_then(Value::as_array) {
                Some(all) => all.iter().filter_map(|a| text(a, &["name"])).collect::<Vec<_>>().join(", "),
                None => track.get("artist").and_then(|a| text(a, &["name"])).unwrap_or_default(),
            };
            Some(FriendActivity {
                name: text(user, &["name", "displayName"]).unwrap_or_else(|| user_id.clone()),
                user_id,
                image_url: https(text(user, &["imageUrl", "image_url", "largeImageUrl"])),
                title: text(track, &["name"]).unwrap_or_default(),
                artists,
                cover_url: https(text(track, &["imageUrl", "image_url"])),
                context_uri: context.and_then(|c| text(c, &["uri"])),
                context_name: context.and_then(|c| text(c, &["name"])),
                listening_now: at_ms > 0 && now - at_ms < LISTENING_NOW_MS,
                at_ms,
                track_uri,
            })
        })
        .collect();
    list.sort_by(|a, b| b.at_ms.cmp(&a.at_ms));
    list
}

static FRIENDS: Mutex<Option<(Instant, Vec<FriendActivity>)>> = Mutex::new(None);

/// Friends' latest songs, newest first; reused for a minute unless `refresh`.
/// An error means the buddy list is not reachable from this client.
pub async fn friend_activity(session: &Session, refresh: bool) -> Result<Vec<FriendActivity>> {
    if !refresh {
        if let Some((at, list)) = FRIENDS.lock().unwrap().as_ref() {
            if at.elapsed() < FRIENDS_TTL {
                return Ok(list.clone());
            }
        }
    }
    let answer = call(session, "buddylist", Method::GET, "/presence-view/v1/buddylist", None).await?;
    if !answer.ok() {
        bail!("atividade dos amigos não disponível ({})", answer.status);
    }
    let json = answer.json()?;
    if json.get("friends").is_none() {
        log::warn!("social buddylist: no friends field in the answer");
        bail!("atividade dos amigos não disponível");
    }
    let list = parse_friends(&json, now_ms());
    log::info!("social buddylist: {} friends", list.len());
    *FRIENDS.lock().unwrap() = Some((Instant::now(), list.clone()));
    Ok(list)
}

// ---------------------------------------------------------------- jam --

/// The join token in a Jam link (open.spotify.com/socialsession/<token>,
/// spotify:socialsession:<token>), or the text itself when it is a bare
/// token. None for anything else.
fn token_in(text: &str) -> Option<String> {
    let text = text.trim();
    let after = text
        .split_once("socialsession/")
        .map(|(_, rest)| rest)
        .or_else(|| text.split_once("socialsession:").map(|(_, rest)| rest));
    let candidate = match after {
        Some(rest) => rest,
        None if !text.contains('/') && !text.contains(':') && !text.contains(' ') => text,
        None => return None,
    };
    let token: String = candidate.chars().take_while(|c| c.is_ascii_alphanumeric() || *c == '-' || *c == '_').collect();
    (token.len() >= 6).then_some(token)
}

/// The join token behind what the user pasted. Short links
/// (spotify.link, spotify.app.link) are followed to the page they open.
async fn join_token(input: &str) -> Result<String> {
    if let Some(token) = token_in(input) {
        return Ok(token);
    }
    let word = input
        .split_whitespace()
        .find(|word| word.starts_with("http") || word.contains("spotify.link") || word.contains("spotify.app.link"))
        .unwrap_or(input.trim());
    let url = match word.strip_prefix("http://") {
        Some(rest) => format!("https://{rest}"),
        None if word.starts_with("https://") => word.to_owned(),
        None if word.contains("spotify.link") || word.contains("spotify.app.link") => format!("https://{word}"),
        None => bail!("esse link não é de uma Jam"),
    };
    let response = http().get(&url).send().await?;
    let landed = response.url().to_string();
    let body = response.text().await.unwrap_or_default();
    log::info!("social jam link {url} opens {landed}");
    // Short links answer a redirect for apps, a page with the link for browsers.
    token_in(&landed)
        .or_else(|| body.split_once("socialsession/").and_then(|(_, rest)| token_in(&format!("socialsession/{rest}"))))
        .ok_or_else(|| anyhow!("esse link não é de uma Jam"))
}

fn parse_jam(json: &Value, me: &str) -> Option<JamSession> {
    // Some answers wrap the session.
    let s = json.get("session").filter(|v| v.is_object()).unwrap_or(json);
    let id = text(s, &["session_id", "sessionId", "id"])?;
    let join_token = text(s, &["join_session_token", "joinSessionToken"]).unwrap_or_default();
    let owner = text(s, &["session_owner_id", "sessionOwnerId", "owner_id"]).unwrap_or_default();
    let members = s
        .get("session_members")
        .or_else(|| s.get("sessionMembers"))
        .or_else(|| s.get("members"))
        .and_then(Value::as_array)
        .map(|all| {
            all.iter()
                .filter_map(|m| {
                    let id = text(m, &["id", "username", "user_id"])?;
                    let username = text(m, &["username"]).unwrap_or_else(|| id.clone());
                    Some(JamMember {
                        name: text(m, &["display_name", "displayName", "name"]).unwrap_or_else(|| username.clone()),
                        image_url: https(text(m, &["image_url", "imageUrl", "large_image_url", "largeImageUrl"])),
                        is_host: !owner.is_empty() && (username == owner || id == owner),
                        is_me: m.get("is_current_user").and_then(Value::as_bool).unwrap_or(username == me),
                        id,
                    })
                })
                .collect()
        })
        .unwrap_or_default();
    let share_url = text(s, &["join_session_url", "joinSessionUrl", "share_url", "shareUrl"])
        .unwrap_or_else(|| format!("https://open.spotify.com/socialsession/{join_token}"));
    Some(JamSession {
        is_host: s.get("is_session_owner").and_then(Value::as_bool).unwrap_or(!owner.is_empty() && owner == me),
        host_device_id: text(s, &["host_active_device_id", "hostActiveDeviceId", "host_device_id"]),
        id,
        join_token,
        share_url,
        members,
    })
}

/// The Jam last seen, for queueing and leaving without asking again.
static JAM: Mutex<Option<JamSession>> = Mutex::new(None);

/// The answer as a Jam (remembered), or an error saying Jams are not reachable.
fn jam_from(what: &str, answer: &Answer, me: &str) -> Result<JamSession> {
    if !answer.ok() {
        bail!("Jam não disponível ({})", answer.status);
    }
    let json = answer.json()?;
    let jam = parse_jam(&json, me).ok_or_else(|| {
        log::warn!("social {what}: no session in the answer");
        anyhow!("Jam não disponível")
    })?;
    log::info!(
        "social {what}: session {} with {} members, host device {:?}",
        jam.id,
        jam.members.len(),
        jam.host_device_id
    );
    *JAM.lock().unwrap() = Some(jam.clone());
    Ok(jam)
}

/// The Jam this account is in, None when it is in none.
pub async fn jam_current(session: &Session) -> Result<Option<JamSession>> {
    let answer = call(session, "jam current", Method::GET, "/social-connect/v2/sessions/current", None).await?;
    // No Jam is a 404 (or an empty answer), not a failure.
    if answer.status == 404 || (answer.ok() && answer.body.trim().is_empty()) {
        *JAM.lock().unwrap() = None;
        return Ok(None);
    }
    jam_from("jam current", &answer, &session.username()).map(Some)
}

/// Starts a Jam playing on this phone, or answers the one already going.
pub async fn jam_start(session: &Session) -> Result<JamSession> {
    let path = format!(
        "/social-connect/v2/sessions/current_or_new?local_device_id={}&type=REMOTE",
        session.device_id()
    );
    let answer = call(session, "jam start", Method::GET, &path, None).await?;
    jam_from("jam start", &answer, &session.username())
}

/// Joins the Jam behind a pasted link or token.
pub async fn jam_join(session: &Session, link: &str) -> Result<JamSession> {
    let token = join_token(link).await?;
    let path = format!(
        "/social-connect/v2/sessions/join/{token}?playback_control=listen_and_control&join_type=deeplinking&participant_id={}",
        session.username()
    );
    let answer = call(session, "jam join", Method::POST, &path, None).await?;
    let me = session.username();
    // A join may be accepted without the session in the answer: read it.
    let parsed = answer.json().ok().and_then(|json| parse_jam(&json, &me));
    if answer.ok() && parsed.is_none() {
        log::info!("social jam join: accepted without a session, reading the current one");
        if let Some(jam) = jam_current(session).await? {
            return Ok(jam);
        }
    }
    let mut jam = jam_from("jam join", &answer, &me)?;
    if jam.join_token.is_empty() {
        jam.share_url = format!("https://open.spotify.com/socialsession/{token}");
        jam.join_token = token;
        *JAM.lock().unwrap() = Some(jam.clone());
    }
    Ok(jam)
}

/// Leaves the Jam; the host ends it for everyone.
pub async fn jam_leave(session: &Session) -> Result<()> {
    let Some(jam) = JAM.lock().unwrap().clone() else {
        return Ok(());
    };
    let id = &jam.id;
    let answer = if jam.is_host {
        call(session, "jam end", Method::DELETE, &format!("/social-connect/v3/sessions/{id}"), None).await?
    } else {
        call(session, "jam leave", Method::POST, &format!("/social-connect/v3/sessions/{id}/leave"), None).await?
    };
    // Already over is as good as left.
    if answer.ok() || answer.status == 404 {
        *JAM.lock().unwrap() = None;
        return Ok(());
    }
    bail!("não deu para sair da Jam ({})", answer.status)
}

/// Where a song queued in the Jam goes.
pub enum JamQueue {
    /// The Jam plays on this phone: its own queue.
    Here,
    /// The Jam plays on another device: a Connect command to it.
    Device(String),
}

/// Where songs queued in the current Jam go, None outside a Jam.
pub fn jam_target(session: &Session) -> Option<JamQueue> {
    let jam = JAM.lock().unwrap().clone()?;
    match jam.host_device_id.filter(|id| !id.is_empty()) {
        Some(id) if id != session.device_id() => Some(JamQueue::Device(id)),
        Some(_) => Some(JamQueue::Here),
        // A host without a device named is this phone's own Jam.
        None if jam.is_host => Some(JamQueue::Here),
        None => None,
    }
}

/// Queues a song on the device the Jam plays on, as Spotify's apps send
/// commands to any Connect device.
pub async fn jam_queue_on(session: &Session, device_id: &str, uri: &str) -> Result<()> {
    let command = serde_json::json!({
        "command": {
            "endpoint": "add_to_queue",
            "track": { "uri": uri, "metadata": { "is_queued": "true" }, "provider": "queue" },
            "logging_params": { "command_id": crate::remote::command_id() },
        }
    });
    let path = format!("/connect-state/v1/player/command/from/{}/to/{device_id}", session.device_id());
    let answer = call(session, "jam queue", Method::POST, &path, Some(&command)).await?;
    if answer.ok() {
        return Ok(());
    }
    bail!("a Jam não aceitou a música ({})", answer.status)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tokens_from_links() {
        assert_eq!(token_in("https://open.spotify.com/socialsession/AbC123xyz?si=1").as_deref(), Some("AbC123xyz"));
        assert_eq!(token_in("spotify:socialsession:AbC123xyz").as_deref(), Some("AbC123xyz"));
        assert_eq!(token_in(" AbC123xyz ").as_deref(), Some("AbC123xyz"));
        assert_eq!(token_in("https://spotify.link/abcdef"), None);
    }

    #[test]
    fn buddy_list() {
        let json = serde_json::json!({ "friends": [
            { "timestamp": 1000, "user": { "uri": "spotify:user:ana", "name": "Ana", "imageUrl": "http://i.scdn.co/image/a" },
              "track": { "uri": "spotify:track:1", "name": "Song", "imageUrl": "http://i.scdn.co/image/b",
                "album": { "uri": "spotify:album:2", "name": "Album" }, "artist": { "uri": "spotify:artist:3", "name": "Band" },
                "context": { "uri": "spotify:playlist:4", "name": "Mix", "index": 0 } } },
            { "timestamp": 5000, "user": { "uri": "spotify:user:bia", "name": "Bia" },
              "track": { "uri": "spotify:track:5", "name": "Other", "artist": { "name": "Solo" } } }
        ]});
        let list = parse_friends(&json, 6000);
        assert_eq!(list.len(), 2);
        assert_eq!(list[0].user_id, "bia");
        assert!(list[0].listening_now);
        assert_eq!(list[1].image_url.as_deref(), Some("https://i.scdn.co/image/a"));
        assert_eq!(list[1].context_name.as_deref(), Some("Mix"));
        assert_eq!(list[1].artists, "Band");
    }
}
