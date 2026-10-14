//! Spotify Web API: profile, library, playlists, albums, artists and search.
//!
//! Two registered apps sign in separately and share the work:
//! - the user's **personal** app has its own rate allowance, but Spotify
//!   denies apps created after November 2024 some data (Spotify-owned
//!   playlists, related artists, playlist search);
//! - the **shared** app (ncspot's) predates that and covers the rest.
//! Requests prefer the personal app and fall back to the shared one when it
//! is missing, refused (403/404) or rate limited.
//!
//! Responses are cached on disk; a failed refresh returns the cached copy.

use std::collections::hash_map::DefaultHasher;
use std::hash::{Hash, Hasher};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use anyhow::{Result, anyhow, bail};
use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};
use tokio::sync::Mutex;

use crate::auth;

const API: &str = "https://api.spotify.com/v1";
/// Cap on playlist and album pages followed, so a 10 000-track playlist
/// does not stall the screen.
const MAX_ITEMS: usize = 2000;
const RATE_LIMIT_WAIT: Duration = Duration::from_secs(5);

// ---------------------------------------------------------------- models --

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum WebApp {
    Shared,
    Personal,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct WebStatus {
    pub shared_signed_in: bool,
    pub personal_client_id: Option<String>,
    pub personal_signed_in: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, uniffi::Record)]
pub struct Profile {
    pub id: String,
    pub display_name: String,
    pub image_url: Option<String>,
    /// "premium", "free"…
    pub product: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, uniffi::Record)]
pub struct Track {
    pub uri: String,
    pub title: String,
    pub artists: String,
    pub artist_ids: Vec<String>,
    pub album: String,
    pub album_id: Option<String>,
    pub image_url: Option<String>,
    pub duration_ms: u32,
    pub explicit: bool,
    pub playable: bool,
    /// When it was added to the playlist or liked songs (Unix ms), if known.
    pub added_at_ms: Option<i64>,
}

#[derive(Debug, Clone, Serialize, Deserialize, uniffi::Record)]
pub struct Album {
    pub id: String,
    pub uri: String,
    pub name: String,
    pub artists: String,
    pub image_url: Option<String>,
    pub year: Option<String>,
    pub track_count: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize, uniffi::Record)]
pub struct AlbumDetail {
    pub album: Album,
    pub tracks: Vec<Track>,
}

#[derive(Debug, Clone, Serialize, Deserialize, uniffi::Record)]
pub struct Artist {
    pub id: String,
    pub uri: String,
    pub name: String,
    pub image_url: Option<String>,
    pub followers: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize, uniffi::Record)]
pub struct ArtistDetail {
    pub artist: Artist,
    pub top_tracks: Vec<Track>,
    pub albums: Vec<Album>,
}

#[derive(Debug, Clone, Serialize, Deserialize, uniffi::Record)]
pub struct Playlist {
    pub id: String,
    pub uri: String,
    pub name: String,
    pub description: String,
    pub owner: String,
    pub owner_id: String,
    pub image_url: Option<String>,
    pub track_count: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize, uniffi::Record)]
pub struct PlaylistDetail {
    pub playlist: Playlist,
    pub tracks: Vec<Track>,
}

#[derive(Debug, Clone, Serialize, Deserialize, uniffi::Record)]
pub struct TrackPage {
    pub tracks: Vec<Track>,
    pub total: u32,
    /// Offset of the next page, None at the end.
    pub next_offset: Option<u32>,
}

/// One recent play and the playlist/album it was played from, if any.
#[derive(Debug, Clone, Serialize, Deserialize, uniffi::Record)]
pub struct RecentPlay {
    pub track: Track,
    /// `spotify:playlist:…`, `spotify:album:…`, `spotify:artist:…` or None.
    pub context_uri: Option<String>,
    pub played_at: String,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize, uniffi::Record)]
pub struct SearchResults {
    pub tracks: Vec<Track>,
    pub albums: Vec<Album>,
    pub artists: Vec<Artist>,
    pub playlists: Vec<Playlist>,
}

// ------------------------------------------------------- raw API shapes --

