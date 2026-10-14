//! Data read over the librespot session instead of the Web API.
//!
//! Since 2026 Spotify refuses development-mode apps the items of playlists
//! the user does not own. The playback session speaks the same protocol as
//! Spotify's own clients and can still read them.

use anyhow::{Result, anyhow};
use futures_util::{StreamExt, stream};
use librespot_core::SpotifyUri;
use librespot_core::session::Session;
use librespot_metadata::{
    Album as SessionAlbum, Artist as SessionArtist, Episode as SessionEpisode, Metadata, Playlist as SessionPlaylist,
    Track as SessionTrack,
};

use crate::webapi::{Album, Artist, ArtistDetail, Track};

/// Same cap as Web API lists, so huge playlists do not stall the screen.
const MAX_TRACKS: usize = 2000;
/// Track metadata requests in flight at once.
const CONCURRENCY: usize = 16;

pub(crate) fn cover_url(file_id: &librespot_core::FileId) -> String {
    format!("https://i.scdn.co/image/{}", file_id.to_base16())
}

/// `large`: the biggest cover, the one the player shows (what TrackChanged
/// reports), instead of the default ~300 px one lists use.
fn to_track(track: SessionTrack, large: bool) -> Track {
    let album = &track.album;
    let cover = if large {
        album.covers.iter().max_by_key(|image| image.width)
    } else {
        // Spotify lists the default (~300 px) cover first.
        album.covers.first()
    };
    Track {
        uri: track.id.to_uri(),
        title: track.name.clone(),
        artists: track
            .artists
            .iter()
            .map(|artist| artist.name.as_str())
            .collect::<Vec<_>>()
            .join(", "),
        artist_ids: track.artists.iter().map(|artist| artist.id.to_id()).collect(),
        album: album.name.clone(),
        album_id: Some(album.id.to_id()),
        image_url: cover.map(|image| cover_url(&image.id)),
        duration_ms: track.duration.max(0) as u32,
        explicit: track.is_explicit,
        playable: true,
        added_at_ms: None,
        added_by: None,
    }
}

/// A podcast episode as a queue row: the show stands where the artists do.
fn episode_track(episode: SessionEpisode) -> Track {
    Track {
        uri: episode.id.to_uri(),
        title: episode.name,
        artists: episode.show_name,
        artist_ids: Vec::new(),
        album: String::new(),
        album_id: None,
        image_url: episode.covers.iter().max_by_key(|image| image.width).map(|image| cover_url(&image.id)),
        duration_ms: episode.duration.max(0) as u32,
        explicit: episode.is_explicit,
        playable: !episode.audio.is_empty(),
        added_at_ms: None,
        added_by: None,
    }
}

/// Metadata for track and episode URIs (the queue view), in order;
/// failures are left out.
pub async fn tracks(session: &Session, uris: &[String]) -> Vec<Track> {
    let parsed: Vec<SpotifyUri> = uris.iter().filter_map(|uri| SpotifyUri::from_uri(uri).ok()).collect();
    stream::iter(parsed)
        .map(|uri| async move {
            match uri {
                SpotifyUri::Episode { .. } => SessionEpisode::get(session, &uri).await.map(episode_track),
                _ => SessionTrack::get(session, &uri).await.map(|track| to_track(track, true)),
            }
        })
        .buffered(CONCURRENCY)
        .filter_map(|result| async move { result.ok() })
        .collect()
        .await
}

/// Most releases shown on an artist page read through the session.
const MAX_RELEASES: usize = 20;

