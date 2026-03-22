/// Chrome DevTools Protocol tab tracker.
///
/// Connects to one or more CDP debug ports (e.g. Brave on 9222, Chrome on 9223)
/// and queries the `/json` endpoint to get tab URLs.
///
/// Browsers must be launched with `--remote-debugging-port=<port>`.
/// Brave's .desktop override at ~/.local/share/applications/brave-browser.desktop
/// already includes `--remote-debugging-port=9222`.
///
/// `focused_domain()` is the preferred method — it uses xdotool to identify which
/// window is active, strips the browser suffix from the window title, and matches
/// against the CDP tab list to return only the focused tab's domain.
/// Falls back to `None` if xdotool is unavailable or no title match is found.
///
/// `poll_domains()` returns ALL open tab domains and is kept for the legacy
/// --cdp-poll mode only.

use url::Url;

/// Default debug ports to probe, in order: Brave, Chrome, Chromium.
pub const DEFAULT_CDP_PORTS: &[u16] = &[9222, 9223, 9224];

/// Browser window-title suffixes that browsers append to the page title.
const BROWSER_SUFFIXES: &[&str] = &[
    " - Brave",
    " - Google Chrome",
    " - Chromium",
    " – Firefox",
    " - Firefox",
    " - Microsoft Edge",
];

pub struct CdpTracker {
    ports: Vec<u16>,
}

impl CdpTracker {
    pub fn new(ports: Vec<u16>) -> Self {
        Self { ports }
    }

    /// Returns the domain of the *currently focused* browser tab, or `None`.
    ///
    /// Uses `xdotool getactivewindow getwindowname` to read the active window
    /// title, strips the browser suffix to recover the page title, then finds
    /// the matching tab in the CDP `/json` response.
    ///
    /// This requires:
    ///   - `xdotool` installed (`apt install xdotool`)
    ///   - browser launched with `--remote-debugging-port`
    ///   - DISPLAY accessible (set via env or systemd unit)
    pub fn focused_domain(&self) -> Option<String> {
        let window_title = active_window_title()?;
        let page_title = strip_browser_suffix(&window_title);
        for &port in &self.ports {
            if let Some(domain) = cdp_focused_on_port(port, page_title) {
                return Some(domain);
            }
        }
        None
    }

    /// Returns the domain of every open browser tab across all configured ports.
    /// Ports where no browser is listening are silently skipped.
    /// Used only by the legacy --cdp-poll mode.
    pub fn poll_domains(&self) -> Vec<String> {
        let mut domains = Vec::new();
        for &port in &self.ports {
            match fetch_all_tab_domains(port) {
                Ok(mut found) => domains.append(&mut found),
                Err(_) => {}
            }
        }
        domains
    }
}

/// Ask xdotool for the active window's title.
/// Sets DISPLAY=:0 so the daemon (which runs as a system service without a
/// desktop session) can still reach the X display.
fn active_window_title() -> Option<String> {
    let output = std::process::Command::new("xdotool")
        .args(["getactivewindow", "getwindowname"])
        .env("DISPLAY", ":0")
        .output()
        .ok()?;
    if !output.status.success() {
        return None;
    }
    let title = String::from_utf8_lossy(&output.stdout).trim().to_string();
    if title.is_empty() { None } else { Some(title) }
}

fn strip_browser_suffix(title: &str) -> &str {
    for suffix in BROWSER_SUFFIXES {
        if let Some(stripped) = title.strip_suffix(suffix) {
            return stripped;
        }
    }
    title
}

/// Find the tab whose title matches `page_title` and return its domain.
fn cdp_focused_on_port(port: u16, page_title: &str) -> Option<String> {
    let url = format!("http://localhost:{port}/json");
    let body = ureq::get(&url)
        .timeout(std::time::Duration::from_millis(200))
        .call()
        .ok()?
        .into_string()
        .ok()?;
    let targets: Vec<serde_json::Value> = serde_json::from_str(&body).ok()?;
    for target in &targets {
        if target.get("type").and_then(|t| t.as_str()) != Some("page") {
            continue;
        }
        let tab_title = target.get("title").and_then(|t| t.as_str()).unwrap_or("");
        if tab_title == page_title {
            if let Some(tab_url) = target.get("url").and_then(|u| u.as_str()) {
                return extract_domain(tab_url);
            }
        }
    }
    None
}

/// Hits `http://localhost:<port>/json` and extracts the host from each tab's URL.
fn fetch_all_tab_domains(port: u16) -> Result<Vec<String>, Box<dyn std::error::Error>> {
    let url = format!("http://localhost:{port}/json");
    let body = ureq::get(&url)
        .timeout(std::time::Duration::from_millis(200))
        .call()?
        .into_string()?;
    let targets: Vec<serde_json::Value> = serde_json::from_str(&body)?;
    let mut domains = Vec::new();
    for target in &targets {
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
