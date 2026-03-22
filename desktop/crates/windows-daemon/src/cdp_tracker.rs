/// Chrome DevTools Protocol tab tracker — Windows edition.
///
/// Three-tier hierarchy (same priority order as Linux daemon):
///
/// Primary — ActivityWatch (see aw_tracker.rs): reports the focused tab only.
///
/// Secondary — CDP focused tab: uses WinAPI GetForegroundWindow + GetWindowTextW
///   to read the active window title, strips the browser suffix, then matches
///   against the CDP /json tab list to return only the focused tab's domain.
///
/// Tertiary — CDP all-tabs: if the browser window class is Chrome_WidgetWin_1
///   (Brave/Chrome/Edge all use this) check every open tab for blocked domains.
///
/// Browsers must be launched with --remote-debugging-port=<port>.
/// Add --remote-debugging-port=9222 to the Brave shortcut if not already set.

use url::Url;
use windows::Win32::Foundation::HWND;
use windows::Win32::UI::WindowsAndMessaging::{
    GetClassNameW, GetForegroundWindow, GetWindowTextW,
};

/// Default debug ports to probe: Brave, Chrome, Chromium, Edge.
pub const DEFAULT_CDP_PORTS: &[u16] = &[9222, 9223, 9224];

/// Browser window-title suffixes appended by each browser.
const BROWSER_SUFFIXES: &[&str] = &[
    " - Brave",
    " - Google Chrome",
    " - Chromium",
    " - Microsoft Edge",
    " – Firefox",
    " - Firefox",
];

/// Window class used by Brave, Chrome, and Edge on Windows.
const CHROMIUM_CLASS: &str = "Chrome_WidgetWin_1";

pub struct CdpTracker {
    ports: Vec<u16>,
}

impl CdpTracker {
    pub fn new(ports: Vec<u16>) -> Self {
        Self { ports }
    }

    /// Returns the domain of the *currently focused* browser tab, or `None`.
    ///
    /// Uses WinAPI GetForegroundWindow + GetWindowTextW to read the active
    /// window title, strips the browser suffix, then finds the matching tab
    /// in the CDP /json response.
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

    /// Returns all tab domains across all configured CDP ports.
    pub fn poll_domains(&self) -> Vec<String> {
        let mut domains = Vec::new();
        for &port in &self.ports {
            if let Ok(mut found) = fetch_all_tab_domains(port) {
                domains.append(&mut found);
            }
        }
        domains
    }

    /// Returns all tab domains if a Chromium-family browser is the active window.
    /// Used as a secondary check: catches cases where AW reports a non-blocked tab
    /// while a blocked site is open in another tab with the browser focused.
    pub fn all_domains_if_browser_focused(&self) -> Vec<String> {
        if !is_browser_window_active() {
            return Vec::new();
        }
        self.poll_domains()
    }
}

/// Returns the title of the currently active (foreground) window, or `None`.
fn active_window_title() -> Option<String> {
    unsafe {
        let hwnd: HWND = GetForegroundWindow();
        if hwnd.0 == 0 {
            return None;
        }
        let mut buf = [0u16; 512];
        let len = GetWindowTextW(hwnd, &mut buf);
        if len == 0 {
            return None;
        }
        let title = String::from_utf16_lossy(&buf[..len as usize]);
        if title.is_empty() {
            None
        } else {
            Some(title)
        }
    }
}

/// Returns `true` if the active window belongs to a Chromium-family browser.
/// Brave, Chrome, and Edge all register the class name "Chrome_WidgetWin_1".
fn is_browser_window_active() -> bool {
    unsafe {
        let hwnd: HWND = GetForegroundWindow();
        if hwnd.0 == 0 {
            return false;
        }
        let mut buf = [0u16; 256];
        let len = GetClassNameW(hwnd, &mut buf);
        if len == 0 {
            return false;
        }
        let class = String::from_utf16_lossy(&buf[..len as usize]);
        class == CHROMIUM_CLASS
    }
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