/// An artist page from the session: Spotify refuses development-mode apps
/// an artist's top tracks. Followers are not in the metadata (0).
pub async fn artist(session: &Session, id: &str) -> Result<ArtistDetail> {
    let uri = SpotifyUri::from_uri(&format!("spotify:artist:{id}")).map_err(|e| anyhow!("{e}"))?;
    let artist = SessionArtist::get(session, &uri).await?;
    let country = session.user_data().country;
    let top: Vec<SpotifyUri> = artist.top_tracks.for_country(&country).0;
    let top_tracks = tracks(session, &top.iter().map(|uri| uri.to_uri()).collect::<Vec<_>>()).await;
    let releases: Vec<SpotifyUri> = artist
        .albums_current()
        .chain(artist.singles_current())
        .take(MAX_RELEASES)
        .cloned()
        .collect();
    let albums = stream::iter(releases)
        .map(|uri| async move { SessionAlbum::get(session, &uri).await })
        .buffered(CONCURRENCY)
        .filter_map(|result| async move {
            result.ok().map(|album| session_album(&album))
        })
        .collect()
        .await;
    let portrait = artist.portraits.iter().max_by_key(|image| image.width).or_else(|| artist.portrait_group.first());
    Ok(ArtistDetail {
        artist: Artist {
            id: id.to_owned(),
            uri: format!("spotify:artist:{id}"),
            name: artist.name.clone(),
            image_url: portrait.map(|image| cover_url(&image.id)),
            followers: 0,
        },
        top_tracks,
        albums,
    })
}

/// A playlist contributor as track rows show them.
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct UserFace {
    pub id: String,
    pub name: String,
    pub image_url: Option<String>,
}

/// Name and photo of a Spotify user, from the profile service the
/// official clients use (the Web API refuses development-mode apps).
pub async fn user(session: &Session, id: &str) -> Result<UserFace> {
    let bytes = session.spclient().get_user_profile(id, Some(0), Some(0)).await?;
    let profile: serde_json::Value = serde_json::from_slice(&bytes)?;
    let text = |key: &str| profile.get(key).and_then(|v| v.as_str()).filter(|s| !s.is_empty()).map(str::to_owned);
    Ok(UserFace {
        id: id.to_owned(),
        name: text("name").unwrap_or_else(|| id.to_owned()),
        image_url: text("image_url"),
    })
}

/// Most artists looked at for new releases.
const RELEASE_ARTISTS: usize = 24;

/// The newest albums and singles of these artists from the last `days`,
/// newest first, at most `limit`. An artist that fails is left out.
pub async fn releases(session: &Session, artist_ids: &[String], days: i64, limit: usize) -> Vec<Album> {
    let cutoff = librespot_core::date::Date::now_utc().as_timestamp_ms() - days * 86_400_000;
    let artists: Vec<SpotifyUri> = artist_ids
        .iter()
        .take(RELEASE_ARTISTS)
        .filter_map(|id| SpotifyUri::from_uri(&format!("spotify:artist:{id}")).ok())
        .collect();
    let candidates: Vec<SpotifyUri> = stream::iter(artists)
        .map(|uri| async move { SessionArtist::get(session, &uri).await })
        .buffered(CONCURRENCY)
        .filter_map(|result| async move {
            let artist = result.ok()?;
            // The order of an artist's releases is not documented: both ends.
            let ends = |list: Vec<SpotifyUri>| {
                let n = list.len();
                list.into_iter().enumerate().filter(move |(i, _)| *i < 2 || *i + 2 >= n).map(|(_, uri)| uri)
            };
            let mut picked: Vec<SpotifyUri> = ends(artist.albums_current().cloned().collect()).collect();
            picked.extend(ends(artist.singles_current().cloned().collect()));
            Some(picked)
        })
        .collect::<Vec<_>>()
        .await
        .into_iter()
        .flatten()
        .collect();
    let mut found: Vec<(i64, Album)> = stream::iter(candidates)
        .map(|uri| async move { SessionAlbum::get(session, &uri).await })
        .buffered(CONCURRENCY)
        .filter_map(|result| async move {
            let album = result.ok()?;
            let released = album.date.as_timestamp_ms();
            (released >= cutoff).then(|| (released, session_album(&album)))
        })
        .collect()
        .await;
    found.sort_by(|a, b| b.0.cmp(&a.0));
    let mut seen = std::collections::HashSet::new();
    found
        .into_iter()
        .map(|(_, album)| album)
        // The same release under two ids (explicit and clean) shows once.
        .filter(|album| seen.insert((album.name.to_lowercase(), album.artists.clone())))
        .take(limit)
        .collect()
}

