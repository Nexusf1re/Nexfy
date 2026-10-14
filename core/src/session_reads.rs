//! Data read over the librespot session instead of the Web API.
//!
//! Since 2026 Spotify refuses development-mode apps the items of playlists
//! the user does not own. The playback session speaks the same protocol as
//! Spotify's own clients and can still read them.

use anyhow::{Result, anyhow};
use futures_util::{StreamExt, stream};
use librespot_core::SpotifyUri;
use librespot_core::session::Session;
use librespot_metadata::{Album as SessionAlbum, Artist as SessionArtist, Metadata, Playlist as SessionPlaylist, Track as SessionTrack};

use crate::webapi::{Album, Artist, ArtistDetail, Track};

/// Same cap as Web API lists, so huge playlists do not stall the screen.
const MAX_TRACKS: usize = 2000;
/// Track metadata requests in flight at once.
const CONCURRENCY: usize = 16;

pub struct SessionPlaylistData {
    pub name: String,
    pub description: String,
    pub tracks: Vec<Track>,
}

fn cover_url(file_id: &librespot_core::FileId) -> String {
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

/// Metadata for track URIs (the queue view), in order; failures are left out.
pub async fn tracks(session: &Session, uris: &[String]) -> Vec<Track> {
    let parsed: Vec<SpotifyUri> = uris.iter().filter_map(|uri| SpotifyUri::from_uri(uri).ok()).collect();
    stream::iter(parsed)
        .map(|uri| async move { SessionTrack::get(session, &uri).await })
        .buffered(CONCURRENCY)
        .filter_map(|result| async move { result.ok().map(|track| to_track(track, true)) })
        .collect()
        .await
}

pub async fn playlist(session: &Session, id: &str) -> Result<SessionPlaylistData> {
    let uri = SpotifyUri::from_uri(&format!("spotify:playlist:{id}")).map_err(|e| anyhow!("{e}"))?;
    let playlist = SessionPlaylist::get(session, &uri).await?;
    // Each track with when and by whom it was added to the playlist.
    let items: Vec<(SpotifyUri, i64, String)> = playlist
        .contents
        .items
        .iter()
        .filter(|item| matches!(item.id, SpotifyUri::Track { .. }))
        .take(MAX_TRACKS)
        .map(|item| (item.id.clone(), item.attributes.timestamp.as_timestamp_ms(), item.attributes.added_by.clone()))
        .collect();

    // Keep playlist order; a track that fails to load is left out.
    let tracks = stream::iter(items)
        .map(|(uri, added, by)| async move { (SessionTrack::get(session, &uri).await, added, by) })
        .buffered(CONCURRENCY)
        .filter_map(|(result, added, by)| async move {
            result.ok().map(|track| Track {
                added_at_ms: Some(added).filter(|ms| *ms > 0),
                added_by: Some(by).filter(|by| !by.is_empty()),
                ..to_track(track, false)
            })
        })
        .collect::<Vec<_>>()
        .await;

    Ok(SessionPlaylistData {
        name: playlist.attributes.name.clone(),
        description: playlist.attributes.description.clone(),
        tracks,
    })
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
            let album = result.ok()?;
            Some(Album {
                id: album.id.to_id(),
                uri: album.id.to_uri(),
                name: album.name.clone(),
                artists: album.artists.iter().map(|a| a.name.as_str()).collect::<Vec<_>>().join(", "),
                image_url: album.covers.first().map(|image| cover_url(&image.id)),
                year: Some(album.date.year().to_string()),
                track_count: album.discs.iter().map(|disc| disc.tracks.len() as u32).sum(),
            })
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
