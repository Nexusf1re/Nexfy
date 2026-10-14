//! The user's library (liked songs, saved albums and podcasts) read and
//! changed over the librespot session, through the collection service
//! Spotify's own clients use. The Web API's development-mode quota is
//! small and runs out in a day; the session's calls do not count against it.

use std::collections::{HashMap, HashSet};
use std::future::Future;
use std::sync::{LazyLock, Mutex};
use std::time::{Duration, Instant};

use anyhow::{Result, anyhow};
use futures_util::{StreamExt, stream};
use librespot_core::SpotifyUri;
use librespot_core::error::ErrorKind;
use librespot_core::session::Session;
use librespot_metadata::{Album as SessionAlbum, Metadata, Show as SessionShow};
use librespot_protocol::collection2v2::{
    CollectionItem, DeltaRequest, DeltaResponse, PageRequest, PageResponse, WriteRequest,
};
use protobuf::{Message, MessageFull};
use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};

use crate::session_reads::{self, cover_url, session_album};
use crate::webapi::{Album, RecentPlay, Show, Track, TrackPage, WebApi};

/// Liked songs and saved albums share one set, told apart by URI type.
const COLLECTION: &str = "collection";
const SHOWS: &str = "show";

/// What Spotify's clients send the collection service; plain protobuf is
/// tried when it is refused.
const MEDIA: &str = "application/vnd.collection-v2.spotify.proto";
const PLAIN: &str = "application/x-protobuf";

const PAGE: i32 = 300;
/// Stops a runaway paging loop; far above any real library.
const MAX_ITEMS: usize = 50_000;
/// A set read this recently is not asked for again.
const FRESH: Duration = Duration::from_secs(600);
/// How old a saved copy may be to stand in when Spotify does not answer.
const STALE: Duration = Duration::from_secs(30 * 86_400);
/// Metadata of library items: it rarely changes.
const META_TTL: Duration = Duration::from_secs(7 * 86_400);
const CONCURRENCY: usize = 16;

#[derive(Debug, Clone, Serialize, Deserialize)]
struct Item {
    uri: String,
    /// Unix seconds.
    added_at: i64,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
struct Synced {
    /// Newest first.
    items: Vec<Item>,
    /// Lets the next read ask only for what changed.
    sync_token: String,
}

/// Sets read this run, by disk key, with when they were last synced.
static SETS: LazyLock<Mutex<HashMap<String, (Instant, Synced)>>> = LazyLock::new(Default::default);

/// The set a URI is saved in, None for what the session does not handle
/// here (playlists, artists, episodes).
fn set_of(uri: &str) -> Option<&'static str> {
    if uri.starts_with("spotify:track:") || uri.starts_with("spotify:album:") {
        Some(COLLECTION)
    } else if uri.starts_with("spotify:show:") {
        Some(SHOWS)
    } else {
        None
    }
}

fn set_key(session: &Session, set: &str) -> String {
    format!("session:collection:{}:{set}", session.username())
}

fn now_secs() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

async fn call<M: MessageFull>(session: &Session, endpoint: &str, message: &M) -> Result<Vec<u8>> {
    let spclient = session.spclient();
    let answer = match spclient.collection_request(endpoint, MEDIA, message).await {
        Err(error) if error.kind == ErrorKind::InvalidArgument => {
            log::info!("{endpoint} refused {MEDIA}: {error}");
            spclient.collection_request(endpoint, PLAIN, message).await
        }
        answer => answer,
    };
    Ok(answer?.to_vec())
}

/// Sets `uri` as saved at `added_at`, or removes it with None.
fn apply(items: &mut Vec<Item>, uri: &str, added_at: Option<i64>) {
    items.retain(|item| item.uri != uri);
    if let Some(added_at) = added_at {
        items.push(Item { uri: uri.to_owned(), added_at });
    }
}

fn sort(items: &mut [Item]) {
    items.sort_by(|a, b| b.added_at.cmp(&a.added_at));
}

