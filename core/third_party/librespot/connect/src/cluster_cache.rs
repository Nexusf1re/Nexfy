//! The latest Spotify Connect cluster the dealer sent: what every device of
//! the account is and plays. Apps that show and control other devices read
//! it instead of asking the Web API.

use std::sync::Mutex;

use librespot_protocol::connect::Cluster;

static LATEST: Mutex<Option<Cluster>> = Mutex::new(None);

/// A full cluster, as the dealer sends it.
pub(crate) fn store(cluster: &Cluster) {
    *LATEST.lock().unwrap() = Some(cluster.clone());
}

/// The cluster in the reply to this device's own state: who is active and
/// what plays are current, but its device list can be partial, so the
/// devices seen before are kept.
pub(crate) fn update_from_reply(cluster: &Cluster) {
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

/// The last cluster seen, if the dealer sent one this session.
pub fn latest() -> Option<Cluster> {
    LATEST.lock().unwrap().clone()
}