#[derive(Debug, Clone, Deserialize)]
struct Paging<T> {
    #[serde(default = "Vec::new")]
    items: Vec<Option<T>>,
    #[serde(default)]
    total: u32,
    next: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
struct RawImage {
    url: String,
    width: Option<u32>,
}

#[derive(Debug, Clone, Deserialize)]
struct RawArtistRef {
    id: Option<String>,
    name: String,
}

#[derive(Debug, Clone, Deserialize)]
struct RawAlbum {
    id: Option<String>,
    uri: Option<String>,
    name: String,
    #[serde(default)]
    images: Vec<RawImage>,
    #[serde(default)]
    artists: Vec<RawArtistRef>,
    release_date: Option<String>,
    total_tracks: Option<u32>,
    tracks: Option<Paging<RawTrack>>,
}

#[derive(Debug, Clone, Deserialize)]
struct RawTrack {
    id: Option<String>,
    uri: Option<String>,
    name: String,
    #[serde(default)]
    duration_ms: u32,
    #[serde(default)]
    explicit: bool,
    #[serde(default)]
    artists: Vec<RawArtistRef>,
    album: Option<RawAlbum>,
    is_playable: Option<bool>,
    #[serde(default)]
    is_local: bool,
    #[serde(rename = "type")]
    kind: Option<String>,
}

/// An entry of /me/tracks or /playlists/{id}/items. Spotify renamed
/// `track` to `item` in 2025; accept both.
#[derive(Debug, Deserialize)]
struct SavedTrack {
    #[serde(alias = "track")]
    item: Option<RawTrack>,
    added_at: Option<String>,
}

impl SavedTrack {
    fn into_track(self) -> Option<Track> {
        let added_at_ms = self
            .added_at
            .as_deref()
            .and_then(|at| librespot_core::date::Date::from_iso8601(at).ok())
            .map(|date| date.as_timestamp_ms());
        let mut track = self.item?.into_track(None)?;
        track.added_at_ms = added_at_ms;
        Some(track)
    }
}

#[derive(Debug, Deserialize)]
struct SavedAlbum {
    album: RawAlbum,
}

#[derive(Debug, Deserialize)]
struct Count {
    #[serde(default)]
    total: u32,
}

#[derive(Debug, Deserialize)]
struct RawOwner {
    id: String,
    display_name: Option<String>,
}

#[derive(Debug, Deserialize)]
struct RawPlaylist {
    id: String,
    uri: String,
    name: String,
    description: Option<String>,
    images: Option<Vec<RawImage>>,
    owner: RawOwner,
    // `tracks` before the 2025 rename, `items` after.
    tracks: Option<Count>,
    items: Option<Count>,
}

#[derive(Debug, Deserialize)]
struct RawArtist {
    id: String,
    uri: String,
    name: String,
    #[serde(default)]
    images: Vec<RawImage>,
    followers: Option<Count>,
}

#[derive(Debug, Deserialize)]
struct RawProfile {
    id: String,
    display_name: Option<String>,
    #[serde(default)]
    images: Vec<RawImage>,
    product: Option<String>,
}

#[derive(Debug, Deserialize)]
struct RawContext {
    uri: Option<String>,
}

#[derive(Debug, Deserialize)]
struct RawRecentPlay {
    #[serde(alias = "item")]
    track: Option<RawTrack>,
    played_at: Option<String>,
    context: Option<RawContext>,
}

#[derive(Debug, Deserialize)]
struct TopTracks {
    tracks: Vec<RawTrack>,
}

#[derive(Debug, Default, Deserialize)]
struct RawSearch {
    tracks: Option<Paging<RawTrack>>,
    albums: Option<Paging<RawAlbum>>,
    artists: Option<Paging<RawArtist>>,
    playlists: Option<Paging<RawPlaylist>>,
}

/// A medium image for lists: the smallest at least 250 px wide, else the
/// largest. Spotify lists images largest first.
fn pick_image(images: &[RawImage]) -> Option<String> {
    images
        .iter()
        .filter(|image| image.width.is_none_or(|width| width >= 250))
        .last()
        .or(images.first())
        .map(|image| image.url.clone())
}

/// Keep the first of each id. Spotify can list a playlist twice (it moved
/// between pages, or sits in a folder), and lists need unique keys.
fn dedupe<T>(items: Vec<T>, id: impl Fn(&T) -> &str) -> Vec<T> {
    let mut seen = std::collections::HashSet::new();
    items
        .into_iter()
        .filter(|item| seen.insert(id(item).to_owned()))
        .collect()
}

fn join_artists(artists: &[RawArtistRef]) -> String {
    artists
        .iter()
        .map(|artist| artist.name.as_str())
        .collect::<Vec<_>>()
        .join(", ")
}

impl RawTrack {
    /// None for podcast episodes and local files, which the player cannot
    /// stream yet.
    fn into_track(self, album: Option<&RawAlbum>) -> Option<Track> {
        if self.is_local
            || self.id.is_none()
            || self.kind.as_deref().is_some_and(|kind| kind != "track")
        {
            return None;
        }
        let album = self.album.as_ref().or(album);
        Some(Track {
            uri: self.uri?,
            title: self.name,
            artists: join_artists(&self.artists),
            artist_ids: self.artists.iter().filter_map(|a| a.id.clone()).collect(),
            album: album.map(|a| a.name.clone()).unwrap_or_default(),
            album_id: album.and_then(|a| a.id.clone()),
            image_url: album.and_then(|a| pick_image(&a.images)),
            duration_ms: self.duration_ms,
            explicit: self.explicit,
            playable: self.is_playable.unwrap_or(true),
            added_at_ms: None,
        })
    }
}

impl RawAlbum {
    fn to_album(&self) -> Option<Album> {
        Some(Album {
            id: self.id.clone()?,
            uri: self.uri.clone()?,
            name: self.name.clone(),
            artists: join_artists(&self.artists),
            image_url: pick_image(&self.images),
            year: self.release_date.as_ref().map(|date| date.chars().take(4).collect()),
            track_count: self.total_tracks.unwrap_or_default(),
        })
    }
}

impl RawPlaylist {
    fn into_playlist(self) -> Playlist {
        Playlist {
            id: self.id,
            uri: self.uri,
            name: self.name,
            description: self.description.unwrap_or_default(),
            owner: self.owner.display_name.unwrap_or_else(|| self.owner.id.clone()),
            owner_id: self.owner.id,
            image_url: self.images.as_deref().and_then(pick_image),
            track_count: self.items.or(self.tracks).map(|c| c.total).unwrap_or_default(),
        }
    }
}

impl RawArtist {
    fn into_artist(self) -> Artist {
        Artist {
            id: self.id,
            uri: self.uri,
            name: self.name,
            image_url: pick_image(&self.images),
            followers: self.followers.map(|f| f.total).unwrap_or_default(),
        }
    }
}

// ---------------------------------------------------------- tokens --

#[derive(Debug, Default, Serialize, Deserialize)]
struct StoredGrant {
    client_id: String,
    refresh_token: Option<String>,
}

#[derive(Default)]
struct GrantState {
    stored: StoredGrant,
    access: Option<(String, Instant)>,
}

/// Request routing: which app may answer.
#[derive(Clone, Copy)]
enum Route {
    /// Personal first, shared as fallback.
    Personal,
    /// Only the shared app has access (e.g. playlist search).
    Shared,
}

pub struct WebApi {
    dir: PathBuf,
    http: reqwest::Client,
    /// Paths being fetched again in the background.
    revalidating: std::sync::Mutex<std::collections::HashSet<String>>,
    shared: Mutex<GrantState>,
    personal: Mutex<GrantState>,
}

impl WebApi {
    pub fn new(cache_dir: &Path) -> Self {
        let dir = cache_dir.join("webapi");
        let _ = std::fs::create_dir_all(dir.join("cache"));
        let load = |name: &str| GrantState {
            stored: std::fs::read(dir.join(name))
                .ok()
                .and_then(|bytes| serde_json::from_slice(&bytes).ok())
                .unwrap_or_default(),
            access: None,
        };
        let mut shared = load("shared.json");
        shared.stored.client_id = auth::SHARED_WEB_CLIENT_ID.to_owned();
        let personal = load("personal.json");
        Self {
            http: reqwest::Client::new(),
            revalidating: Default::default(),
            shared: Mutex::new(shared),
            personal: Mutex::new(personal),
            dir,
        }
    }

