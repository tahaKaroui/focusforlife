/// ActivityWatch web-bucket tracker.
///
/// Queries the local ActivityWatch server for the most recent event in the
/// web bucket (written by the browser extension). The extension only logs the
/// focused/active tab, so the domain returned is always the tab the user is
/// actually looking at.
///
/// Identical to the Linux daemon version — pure HTTP, no platform-specific code.

use std::time::{Duration, SystemTime, UNIX_EPOCH};
use url::Url;

/// Events older than this are considered stale (browser closed / tab switched away).
const MAX_EVENT_AGE_SECS: f64 = 60.0;

pub const DEFAULT_AW_BASE_URL: &str = "http://127.0.0.1:5600";
pub const DEFAULT_WEB_BUCKET_PREFIX: &str = "aw-watcher-web";

pub struct AwTracker {
    base_url: String,
    web_bucket_prefix: String,
    /// Cached bucket id once discovered.
    bucket_id: Option<String>,
}

impl AwTracker {
    pub fn new(base_url: impl Into<String>, web_bucket_prefix: impl Into<String>) -> Self {
        Self {
            base_url: base_url.into(),
            web_bucket_prefix: web_bucket_prefix.into(),
            bucket_id: None,
        }
    }

    /// Returns the domain of the currently focused browser tab, or `None` if
    /// ActivityWatch is not running / no web bucket exists / no recent event.
    pub fn focused_domain(&mut self) -> Option<String> {
        let bucket_id = self.resolve_bucket_id()?;
        fetch_latest_domain(&self.base_url, &bucket_id)
    }

    fn resolve_bucket_id(&mut self) -> Option<String> {
        if let Some(ref id) = self.bucket_id {
            return Some(id.clone());
        }
        let url = format!("{}/api/0/buckets/", self.base_url);
        let body = ureq::get(&url)
            .timeout(Duration::from_millis(500))
            .call()
            .ok()?
            .into_string()
            .ok()?;
        let buckets: serde_json::Value = serde_json::from_str(&body).ok()?;
        let bucket_id = buckets
            .as_object()?
            .keys()
            .find(|k| k.starts_with(&self.web_bucket_prefix))?
            .clone();
        self.bucket_id = Some(bucket_id.clone());
        Some(bucket_id)
    }
}

fn fetch_latest_domain(base_url: &str, bucket_id: &str) -> Option<String> {
    let url = format!("{base_url}/api/0/buckets/{bucket_id}/events?limit=1");
    let body = ureq::get(&url)
        .timeout(Duration::from_millis(500))
        .call()
        .ok()?
        .into_string()
        .ok()?;
    let events: Vec<serde_json::Value> = serde_json::from_str(&body).ok()?;
    let event = events.first()?;

    // Reject stale events: timestamp + duration must be within MAX_EVENT_AGE_SECS.
    if let Some(ts_str) = event.get("timestamp").and_then(|v| v.as_str()) {
        if let Ok(ts) = chrono::DateTime::parse_from_rfc3339(ts_str) {
            let duration_secs = event
                .get("duration")
                .and_then(|v| v.as_f64())
                .unwrap_or(0.0);
            let event_end_secs = ts.timestamp() as f64 + duration_secs;
            let now_secs = SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap_or_default()
                .as_secs_f64();
            if now_secs - event_end_secs > MAX_EVENT_AGE_SECS {
                return None;
            }
        }
    }

    let raw_url = event
        .get("data")
        .and_then(|d| d.get("url"))
        .and_then(|u| u.as_str())?;
    extract_domain(raw_url)
}

fn extract_domain(raw_url: &str) -> Option<String> {
    let parsed = Url::parse(raw_url).ok()?;
    let host = parsed.host_str()?.trim().to_lowercase();
    if host.is_empty() {
        return None;
    }
    Some(host.strip_prefix("www.").unwrap_or(&host).to_string())
}
