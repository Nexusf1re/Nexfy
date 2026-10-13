//! Lyrics for a track: Spotify's own transcription first (over the playback
//! session), then LRCLIB (lrclib.net, free and keyless). Answers, "no
//! lyrics" included, are cached on disk for a month.
//!
//! LRCLIB matching starts with an exact lookup, then ranks search results;
//! the track length is the strongest signal between versions that share a
//! title.

use std::path::Path;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use anyhow::{Context, Result};
use librespot_core::SpotifyId;
use librespot_core::session::Session;
use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};

const LRCLIB: &str = "https://lrclib.net/api";
const CACHE_LIFETIME: Duration = Duration::from_secs(30 * 24 * 60 * 60);
/// A candidate this far from the track's length is another recording.
const MAX_DRIFT_SECS: f64 = 30.0;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
pub struct LyricsLine {
    /// When the line starts; None in lyrics without timing.
    pub at_ms: Option<u32>,
    pub text: String,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
pub struct Lyrics {
    pub lines: Vec<LyricsLine>,
    /// Every line has a time, so the view can follow the song.
    pub synced: bool,
    pub instrumental: bool,
    /// "Spotify" or "LRCLIB".
    pub source: String,
}

pub struct Query<'a> {
    pub track_uri: &'a str,
    pub title: &'a str,
    pub artist: &'a str,
    pub album: &'a str,
    pub duration_ms: u32,
}

/// Cached answer, else Spotify, else LRCLIB. Ok(None) means nobody has them.
pub async fn fetch(session: Option<&Session>, cache_dir: &Path, query: &Query<'_>) -> Result<Option<Lyrics>> {
    let id = query.track_uri.rsplit(':').next().unwrap_or_default();
    let cache = cache_dir.join("lyrics").join(format!("{id}.json"));
    if let Some(cached) = read_cache(&cache) {
        return Ok(cached);
    }

    let mut found = None;
    if let Some(session) = session {
        match from_spotify(session, id).await {
            Ok(lyrics) => found = lyrics,
            Err(error) => log::debug!("Spotify lyrics for {id}: {error:#}"),
        }
    }
    if found.is_none() {
        found = from_lrclib(query).await?;
    }
    write_cache(&cache, &found);
    Ok(found)
}

// ---------------------------------------------------------------- Spotify --

async fn from_spotify(session: &Session, id: &str) -> Result<Option<Lyrics>> {
    let track = SpotifyId::from_base62(id).context("bad track id")?;
    let bytes = session.spclient().get_lyrics(&track).await?;
    let json: serde_json::Value = serde_json::from_slice(&bytes)?;
    Ok(parse_spotify(&json))
}

/// Parses Spotify's `color-lyrics` response.
fn parse_spotify(json: &serde_json::Value) -> Option<Lyrics> {
    let lyrics = json.get("lyrics")?;
    let synced = lyrics.get("syncType").and_then(|v| v.as_str()) == Some("LINE_SYNCED");
    let lines: Vec<LyricsLine> = lyrics
        .get("lines")?
        .as_array()?
        .iter()
        .filter_map(|line| {
            let text = line.get("words")?.as_str()?.trim();
            if text.is_empty() || text == "\u{266a}" {
                return None;
            }
            let at_ms = line
                .get("startTimeMs")
                .and_then(|v| v.as_str().and_then(|s| s.parse().ok()).or_else(|| v.as_u64().map(|n| n as u32)))
                .filter(|_| synced);
            Some(LyricsLine { at_ms, text: text.to_owned() })
        })
        .collect();
    if lines.is_empty() {
        return None;
    }
    let synced = synced && lines.iter().all(|line| line.at_ms.is_some());
    Some(Lyrics { lines, synced, instrumental: false, source: "Spotify".into() })
}

// ----------------------------------------------------------------- LRCLIB --

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
struct Record {
    track_name: String,
    artist_name: String,
    duration: Option<f64>,
    instrumental: bool,
    synced_lyrics: Option<String>,
    plain_lyrics: Option<String>,
}

impl Record {
    fn synced(&self) -> Option<&str> {
        self.synced_lyrics.as_deref().filter(|t| !t.trim().is_empty())
    }

    fn plain(&self) -> Option<&str> {
        self.plain_lyrics.as_deref().filter(|t| !t.trim().is_empty())
    }

    fn lyrics(&self) -> Option<Lyrics> {
        let source = "LRCLIB".to_owned();
        if self.instrumental {
            return Some(Lyrics { instrumental: true, source, ..Lyrics::default() });
        }
        if let Some(lines) = self.synced().map(parse_lrc).filter(|l| !l.is_empty()) {
            return Some(Lyrics { lines, synced: true, instrumental: false, source });
        }
        let lines = self
            .plain()?
            .lines()
            .map(|line| LyricsLine { at_ms: None, text: line.trim_end().to_owned() })
            .collect();
        Some(Lyrics { lines, synced: false, instrumental: false, source })
    }
}

