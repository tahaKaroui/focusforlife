/// Firebase Realtime Database sync.
///
/// Each device PUTs its own usage state and GETs all other devices' state.
/// Schema under devices/{device_id}:
///   { date, daily_seconds, hourly_used_seconds, hourly_stamp }
///
/// Combined totals: my_usage + sum(others with matching date/hour_stamp).
/// Identical to the Linux daemon version — pure HTTP REST, no Firebase SDK.

use std::time::Instant;

use serde::{Deserialize, Serialize};

use ffl_shared::config::Sync as SyncConfig;

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct DeviceState {
    pub date: String,
    pub daily_seconds: u32,
    pub hourly_used_seconds: u32,
    pub hourly_stamp: u64,
}

/// Aggregated remote usage from all other devices.
#[derive(Debug, Clone, Default)]
pub struct RemoteUsage {
    pub daily_seconds: u32,
    pub hourly_used_seconds: u32,
}

pub struct FirebaseSync {
    db_url: String,
    device_id: String,
    interval_secs: u32,
    last_sync: Option<Instant>,
    last_remote: RemoteUsage,
}

impl FirebaseSync {
    pub fn new(config: &SyncConfig) -> Option<Self> {
        if config.firebase_db_url.is_empty() {
            return None;
        }
        Some(Self {
            db_url: config.firebase_db_url.trim_end_matches('/').to_string(),
            device_id: config.device_id.clone(),
            interval_secs: config.sync_interval_seconds,
            last_sync: None,
            last_remote: RemoteUsage::default(),
        })
    }

    pub fn should_sync(&self) -> bool {
        match self.last_sync {
            None => true,
            Some(t) => t.elapsed().as_secs() >= self.interval_secs as u64,
        }
    }

    /// Push local state and pull remote state.
    pub fn sync(
        &mut self,
        local_daily_seconds: u32,
        local_hourly_used: u32,
        local_hourly_stamp: u64,
        today: &str,
    ) -> &RemoteUsage {
        self.last_sync = Some(Instant::now());

        let local_state = DeviceState {
            date: today.to_string(),
            daily_seconds: local_daily_seconds,
            hourly_used_seconds: local_hourly_used,
            hourly_stamp: local_hourly_stamp,
        };
        if let Err(e) = self.push_state(&local_state) {
            eprintln!("firebase push failed: {e}");
            return &self.last_remote;
        }

        match self.pull_all_devices() {
            Ok(devices) => {
                let mut remote = RemoteUsage::default();
                for (id, state) in &devices {
                    if id == &self.device_id {
                        continue;
                    }
                    if state.date == today {
                        remote.daily_seconds =
                            remote.daily_seconds.saturating_add(state.daily_seconds);
                    }
                    if state.hourly_stamp == local_hourly_stamp {
                        remote.hourly_used_seconds = remote
                            .hourly_used_seconds
                            .saturating_add(state.hourly_used_seconds);
                    }
                }
                self.last_remote = remote;
            }
            Err(e) => {
                eprintln!("firebase pull failed: {e}");
            }
        }

        &self.last_remote
    }

    pub fn last_remote(&self) -> &RemoteUsage {
        &self.last_remote
    }

    fn push_state(&self, state: &DeviceState) -> Result<(), Box<dyn std::error::Error>> {
        let url = format!("{}/devices/{}.json", self.db_url, self.device_id);
        let body = serde_json::to_string(state)?;
        ureq::put(&url)
            .timeout(std::time::Duration::from_millis(3000))
            .set("Content-Type", "application/json")
            .send_string(&body)?;
        Ok(())
    }

    fn pull_all_devices(
        &self,
    ) -> Result<Vec<(String, DeviceState)>, Box<dyn std::error::Error>> {
        let url = format!("{}/devices.json", self.db_url);
        let body = ureq::get(&url)
            .timeout(std::time::Duration::from_millis(3000))
            .call()?
            .into_string()?;

        if body.trim() == "null" {
            return Ok(Vec::new());
        }

        let map: std::collections::HashMap<String, DeviceState> = serde_json::from_str(&body)?;
        Ok(map.into_iter().collect())
    }
}
