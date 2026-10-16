//! The latest Spotify Connect cluster the dealer sent: what every device of
//! the account is and plays. Apps that show and control other devices read
//! it instead of asking the Web API, and wait on [changed] instead of
//! polling it.

use std::sync::{Mutex, OnceLock};

use librespot_protocol::connect::Cluster;
use tokio::sync::watch;

static LATEST: Mutex<Option<Cluster>> = Mutex::new(None);

/// Moves on every cluster stored, so waiters wake only when something came.
fn version() -> &'static watch::Sender<u64> {
    static VERSION: OnceLock<watch::Sender<u64>> = OnceLock::new();
    VERSION.get_or_init(|| watch::channel(0).0)
}

fn bump() {
    version().send_modify(|v| *v += 1);
}

/// A full cluster, as the dealer sends it.
pub(crate) fn store(cluster: &Cluster) {
    *LATEST.lock().unwrap() = Some(cluster.clone());
    bump();
}

/// The cluster in the reply to this device's own state: who is active and
/// what plays are current, but its device list can be partial, so the
/// devices seen before are kept.
pub(crate) fn update_from_reply(cluster: &Cluster) {
    {
        let mut latest = LATEST.lock().unwrap();
        match latest.as_mut() {
            Some(known) => {
                known.active_device_id = cluster.active_device_id.clone();
                known.player_state = cluster.player_state.clone();
                known.changed_timestamp_ms = cluster.changed_timestamp_ms;
                for (id, info) in &cluster.device {
                    known.device.insert(id.clone(), info.clone());
                }
            }
            None => *latest = Some(cluster.clone()),
        }
    }
    bump();
}

/// The last cluster seen, if the dealer sent one this session.
pub fn latest() -> Option<Cluster> {
    LATEST.lock().unwrap().clone()
}

/// Waits until a cluster newer than version [seen] arrives and returns its
/// version; at once when one already has. Sleeping here costs nothing.
pub async fn changed(seen: u64) -> u64 {
    let mut rx = version().subscribe();
    loop {
        let now = *rx.borrow_and_update();
        if now != seen {
            return now;
        }
        if rx.changed().await.is_err() {
            return now;
        }
    }
}