pub(crate) fn session_album(album: &SessionAlbum) -> Album {
    Album {
        id: album.id.to_id(),
        uri: album.id.to_uri(),
        name: album.name.clone(),
        artists: album.artists.iter().map(|a| a.name.as_str()).collect::<Vec<_>>().join(", "),
        image_url: album.covers.first().map(|image| cover_url(&image.id)),
        year: Some(album.date.year().to_string()),
        track_count: album.discs.iter().map(|disc| disc.tracks.len() as u32).sum(),
    }
}

/// A song's or artist's radio, as something to play.
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct Radio {
    /// The playlist Spotify generates for the seed ("Rádio de ..."), played
    /// as a context so Spotify keeps its queue and autoplay going after it.
    pub context_uri: Option<String>,
    /// The station's songs, when only those could be read; the seed song first.
    pub track_uris: Vec<String>,
}

/// Songs read from a station when there is no radio playlist.
const RADIO_TRACKS: usize = 50;

/// The radio for a `spotify:track:` or `spotify:artist:` seed. Spotify's
/// own clients now play song and artist radio as a generated playlist;
/// the older radio-apollo station's song list is the fallback.
pub async fn radio(session: &Session, seed: &str) -> Result<Radio> {
    let uri = SpotifyUri::from_uri(seed).map_err(|e| anyhow!("{e}"))?;
    match session.spclient().get_radio_for_track(&uri).await {
        Ok(bytes) => {
            let json: serde_json::Value = serde_json::from_slice(&bytes)?;
            if let Some(playlist) = uris_in(&json, "spotify:playlist:").into_iter().next() {
                return Ok(Radio { context_uri: Some(playlist), track_uris: Vec::new() });
            }
            log::info!("radio {seed}: no playlist in the answer");
        }
        Err(error) => log::info!("radio playlist for {seed}: {error}"),
    }
    let bytes = session
        .spclient()
        .get_apollo_station("stations", seed, Some(RADIO_TRACKS), Vec::new(), false)
        .await?;
    let json: serde_json::Value = serde_json::from_slice(&bytes)?;
    // The station's own list when there is one, so seeds and links are not taken for songs.
    let mut tracks = uris_in(json.get("tracks").unwrap_or(&json), "spotify:track:");
    if seed.starts_with("spotify:track:") {
        tracks.retain(|t| t != seed);
        tracks.insert(0, seed.to_owned());
    }
    if tracks.len() < 2 {
        return Err(anyhow!("rádio indisponível"));
    }
    Ok(Radio { context_uri: None, track_uris: tracks })
}

/// Every distinct string under `value` starting with `prefix`; arrays keep their order.
fn uris_in(value: &serde_json::Value, prefix: &str) -> Vec<String> {
    fn walk(value: &serde_json::Value, prefix: &str, found: &mut Vec<String>) {
        match value {
            serde_json::Value::String(s) if s.starts_with(prefix) && !found.contains(s) => found.push(s.clone()),
            serde_json::Value::Array(items) => items.iter().for_each(|v| walk(v, prefix, found)),
            serde_json::Value::Object(map) => map.values().for_each(|v| walk(v, prefix, found)),
            _ => {}
        }
    }
    let mut found = Vec::new();
    walk(value, prefix, &mut found);
    found
}