async fn from_lrclib(query: &Query<'_>) -> Result<Option<Lyrics>> {
    let http = reqwest::Client::builder()
        .user_agent(concat!("Nexfy/", env!("CARGO_PKG_VERSION"), " (personal Android player)"))
        .build()?;
    let title = clean_title(query.title);
    let artist = first_artist(query.artist);
    if title.is_empty() || artist.is_empty() {
        return Ok(None);
    }
    let duration = (query.duration_ms / 1000).to_string();
    let mut exact = vec![("artist_name", artist.as_str()), ("track_name", title.as_str())];
    if !query.album.trim().is_empty() {
        exact.push(("album_name", query.album.trim()));
    }
    if query.duration_ms > 0 {
        exact.push(("duration", duration.as_str()));
    }
    if let Some(lyrics) = get::<Record>(&http, "/get", &exact).await?.and_then(|r| r.lyrics()) {
        return Ok(Some(lyrics));
    }
    let candidates = get::<Vec<Record>>(&http, "/search", &[("artist_name", &artist), ("track_name", &title)])
        .await?
        .unwrap_or_default();
    Ok(pick(candidates, &title, &artist, query.duration_ms).and_then(|r| r.lyrics()))
}

/// One LRCLIB request; 404 and 400 mean "nothing here", not a failure.
async fn get<T: DeserializeOwned>(http: &reqwest::Client, path: &str, params: &[(&str, &str)]) -> Result<Option<T>> {
    let response = http.get(format!("{LRCLIB}{path}")).query(params).send().await.context("LRCLIB inacessível")?;
    let status = response.status().as_u16();
    if status == 404 || status == 400 {
        return Ok(None);
    }
    if !(200..300).contains(&status) {
        anyhow::bail!("LRCLIB respondeu {status}");
    }
    Ok(Some(response.json().await?))
}

/// The best search result: same title, artist preferred, closest length,
/// synced over plain. None when nothing fits.
fn pick(records: Vec<Record>, title: &str, artist: &str, duration_ms: u32) -> Option<Record> {
    records
        .into_iter()
        .filter_map(|record| {
            if normalize(&record.track_name) != normalize(title) {
                return None;
            }
            let mut score = 0i64;
            if normalize(&record.artist_name) == normalize(artist) {
                score += 1000;
            }
            if duration_ms > 0 {
                if let Some(duration) = record.duration.filter(|d| *d > 0.0) {
                    let drift = (duration - f64::from(duration_ms) / 1000.0).abs();
                    if drift > MAX_DRIFT_SECS {
                        return None;
                    }
                    score += ((MAX_DRIFT_SECS - drift) * 10.0) as i64;
                }
            }
            score += if record.synced().is_some() { 200 } else if record.plain().is_some() { 50 } else { 0 };
            Some((score, record))
        })
        .max_by_key(|(score, _)| *score)
        .map(|(_, record)| record)
}

// ------------------------------------------------------------------ names --

/// Drops what lyrics databases leave out: "(feat. …)", "- 2004 Remaster",
/// "(Live)", and similar.
fn clean_title(title: &str) -> String {
    let mut out = title.to_owned();
    // " - Remastered 2011", " - Radio Edit", " - Live at …"
    if let Some((head, tail)) = out.split_once(" - ") {
        let tail = tail.to_lowercase();
        if ["remaster", "edit", "version", "live", "mono", "stereo", "mix", "recorded"]
            .iter()
            .any(|word| tail.contains(word))
        {
            out = head.to_owned();
        }
    }
    // "(feat. X)", "[Remastered]"
    for (open, close) in [('(', ')'), ('[', ']')] {
        while let (Some(start), Some(end)) = (out.find(open), out.find(close)) {
            if end <= start {
                break;
            }
            let inside = out[start + 1..end].to_lowercase();
            if ["feat", "with", "remaster", "live", "version", "edit", "mix", "deluxe", "bonus", "explicit"]
                .iter()
                .any(|word| inside.contains(word))
            {
                out.replace_range(start..=end, "");
            } else {
                break;
            }
        }
    }
    out.split_whitespace().collect::<Vec<_>>().join(" ")
}

/// The first artist of "A, B" or "A feat. B".
fn first_artist(artists: &str) -> String {
    let first = artists.split(", ").next().unwrap_or(artists);
    let lower = first.to_lowercase();
    let cut = [" feat", " ft.", " featuring", " & "]
        .iter()
        .filter_map(|marker| lower.find(marker))
        .min()
        .unwrap_or(first.len());
    first[..cut].trim().to_owned()
}

