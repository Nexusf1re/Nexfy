//! Data read over the librespot session instead of the Web API.
//!
//! Since 2026 Spotify refuses development-mode apps the items of playlists
//! the user does not own. The playback session speaks the same protocol as
//! Spotify's own clients and can still read them.

use anyhow::{Result, anyhow};
use futures_util::{StreamExt, stream};
use librespot_core::SpotifyUri;
use librespot_core::session::Session;
use librespot_metadata::{Metadata, Playlist as SessionPlaylist, Track as SessionTrack};

use crate::webapi::Track;

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

fn to_track(track: SessionTrack) -> Track {
    let album = &track.album;
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
        // Spotify lists the default (~300 px) cover first.
        image_url: album.covers.first().map(|image| cover_url(&image.id)),
        duration_ms: track.duration.max(0) as u32,
        explicit: track.is_explicit,
        playable: true,
        added_at_ms: None,
    }
}

pub async fn playlist(session: &Session, id: &str) -> Result<SessionPlaylistData> {
    let uri = SpotifyUri::from_uri(&format!("spotify:playlist:{id}")).map_err(|e| anyhow!("{e}"))?;
    let playlist = SessionPlaylist::get(session, &uri).await?;
    // Each track with when it was added to the playlist.
    let items: Vec<(SpotifyUri, i64)> = playlist
        .contents
        .items
        .iter()
        .filter(|item| matches!(item.id, SpotifyUri::Track { .. }))
        .take(MAX_TRACKS)
        .map(|item| (item.id.clone(), item.attributes.timestamp.as_timestamp_ms()))
        .collect();

    // Keep playlist order; a track that fails to load is left out.
    let tracks = stream::iter(items)
        .map(|(uri, added)| async move { (SessionTrack::get(session, &uri).await, added) })
        .buffered(CONCURRENCY)
        .filter_map(|(result, added)| async move {
            result.ok().map(|track| Track {
                added_at_ms: Some(added).filter(|ms| *ms > 0),
                ..to_track(track)
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