    fn grant(&self, app: WebApp) -> &Mutex<GrantState> {
        match app {
            WebApp::Shared => &self.shared,
            WebApp::Personal => &self.personal,
        }
    }

    fn save(&self, app: WebApp, stored: &StoredGrant) {
        let name = match app {
            WebApp::Shared => "shared.json",
            WebApp::Personal => "personal.json",
        };
        if let Ok(json) = serde_json::to_vec(stored) {
            let _ = std::fs::write(self.dir.join(name), json);
        }
    }

    pub async fn status(self: &Arc<Self>) -> WebStatus {
        let shared = self.shared.lock().await;
        let personal = self.personal.lock().await;
        WebStatus {
            shared_signed_in: shared.stored.refresh_token.is_some(),
            personal_client_id: Some(personal.stored.client_id.clone()).filter(|id| !id.is_empty()),
            personal_signed_in: !personal.stored.client_id.is_empty()
                && personal.stored.refresh_token.is_some(),
        }
    }

    /// Set or clear the personal app. A different id drops its sign-in.
    pub async fn set_personal_client_id(self: &Arc<Self>, client_id: Option<String>) {
        let client_id = client_id.map(|id| id.trim().to_owned()).unwrap_or_default();
        let mut personal = self.personal.lock().await;
        if personal.stored.client_id != client_id {
            *personal = GrantState {
                stored: StoredGrant {
                    client_id,
                    refresh_token: None,
                },
                access: None,
            };
            self.save(WebApp::Personal, &personal.stored);
        }
    }