async fn read_all(session: &Session, set: &str) -> Result<Synced> {
    let mut synced = Synced::default();
    let mut token = String::new();
    loop {
        let request = PageRequest {
            username: session.username(),
            set: set.to_owned(),
            pagination_token: token,
            limit: PAGE,
            ..Default::default()
        };
        let page = PageResponse::parse_from_bytes(&call(session, "/collection/v2/paging", &request).await?)?;
        // The first page's token: changes made while paging come again in the next delta.
        if synced.sync_token.is_empty() {
            synced.sync_token = page.sync_token;
        }
        synced.items.extend(
            page.items
                .into_iter()
                .filter(|item| !item.is_removed)
                .map(|item| Item { uri: item.uri, added_at: item.added_at as i64 }),
        );
        token = page.next_page_token;
        if token.is_empty() || synced.items.len() >= MAX_ITEMS {
            break;
        }
    }
    sort(&mut synced.items);
    Ok(synced)
}

/// What changed since `old` was read, applied to it; None when Spotify
/// wants a full read instead.
async fn read_delta(session: &Session, set: &str, old: &Synced) -> Result<Option<Synced>> {
    let request = DeltaRequest {
        username: session.username(),
        set: set.to_owned(),
        last_sync_token: old.sync_token.clone(),
        ..Default::default()
    };
    let delta = DeltaResponse::parse_from_bytes(&call(session, "/collection/v2/delta", &request).await?)?;
    if !delta.delta_update_possible {
        return Ok(None);
    }
    let mut synced = old.clone();
    for change in delta.items {
        apply(&mut synced.items, &change.uri, (!change.is_removed).then_some(change.added_at as i64));
    }
    if !delta.sync_token.is_empty() {
        synced.sync_token = delta.sync_token;
    }
    sort(&mut synced.items);
    Ok(Some(synced))
}

/// The items of a set, newest first: from memory when synced in the last
/// minutes, else synced from the saved copy (only the changes) or read
/// whole. The saved copy answers when Spotify does not.
async fn items(web: &WebApi, session: &Session, set: &'static str, refresh: bool) -> Result<Vec<Item>> {
    let key = set_key(session, set);
    let known = SETS.lock().unwrap().get(&key).cloned();
    if let Some((at, synced)) = &known {
        if !refresh && at.elapsed() < FRESH {
            return Ok(synced.items.clone());
        }
    }
    let base = known.map(|(_, synced)| synced).or_else(|| web.load_local::<Synced>(&key, STALE));
    let read = async {
        if let Some(base) = base.as_ref().filter(|base| !base.sync_token.is_empty()) {
            match read_delta(session, set, base).await {
                Ok(Some(synced)) => return Ok(synced),
                Ok(None) => {}
                Err(error) => log::info!("collection {set} delta: {error:#}"),
            }
        }
        read_all(session, set).await
    };
    match read.await {
        Ok(synced) => {
            web.save_local(&key, &synced);
            let items = synced.items.clone();
            SETS.lock().unwrap().insert(key, (Instant::now(), synced));
            Ok(items)
        }
        Err(error) => match base {
            Some(base) => {
                log::info!("collection {set}: {error:#}; the saved copy answers");
                Ok(base.items)
            }
            None => Err(error),
        },
    }
}

/// Metadata for `uris` in order, kept on disk under `key`; only what is
/// not kept yet is fetched. Items that fail to load are left out.
async fn resolve<T, F, Fut>(web: &WebApi, key: &str, uris: &[String], uri_of: fn(&T) -> &str, fetch: F) -> Vec<T>
where
    T: Clone + Serialize + DeserializeOwned,
    F: FnOnce(Vec<String>) -> Fut,
    Fut: Future<Output = Vec<T>>,
{
    let mut known: HashMap<String, T> = web.load_local(key, META_TTL).unwrap_or_default();
    let missing: Vec<String> = uris.iter().filter(|uri| !known.contains_key(*uri)).cloned().collect();
    if !missing.is_empty() || known.len() > uris.len() {
        for item in fetch(missing).await {
            known.insert(uri_of(&item).to_owned(), item);
        }
        // Only what is still asked for, so removed items do not pile up.
        let wanted: HashSet<&String> = uris.iter().collect();
        known.retain(|uri, _| wanted.contains(uri));
        web.save_local(key, &known);
    }
    uris.iter().filter_map(|uri| known.get(uri).cloned()).collect()
}