/// Case, accents and punctuation folded away for comparison.
fn normalize(text: &str) -> String {
    text.to_lowercase()
        .chars()
        .map(|c| match c {
            'á' | 'à' | 'â' | 'ã' | 'ä' => 'a',
            'é' | 'è' | 'ê' | 'ë' => 'e',
            'í' | 'ì' | 'î' | 'ï' => 'i',
            'ó' | 'ò' | 'ô' | 'õ' | 'ö' => 'o',
            'ú' | 'ù' | 'û' | 'ü' => 'u',
            'ç' => 'c',
            'ñ' => 'n',
            c => c,
        })
        .filter(|c| c.is_alphanumeric() || c.is_whitespace())
        .collect::<String>()
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
}

/// LRC text to timed lines, sorted; lines may carry several stamps.
fn parse_lrc(text: &str) -> Vec<LyricsLine> {
    let mut lines = Vec::new();
    for raw in text.lines() {
        let mut rest = raw.trim();
        let mut stamps = Vec::new();
        while let Some((ms, len)) = leading_stamp(rest) {
            stamps.push(ms);
            rest = rest[len..].trim_start();
        }
        for ms in stamps {
            lines.push(LyricsLine { at_ms: Some(ms), text: rest.to_owned() });
        }
    }
    lines.sort_by_key(|line| line.at_ms);
    lines.retain(|line| !line.text.is_empty());
    lines
}

/// "[mm:ss.xx]" at the start of `text`: its time in ms and its length.
fn leading_stamp(text: &str) -> Option<(u32, usize)> {
    let end = text.strip_prefix('[')?.find(']')? + 1;
    let (minutes, seconds) = text[1..end].split_once(':')?;
    let minutes: u32 = minutes.parse().ok()?;
    let seconds: f64 = seconds.parse().ok()?;
    Some((minutes * 60_000 + (seconds * 1000.0).round() as u32, end + 1))
}

// ------------------------------------------------------------------ cache --

fn now() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or_default()
}

fn read_cache(path: &Path) -> Option<Option<Lyrics>> {
    let (saved, lyrics): (u64, Option<Lyrics>) = serde_json::from_slice(&std::fs::read(path).ok()?).ok()?;
    (now().saturating_sub(saved) < CACHE_LIFETIME.as_secs()).then_some(lyrics)
}

fn write_cache(path: &Path, lyrics: &Option<Lyrics>) {
    if let Some(dir) = path.parent() {
        let _ = std::fs::create_dir_all(dir);
    }
    if let Ok(bytes) = serde_json::to_vec(&(now(), lyrics)) {
        let _ = std::fs::write(path, bytes);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn lrc_lines_parse_and_sort() {
        let lines = parse_lrc("[00:12.50]Second\n[00:01.00][00:20.00]First and third\n[00:05.00]\n");
        let stamps: Vec<_> = lines.iter().map(|l| (l.at_ms.unwrap(), l.text.as_str())).collect();
        assert_eq!(stamps, [(1000, "First and third"), (12500, "Second"), (20000, "First and third")]);
    }

    #[test]
    fn titles_lose_remaster_and_featuring() {
        assert_eq!(clean_title("Velha Infância - 2004 Digital Remaster"), "Velha Infância");
        assert_eq!(clean_title("Song (feat. Someone) [Remastered]"), "Song");
        assert_eq!(clean_title("Love (Is All Around)"), "Love (Is All Around)");
        assert_eq!(first_artist("PhaseOne, Eliminate"), "PhaseOne");
    }

    #[test]
    fn closest_length_wins_and_synced_breaks_ties() {
        let record = |duration: f64, synced: bool| Record {
            track_name: "Tempo Perdido".into(),
            artist_name: "Legião Urbana".into(),
            duration: Some(duration),
            synced_lyrics: synced.then(|| "[00:01.00]x".into()),
            plain_lyrics: Some("x".into()),
            ..Record::default()
        };
        let picked = pick(vec![record(250.0, true), record(302.0, false), record(400.0, true)], "Tempo Perdido", "Legiao Urbana", 302_000).unwrap();
        assert_eq!(picked.duration, Some(302.0));
    }

    #[test]
    fn spotify_lines_are_read() {
        let json = serde_json::json!({"lyrics": {"syncType": "LINE_SYNCED", "lines": [
            {"startTimeMs": "1000", "words": "Hello"}, {"startTimeMs": "2000", "words": "♪"}, {"startTimeMs": "3000", "words": "World"}
        ]}});
        let lyrics = parse_spotify(&json).unwrap();
        assert!(lyrics.synced);
        assert_eq!(lyrics.lines.iter().map(|l| l.text.as_str()).collect::<Vec<_>>(), ["Hello", "World"]);
    }
}