    pub async fn login_grant(self: &Arc<Self>, app: WebApp) -> Result<auth::Grant> {
        let client_id = self.grant(app).lock().await.stored.client_id.clone();
        if client_id.is_empty() {
            bail!("informe o Client ID do app pessoal primeiro");
        }
        Ok(auth::Grant::web(&client_id))
    }

    pub async fn finish_login(self: &Arc<Self>, app: WebApp, token: auth::TokenResponse) {
        let mut grant = self.grant(app).lock().await;
        grant.stored.refresh_token = token.refresh_token;
        grant.access = Some((token.access_token, expiry(token.expires_in)));
        self.save(app, &grant.stored);
    }

    pub async fn sign_out(self: &Arc<Self>, app: WebApp) {
        let mut grant = self.grant(app).lock().await;
        grant.stored.refresh_token = None;
        grant.access = None;
        self.save(app, &grant.stored);
    }

    /// A valid access token for `app`, refreshing it when it expires soon.
    async fn token(self: &Arc<Self>, app: WebApp, force_refresh: bool) -> Result<String> {
        let mut grant = self.grant(app).lock().await;
        if !force_refresh {
            if let Some((token, expires)) = &grant.access {
                if *expires > Instant::now() + Duration::from_secs(60) {
                    return Ok(token.clone());
                }
            }
        }
        let refresh_token = grant
            .stored
            .refresh_token
            .clone()
            .ok_or_else(|| anyhow!("app {app:?} não conectado"))?;
        let response = auth::refresh(&grant.stored.client_id, &refresh_token).await?;
        if let Some(rotated) = response.refresh_token {
            grant.stored.refresh_token = Some(rotated);
            self.save(app, &grant.stored);
        }
        grant.access = Some((response.access_token.clone(), expiry(response.expires_in)));
        Ok(response.access_token)
    }

    async fn signed_in(self: &Arc<Self>, app: WebApp) -> bool {
        let grant = self.grant(app).lock().await;
        !grant.stored.client_id.is_empty() && grant.stored.refresh_token.is_some()
    }

    // ------------------------------------------------------ requests --

    /// GET a path (or a full `next` URL) through the apps `route` allows.
    async fn get_json(self: &Arc<Self>, path: &str, route: Route) -> Result<serde_json::Value> {
        let apps: &[WebApp] = match route {
            Route::Personal => &[WebApp::Personal, WebApp::Shared],
            Route::Shared => &[WebApp::Shared],
        };
        let mut last_error = anyhow!("nenhum app da Web API conectado");
        for &app in apps {
            if !self.signed_in(app).await {
                continue;
            }
            match self.get_with(app, path).await {
                Ok(value) => return Ok(value),
                Err(error) => {
                    log::warn!("{app:?} could not answer {path}: {error:#}");
                    last_error = error;
                }
            }
        }
        Err(last_error)
    }

