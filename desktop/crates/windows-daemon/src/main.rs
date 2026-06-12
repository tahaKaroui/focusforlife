/// FocusForLife — Windows daemon (ffl-windows)
///
/// Tracks the focused browser tab, enforces domain blocks via the hosts file,
/// and syncs usage with Linux/Android via Firebase Realtime Database.
///
/// Run as Administrator (required for hosts file writes).
/// Recommended: Task Scheduler → trigger "At startup" → action "ffl-windows.exe"
///              → "Run whether user is logged on or not" + "Run with highest privileges"
///
/// Default paths:
///   config    C:\ProgramData\FocusForLife\config.toml
///   blocklist C:\ProgramData\FocusForLife\blocked-domains.txt
///   database  C:\ProgramData\FocusForLife\ffl.sqlite

use std::collections::HashSet;
use std::fs;
use std::path::PathBuf;
use std::thread;
use std::time::Duration;

use anyhow::{Context, Result};
use ffl_shared::config::Config;
use ffl_shared::ipc::FocusState;

mod aw_tracker;
mod cdp_tracker;
mod enforcement;
mod firebase_sync;
mod rules;
mod storage;
mod tracker;

use aw_tracker::{AwTracker, DEFAULT_AW_BASE_URL, DEFAULT_WEB_BUCKET_PREFIX};
use cdp_tracker::{CdpTracker, DEFAULT_CDP_PORTS};
use enforcement::Enforcement;
use firebase_sync::FirebaseSync;
use storage::Storage;
use tracker::HourlyTracker;

const DEFAULT_CONFIG_PATH: &str = r"C:\ProgramData\FocusForLife\config.toml";
const DEFAULT_BLOCKLIST_PATH: &str = r"C:\ProgramData\FocusForLife\blocked-domains.txt";
const DEFAULT_DB_PATH: &str = r"C:\ProgramData\FocusForLife\ffl.sqlite";

