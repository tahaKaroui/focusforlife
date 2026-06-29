use std::process::Command;
use std::thread;
use std::time::Duration;

/// Fast mutual watchdog. Together with the daemon's own guardian thread and the
/// ffl-guardian.timer, this forms three independent resurrection sources: the
/// daemon revives the watcher, the watcher revives the daemon, and the timer
/// revives both. Killing or `systemctl stop`-ing one is undone within ~2s.
///
/// Deliberate OFF (stays reliable, never auto-undone):
///   sudo systemctl disable --now ffl-guardian.timer ffl-daemon ffl-watcher
const CHECK_INTERVAL: Duration = Duration::from_secs(2);

fn main() {
    println!("ffl-watcher starting (mutual watchdog, interval: 2s)");
    loop {
        check_and_repair();
        thread::sleep(CHECK_INTERVAL);
    }
}

fn is_active(unit: &str) -> bool {
    Command::new("systemctl")
        .args(["is-active", "--quiet", unit])
        .status()
        .map(|s| s.success())
        .unwrap_or(false)
}

fn is_enabled(unit: &str) -> bool {
    Command::new("systemctl")
        .args(["is-enabled", "--quiet", unit])
        .status()
        .map(|s| s.success())
        .unwrap_or(false)
}

/// Keep a unit both enabled (survives reboot) and running (survives stop/kill).
/// A masked unit is left alone, so a deliberate mask is also honored if used.
fn ensure_unit(unit: &str) {
    // `is-enabled` prints "masked" for masked units; never fight a mask.
    let state = Command::new("systemctl")
        .args(["is-enabled", unit])
        .output()
        .map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string())
        .unwrap_or_default();
    if state == "masked" {
        return;
    }
    if !is_enabled(unit) {
        let _ = Command::new("systemctl").args(["enable", unit]).status();
    }
    if !is_active(unit) {
        eprintln!("{unit} down; reviving");
        let _ = Command::new("systemctl").args(["start", unit]).status();
    }
}

fn check_and_repair() {
    // Mutual half: keep the daemon alive + enabled (the daemon keeps us alive).
    ensure_unit("ffl-daemon.service");

    // Keep DNS-level enforcement up.
    if !is_active("unbound") {
        eprintln!("unbound not active; restarting ffl-resolver");
        let _ = Command::new("systemctl").args(["restart", "ffl-resolver"]).status();
    }
    if let Ok(out) = Command::new("nft").args(["list", "ruleset"]).output() {
        if !String::from_utf8_lossy(&out.stdout).contains("focusforlife") {
            eprintln!("focusforlife nftables chain missing; restarting ffl-firewall");
            let _ = Command::new("systemctl").args(["restart", "ffl-firewall"]).status();
        }
    }
}
