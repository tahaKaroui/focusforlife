/// Chrome DevTools Protocol tab tracker.
///
/// Connects to one or more CDP debug ports (e.g. Brave on 9222, Chrome on 9223)
/// and queries the `/json` endpoint to get the URL of every open tab.
/// This is exact and real-time — no history lag, no grace-period hacks.
///
/// Browsers must be launched with `--remote-debugging-port=<port>`.
/// See docs/cdp-setup.md for how to configure this via .desktop overrides.

use url::Url;

/// Default debug ports to probe, in order: Brave, Chrome, Chromium.
pub const DEFAULT_CDP_PORTS: &[u16] = &[9222, 9223, 9224];

pub struct CdpTracker {
    ports: Vec<u16>,
}

impl CdpTracker {
    pub fn new(ports: Vec<u16>) -> Self {
        Self { ports }
    }

    /// Returns the domain of every open browser tab across all configured ports.
    /// Ports where no browser is listening are silently skipped.
    pub fn poll_domains(&self) -> Vec<String> {
        let mut domains = Vec::new();
        for &port in &self.ports {
            match fetch_tab_domains(port) {
                Ok(mut found) => domains.append(&mut found),
                Err(_) => {
                    // Browser not running on this port — normal, skip silently.
                }
            }
        }
        domains
    }
}

/// Hits `http://localhost:<port>/json` and extracts the host from each tab's URL.
fn fetch_tab_domains(port: u16) -> Result<Vec<String>, Box<dyn std::error::Error>> {
    let url = format!("http://localhost:{port}/json");
    let body = ureq::get(&url)
        .timeout(std::time::Duration::from_millis(200))
        .call()?
        .into_string()?;

    let targets: Vec<serde_json::Value> = serde_json::from_str(&body)?;
    let mut domains = Vec::new();
    for target in &targets {
        // Only consider page targets (not service workers, extensions, etc.)
        if target.get("type").and_then(|t| t.as_str()) != Some("page") {
            continue;
        }
        if let Some(tab_url) = target.get("url").and_then(|u| u.as_str()) {
            if let Some(domain) = extract_domain(tab_url) {
                domains.push(domain);
            }
        }
    }
    Ok(domains)
}

fn extract_domain(raw_url: &str) -> Option<String> {
    let parsed = Url::parse(raw_url).ok()?;
    let host = parsed.host_str()?.trim().to_lowercase();
    if host.is_empty() {
        return None;
    }
    Some(host)
}