/// The user's playlists from the session's rootlist, in library order:
/// what the library shows when the Web API cannot answer.
pub async fn rootlist(session: &Session) -> Result<Vec<crate::webapi::Playlist>> {
    use protobuf::Message;
    let bytes = session.spclient().get_rootlist(0, Some(1000)).await?;
    let list = librespot_protocol::playlist4_external::SelectedListContent::parse_from_bytes(&bytes)?;
    let contents = list.contents.into_option().unwrap_or_default();
    let me = session.username();
    Ok(contents
        .items
        .iter()
        .zip(contents.meta_items.iter())
        .filter_map(|(item, meta)| {
            let id = item.uri().strip_prefix("spotify:playlist:")?.to_owned();
            let attributes = meta.attributes.get_or_default();
            let image = attributes
                .picture_size
                .iter()
                .find(|size| size.target_name() == "default")
                .or_else(|| attributes.picture_size.first())
                .map(|size| size.url().to_owned())
                .filter(|url| !url.is_empty())
                .or_else(|| {
                    (!attributes.picture().is_empty())
                        .then(|| format!("https://i.scdn.co/image/{}", hex(attributes.picture())))
                });
            let owner = meta.owner_username().to_owned();
            Some(crate::webapi::Playlist {
                uri: format!("spotify:playlist:{id}"),
                id,
                name: attributes.name().to_owned(),
                description: attributes.description().to_owned(),
                // The session gives the username only; the user's own name reads "você".
                owner: if owner == me { "você".to_owned() } else { owner.clone() },
                owner_id: owner,
                image_url: image,
                track_count: meta.length().max(0) as u32,
                followers: 0,
            })
        })
        .collect())
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

/// A playlist's name, description, cover and size, without its songs: one
/// request, for a page header when the Web API cannot answer.
pub async fn playlist_info(session: &Session, id: &str) -> Result<crate::webapi::Playlist> {
    let uri = SpotifyUri::from_uri(&format!("spotify:playlist:{id}")).map_err(|e| anyhow!("{e}"))?;
    let playlist = SessionPlaylist::get(session, &uri).await?;
    Ok(crate::webapi::Playlist {
        id: id.to_owned(),
        uri: format!("spotify:playlist:{id}"),
        name: playlist.attributes.name.clone(),
        description: playlist.attributes.description.clone(),
        // The parsed playlist drops its owner; the header then shows none.
        owner: String::new(),
        owner_id: String::new(),
        image_url: playlist
            .attributes
            .picture_sizes
            .iter()
            .find(|size| size.target_name == "default")
            .or_else(|| playlist.attributes.picture_sizes.iter().next())
            .map(|size| size.url.clone())
            .filter(|url| !url.is_empty()),
        track_count: playlist.contents.items.len() as u32,
        followers: 0,
    })
}

/// An album page over the session, for when the Web API cannot answer.
pub async fn album(session: &Session, id: &str) -> Result<crate::webapi::AlbumDetail> {
    let uri = SpotifyUri::from_uri(&format!("spotify:album:{id}")).map_err(|e| anyhow!("{e}"))?;
    let album = SessionAlbum::get(session, &uri).await?;
    let uris: Vec<String> = album.discs.iter().flat_map(|disc| disc.tracks.iter().map(|t| t.to_uri())).collect();
    let tracks = tracks(session, &uris).await;
    Ok(crate::webapi::AlbumDetail { album: session_album(&album), tracks })
}

/// One row of a playlist as the playlist service lists it.
pub struct PlaylistEntry {
    pub uri: String,
    pub added_at_ms: Option<i64>,
    pub added_by: Option<String>,
}

/// A playlist's header and rows from the playlist service, without the
/// songs' metadata (the caller resolves it from its track cache).
pub struct PlaylistEntries {
    pub name: String,
    pub description: String,
    pub owner_username: String,
    pub image_url: Option<String>,
    pub entries: Vec<PlaylistEntry>,
}

/// Rows asked for per request; the service pages long playlists.
const PLAYLIST_PAGE: usize = 500;

pub async fn playlist_entries(session: &Session, id: &str) -> Result<PlaylistEntries> {
    use protobuf::Message;
    let spotify_id = librespot_core::SpotifyId::from_base62(id).map_err(|e| anyhow!("{e}"))?;
    let mut header: Option<PlaylistEntries> = None;
    let mut entries = Vec::new();
    loop {
        let bytes = session.spclient().get_playlist_range(&spotify_id, entries.len(), PLAYLIST_PAGE).await?;
        let list = librespot_protocol::playlist4_external::SelectedListContent::parse_from_bytes(&bytes)?;
        let total = list.length().max(0) as usize;
        if header.is_none() {
            let attributes = list.attributes.get_or_default();
            header = Some(PlaylistEntries {
                name: attributes.name().to_owned(),
                description: attributes.description().to_owned(),
                owner_username: list.owner_username().to_owned(),
                image_url: list_image(attributes),
                entries: Vec::new(),
            });
        }
        let page = list.contents.get_or_default();
        let before = entries.len();
        for item in &page.items {
            let attributes = item.attributes.get_or_default();
            entries.push(PlaylistEntry {
                uri: item.uri().to_owned(),
                added_at_ms: Some(attributes.timestamp()).filter(|ms| *ms > 0),
                added_by: Some(attributes.added_by().to_owned()).filter(|by| !by.is_empty()),
            });
        }
        if entries.len() >= total || entries.len() == before || entries.len() >= MAX_TRACKS {
            break;
        }
    }
    let mut playlist = header.ok_or_else(|| anyhow!("playlist vazia"))?;
    playlist.entries = entries;
    Ok(playlist)
}

/// A list's cover: its picture sizes (custom or mosaic), else the picture id.
fn list_image(attributes: &librespot_protocol::playlist4_external::ListAttributes) -> Option<String> {
    attributes
        .picture_size
        .iter()
        .find(|size| size.target_name() == "default")
        .or_else(|| attributes.picture_size.first())
        .map(|size| size.url().to_owned())
        .filter(|url| !url.is_empty())
        .or_else(|| {
            (!attributes.picture().is_empty()).then(|| format!("https://i.scdn.co/image/{}", hex(attributes.picture())))
        })
}

/// Episodes a show page lists.
const SHOW_EPISODES: usize = 50;

/// A podcast and its latest episodes over the session. Resume points are
/// not in the metadata (the caller adds what the Web API last said).
pub async fn show(session: &Session, id: &str) -> Result<crate::webapi::ShowDetail> {
    let uri = SpotifyUri::from_uri(&format!("spotify:show:{id}")).map_err(|e| anyhow!("{e}"))?;
    let show = librespot_metadata::Show::get(session, &uri).await?;
    let image = show.covers.iter().max_by_key(|image| image.width).map(|image| cover_url(&image.id));
    let uris: Vec<SpotifyUri> = show.episodes.iter().take(SHOW_EPISODES).cloned().collect();
    let episodes: Vec<crate::webapi::Episode> = stream::iter(uris)
        .map(|uri| async move { librespot_metadata::Episode::get(session, &uri).await })
        .buffered(CONCURRENCY)
        .filter_map(|result| {
            let image = image.clone();
            async move {
                let episode = result.ok()?;
                let description: String = episode.description.split_whitespace().collect::<Vec<_>>().join(" ").chars().take(300).collect();
                Some(crate::webapi::Episode {
                    uri: episode.id.to_uri(),
                    id: episode.id.to_id(),
                    title: episode.name.clone(),
                    description,
                    image_url: episode.covers.iter().max_by_key(|image| image.width).map(|image| cover_url(&image.id)).or(image),
                    duration_ms: episode.duration.max(0) as u32,
                    release_date: episode.publish_time.as_utc().date().to_string(),
                    resume_position_ms: 0,
                    fully_played: false,
                    playable: !episode.audio.is_empty(),
                })
            }
        })
        .collect()
        .await;
    Ok(crate::webapi::ShowDetail {
        show: crate::webapi::Show {
            id: id.to_owned(),
            uri: format!("spotify:show:{id}"),
            name: show.name.clone(),
            publisher: show.publisher.clone(),
            image_url: image,
            episode_count: show.episodes.len() as u32,
        },
        episodes,
    })
}