fn main() -> Result<()> {
    let args = parse_args();

    let config = load_config(&args.config_path)?;
    let domains = load_domain_list(&args.blocklist_path)?;
    let domain_set: HashSet<String> = domains.iter().cloned().collect();

    println!("ffl-windows starting...");
    println!("config:    {}", args.config_path.display());
    println!("blocklist: {} ({} domains)", args.blocklist_path.display(), domains.len());
    println!("database:  {}", args.db_path.display());

    let storage = Storage::open(&args.db_path)?;
    let enforcement = Enforcement::new();

    let mut aw = AwTracker::new(DEFAULT_AW_BASE_URL, DEFAULT_WEB_BUCKET_PREFIX);
    let cdp = CdpTracker::new(DEFAULT_CDP_PORTS.to_vec());

    let mut tracker = HourlyTracker::restore_from_storage(&storage)
        .unwrap_or_else(|_| HourlyTracker::new());
    println!("hourly bucket restored: used={}s", tracker.hourly_used_seconds());

    let mut sync = FirebaseSync::new(&config.sync);
    if sync.is_some() {
        println!(
            "firebase sync enabled: device={}, interval={}s",
            config.sync.device_id, config.sync.sync_interval_seconds
        );
    }

    let now = chrono::Local::now();
    let usage = storage.get_daily_usage(now.date_naive())?;
    println!(
        "daily usage today: {}s / {}s",
        usage.used_seconds,
        config.rules.daily_quota_minutes * 60
    );

    let mut last_blocked: Option<bool> = None;
    let mut tick_count: u64 = 0;

    loop {
        let now = chrono::Local::now();
        let today = now.format("%Y-%m-%d").to_string();

        // --- Tab detection (three-tier hierarchy) ---
        //
        // Primary: ActivityWatch reports only the focused tab.
        // Secondary (CDP focused): WinAPI window title → CDP tab match.
        // Tertiary (CDP all-tabs): browser is active window → scan all tabs.

        let (focused_domain, tracking_source) = if let Some(d) = aw.focused_domain() {
            (Some(d), "aw")
        } else if let Some(d) = cdp.focused_domain() {
            (Some(d), "cdp")
        } else {
            (None, "none")
        };

        let on_target_primary = focused_domain
            .as_deref()
            .map(|d| domain_matches_blocked(d, &domain_set))
            .unwrap_or(false);

        // Tertiary: if primary says no blocked tab but browser is focused,
        // check all open tabs (catches AW reporting e.g. an extensions page
        // while YouTube is open in another tab).
        let on_target_tertiary = !on_target_primary
            && cdp
                .all_domains_if_browser_focused()
                .iter()
                .any(|d| domain_matches_blocked(d, &domain_set));

        // --- Firebase sync ---
        if let Some(ref mut fb) = sync {
            if fb.should_sync() {
                let usage = storage.get_daily_usage(now.date_naive())?;
                fb.sync(
                    usage.used_seconds,
                    tracker.hourly_used_seconds(),
                    tracker.current_hour_stamp(),
                    &today,
                );
            }
        }

        // --- Compute combined usage (local + remote) ---
        let usage = storage.get_daily_usage(now.date_naive())?;
        let remote = sync.as_ref().map(|fb| fb.last_remote());
        let combined_daily = usage
            .used_seconds
            .saturating_add(remote.map_or(0, |r| r.daily_seconds));
        let combined_hourly = tracker
            .hourly_used_seconds()
            .saturating_add(remote.map_or(0, |r| r.hourly_used_seconds));

        let free_time_granted = storage
            .get_free_time_grant(now.date_naive())?
            .unwrap_or(false);

        let result = rules::evaluate(
            &config,
            rules::RuleInputs {
                now,
                usage: storage::DailyUsage {
                    used_seconds: combined_daily,
                    sessions_count: usage.sessions_count,
                },
                hourly_used_seconds: combined_hourly,
                free_time_granted,
            },
        )?;
        let is_blocked = !matches!(result.state, FocusState::Allowed);

        // --- Apply / lift hosts-file block on state change ---
        if last_blocked != Some(is_blocked) {
            if is_blocked {
                println!(
                    "enforcement: blocking {} domains (state={:?})",
                    domains.len(),
                    result.state
                );
                if let Err(e) = enforcement.apply_blocklist(&domains) {
                    eprintln!("failed to apply blocklist: {e}");
                }
            } else {
                println!("enforcement: unblocking (state=allowed)");
                if let Err(e) = enforcement.apply_blocklist(&[]) {
                    eprintln!("failed to clear blocklist: {e}");
                }
            }
            last_blocked = Some(is_blocked);
        }

        // --- Tick the hourly tracker ---
        // Only count time when access is allowed AND a blocked site is active.
        let on_target = !is_blocked && (on_target_primary || on_target_tertiary);
        tracker.tick(&storage, now, on_target, 1)?;

        tick_count += 1;
        if tick_count % 10 == 0 {
            let usage = storage.get_daily_usage(now.date_naive())?;
            println!(
                "tracking... daily={}s(+{}s remote) hourly={}s(+{}s remote) \
                 focused={}[{}] tertiary={} on_target={} blocked={:?}",
                usage.used_seconds,
                remote.map_or(0, |r| r.daily_seconds),
                tracker.hourly_used_seconds(),
                remote.map_or(0, |r| r.hourly_used_seconds),
                focused_domain.as_deref().unwrap_or("none"),
                tracking_source,
                on_target_tertiary,
                on_target,
                result.state,
            );
        }

        // Write status file for UI.
        let daily_quota_s = config.rules.daily_quota_minutes * 60;
        let hourly_limit_s = config.rules.hourly_limit_minutes * 60;
        let status_json = format!(
            "{{\"state\":\"{}\",\"daily_remaining\":{},\"hourly_remaining\":{},\
             \"daily_quota\":{},\"hourly_limit\":{},\
             \"hard_block_start\":\"{}\",\"hard_block_end\":\"{}\"}}",
            match result.state {
                FocusState::Allowed => "allowed",
                FocusState::BlockedHardWindow => "blocked_hard_window",
                FocusState::BlockedCooldown => "blocked_cooldown",
                FocusState::BlockedQuota => "blocked_quota",
            },
            daily_quota_s.saturating_sub(combined_daily),
            hourly_limit_s.saturating_sub(combined_hourly),
            daily_quota_s,
            hourly_limit_s,
            config.windows.hard_block.start,
            config.windows.hard_block.end,
        );
        let _ = fs::write(r"C:\ProgramData\FocusForLife\status.json", &status_json);

        thread::sleep(Duration::from_secs(1));
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

struct Args {
    config_path: PathBuf,
    blocklist_path: PathBuf,
    db_path: PathBuf,
}

fn parse_args() -> Args {
    let mut config_path = PathBuf::from(DEFAULT_CONFIG_PATH);
    let mut blocklist_path = PathBuf::from(DEFAULT_BLOCKLIST_PATH);
    let mut db_path = PathBuf::from(DEFAULT_DB_PATH);

    let mut args = std::env::args().skip(1);
    while let Some(arg) = args.next() {
        match arg.as_str() {
            "--config" => {
                if let Some(p) = args.next() {
                    config_path = PathBuf::from(p);
                }
            }
            "--blocklist" => {
                if let Some(p) = args.next() {
                    blocklist_path = PathBuf::from(p);
                }
            }
            "--db" => {
                if let Some(p) = args.next() {
                    db_path = PathBuf::from(p);
                }
            }
            _ => {}
        }
    }

    Args { config_path, blocklist_path, db_path }
}

fn load_config(path: &PathBuf) -> Result<Config> {
    match fs::read_to_string(path) {
        Ok(contents) => toml::from_str(&contents)
            .with_context(|| format!("failed to parse config: {}", path.display())),
        Err(_) => {
            println!("config not found at {}, using defaults", path.display());
            Ok(Config::default())
        }
    }
}

fn load_domain_list(path: &PathBuf) -> Result<Vec<String>> {
    let contents = fs::read_to_string(path)
        .with_context(|| format!("failed to read blocklist: {}", path.display()))?;
    let domains = contents
        .lines()
        .map(str::trim)
        .filter(|l| !l.is_empty() && !l.starts_with('#'))
        .map(|l| l.to_lowercase())
        .collect();
    Ok(domains)
}

fn domain_matches_blocked(observed: &str, blocked: &HashSet<String>) -> bool {
    if blocked.contains(observed) {
        return true;
    }
    blocked.iter().any(|b| {
        observed == format!("www.{b}") || observed.ends_with(&format!(".{b}"))
    })
}
