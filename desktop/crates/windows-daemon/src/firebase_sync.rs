/// Firebase Realtime Database sync.
///
/// Every device signs in to one shared Firebase account (email/password) so
/// they all share a single `uid`, then PUTs its own usage state and GETs the
/// other devices' state. The data model under `users/{uid}/devices/{device_id}`:
///   { date, daily_seconds, hourly_used_seconds, hourly_stamp }
///
/// Combined totals are computed locally: my_usage + sum(others).
/// Pure HTTP REST against the RTDB + Google Identity Toolkit — no Firebase SDK.

use std::time::{Duration, Instant};

use serde::{Deserialize, Serialize};

use ffl_shared::config::Sync as SyncConfig;

const HTTP_TIMEOUT: Duration = Duration::from_millis(5000);

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
    /// Sum of daily_seconds from other devices (same date).
    pub daily_seconds: u32,
    /// Sum of hourly_used_seconds from other devices (same hour_stamp).
    pub hourly_used_seconds: u32,
}

/// Response shape of `accounts:signInWithPassword`.
#[derive(Deserialize)]
struct SignInResponse {
    #[serde(rename = "idToken")]
    id_token: String,
    #[serde(rename = "refreshToken")]
    refresh_token: String,
    #[serde(rename = "expiresIn")]
    expires_in: String,
    #[serde(rename = "localId")]
    local_id: String,
}

/// Response shape of the secure-token refresh endpoint.
#[derive(Deserialize)]
struct RefreshResponse {
    id_token: String,
    refresh_token: String,
    expires_in: String,
    user_id: String,
}

pub struct FirebaseSync {
    db_url: String,
    api_key: String,
    email: String,
    password: String,
    device_id: String,
    interval_secs: u32,
    last_sync: Option<Instant>,
    last_remote: RemoteUsage,
    // Auth state for the shared account.
    id_token: Option<String>,
    refresh_token: Option<String>,
    uid: Option<String>,
    token_expiry: Option<Instant>,
}

impl FirebaseSync {
    pub fn new(config: &SyncConfig) -> Option<Self> {
        if config.firebase_db_url.is_empty()
            || config.firebase_api_key.is_empty()
            || config.firebase_email.is_empty()
            || config.firebase_password.is_empty()
        {
            return None;
        }
        Some(Self {
            db_url: config.firebase_db_url.trim_end_matches('/').to_string(),
            api_key: config.firebase_api_key.clone(),
            email: config.firebase_email.clone(),
            password: config.firebase_password.clone(),
            device_id: config.device_id.clone(),
            interval_secs: config.sync_interval_seconds,
            last_sync: None,
            last_remote: RemoteUsage::default(),
            id_token: None,
            refresh_token: None,
            uid: None,
            token_expiry: None,
        })
    }

    /// Returns true if enough time has passed since last sync.
    pub fn should_sync(&self) -> bool {
        match self.last_sync {
            None => true,
            Some(t) => t.elapsed().as_secs() >= self.interval_secs as u64,
        }
    }