/// A page of liked songs, newest first, with when each was liked.
pub async fn liked_tracks(web: &WebApi, session: &Session, offset: u32, limit: u32, refresh: bool) -> Result<TrackPage> {
    let liked: Vec<Item> = items(web, session, COLLECTION, refresh)
        .await?
        .into_iter()
        .filter(|item| item.uri.starts_with("spotify:track:"))
        .collect();
    let total = liked.len() as u32;
    let page: Vec<&Item> = liked.iter().skip(offset as usize).take(limit.clamp(1, 100) as usize).collect();
    let end = offset + page.len() as u32;
    let uris: Vec<String> = page.iter().map(|item| item.uri.clone()).collect();
    // One saved page per offset: a like at the top shifts every page by one,
    // and the copy still has all but one of its songs.
    let key = format!("session:liked:{}:{offset}:{limit}", session.username());
    let added: HashMap<&str, i64> = page.iter().map(|item| (item.uri.as_str(), item.added_at)).collect();
    let tracks = resolve(web, &key, &uris, |t: &Track| t.uri.as_str(), |missing| async move {
        session_reads::tracks(session, &missing).await
    })
    .await
    .into_iter()
    .map(|track| Track {
        added_at_ms: added.get(track.uri.as_str()).map(|secs| secs * 1000).filter(|ms| *ms > 0),
        ..track
    })
    .collect();
    Ok(TrackPage {
        tracks,
        total,
        next_offset: (end < total).then_some(end),
    })
}

/// Saved albums, newest first.
pub async fn saved_albums(web: &WebApi, session: &Session, refresh: bool) -> Result<Vec<Album>> {
    let uris: Vec<String> = items(web, session, COLLECTION, refresh)
        .await?
        .into_iter()
        .filter(|item| item.uri.starts_with("spotify:album:"))
        .map(|item| item.uri)
        .collect();
    let key = format!("session:albums:{}", session.username());
    Ok(resolve(web, &key, &uris, |a: &Album| a.uri.as_str(), |missing| async move {
        stream::iter(missing)
            .map(|uri| async move {
                let uri = SpotifyUri::from_uri(&uri).ok()?;
                SessionAlbum::get(session, &uri).await.ok().map(|album| session_album(&album))
            })
            .buffered(CONCURRENCY)
            .filter_map(|album| async move { album })
            .collect()
            .await
    })
    .await)
}

/// Saved podcasts, newest first.
pub async fn saved_shows(web: &WebApi, session: &Session, refresh: bool) -> Result<Vec<Show>> {
    let uris: Vec<String> = items(web, session, SHOWS, refresh).await?.into_iter().map(|item| item.uri).collect();
    let key = format!("session:shows:{}", session.username());
    Ok(resolve(web, &key, &uris, |s: &Show| s.uri.as_str(), |missing| async move {
        stream::iter(missing)
            .map(|uri| async move {
                let uri = SpotifyUri::from_uri(&uri).ok()?;
                let show = SessionShow::get(session, &uri).await.ok()?;
                Some(Show {
                    id: show.id.to_id(),
                    uri: show.id.to_uri(),
                    name: show.name.clone(),
                    publisher: show.publisher.clone(),
                    image_url: show.covers.first().map(|image| cover_url(&image.id)),
                    episode_count: show.episodes.len() as u32,
                })
            })
            .buffered(CONCURRENCY)
            .filter_map(|show| async move { show })
            .collect()
            .await
    })
    .await)
}

/// Whether each URI is saved, in order; None for URIs the session does
/// not answer here (see [set_of]).
pub async fn contains(web: &WebApi, session: &Session, uris: &[String]) -> Result<Vec<Option<bool>>> {
    let mut sets: HashMap<&'static str, HashSet<String>> = HashMap::new();
    for set in uris.iter().filter_map(|uri| set_of(uri)) {
        if !sets.contains_key(set) {
            let saved = items(web, session, set, false).await?.into_iter().map(|item| item.uri).collect();
            sets.insert(set, saved);
        }
    }
    Ok(uris
        .iter()
        .map(|uri| set_of(uri).map(|set| sets[set].contains(uri)))
        .collect())
}