    async fn get_with(self: &Arc<Self>, app: WebApp, path: &str) -> Result<serde_json::Value> {
        let url = if path.starts_with("https://") {
            path.to_owned()
        } else {
            format!("{API}{path}")
        };
        let mut refreshed = false;
        let mut waited = false;
        loop {
            let token = self.token(app, false).await?;
            let response = self.http.get(&url).bearer_auth(&token).send().await?;
            match response.status().as_u16() {
                200..=299 => return Ok(response.json().await?),
                401 if !refreshed => {
                    refreshed = true;
                    self.token(app, true).await?;
                }
                429 => {
                    let wait = response
                        .headers()
                        .get("retry-after")
                        .and_then(|v| v.to_str().ok())
                        .and_then(|v| v.parse::<u64>().ok())
                        .map(Duration::from_secs)
                        .unwrap_or(RATE_LIMIT_WAIT);
                    if waited || wait > RATE_LIMIT_WAIT {
                        bail!("limite de requisições ({}s)", wait.as_secs());
                    }
                    waited = true;
                    tokio::time::sleep(wait).await;
                }
                status => {
                    let detail = response.text().await.unwrap_or_default();
                    log::debug!("HTTP {status} for {url}: {}", detail.chars().take(200).collect::<String>());
                    match status {
                        403 => bail!("o Spotify não liberou isto para o app conectado (403)"),
                        404 => bail!("não encontrado no Spotify (404)"),
                        500..=599 => bail!("o Spotify está com problemas agora ({status})"),
                        _ => bail!("o Spotify recusou o pedido ({status})"),
                    }
                }
            }
        }
    }

    /// Cached GET, stale-while-revalidate: any cached copy answers at once
    /// (unless `refresh`); one older than `ttl` is also fetched again in the
    /// background for next time. Without a copy, the network answers.
    async fn cached<T: DeserializeOwned>(
        self: &Arc<Self>,
        path: &str,
        route: Route,
        ttl: Duration,
        refresh: bool,
    ) -> Result<T> {
        let file = self.cache_file(path);
        let cached: Option<(u64, serde_json::Value)> = std::fs::read(&file)
            .ok()
            .and_then(|bytes| serde_json::from_slice(&bytes).ok());
        if let Some((saved, value)) = &cached {
            if !refresh {
                if now_secs().saturating_sub(*saved) >= ttl.as_secs() {
                    self.revalidate(path.to_owned(), route, file.clone());
                }
                return Ok(serde_json::from_value(value.clone())?);
            }
        }
        match self.get_json(path, route).await {
            Ok(value) => {
                if let Ok(bytes) = serde_json::to_vec(&(now_secs(), &value)) {
                    let _ = std::fs::write(&file, bytes);
                }
                Ok(serde_json::from_value(value)?)
            }
            Err(error) => match cached {
                Some((_, value)) => {
                    log::warn!("serving stale {path}: {error:#}");
                    Ok(serde_json::from_value(value)?)
                }
                None => Err(error),
            },
        }
    }

    /// Fetch `path` again in the background and refresh its cached copy;
    /// one fetch per path at a time.
    fn revalidate(self: &Arc<Self>, path: String, route: Route, file: PathBuf) {
        if !self.revalidating.lock().unwrap().insert(path.clone()) {
            return;
        }
        let this = Arc::clone(self);
        tokio::spawn(async move {
            match this.get_json(&path, route).await {
                Ok(value) => {
                    if let Ok(bytes) = serde_json::to_vec(&(now_secs(), &value)) {
                        let _ = std::fs::write(&file, bytes);
                    }
                }
                Err(error) => log::info!("could not revalidate {path}: {error:#}"),
            }
            this.revalidating.lock().unwrap().remove(&path);
        });
    }

    /// Follow `next` links, appending items, up to [MAX_ITEMS].
    async fn all_pages<T: DeserializeOwned>(
        self: &Arc<Self>,
        first: Paging<T>,
        route: Route,
    ) -> Result<(Vec<T>, u32)> {
        let total = first.total;
        let mut items: Vec<T> = first.items.into_iter().flatten().collect();
        let mut next = first.next;
        while let Some(url) = next.take() {
            if items.len() >= MAX_ITEMS {
                break;
            }
            let page: Paging<T> = serde_json::from_value(self.get_json(&url, route).await?)?;
            items.extend(page.items.into_iter().flatten());
            next = page.next;
        }
        Ok((items, total))
    }

