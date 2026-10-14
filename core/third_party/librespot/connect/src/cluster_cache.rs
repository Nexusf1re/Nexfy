//! The latest Spotify Connect cluster the dealer sent: what every device of
//! the account is and plays. Apps that show and control other devices read
//! it instead of asking the Web API.

use std::sync::Mutex;

use librespot_protocol::connect::Cluster;

static LATEST: Mutex<Option<Cluster>> = Mutex::new(None);

pub(crate) fn store(cluster: &Cluster) {
    *LATEST.lock().unwrap() = Some(cluster.clone());
}

/// The last cluster seen, if the dealer sent one this session.
pub fn latest() -> Option<Cluster> {
    LATEST.lock().unwrap().clone()
}