    /// Push local state and pull remote state. Returns combined remote usage.
    pub fn sync(
        &mut self,
        local_daily_seconds: u32,
        local_hourly_used: u32,
        local_hourly_stamp: u64,
        today: &str,
    ) -> &RemoteUsage {
        self.last_sync = Some(Instant::now());

        // Make sure we hold a valid id token (and uid) before touching the DB.
        if let Err(e) = self.ensure_auth() {
            eprintln!("firebase auth failed: {e}");
            return &self.last_remote;
        }

        // Push our state.
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

        // Pull all devices.
        match self.pull_all_devices() {
            Ok(devices) => {
                let mut remote = RemoteUsage::default();
                for (id, state) in &devices {
                    if id == &self.device_id {
                        continue;
                    }
                    // Only count usage from the same date.
                    if state.date == today {
                        remote.daily_seconds = remote.daily_seconds.saturating_add(state.daily_seconds);
                    }
                    // Only count hourly usage from the same hour.
                    if state.hourly_stamp == local_hourly_stamp {
                        remote.hourly_used_seconds = remote.hourly_used_seconds.saturating_add(state.hourly_used_seconds);
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

    /// Returns the last known remote usage (cached between syncs).
    pub fn last_remote(&self) -> &RemoteUsage {
        &self.last_remote
    }

    /// Ensure a valid id token is cached, signing in or refreshing as needed.
    fn ensure_auth(&mut self) -> Result<(), Box<dyn std::error::Error>> {
        if self.id_token.is_some() {
            if let Some(expiry) = self.token_expiry {
                if Instant::now() < expiry {
                    return Ok(());
                }
            }
        }

        // Prefer refreshing over a full sign-in when we have a refresh token.
        if let Some(refresh_token) = self.refresh_token.clone() {
            match self.refresh_grant(&refresh_token) {
                Ok(()) => return Ok(()),
                Err(e) => eprintln!("firebase token refresh failed: {e}; re-signing in"),
            }
        }

        self.sign_in()
    }

    fn sign_in(&mut self) -> Result<(), Box<dyn std::error::Error>> {
        let url = format!(
            "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key={}",
            self.api_key
        );
        let body = serde_json::json!({
            "email": self.email,
            "password": self.password,
            "returnSecureToken": true,
        });
        let resp = ureq::post(&url)
            .timeout(HTTP_TIMEOUT)
            .set("Content-Type", "application/json")
            .send_string(&body.to_string())?
            .into_string()?;
        let parsed: SignInResponse = serde_json::from_str(&resp)?;
        self.apply_tokens(
            parsed.id_token,
            parsed.refresh_token,
            parsed.local_id,
            &parsed.expires_in,
        );
        Ok(())
    }

    fn refresh_grant(&mut self, refresh_token: &str) -> Result<(), Box<dyn std::error::Error>> {
        let url = format!(
            "https://securetoken.googleapis.com/v1/token?key={}",
            self.api_key
        );
        let resp = ureq::post(&url)
            .timeout(HTTP_TIMEOUT)
            .send_form(&[
                ("grant_type", "refresh_token"),
                ("refresh_token", refresh_token),
            ])?
            .into_string()?;
        let parsed: RefreshResponse = serde_json::from_str(&resp)?;
        self.apply_tokens(
            parsed.id_token,
            parsed.refresh_token,
            parsed.user_id,
            &parsed.expires_in,
        );
        Ok(())
    }

    fn apply_tokens(
        &mut self,
        id_token: String,
        refresh_token: String,
        uid: String,
        expires_in: &str,
    ) {
        // Tokens last ~1h; refresh 60s early to avoid races.
        let lifetime = expires_in.parse::<u64>().unwrap_or(3600).saturating_sub(60).max(1);
        self.token_expiry = Some(Instant::now() + Duration::from_secs(lifetime));
        self.id_token = Some(id_token);
        self.refresh_token = Some(refresh_token);
        self.uid = Some(uid);
    }

    fn push_state(&self, state: &DeviceState) -> Result<(), Box<dyn std::error::Error>> {
        let uid = self.uid.as_deref().ok_or("not authenticated")?;
        let token = self.id_token.as_deref().ok_or("not authenticated")?;
        let url = format!(
            "{}/users/{}/devices/{}.json?auth={}",
            self.db_url, uid, self.device_id, token
        );
        let body = serde_json::to_string(state)?;
        ureq::put(&url)
            .timeout(HTTP_TIMEOUT)
            .set("Content-Type", "application/json")
            .send_string(&body)?;
        Ok(())
    }

    fn pull_all_devices(&self) -> Result<Vec<(String, DeviceState)>, Box<dyn std::error::Error>> {
        let uid = self.uid.as_deref().ok_or("not authenticated")?;
        let token = self.id_token.as_deref().ok_or("not authenticated")?;
        let url = format!("{}/users/{}/devices.json?auth={}", self.db_url, uid, token);
        let body = ureq::get(&url)
            .timeout(HTTP_TIMEOUT)
            .call()?
            .into_string()?;

        if body.trim() == "null" {
            return Ok(Vec::new());
        }

        let map: std::collections::HashMap<String, DeviceState> = serde_json::from_str(&body)?;
        Ok(map.into_iter().collect())
    }
}