    fn cache_file(&self, path: &str) -> PathBuf {
        let mut hasher = DefaultHasher::new();
        path.hash(&mut hasher);
        self.dir.join("cache").join(format!("{:016x}.json", hasher.finish()))
    }

    // ------------------------------------------------------- endpoints --

    pub async fn profile(self: &Arc<Self>, refresh: bool) -> Result<Profile> {
        let raw: RawProfile = self
            .cached("/me", Route::Personal, Duration::from_secs(86_400), refresh)
            .await?;
        Ok(Profile {
            display_name: raw.display_name.unwrap_or_else(|| raw.id.clone()),
            id: raw.id,
            image_url: pick_image(&raw.images),
            product: raw.product.unwrap_or_default(),
        })
    }

    pub async fn my_playlists(self: &Arc<Self>, refresh: bool) -> Result<Vec<Playlist>> {
        let first: Paging<RawPlaylist> = self
            .cached("/me/playlists?limit=50", Route::Personal, Duration::from_secs(600), refresh)
            .await?;
        let (items, _) = self.all_pages(first, Route::Personal).await?;
        let playlists = items.into_iter().map(RawPlaylist::into_playlist).collect();
        Ok(dedupe(playlists, |p: &Playlist| &p.id))
    }

    pub async fn playlist_meta(self: &Arc<Self>, id: &str, refresh: bool) -> Result<Playlist> {
        let raw: RawPlaylist = self
            .cached(
                &format!("/playlists/{id}?fields=id,uri,name,description,images,owner(id,display_name),tracks(total),items(total)"),
                Route::Personal,
                Duration::from_secs(600),
                refresh,
            )
            .await?;
        Ok(raw.into_playlist())
    }

    /// Fails with 403 for playlists the user does not own when only a
    /// development-mode app answers; the caller then reads them over the
    /// playback session.
    pub async fn playlist_items(self: &Arc<Self>, id: &str, refresh: bool) -> Result<Vec<Track>> {
        let first: Paging<SavedTrack> = self
            .cached(
                &format!("/playlists/{id}/items?limit=100&market=from_token"),
                Route::Personal,
                Duration::from_secs(600),
                refresh,
            )
            .await?;
        let (items, _) = self.all_pages(first, Route::Personal).await?;
        Ok(items
            .into_iter()
            .filter_map(SavedTrack::into_track)
            .collect())
    }

    /// Disk cache for data that does not come from the Web API.
    pub fn load_local<T: DeserializeOwned>(&self, key: &str, ttl: Duration) -> Option<T> {
        let (saved, value): (u64, serde_json::Value) =
            serde_json::from_slice(&std::fs::read(self.cache_file(key)).ok()?).ok()?;
        if now_secs().saturating_sub(saved) >= ttl.as_secs() {
            return None;
        }
        serde_json::from_value(value).ok()
    }

    pub fn save_local<T: Serialize>(&self, key: &str, value: &T) {
        if let Ok(bytes) = serde_json::to_vec(&(now_secs(), value)) {
            let _ = std::fs::write(self.cache_file(key), bytes);
        }
    }

    pub async fn liked_tracks(self: &Arc<Self>, offset: u32, limit: u32, refresh: bool) -> Result<TrackPage> {
        let limit = limit.clamp(1, 50);
        let page: Paging<SavedTrack> = self
            .cached(
                &format!("/me/tracks?limit={limit}&offset={offset}&market=from_token"),
                Route::Personal,
                Duration::from_secs(300),
                refresh,
            )
            .await?;
        let next_offset = page.next.as_ref().map(|_| offset + limit);
        Ok(TrackPage {
            total: page.total,
            next_offset,
            tracks: page
                .items
                .into_iter()
                .flatten()
                .filter_map(SavedTrack::into_track)
                .collect(),
        })
    }

    pub async fn saved_albums(self: &Arc<Self>, refresh: bool) -> Result<Vec<Album>> {
        let first: Paging<SavedAlbum> = self
            .cached("/me/albums?limit=50", Route::Personal, Duration::from_secs(600), refresh)
            .await?;
        let (items, _) = self.all_pages(first, Route::Personal).await?;
        let albums = items.iter().filter_map(|saved| saved.album.to_album()).collect();
        Ok(dedupe(albums, |a: &Album| &a.id))
    }

