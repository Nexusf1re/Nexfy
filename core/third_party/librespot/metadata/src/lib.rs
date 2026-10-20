#[macro_use]
extern crate log;

#[macro_use]
extern crate async_trait;

use protobuf::Message;

use librespot_core::{Error, Session, SpotifyUri};

pub mod album;
pub mod artist;
pub mod audio;
pub mod availability;
pub mod content_rating;
pub mod copyright;
pub mod episode;
pub mod error;
pub mod external_id;
pub mod image;
pub mod lyrics;
pub mod playlist;
mod request;
pub mod restriction;
pub mod sale_period;
pub mod show;
pub mod track;
mod util;
pub mod video;

pub use error::MetadataError;
use request::RequestResult;

pub use album::Album;
pub use artist::Artist;
pub use episode::Episode;
pub use lyrics::Lyrics;
pub use playlist::Playlist;
pub use show::Show;
pub use track::Track;

#[async_trait]
pub trait Metadata: Send + Sized + 'static {
    type Message: protobuf::Message + std::fmt::Debug;

    // Request a protobuf
    async fn request(session: &Session, id: &SpotifyUri) -> RequestResult;

    // Request a metadata struct. A song's or episode's is kept in the cache:
    // it answers when the session is down or the request fails, so a song in
    // the audio cache plays offline.
    async fn get(session: &Session, id: &SpotifyUri) -> Result<Self, Error> {
        let cache = session
            .cache()
            .filter(|_| matches!(id, SpotifyUri::Track { .. } | SpotifyUri::Episode { .. }));
        let uri = id.to_uri();
        let kept = || cache.and_then(|cache| cache.metadata(&uri));
        let response = match kept().filter(|_| session.is_invalid()) {
            Some(bytes) => bytes::Bytes::from(bytes),
            None => match Self::request(session, id).await {
                Ok(bytes) => {
                    if let Some(cache) = cache {
                        cache.save_metadata(&uri, &bytes);
                    }
                    bytes
                }
                Err(error) => match kept() {
                    Some(bytes) => {
                        debug!("Metadata for {uri} from the cache: {error}");
                        bytes::Bytes::from(bytes)
                    }
                    None => return Err(error),
                },
            },
        };
        let msg = Self::Message::parse_from_bytes(&response)?;
        trace!("Received metadata: {msg:#?}");
        Self::parse(&msg, id)
    }

    fn parse(msg: &Self::Message, _: &SpotifyUri) -> Result<Self, Error>;
}
