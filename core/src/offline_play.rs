//! Songs on the phone played with no connection at all. Online, Spirc keeps
//! the queue and the session streams; offline there is no session to sign
//! in, so a player of its own runs on one that never connects, reading each
//! song's file, key and metadata from the cache, and the queue is kept here.

use std::sync::{Arc, Mutex, Weak};

use librespot_core::SpotifyUri;
use librespot_playback::player::{Player, PlayerEvent, PlayerEventChannel};
use rand::seq::SliceRandom;

use crate::{Inner, QueueEntry, QueueState, RepeatMode, QUEUE_AHEAD, QUEUE_BEHIND};

pub(crate) struct OfflinePlay {
    pub player: Arc<Player>,
    order: Mutex<Order>,
}

struct Order {
    /// The list as given, for turning shuffle off again.
    original: Vec<String>,
    /// In play order: shuffled after the current song while shuffle is on.
    uris: Vec<String>,
    at: usize,
    shuffle: bool,
    repeat: RepeatMode,
}

impl OfflinePlay {
    /// Play `uris` from `start`, every one already checked to be on the phone.
    pub fn start(
        inner: &Arc<Inner>,
        player: Arc<Player>,
        uris: Vec<String>,
        start: usize,
        shuffle: bool,
        repeat: RepeatMode,
    ) -> Arc<Self> {
        let mut order = Order { original: uris.clone(), uris, at: start, shuffle: false, repeat };
        order.set_shuffle(shuffle);
        let events = player.get_player_event_channel();
        let this = Arc::new(Self { player, order: Mutex::new(order) });
        tokio::spawn(drive(Arc::downgrade(inner), Arc::downgrade(&this), events));
        this.load(true);
        this
    }

    fn load(&self, play: bool) {
        let current = {
            let order = self.order.lock().unwrap();
            order.uris.get(order.at).cloned()
        };
        if let Some(uri) = current.and_then(|uri| SpotifyUri::from_uri(&uri).ok()) {
            self.player.load(uri, play, 0);
        }
    }

    /// One song on, or back; false past either end of a list not repeating.
    pub fn skip(&self, forward: bool, play: bool) -> bool {
        let moved = {
            let mut order = self.order.lock().unwrap();
            let last = order.uris.len().saturating_sub(1);
            match (forward, order.repeat) {
                (true, _) if order.at < last => order.at += 1,
                (true, RepeatMode::All) => order.at = 0,
                (false, _) if order.at > 0 => order.at -= 1,
                (false, RepeatMode::All) => order.at = last,
                _ => return false,
            }
            true
        };
        if moved {
            self.load(play);
        }
        moved
    }

    fn upcoming(&self) -> Option<SpotifyUri> {
        let order = self.order.lock().unwrap();
        let next = match order.repeat {
            RepeatMode::One => order.at,
            _ if order.at + 1 < order.uris.len() => order.at + 1,
            RepeatMode::All => 0,
            RepeatMode::Off => return None,
        };
        SpotifyUri::from_uri(order.uris.get(next)?).ok()
    }

    pub fn set_shuffle(&self, shuffle: bool) {
        self.order.lock().unwrap().set_shuffle(shuffle);
    }

    pub fn set_repeat(&self, repeat: RepeatMode) {
        self.order.lock().unwrap().repeat = repeat;
    }

    /// The queue as the screens show it: no context, the songs around.
    pub fn show(&self, queue: &mut QueueState) {
        let order = self.order.lock().unwrap();
        let entry = |uri: &String| QueueEntry { uri: uri.clone(), queued: false, added_by: None };
        queue.context_uri = None;
        queue.previous = order.uris[order.at.saturating_sub(QUEUE_BEHIND)..order.at].iter().map(entry).collect();
        queue.current = order.uris.get(order.at).map(entry);
        queue.next = order.uris.iter().skip(order.at + 1).take(QUEUE_AHEAD).map(entry).collect();
        queue.shuffle = order.shuffle;
        queue.repeat = order.repeat;
    }
}

impl Order {
    /// On, the songs after the current one go in a random order; off, the
    /// list's own order comes back from where the current song sits in it.
    fn set_shuffle(&mut self, shuffle: bool) {
        let current = self.uris.get(self.at).cloned();
        if shuffle {
            let mut rest: Vec<String> = self.original.clone();
            if let Some(position) = current.as_ref().and_then(|uri| rest.iter().position(|u| u == uri)) {
                rest.remove(position);
            }
            rest.shuffle(&mut rand::rng());
            self.uris = current.into_iter().chain(rest).collect();
            self.at = 0;
        } else {
            self.uris = self.original.clone();
            self.at = current.and_then(|uri| self.uris.iter().position(|u| *u == uri)).unwrap_or(0);
        }
        self.shuffle = shuffle;
    }
}

/// The player's events, for the queue: the next song at the end of one (or
/// in place of one that will not load), and the one after preloaded.
async fn drive(inner: Weak<Inner>, play: Weak<OfflinePlay>, mut events: PlayerEventChannel) {
    while let Some(event) = events.recv().await {
        let (Some(inner), Some(play)) = (inner.upgrade(), play.upgrade()) else { break };
        match event {
            PlayerEvent::EndOfTrack { .. } => {
                if play.order.lock().unwrap().repeat == RepeatMode::One {
                    play.load(true);
                } else if !play.skip(true, true) {
                    play.player.stop();
                }
            }
            PlayerEvent::Unavailable { .. } => {
                if !play.skip(true, true) {
                    play.player.stop();
                }
            }
            PlayerEvent::TimeToPreloadNextTrack { .. } => {
                if let Some(next) = play.upcoming() {
                    play.player.preload(next);
                }
            }
            PlayerEvent::TrackChanged { .. } => inner.update_queue(|queue| play.show(queue)),
            _ => {}
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn order(n: usize, at: usize) -> Order {
        let uris: Vec<String> = (0..n).map(|i| format!("spotify:track:{i}")).collect();
        Order { original: uris.clone(), uris, at, shuffle: false, repeat: RepeatMode::Off }
    }

    #[test]
    fn shuffle_keeps_the_current_song_first_and_off_restores_the_list() {
        let mut order = order(20, 7);
        order.set_shuffle(true);
        assert_eq!(order.at, 0);
        assert_eq!(order.uris[0], "spotify:track:7");
        assert_eq!(order.uris.len(), 20);
        order.at = 3;
        let playing = order.uris[3].clone();
        order.set_shuffle(false);
        assert_eq!(order.uris, order.original);
        assert_eq!(order.uris[order.at], playing);
    }
}