/// Saves (or with `on` false, removes) what the session handles. Returns
/// the URIs left for the Web API: those of other kinds and those Spotify
/// refused here.
pub async fn set_saved(web: &WebApi, session: &Session, uris: &[String], on: bool) -> Vec<String> {
    let mut rest = Vec::new();
    let mut groups: Vec<(&'static str, Vec<String>)> = Vec::new();
    for uri in uris {
        match set_of(uri) {
            Some(set) => match groups.iter_mut().find(|(s, _)| *s == set) {
                Some((_, group)) => group.push(uri.clone()),
                None => groups.push((set, vec![uri.clone()])),
            },
            None => rest.push(uri.clone()),
        }
    }
    for (set, group) in groups {
        if let Err(error) = write(web, session, set, &group, on).await {
            log::info!("collection {set} write: {error:#}");
            // Written through the Web API instead: read the set again next time.
            SETS.lock().unwrap().remove(&set_key(session, set));
            rest.extend(group);
        }
    }
    rest
}

async fn write(web: &WebApi, session: &Session, set: &'static str, uris: &[String], on: bool) -> Result<()> {
    let now = now_secs();
    let request = WriteRequest {
        username: session.username(),
        set: set.to_owned(),
        items: uris
            .iter()
            .map(|uri| CollectionItem {
                uri: uri.clone(),
                added_at: now as i32,
                is_removed: !on,
                ..Default::default()
            })
            .collect(),
        client_update_id: format!("{:016x}", rand::random::<u64>()),
        ..Default::default()
    };
    call(session, "/collection/v2/write", &request).await?;

    // The copies follow at once, so the next screen shows the change
    // without reading the set again.
    let key = set_key(session, set);
    let mut sets = SETS.lock().unwrap();
    let mut from_disk: Option<Synced>;
    let synced = match sets.get_mut(&key) {
        Some((_, synced)) => Some(synced),
        None => {
            from_disk = web.load_local::<Synced>(&key, STALE);
            from_disk.as_mut()
        }
    };
    if let Some(synced) = synced {
        for uri in uris {
            apply(&mut synced.items, uri, on.then_some(now));
        }
        sort(&mut synced.items);
        web.save_local(&key, &*synced);
    }
    Ok(())
}

/// Most contexts asked for.
const RECENT: usize = 50;

/// The playlists, albums and artists played lately, newest first, each
/// with the last song played from it when Spotify says which. One row per
/// context, unlike the Web API's one row per play.
pub async fn recently_played(session: &Session) -> Result<Vec<RecentPlay>> {
    let endpoint = format!(
        "/recently-played/v3/user/{}/recently-played?format=json&offset=0&limit={RECENT}&filter=default,collection-new-episodes",
        session.username()
    );
    let bytes = session.spclient().request_as_json(&reqwest::Method::GET, &endpoint, None, None).await?;
    let json: serde_json::Value = serde_json::from_slice(&bytes)?;
    let contexts = json
        .get("playContexts")
        .or_else(|| json.get("contexts"))
        .and_then(|value| value.as_array())
        .ok_or_else(|| anyhow!("o Spotify não mandou as tocadas recentemente"))?;

    struct Play {
        context: String,
        track: Option<String>,
        at_ms: i64,
    }
    let mut plays: Vec<Play> = contexts
        .iter()
        .filter_map(|context| {
            let uri = context.get("uri")?.as_str()?.to_owned();
            let at = ["lastPlayedTime", "lastPlayedTimestamp", "timestamp"]
                .iter()
                .find_map(|key| context.get(*key)?.as_i64())
                .unwrap_or(0);
            let track = context
                .get("lastPlayedTrackUri")
                .and_then(|value| value.as_str())
                .filter(|uri| uri.starts_with("spotify:track:") || uri.starts_with("spotify:episode:"))
                .map(str::to_owned);
            // Seconds or milliseconds: no play is from before 1973.
            let at_ms = if at < 100_000_000_000 { at * 1000 } else { at };
            Some(Play { context: uri, track, at_ms })
        })
        .take(RECENT)
        .collect();
    plays.sort_by(|a, b| b.at_ms.cmp(&a.at_ms));

    let track_uris: Vec<String> = plays.iter().filter_map(|play| play.track.clone()).collect();
    let tracks: HashMap<String, Track> = session_reads::tracks(session, &track_uris)
        .await
        .into_iter()
        .map(|track| (track.uri.clone(), track))
        .collect();
    // Albums without a known song show the album itself, as the Web API's
    // rows would (its name and cover come from the song's album).
    let albums: Vec<SpotifyUri> = plays
        .iter()
        .filter(|play| play.track.as_ref().is_none_or(|uri| !tracks.contains_key(uri)))
        .filter(|play| play.context.starts_with("spotify:album:"))
        .filter_map(|play| SpotifyUri::from_uri(&play.context).ok())
        .collect();
    let albums: HashMap<String, Track> = stream::iter(albums)
        .map(|uri| async move { SessionAlbum::get(session, &uri).await })
        .buffered(CONCURRENCY)
        .filter_map(|result| async move {
            let album = result.ok()?;
            let face = session_album(&album);
            Some((
                face.uri.clone(),
                Track {
                    uri: face.uri,
                    title: face.name.clone(),
                    artists: face.artists,
                    artist_ids: album.artists.iter().map(|artist| artist.id.to_id()).collect(),
                    album: face.name,
                    album_id: Some(face.id),
                    image_url: face.image_url,
                    duration_ms: 0,
                    explicit: false,
                    playable: true,
                    added_at_ms: None,
                    added_by: None,
                },
            ))
        })
        .collect()
        .await;

    Ok(plays
        .into_iter()
        .map(|play| {
            let track = play
                .track
                .as_ref()
                .and_then(|uri| tracks.get(uri))
                .or_else(|| albums.get(&play.context))
                .cloned()
                // Playlists and artists: the screens show them from the context.
                .unwrap_or_else(|| Track {
                    uri: play.context.clone(),
                    title: String::new(),
                    artists: String::new(),
                    artist_ids: Vec::new(),
                    album: String::new(),
                    album_id: None,
                    image_url: None,
                    duration_ms: 0,
                    explicit: false,
                    playable: true,
                    added_at_ms: None,
                    added_by: None,
                });
            RecentPlay {
                track,
                context_uri: Some(play.context),
                played_at: iso_time(play.at_ms),
            }
        })
        .collect())
}

/// `2026-10-14T18:03:00Z`, like the Web API's `played_at`.
fn iso_time(ms: i64) -> String {
    match librespot_core::date::Date::from_timestamp_ms(ms) {
        Ok(date) => format!(
            "{:04}-{:02}-{:02}T{:02}:{:02}:{:02}Z",
            date.year(),
            date.month() as u8,
            date.day(),
            date.hour(),
            date.minute(),
            date.second()
        ),
        Err(_) => String::new(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn apply_moves_and_removes() {
        let mut items = vec![
            Item { uri: "spotify:track:a".into(), added_at: 3 },
            Item { uri: "spotify:track:b".into(), added_at: 2 },
        ];
        apply(&mut items, "spotify:track:b", Some(5));
        apply(&mut items, "spotify:track:a", None);
        sort(&mut items);
        assert_eq!(items.len(), 1);
        assert_eq!(items[0].uri, "spotify:track:b");
        assert_eq!(items[0].added_at, 5);
    }

    #[test]
    fn sets_by_uri() {
        assert_eq!(set_of("spotify:track:x"), Some(COLLECTION));
        assert_eq!(set_of("spotify:album:x"), Some(COLLECTION));
        assert_eq!(set_of("spotify:show:x"), Some(SHOWS));
        assert_eq!(set_of("spotify:playlist:x"), None);
    }

    #[test]
    fn write_request_round_trips() {
        let request = WriteRequest {
            username: "me".into(),
            set: COLLECTION.into(),
            items: vec![CollectionItem { uri: "spotify:track:x".into(), added_at: 7, ..Default::default() }],
            ..Default::default()
        };
        let parsed = WriteRequest::parse_from_bytes(&request.write_to_bytes().unwrap()).unwrap();
        assert_eq!(parsed.items[0].uri, "spotify:track:x");
        assert_eq!(parsed.set, "collection");
    }

    #[test]
    fn iso_time_is_utc() {
        assert_eq!(iso_time(0), "1970-01-01T00:00:00Z");
        assert_eq!(iso_time(1_760_000_000_000), "2025-10-09T08:53:20Z");
    }
}