    pub async fn album(self: &Arc<Self>, id: &str, refresh: bool) -> Result<AlbumDetail> {
        let mut raw: RawAlbum = self
            .cached(
                &format!("/albums/{id}?market=from_token"),
                Route::Personal,
                Duration::from_secs(86_400),
                refresh,
            )
            .await?;
        let album = raw.to_album().ok_or_else(|| anyhow!("álbum sem id"))?;
        let tracks = match raw.tracks.take() {
            Some(first) => self.all_pages(first, Route::Personal).await?.0,
            None => Vec::new(),
        };
        Ok(AlbumDetail {
            tracks: tracks
                .into_iter()
                .filter_map(|track| track.into_track(Some(&raw)))
                .collect(),
            album,
        })
    }

    pub async fn artist(self: &Arc<Self>, id: &str, refresh: bool) -> Result<ArtistDetail> {
        let ttl = Duration::from_secs(86_400);
        let raw: RawArtist = self
            .cached(&format!("/artists/{id}"), Route::Personal, ttl, refresh)
            .await?;
        let top: TopTracks = self
            .cached(
                &format!("/artists/{id}/top-tracks?market=from_token"),
                Route::Personal,
                ttl,
                refresh,
            )
            .await?;
        let albums: Paging<RawAlbum> = self
            .cached(
                &format!("/artists/{id}/albums?include_groups=album,single&limit=10&market=from_token"),
                Route::Personal,
                ttl,
                refresh,
            )
            .await?;
        Ok(ArtistDetail {
            artist: raw.into_artist(),
            top_tracks: top.tracks.into_iter().filter_map(|t| t.into_track(None)).collect(),
            albums: dedupe(
                albums.items.iter().flatten().filter_map(RawAlbum::to_album).collect(),
                |a: &Album| &a.id,
            ),
        })
    }

    /// The last 50 plays, newest first, with the playlist or album each
    /// came from. Spotify gives no per-playlist play counts; these contexts
    /// are the closest signal of what is listened to most.
    pub async fn recently_played(self: &Arc<Self>, refresh: bool) -> Result<Vec<RecentPlay>> {
        let page: Paging<RawRecentPlay> = self
            .cached("/me/player/recently-played?limit=50", Route::Personal, Duration::from_secs(300), refresh)
            .await?;
        Ok(page
            .items
            .into_iter()
            .flatten()
            .filter_map(|play| {
                Some(RecentPlay {
                    track: play.track?.into_track(None)?,
                    context_uri: play.context.and_then(|c| c.uri),
                    played_at: play.played_at.unwrap_or_default(),
                })
            })
            .collect())
    }

    /// The user's most played tracks of the last weeks.
    pub async fn top_tracks(self: &Arc<Self>, refresh: bool) -> Result<Vec<Track>> {
        let page: Paging<RawTrack> = self
            .cached("/me/top/tracks?time_range=short_term&limit=30", Route::Personal, Duration::from_secs(3600), refresh)
            .await?;
        Ok(page.items.into_iter().flatten().filter_map(|t| t.into_track(None)).collect())
    }

    /// The user's most played artists of the last months.
    pub async fn top_artists(self: &Arc<Self>, refresh: bool) -> Result<Vec<Artist>> {
        let page: Paging<RawArtist> = self
            .cached("/me/top/artists?time_range=medium_term&limit=20", Route::Personal, Duration::from_secs(3600), refresh)
            .await?;
        Ok(dedupe(
            page.items.into_iter().flatten().map(RawArtist::into_artist).collect(),
            |a: &Artist| &a.id,
        ))
    }

