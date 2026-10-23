//! Desktop smoke test: brings the core up on this PC, signs in through the
//! browser and stays on as a Spotify Connect device, so audio pushed from
//! the phone comes out of the PC's speakers.
//!
//! Proves on Windows/macOS/Linux what the Android app proves on a phone:
//! session, Spirc, the rodio/cpal sink and the equalizer chain.
//!
//!     cargo run --release --example desktop_smoke

use std::io::Write;
use std::path::PathBuf;
use std::sync::Arc;

use nexfy_core::{CoreEvent, CoreListener, NexfyCore};

struct Printer;

impl CoreListener for Printer {
    fn on_event(&self, event: CoreEvent) {
        match event {
            CoreEvent::TrackChanged { track } => println!(
                "  track    {} — {} [{}:{:02}]",
                track.title,
                track.artists,
                track.duration_ms / 60_000,
                track.duration_ms / 1_000 % 60,
            ),
            CoreEvent::Playing { position_ms } => println!("  playing  {position_ms} ms"),
            CoreEvent::Paused { position_ms } => println!("  paused   {position_ms} ms"),
            CoreEvent::Loading => println!("  loading"),
            CoreEvent::Stopped => println!("  stopped"),
            CoreEvent::QueueChanged { queue } => {
                println!("  queue    {} next, context {:?}", queue.next.len(), queue.context_uri)
            }
            CoreEvent::Error { message } => eprintln!("  error    {message}"),
        }
        let _ = std::io::stdout().flush();
    }
}

/// Same place the desktop app will keep it: %LOCALAPPDATA%\Nexfy (Windows),
/// ~/.local/share/nexfy or ~/Library/Application Support/Nexfy elsewhere.
fn cache_dir() -> PathBuf {
    let base = std::env::var_os("LOCALAPPDATA")
        .map(PathBuf::from)
        .or_else(|| std::env::var_os("XDG_DATA_HOME").map(PathBuf::from))
        .or_else(|| std::env::var_os("HOME").map(|h| PathBuf::from(h).join(".local/share")))
        .unwrap_or_else(std::env::temp_dir);
    base.join("Nexfy")
}

/// cmd.exe re-parses its own command line, and there an unquoted `&`
/// separates commands: handed over as a plain argument the URL is cut at its
/// first query separator, so Spotify sees only `client_id` and answers
/// "response_type must be code". One raw argument, with the URL quoted
/// inside it, is what survives `start`. The Unix launchers take argv
/// straight, with no shell in between.
fn open_in_browser(url: &str) {
    println!("{url}");

    #[cfg(target_os = "windows")]
    let opened = {
        use std::os::windows::process::CommandExt;
        std::process::Command::new("cmd")
            .raw_arg(format!("/c start \"\" \"{url}\""))
            .spawn()
    };
    #[cfg(target_os = "macos")]
    let opened = std::process::Command::new("open").arg(url).spawn();
    #[cfg(all(unix, not(target_os = "macos")))]
    let opened = std::process::Command::new("xdg-open").arg(url).spawn();

    if opened.is_err() {
        println!("(browser did not open: paste the link above by hand)");
    }
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    env_logger::Builder::from_env(env_logger::Env::default().default_filter_or("info")).init();

    let dir = cache_dir();
    std::fs::create_dir_all(&dir)?;
    println!("cache    {}", dir.display());

    let core = NexfyCore::new(dir.to_string_lossy().into_owned(), "Nexfy PC".to_owned());
    core.set_listener(Arc::new(Printer));

    let username = match core.restore_session().await? {
        Some(username) => {
            println!("restored {username}");
            username
        }
        None => {
            let url = core.begin_login().await?;
            println!("Sign in to Spotify in the browser (Premium account).");
            open_in_browser(&url);
            let username = core.finish_login().await?;
            println!("signed in as {username}");
            username
        }
    };

    println!();
    println!("Nexfy PC is on as a Spotify Connect device for {username}.");
    println!("On your phone's Spotify, pick \"Nexfy PC\" and play something.");
    println!("Audio should come out of this PC. Ctrl+C to stop.");
    println!();

    tokio::signal::ctrl_c().await?;
    println!("bye");
    Ok(())
}
