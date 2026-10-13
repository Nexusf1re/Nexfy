//! The play queue: tracks in the order they were given, the order they play
//! in (shuffled or not), and where playback is.

use librespot_core::SpotifyUri;
use rand::seq::SliceRandom;

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum RepeatMode {
    Off,
    All,
    One,
}

#[derive(Debug)]
pub struct Queue {
    tracks: Vec<SpotifyUri>,
    /// Indices into `tracks`, in play order.
    order: Vec<usize>,
    /// Position in `order`.
    pos: usize,
    shuffle: bool,
    repeat: RepeatMode,
}

impl Default for Queue {
    fn default() -> Self {
        Self {
            tracks: Vec::new(),
            order: Vec::new(),
            pos: 0,
            shuffle: false,
            repeat: RepeatMode::Off,
        }
    }
}

impl Queue {
    /// Replace the queue; playback starts at `start` (an index into `tracks`).
    pub fn set(&mut self, tracks: Vec<SpotifyUri>, start: usize) {
        self.tracks = tracks;
        self.order = (0..self.tracks.len()).collect();
        self.pos = start.min(self.tracks.len().saturating_sub(1));
        if self.shuffle {
            self.reshuffle();
        }
    }

    pub fn current(&self) -> Option<SpotifyUri> {
        self.order.get(self.pos).map(|&i| self.tracks[i].clone())
    }

    /// Play-order position, for the UI and the system media session.
    pub fn position(&self) -> usize {
        self.pos
    }

    /// Tracks in play order.
    pub fn in_play_order(&self) -> Vec<SpotifyUri> {
        self.order.iter().map(|&i| self.tracks[i].clone()).collect()
    }

    pub fn shuffle(&self) -> bool {
        self.shuffle
    }

    pub fn repeat(&self) -> RepeatMode {
        self.repeat
    }

    pub fn set_repeat(&mut self, repeat: RepeatMode) {
        self.repeat = repeat;
    }

    /// Jump to a play-order position. Returns the track to load.
    pub fn skip_to(&mut self, pos: usize) -> Option<SpotifyUri> {
        if pos >= self.order.len() {
            return None;
        }
        self.pos = pos;
        self.current()
    }

    /// The track that follows when the current one ends on its own.
    pub fn peek_auto_next(&self) -> Option<SpotifyUri> {
        self.auto_next_pos()
            .map(|pos| self.tracks[self.order[pos]].clone())
    }

    /// Move to the track that follows the current one ending on its own;
    /// None when the queue is finished.
    pub fn advance_auto(&mut self) -> Option<SpotifyUri> {
        let pos = self.auto_next_pos()?;
        self.pos = pos;
        self.current()
    }

    fn auto_next_pos(&self) -> Option<usize> {
        if self.order.is_empty() {
            return None;
        }
        match self.repeat {
            RepeatMode::One => Some(self.pos),
            _ if self.pos + 1 < self.order.len() => Some(self.pos + 1),
            RepeatMode::All => Some(0),
            RepeatMode::Off => None,
        }
    }

    /// Turning shuffle on keeps the current track first and shuffles the
    /// rest; turning it off returns to the original order at the same track.
    pub fn set_shuffle(&mut self, shuffle: bool) {
        if self.shuffle == shuffle {
            return;
        }
        self.shuffle = shuffle;
        if shuffle {
            self.reshuffle();
        } else if let Some(&current) = self.order.get(self.pos) {
            self.order = (0..self.tracks.len()).collect();
            self.pos = current;
        }
    }

    fn reshuffle(&mut self) {
        let Some(&current) = self.order.get(self.pos) else {
            return;
        };
        let mut rest: Vec<usize> = (0..self.tracks.len()).filter(|&i| i != current).collect();
        rest.shuffle(&mut rand::rng());
        self.order = std::iter::once(current).chain(rest).collect();
        self.pos = 0;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use librespot_core::SpotifyId;

    fn uri(n: u128) -> SpotifyUri {
        SpotifyUri::Track { id: SpotifyId { id: n } }
    }

    fn queue(len: u128, start: usize) -> Queue {
        let mut q = Queue::default();
        q.set((0..len).map(uri).collect(), start);
        q
    }

    #[test]
    fn auto_advance_stops_at_end_without_repeat() {
        let mut q = queue(2, 0);
        assert_eq!(q.advance_auto(), Some(uri(1)));
        assert_eq!(q.advance_auto(), None);
        assert_eq!(q.position(), 1);
    }

    #[test]
    fn repeat_all_wraps_and_repeat_one_stays() {
        let mut q = queue(2, 1);
        q.set_repeat(RepeatMode::All);
        assert_eq!(q.advance_auto(), Some(uri(0)));
        q.set_repeat(RepeatMode::One);
        assert_eq!(q.advance_auto(), Some(uri(0)));
    }

    #[test]
    fn shuffle_keeps_current_track_and_all_tracks() {
        let mut q = queue(20, 7);
        q.set_shuffle(true);
        assert_eq!(q.position(), 0);
        assert_eq!(q.current(), Some(uri(7)));
        let mut order = q.in_play_order();
        order.sort_by_key(|u| u.to_uri());
        let mut all: Vec<_> = (0..20).map(uri).collect();
        all.sort_by_key(|u| u.to_uri());
        assert_eq!(order, all);

        q.skip_to(5);
        let playing = q.current();
        q.set_shuffle(false);
        assert_eq!(q.current(), playing);
    }
}