    /// Songs, albums and artists from the personal app (10 each, its
    /// limit); playlists only from the shared app.
    pub async fn search(self: &Arc<Self>, query: &str) -> Result<SearchResults> {
        let q = urlencoding::encode(query.trim());
        let catalogue_path = format!("/search?q={q}&type=track,album,artist&limit=10&market=from_token");
        let playlists_path = format!("/search?q={q}&type=playlist&limit=20");
        let catalogue = self.get_json(&catalogue_path, Route::Personal);
        let playlists = async {
            if self.signed_in(WebApp::Shared).await {
                self.get_json(&playlists_path, Route::Shared).await
            } else {
                Ok(serde_json::Value::Null)
            }
        };
        let (catalogue, playlists) = tokio::join!(catalogue, playlists);
        let catalogue: RawSearch = serde_json::from_value(catalogue?)?;
        let playlists: RawSearch = playlists
            .ok()
            .and_then(|value| serde_json::from_value(value).ok())
            .unwrap_or_default();

        Ok(SearchResults {
            tracks: catalogue
                .tracks
                .map(|p| p.items.into_iter().flatten().filter_map(|t| t.into_track(None)).collect())
                .unwrap_or_default(),
            albums: dedupe(
                catalogue
                    .albums
                    .map(|p| p.items.iter().flatten().filter_map(RawAlbum::to_album).collect())
                    .unwrap_or_default(),
                |a: &Album| &a.id,
            ),
            artists: dedupe(
                catalogue
                    .artists
                    .map(|p| p.items.into_iter().flatten().map(RawArtist::into_artist).collect())
                    .unwrap_or_default(),
                |a: &Artist| &a.id,
            ),
            playlists: dedupe(
                playlists
                    .playlists
                    .map(|p| p.items.into_iter().flatten().map(RawPlaylist::into_playlist).collect())
                    .unwrap_or_default(),
                |p: &Playlist| &p.id,
            ),
        })
    }
}

fn expiry(expires_in: u64) -> Instant {
    Instant::now() + Duration::from_secs(expires_in)
}

fn now_secs() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or_default()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn playlist_items_accept_old_and_new_field_names() {
        let new: Paging<SavedTrack> = serde_json::from_str(
            r#"{"items":[{"item":{"id":"a","uri":"spotify:track:a","name":"A","type":"track"}}],"total":1,"next":null}"#,
        )
        .unwrap();
        let old: Paging<SavedTrack> = serde_json::from_str(
            r#"{"items":[{"track":{"id":"b","uri":"spotify:track:b","name":"B","type":"track"}},null],"total":2,"next":null}"#,
        )
        .unwrap();
        let titles = |page: Paging<SavedTrack>| -> Vec<String> {
            page.items
                .into_iter()
                .flatten()
                .filter_map(|s| s.item?.into_track(None))
                .map(|t| t.title)
                .collect()
        };
        assert_eq!(titles(new), ["A"]);
        assert_eq!(titles(old), ["B"]);
    }

    #[test]
    fn episodes_and_local_files_are_skipped() {
        let page: Paging<SavedTrack> = serde_json::from_str(
            r#"{"items":[
                {"item":{"id":"e","uri":"spotify:episode:e","name":"E","type":"episode"}},
                {"item":{"id":null,"uri":"spotify:local:x","name":"L","is_local":true}}
            ],"total":2,"next":null}"#,
        )
        .unwrap();
        assert!(page.items.into_iter().flatten().all(|s| s.item.unwrap().into_track(None).is_none()));
    }

    #[test]
    fn dedupe_keeps_first_of_each_id() {
        let items = vec![("a", 1), ("b", 2), ("a", 3)];
        assert_eq!(dedupe(items, |item| item.0), vec![("a", 1), ("b", 2)]);
    }

    #[test]
    fn picks_a_medium_image() {
        let images = [
            RawImage { url: "640".into(), width: Some(640) },
            RawImage { url: "300".into(), width: Some(300) },
            RawImage { url: "64".into(), width: Some(64) },
        ];
        assert_eq!(pick_image(&images).as_deref(), Some("300"));
        assert_eq!(pick_image(&images[2..]).as_deref(), Some("64"));
    }
}

/// A track object from any endpoint (e.g. the player's `item`).
pub fn parse_track(value: serde_json::Value) -> Option<Track> {
    serde_json::from_value::<RawTrack>(value).ok()?.into_track(None)
}

impl WebApi {
    /// Access tokens for the player endpoints, personal app first.
    pub async fn player_tokens(self: &Arc<Self>) -> Vec<String> {
        let mut tokens = Vec::new();
        for app in [WebApp::Personal, WebApp::Shared] {
            if self.signed_in(app).await {
                match self.token(app, false).await {
                    Ok(token) => tokens.push(token),
                    Err(error) => log::info!("{app:?} has no player token: {error:#}"),
                }
            }
        }
        tokens
    }
}
