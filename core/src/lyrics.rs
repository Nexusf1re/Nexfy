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
    /// Word (or syllable) timings when the source has them; their texts
    /// join back into `text` exactly, so the view can map them to offsets.
    #[serde(default)]
    pub words: Vec<LyricsWord>,
    /// When the last word ends, if the source says.
    #[serde(default)]
    pub end_ms: Option<u32>,
}

/// One timed piece of a line: a word with its trailing space, or a syllable.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
pub struct LyricsWord {
    pub at_ms: u32,
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

/// A time or count Spotify sends as a string ("1234") or, sometimes, a number.
fn spotify_ms(value: Option<&serde_json::Value>) -> Option<u32> {
    let value = value?;
    value.as_str().and_then(|s| s.parse().ok()).or_else(|| value.as_u64().map(|n| n as u32))
}

/// Parses Spotify's `color-lyrics` response. "SYLLABLE_SYNCED" lines are
/// timed like "LINE_SYNCED" ones and carry their syllables on top.
fn parse_spotify(json: &serde_json::Value) -> Option<Lyrics> {
    let lyrics = json.get("lyrics")?;
    let sync = lyrics.get("syncType").and_then(|v| v.as_str()).unwrap_or_default();
    let synced = sync == "LINE_SYNCED" || sync == "SYLLABLE_SYNCED";
    let lines: Vec<LyricsLine> = lyrics
        .get("lines")?
        .as_array()?
        .iter()
        .filter_map(|line| {
            let raw = line.get("words")?.as_str()?;
            let text = raw.trim();
            if text.is_empty() || text == "\u{266a}" {
                return None;
            }
            let at_ms = spotify_ms(line.get("startTimeMs")).filter(|_| synced);
            let (words, end_ms) = match (at_ms, line.get("syllables").and_then(|v| v.as_array())) {
                (Some(_), Some(syllables)) if !syllables.is_empty() => spotify_syllables(raw, syllables, line),
                _ => (Vec::new(), None),
            };
            Some(LyricsLine { at_ms, text: text.to_owned(), words, end_ms })
        })
        .collect();
    if lines.is_empty() {
        return None;
    }
    let synced = synced && lines.iter().all(|line| line.at_ms.is_some());
    Some(Lyrics { lines, synced, instrumental: false, source: "Spotify".into() })
}

/// Syllables to timed pieces of the line. Each syllable gives its start and
/// its position (`charsIndex`), its length (`numChars`) or its own text; a
/// piece runs to the next syllable, so spaces stay with the word before
/// them. Anything that does not add up drops the timings, never the line.
fn spotify_syllables(raw: &str, syllables: &[serde_json::Value], line: &serde_json::Value) -> (Vec<LyricsWord>, Option<u32>) {
    let chars: Vec<char> = raw.chars().collect();
    let count = |v: Option<&serde_json::Value>| spotify_ms(v).map(|n| n as usize);
    let mut cursor = 0usize;
    let mut starts: Vec<(usize, u32)> = Vec::new();
    for syllable in syllables {
        let Some(at) = spotify_ms(syllable.get("startTimeMs")) else {
            return (Vec::new(), None);
        };
        let own = syllable.get("words").or_else(|| syllable.get("text")).and_then(|v| v.as_str());
        let explicit = count(syllable.get("charsIndex").or_else(|| syllable.get("charIndex")).or_else(|| syllable.get("startIndex")))
            .or_else(|| own.and_then(|own| find_chars(&chars, own, cursor)));
        let length = count(syllable.get("numChars").or_else(|| syllable.get("length")))
            .or_else(|| own.map(|own| own.chars().count()));
        if explicit.is_none() && length.is_none() {
            // Nothing tells where this syllable sits or where the next one starts.
            return (Vec::new(), None);
        }
        let index = explicit.unwrap_or(cursor);
        cursor = index + length.unwrap_or(0);
        starts.push((index, at));
    }
    let last_at = starts.last().map(|s| s.1).unwrap_or_default();
    let end_ms = syllables
        .last()
        .and_then(|s| spotify_ms(s.get("endTimeMs")))
        .filter(|end| *end > last_at)
        .or_else(|| spotify_ms(line.get("endTimeMs")).filter(|end| *end > last_at));
    let pieces = starts.iter().enumerate().filter_map(|(i, &(index, at))| {
        let from = if i == 0 { 0 } else { index.min(chars.len()) };
        let to = starts.get(i + 1).map(|s| s.0).unwrap_or(chars.len()).min(chars.len());
        (from < to).then(|| (at, chars[from..to].iter().collect::<String>()))
    });
    (checked_words(pieces.collect(), raw.trim()), end_ms)
}

/// Where `needle` next appears in `chars`, from `from` on.
fn find_chars(chars: &[char], needle: &str, from: usize) -> Option<usize> {
    let needle: Vec<char> = needle.chars().collect();
    if needle.is_empty() || needle.len() > chars.len() {
        return None;
    }
    (from..=chars.len() - needle.len()).find(|&i| chars[i..i + needle.len()] == needle[..])
}

/// Timed pieces trimmed like their line; empty unless they join back into
/// `text` exactly and their times only move forward.
fn checked_words(mut pieces: Vec<(u32, String)>, text: &str) -> Vec<LyricsWord> {
    pieces.retain(|(_, piece)| !piece.is_empty());
    if let Some(first) = pieces.first_mut() {
        first.1 = first.1.trim_start().to_owned();
    }
    if let Some(last) = pieces.last_mut() {
        last.1 = last.1.trim_end().to_owned();
    }
    pieces.retain(|(_, piece)| !piece.is_empty());
    let joined: String = pieces.iter().map(|(_, piece)| piece.as_str()).collect();
    if joined != text || pieces.windows(2).any(|w| w[1].0 < w[0].0) {
        return Vec::new();
    }
    pieces.into_iter().map(|(at_ms, text)| LyricsWord { at_ms, text }).collect()
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
            .map(|line| LyricsLine { at_ms: None, text: line.trim_end().to_owned(), words: Vec::new(), end_ms: None })
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
/// Enhanced LRC word stamps ("<mm:ss.xx>word") become word timings.
fn parse_lrc(text: &str) -> Vec<LyricsLine> {
    let mut lines = Vec::new();
    for raw in text.lines() {
        let mut rest = raw.trim();
        let mut stamps = Vec::new();
        while let Some((ms, len)) = leading_stamp(rest, '[', ']') {
            stamps.push(ms);
            rest = rest[len..].trim_start();
        }
        let Some(&first) = stamps.first() else { continue };
        let (text, words, end_ms) = lrc_words(rest, first);
        for ms in stamps {
            // A repeated line sings its words as much later as it starts.
            let shift = |at: u32| at.saturating_add(ms).saturating_sub(first);
            lines.push(LyricsLine {
                at_ms: Some(ms),
                text: text.clone(),
                words: words.iter().map(|w| LyricsWord { at_ms: shift(w.at_ms), text: w.text.clone() }).collect(),
                end_ms: end_ms.map(shift),
            });
        }
    }
    lines.sort_by_key(|line| line.at_ms);
    lines.retain(|line| !line.text.is_empty());
    lines
}

/// The text of an LRC line without its word stamps, its timed words (empty
/// when it has none) and the closing stamp after the last word, if any.
fn lrc_words(rest: &str, line_ms: u32) -> (String, Vec<LyricsWord>, Option<u32>) {
    let mut pieces: Vec<(u32, String)> = vec![(line_ms, String::new())];
    let mut tagged = false;
    let mut tail = rest;
    while let Some(open) = tail.find('<') {
        let current = &mut pieces.last_mut().expect("starts with one piece").1;
        if let Some((ms, len)) = leading_stamp(&tail[open..], '<', '>') {
            current.push_str(&tail[..open]);
            pieces.push((ms, String::new()));
            tagged = true;
            tail = &tail[open + len..];
        } else {
            current.push_str(&tail[..=open]);
            tail = &tail[open + 1..];
        }
    }
    pieces.last_mut().expect("starts with one piece").1.push_str(tail);
    let text = pieces.iter().map(|(_, piece)| piece.as_str()).collect::<String>().trim().to_owned();
    if !tagged {
        return (text, Vec::new(), None);
    }
    // A stamp with nothing after it closes the line.
    let end_ms = pieces.last().filter(|(_, piece)| piece.trim().is_empty()).map(|(ms, _)| *ms);
    // Spaces between stamps belong to the word before them.
    let mut merged: Vec<(u32, String)> = Vec::new();
    for (ms, piece) in pieces {
        match merged.last_mut() {
            Some(last) if piece.trim().is_empty() => last.1.push_str(&piece),
            _ if piece.trim().is_empty() => {}
            _ => merged.push((ms, piece)),
        }
    }
    let words = checked_words(merged, &text);
    (text, words, end_ms)
}

/// A "[mm:ss.xx]" stamp (or "<mm:ss.xx>", with those delimiters) at the
/// start of `text`: its time in ms and its length.
fn leading_stamp(text: &str, open: char, close: char) -> Option<(u32, usize)> {
    let end = text.strip_prefix(open)?.find(close)? + 1;
    let (minutes, seconds) = text[1..end].split_once(':')?;
    let minutes: u32 = minutes.trim().parse().ok()?;
    let seconds: f64 = seconds.trim().parse().ok()?;
    Some((minutes * 60_000 + (seconds * 1000.0).round() as u32, end + 1))
}

// ------------------------------------------------------------------ cache --

pub(crate) fn now() -> u64 {
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
        assert!(lyrics.lines.iter().all(|l| l.words.is_empty()));
    }

    #[test]
    fn spotify_syllables_become_words() {
        let json = serde_json::json!({"lyrics": {"syncType": "SYLLABLE_SYNCED", "lines": [
            {"startTimeMs": "1000", "endTimeMs": "2600", "words": "Olá mundo", "syllables": [
                {"startTimeMs": "1000", "charsIndex": "0", "numChars": "3"},
                {"startTimeMs": "1800", "charsIndex": "4", "numChars": "5"}
            ]},
            {"startTimeMs": "3000", "words": "Sem sílabas", "syllables": []},
            {"startTimeMs": "4000", "words": "Sem posição", "syllables": [{"startTimeMs": "4000"}, {"startTimeMs": "4500"}]}
        ]}});
        let lyrics = parse_spotify(&json).unwrap();
        assert!(lyrics.synced);
        let words: Vec<_> = lyrics.lines[0].words.iter().map(|w| (w.at_ms, w.text.as_str())).collect();
        assert_eq!(words, [(1000, "Olá "), (1800, "mundo")]);
        assert_eq!(lyrics.lines[0].end_ms, Some(2600));
        assert!(lyrics.lines[1].words.is_empty());
        assert!(lyrics.lines[2].words.is_empty());
    }

    #[test]
    fn enhanced_lrc_words_parse() {
        let lines = parse_lrc("[00:01.00]<00:01.00>Hello <00:01.50>big <00:02.00>world<00:03.00>\n[00:04.00]Plain line\n");
        assert_eq!(lines[0].text, "Hello big world");
        let words: Vec<_> = lines[0].words.iter().map(|w| (w.at_ms, w.text.as_str())).collect();
        assert_eq!(words, [(1000, "Hello "), (1500, "big "), (2000, "world")]);
        assert_eq!(lines[0].end_ms, Some(3000));
        assert_eq!(lines[1].text, "Plain line");
        assert!(lines[1].words.is_empty());
    }

    #[test]
    fn old_cached_lines_still_load() {
        let line: LyricsLine = serde_json::from_str(r#"{"at_ms":1000,"text":"Hello"}"#).unwrap();
        assert!(line.words.is_empty() && line.end_ms.is_none());
    }
}
