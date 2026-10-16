//! Translating lyrics, through MyMemory: free, no account, no key.
//!
//! It takes 500 characters a request, so a song goes in a few pieces, cut
//! between lines so each translated line still matches its own. A song is
//! translated once and kept on disk: the daily allowance (5 000 characters,
//! or 50 000 with an email) is spent only on songs never seen before.

use std::path::{Path, PathBuf};
use std::sync::OnceLock;
use std::time::Duration;

use anyhow::{Result, anyhow};
use serde::Deserialize;

const API: &str = "https://api.mymemory.translated.net/get";
/// What the service takes in one `q`.
const MAX_CHARS: usize = 480;
const CACHE_LIFETIME: Duration = Duration::from_secs(365 * 86_400);

/// A lyric in another language: one entry per original line, empty where a
/// line could not be translated.
#[derive(Debug, Clone, serde::Serialize, Deserialize, uniffi::Record)]
pub struct LyricsTranslation {
    pub lines: Vec<String>,
    /// What the service made of the original ("en"), when it said.
    pub from: String,
    pub target: String,
}

fn http() -> &'static reqwest::Client {
    static CLIENT: OnceLock<reqwest::Client> = OnceLock::new();
    CLIENT.get_or_init(|| {
        reqwest::Client::builder()
            .timeout(Duration::from_secs(20))
            .user_agent(concat!("Nexfy/", env!("CARGO_PKG_VERSION"), " (personal Android player)"))
            .build()
            .unwrap_or_default()
    })
}

#[derive(Debug, Deserialize)]
struct Answer {
    #[serde(rename = "responseData")]
    data: Data,
    #[serde(rename = "responseStatus")]
    status: serde_json::Value,
    #[serde(rename = "quotaFinished")]
    quota_finished: Option<bool>,
}

#[derive(Debug, Deserialize)]
struct Data {
    #[serde(rename = "translatedText")]
    text: String,
    #[serde(rename = "detectedLanguage")]
    detected: Option<String>,
}

/// Lines grouped so each group stays inside the request limit; a line longer
/// than the limit on its own goes alone and is sent as it is.
fn chunks(lines: &[String]) -> Vec<(usize, usize)> {
    let mut groups = Vec::new();
    let mut start = 0;
    let mut chars = 0;
    for (i, line) in lines.iter().enumerate() {
        let cost = line.chars().count() + 1;
        if i > start && chars + cost > MAX_CHARS {
            groups.push((start, i));
            start = i;
            chars = 0;
        }
        chars += cost;
    }
    if start < lines.len() {
        groups.push((start, lines.len()));
    }
    groups
}

async fn ask(text: &str, target: &str, email: Option<&str>) -> Result<(String, Option<String>)> {
    let mut request = http()
        .get(API)
        .query(&[("q", text), ("langpair", &format!("Autodetect|{target}"))]);
    // An email of one's own raises the day's allowance tenfold.
    if let Some(email) = email.filter(|e| !e.is_empty()) {
        request = request.query(&[("de", email)]);
    }
    let answer: Answer = request.send().await?.json().await?;
    let ok = match &answer.status {
        serde_json::Value::Number(n) => n.as_i64() == Some(200),
        serde_json::Value::String(s) => s == "200",
        _ => false,
    };
    if answer.quota_finished == Some(true) {
        return Err(anyhow!("a cota de traduções de hoje acabou"));
    }
    if !ok {
        return Err(anyhow!("{}", answer.data.text));
    }
    let detected = answer.detected();
    Ok((answer.data.text, detected))
}

impl Answer {
    fn detected(&self) -> Option<String> {
        self.data.detected.clone().filter(|l| !l.is_empty())
    }
}

fn cache_file(dir: &Path, track_id: &str, target: &str) -> PathBuf {
    dir.join("lyrics").join(format!("{track_id}.{target}.json"))
}

fn read_cache(path: &Path) -> Option<LyricsTranslation> {
    let (saved, value): (u64, LyricsTranslation) = serde_json::from_slice(&std::fs::read(path).ok()?).ok()?;
    let age = crate::lyrics::now().saturating_sub(saved);
    (age < CACHE_LIFETIME.as_secs()).then_some(value)
}

fn write_cache(path: &Path, value: &LyricsTranslation) {
    if let Some(dir) = path.parent() {
        let _ = std::fs::create_dir_all(dir);
    }
    if let Ok(bytes) = serde_json::to_vec(&(crate::lyrics::now(), value)) {
        let _ = std::fs::write(path, bytes);
    }
}

/// The lines of a song in `target`, from the disk when it has them. None
/// when the song is already in that language, or nothing could be read.
pub async fn translate(
    cache_dir: &Path,
    track_id: &str,
    lines: &[String],
    target: &str,
    email: Option<&str>,
) -> Result<Option<LyricsTranslation>> {
    let short = target.split('-').next().unwrap_or(target).to_lowercase();
    let path = cache_file(cache_dir, track_id, &short);
    if let Some(known) = read_cache(&path) {
        return Ok(Some(known));
    }
    let mut out: Vec<String> = vec![String::new(); lines.len()];
    let mut from = String::new();
    let mut any = false;
    for (start, end) in chunks(lines) {
        let piece = lines[start..end].join("\n");
        if piece.trim().is_empty() {
            continue;
        }
        let (text, detected) = ask(&piece, target, email).await?;
        if let Some(detected) = detected {
            // The song is already in this language: nothing to show.
            if from.is_empty() && detected.split('-').next().unwrap_or(&detected).eq_ignore_ascii_case(&short) {
                return Ok(None);
            }
            if from.is_empty() {
                from = detected;
            }
        }
        let got: Vec<&str> = text.split('\n').collect();
        // Only when every line came back: a short answer would pair each
        // translation with the wrong line.
        if got.len() == end - start {
            for (i, line) in got.iter().enumerate() {
                out[start + i] = (*line).trim().to_owned();
            }
            any = true;
        }
    }
    if !any {
        return Err(anyhow!("a tradução não veio"));
    }
    let value = LyricsTranslation { lines: out, from, target: short };
    write_cache(&path, &value);
    Ok(Some(value))
}
