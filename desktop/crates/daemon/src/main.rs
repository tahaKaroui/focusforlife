use std::fs;
use std::io::BufRead;
use std::io::Seek;
use std::path::{Path, PathBuf};
use std::sync::mpsc;
use std::thread;
use std::time::Duration;

use anyhow::{bail, Context, Result};
use ffl_shared::config::{Config, PromptPolicy};
use ffl_shared::ipc::{DaemonEvent, PromptRequest, StatusSnapshot};

mod browser_history;
use browser_history::BrowserHistoryTracker;

mod cdp_tracker;
use cdp_tracker::{CdpTracker, DEFAULT_CDP_PORTS};

mod enforcement;
use enforcement::{DnsTestAssets, Enforcement};

mod ipc_server;
use ipc_server::IpcServer;

mod rules;
mod storage;
use storage::Storage;
mod tracker;
use tracker::SessionTracker;

fn main() -> Result<()> {
    // Daemon entrypoint (rule engine + enforcement).
    let args = parse_args();
    let config_path = args
        .config_path
        .unwrap_or_else(|| PathBuf::from("config/example.toml"));
    let config = load_config(&config_path)?;
    validate_config(&config)?;

    let enforcement = Enforcement::new(args.blocklist_conf_path.display().to_string());
    if let Some(path) = args.blocklist_path {
        let domains = load_domain_list(&path)?;
        enforcement.apply_blocklist(&domains)?;
        println!("applied blocklist from: {}", path.display());
        println!("domains: {}", domains.len());
    }

    if let Some(output_dir) = args.write_dns_test_assets_dir {
        let source = args
            .domains_path
            .clone()
            .unwrap_or_else(|| PathBuf::from("config/blocked-domains.txt"));
        let domains = load_domain_list(&source)?;
        let runtime_enforcement = Enforcement::new(
            output_dir
                .join("focusforlife-blocklist.conf")
                .display()
                .to_string(),
        );
        let assets = runtime_enforcement.write_dns_test_assets(
            &output_dir,
            &domains,
            args.unbound_blocklist_include_path.as_deref(),
            args.resolver_port,
        )?;
        print_dns_test_assets(&assets);
        return Ok(());
    }

    let db_path = PathBuf::from("/var/lib/focusforlife/ffl.sqlite");
    let storage = Storage::open(&db_path)?;
    let now = chrono::Local::now();
    let usage = storage.get_daily_usage(now.date_naive())?;
    let cooldown_end = storage.get_cooldown_end()?;
    let free_time_granted = storage
        .get_free_time_grant(now.date_naive())?
        .unwrap_or(false);

    let result = rules::evaluate(
        &config,
        rules::RuleInputs {
            now,
            usage,
            cooldown_end,
            free_time_granted,
        },
    )?;

    println!("ffl-daemon starting...");
    println!("loaded config from: {}", config_path.display());
    println!(
        "state: {:?}, used: {}/{}s, cooldown_remaining: {}s",
        result.state, result.daily_used_seconds, result.daily_quota_seconds, result.cooldown_remaining_seconds
    );

    if args.simulate_tracking {
        run_tracking_simulation(&config, &storage)?;
    }

    if args.browser_history_poll {
        let profile_root = args
            .browser_profile_root
            .unwrap_or_else(|| PathBuf::from("/home/user"));
        run_browser_history_tracking(&config, &storage, &profile_root, &enforcement)?;
    }

    if args.cdp_poll {
        let ports = if args.cdp_ports.is_empty() {
            DEFAULT_CDP_PORTS.to_vec()
        } else {
            args.cdp_ports
        };
        run_cdp_tracking(&config, &storage, ports, &enforcement)?;
    }

    if let Some(hit_stream) = args.hit_stream_path {
        run_live_tracking(&config, &storage, &hit_stream)?;
    }
    Ok(())
}

struct Args {
    config_path: Option<PathBuf>,
    blocklist_path: Option<PathBuf>,
    blocklist_conf_path: PathBuf,
    domains_path: Option<PathBuf>,
    simulate_tracking: bool,
    hit_stream_path: Option<PathBuf>,
    browser_history_poll: bool,
    browser_profile_root: Option<PathBuf>,
    cdp_poll: bool,
    cdp_ports: Vec<u16>,
    write_dns_test_assets_dir: Option<PathBuf>,
    unbound_blocklist_include_path: Option<String>,
    resolver_port: u16,
}

fn parse_args() -> Args {
    let mut config_path = None;
    let mut blocklist_path = None;
    let mut blocklist_conf_path = PathBuf::from("/etc/unbound/focusforlife/focusforlife-blocklist.conf");
    let mut domains_path = None;
    let mut simulate_tracking = false;
    let mut hit_stream_path = None;
    let mut browser_history_poll = false;
    let mut browser_profile_root = None;
    let mut cdp_poll = false;
    let mut cdp_ports: Vec<u16> = Vec::new();
    let mut write_dns_test_assets_dir = None;
    let mut unbound_blocklist_include_path = None;
    let mut resolver_port: u16 = 5335;

    let mut args = std::env::args().skip(1);
    while let Some(arg) = args.next() {
        match arg.as_str() {
            "--config" => {
                if let Some(path) = args.next() {
                    config_path = Some(PathBuf::from(path));
                }
            }
            "--blocklist" => {
                if let Some(path) = args.next() {
                    blocklist_path = Some(PathBuf::from(path));
                }
            }
            "--blocklist-conf" => {
                if let Some(path) = args.next() {
                    blocklist_conf_path = PathBuf::from(path);
                }
            }
            "--domains" => {
                if let Some(path) = args.next() {
                    domains_path = Some(PathBuf::from(path));
                }
            }
            "--simulate-tracking" => {
                simulate_tracking = true;
            }
            "--hit-stream" => {
                if let Some(path) = args.next() {
                    hit_stream_path = Some(PathBuf::from(path));
                }
            }
            "--browser-history-poll" => {
                browser_history_poll = true;
            }
            "--browser-profile-root" => {
                if let Some(path) = args.next() {
                    browser_profile_root = Some(PathBuf::from(path));
                }
            }
            "--write-dns-test-assets" => {
                if let Some(path) = args.next() {
                    write_dns_test_assets_dir = Some(PathBuf::from(path));
                }
            }
            "--unbound-blocklist-include-path" => {
                if let Some(value) = args.next() {
                    unbound_blocklist_include_path = Some(value);
                }
            }
            "--resolver-port" => {
                if let Some(value) = args.next() {
                    resolver_port = value.parse().unwrap_or(5335);
                }
            }
            "--cdp-poll" => {
                cdp_poll = true;
            }
            "--cdp-ports" => {
                // Comma-separated list of ports, e.g. "9222,9223"
                if let Some(value) = args.next() {
                    cdp_ports = value
                        .split(',')
                        .filter_map(|s| s.trim().parse().ok())
                        .collect();
                }
            }
            _ => {}
        }
    }

    Args {
        config_path,
        blocklist_path,
        blocklist_conf_path,
        domains_path,
        simulate_tracking,
        hit_stream_path,
        browser_history_poll,
        browser_profile_root,
        cdp_poll,
        cdp_ports,
        write_dns_test_assets_dir,
        unbound_blocklist_include_path,
        resolver_port,
    }
}

fn load_config(path: &PathBuf) -> Result<Config> {
    match fs::read_to_string(path) {
        Ok(contents) => {
            let config: Config = toml::from_str(&contents)
                .with_context(|| format!("failed to parse TOML: {}", path.display()))?;
            Ok(config)
        }
        Err(_) => Ok(Config::default()),
    }
}

fn load_domain_list(path: &PathBuf) -> Result<Vec<String>> {
    let contents = fs::read_to_string(path)
        .with_context(|| format!("failed to read domain list: {}", path.display()))?;
    let mut domains = Vec::new();
    for line in contents.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        domains.push(line.to_lowercase());
    }
    Ok(domains)
}

fn validate_config(config: &Config) -> Result<()> {
    validate_window("hard_block", &config.windows.hard_block)?;
    validate_window("free_time_evening", &config.windows.free_time_evening)?;
    validate_window("free_time_break", &config.windows.free_time_break)?;
    Ok(())
}

fn validate_window(name: &str, window: &ffl_shared::config::TimeWindow) -> Result<()> {
    let start = parse_hhmm(&window.start)
        .with_context(|| format!("invalid {} window start", name))?;
    let end = parse_hhmm(&window.end)
        .with_context(|| format!("invalid {} window end", name))?;
    if start == end {
        bail!("{} window start and end cannot be equal", name);
    }
    Ok(())
}

fn parse_hhmm(value: &str) -> Result<u32> {
    let parts: Vec<&str> = value.split(':').collect();
    if parts.len() != 2 {
        bail!("expected HH:MM");
    }
    let hour: u32 = parts[0].parse().context("invalid hour")?;
    let minute: u32 = parts[1].parse().context("invalid minute")?;
    if hour > 23 || minute > 59 {
        bail!("time out of range");
    }
    Ok(hour * 60 + minute)
}

fn run_tracking_simulation(config: &Config, storage: &Storage) -> Result<()> {
    let mut tracker = SessionTracker::new();
    let mut now = chrono::Local::now();

    // Simulate 5 minutes on-target, 2 minutes idle, 6 minutes on-target.
    for _ in 0..300 {
        tracker.tick(config, storage, now, true, 1)?;
        now = now + chrono::Duration::seconds(1);
    }
    for _ in 0..120 {
        tracker.tick(config, storage, now, false, 1)?;
        now = now + chrono::Duration::seconds(1);
    }
    for _ in 0..360 {
        tracker.tick(config, storage, now, true, 1)?;
        now = now + chrono::Duration::seconds(1);
    }
    Ok(())
}

fn run_live_tracking(config: &Config, storage: &Storage, hit_stream: &PathBuf) -> Result<()> {
    let domains = load_domain_list(&PathBuf::from("/etc/focusforlife/blocked-domains.txt")).unwrap_or_default();
    let domain_set: std::collections::HashSet<String> = domains.into_iter().collect();

    let (tx, rx) = mpsc::channel::<String>();
    let hit_stream = hit_stream.clone();
    thread::spawn(move || {
        let mut offset: u64 = 0;
        loop {
            if let Ok(mut file) = std::fs::File::open(&hit_stream) {
                let _ = file.seek(std::io::SeekFrom::Start(offset));
                let mut reader = std::io::BufReader::new(file);
                let mut line = String::new();
                loop {
                    line.clear();
                    let bytes = reader.read_line(&mut line).unwrap_or(0);
                    if bytes == 0 {
                        break;
                    }
                    offset += bytes as u64;
                    let domain = line.trim().to_lowercase();
                    if !domain.is_empty() {
                        let _ = tx.send(domain);
                    }
                }
            }
            thread::sleep(Duration::from_millis(200));
        }
    });

    let mut tracker = SessionTracker::new();
    let mut active_until: Option<chrono::DateTime<chrono::Local>> = None;
    let mut tick_count: u64 = 0;

    loop {
        let now = chrono::Local::now();
        while let Ok(domain) = rx.try_recv() {
            if domain_set.contains(&domain) {
                active_until = Some(now + chrono::Duration::seconds(config.rules.activity_grace_seconds as i64));
            }
        }

        let on_target = active_until.map(|t| t > now).unwrap_or(false);
        tracker.tick(config, storage, now, on_target, 1)?;

        tick_count += 1;
        if tick_count % 10 == 0 {
            let usage = storage.get_daily_usage(now.date_naive())?;
            println!("tracking... used={}s on_target={}", usage.used_seconds, on_target);
        }
        thread::sleep(Duration::from_secs(1));
    }
}

fn run_browser_history_tracking(
    config: &Config,
    storage: &Storage,
    profile_root: &PathBuf,
    enforcement: &Enforcement,
) -> Result<()> {
    let domains = load_domain_list(&PathBuf::from("/etc/focusforlife/blocked-domains.txt")).unwrap_or_default();
    let domain_set: std::collections::HashSet<String> = domains.iter().cloned().collect();
    let mut history = BrowserHistoryTracker::discover(profile_root)?;
    println!(
        "browser history tracking active from: {} (sources={})",
        profile_root.display(),
        history.source_count()
    );

    fs::create_dir_all("/run/focusforlife")?;
    let ipc = IpcServer::bind(Path::new("/run/focusforlife/daemon.sock"))?;
    println!("IPC socket bound at /run/focusforlife/daemon.sock");

    let mut tracker = SessionTracker::restore_from_storage(storage).unwrap_or_else(|_| SessionTracker::new());
    println!("session restored: used={}s", tracker.used_in_session());
    let mut active_until: Option<chrono::DateTime<chrono::Local>> = None;
    let mut tick_count: u64 = 0;
    // None = not yet applied; Some(true) = blocklist active; Some(false) = blocklist cleared.
    let mut last_blocked: Option<bool> = None;

    loop {
        let now = chrono::Local::now();
        for domain in history.poll_domains()? {
            if domain_matches_blocked(&domain, &domain_set) {
                active_until = Some(now + chrono::Duration::seconds(config.rules.activity_grace_seconds as i64));
            }
        }

        // Evaluate current rule state from persistent storage.
        let usage = storage.get_daily_usage(now.date_naive())?;
        let cooldown_end = storage.get_cooldown_end()?;
        let free_time_granted = storage
            .get_free_time_grant(now.date_naive())?
            .unwrap_or(false);
        let result = rules::evaluate(
            config,
            rules::RuleInputs { now, usage, cooldown_end, free_time_granted },
        )?;
        let is_blocked = !matches!(result.state, ffl_shared::ipc::FocusState::Allowed);

        // Apply or clear the DNS blocklist whenever the blocking state changes.
        if last_blocked != Some(is_blocked) {
            if is_blocked {
                println!("enforcement: applying blocklist ({} domains, state={:?})", domains.len(), result.state);
                if let Err(e) = enforcement.apply_blocklist(&domains) {
                    eprintln!("failed to apply blocklist: {e}");
                } else if let Err(e) = enforcement.reload_unbound() {
                    eprintln!("failed to reload unbound: {e}");
                }
            } else {
                println!("enforcement: clearing blocklist (state=allowed)");
                if let Err(e) = enforcement.apply_blocklist(&[]) {
                    eprintln!("failed to clear blocklist: {e}");
                } else if let Err(e) = enforcement.reload_unbound() {
                    eprintln!("failed to reload unbound: {e}");
                }
            }
            last_blocked = Some(is_blocked);
        }

        // Only count time on blocked sites when access is currently allowed.
        // This prevents failed navigation attempts (DNS-blocked) from ticking the timer.
        let on_target = !is_blocked && active_until.map(|t| t > now).unwrap_or(false);
        tracker.tick(config, storage, now, on_target, 1)?;

        // Broadcast status snapshot every 2 ticks.
        if tick_count % 2 == 0 {
            broadcast_status(config, storage, &tracker, now, &ipc);
        }

        // Free-time prompt: fires once when entering a window with no grant yet.
        handle_free_time_prompt(config, storage, now, &ipc)?;

        tick_count += 1;
        if tick_count % 10 == 0 {
            let usage = storage.get_daily_usage(now.date_naive())?;
            println!("history-tracking... used={}s on_target={} blocked={}", usage.used_seconds, on_target, is_blocked);
        }
        thread::sleep(Duration::from_secs(1));
    }
}

fn run_cdp_tracking(
    config: &Config,
    storage: &Storage,
    ports: Vec<u16>,
    enforcement: &Enforcement,
) -> Result<()> {
    let domains = load_domain_list(&PathBuf::from("/etc/focusforlife/blocked-domains.txt")).unwrap_or_default();
    let domain_set: std::collections::HashSet<String> = domains.iter().cloned().collect();
    let tracker_cdp = CdpTracker::new(ports.clone());

    println!("CDP tracking active on ports: {:?}", ports);

    fs::create_dir_all("/run/focusforlife")?;
    let ipc = IpcServer::bind(Path::new("/run/focusforlife/daemon.sock"))?;
    println!("IPC socket bound at /run/focusforlife/daemon.sock");

    let mut tracker = SessionTracker::restore_from_storage(storage).unwrap_or_else(|_| SessionTracker::new());
    println!("session restored: used={}s", tracker.used_in_session());
    let mut tick_count: u64 = 0;
    let mut last_blocked: Option<bool> = None;

    loop {
        let now = chrono::Local::now();

        // Check all open tabs right now — exact, no lag.
        let on_target_cdp = tracker_cdp
            .poll_domains()
            .iter()
            .any(|d| domain_matches_blocked(d, &domain_set));

        let usage = storage.get_daily_usage(now.date_naive())?;
        let cooldown_end = storage.get_cooldown_end()?;
        let free_time_granted = storage
            .get_free_time_grant(now.date_naive())?
            .unwrap_or(false);
        let result = rules::evaluate(
            config,
            rules::RuleInputs { now, usage, cooldown_end, free_time_granted },
        )?;
        let is_blocked = !matches!(result.state, ffl_shared::ipc::FocusState::Allowed);

        if last_blocked != Some(is_blocked) {
            if is_blocked {
                println!("enforcement: applying blocklist ({} domains, state={:?})", domains.len(), result.state);
                if let Err(e) = enforcement.apply_blocklist(&domains) {
                    eprintln!("failed to apply blocklist: {e}");
                } else if let Err(e) = enforcement.reload_unbound() {
                    eprintln!("failed to reload unbound: {e}");
                }
            } else {
                println!("enforcement: clearing blocklist (state=allowed)");
                if let Err(e) = enforcement.apply_blocklist(&[]) {
                    eprintln!("failed to clear blocklist: {e}");
                } else if let Err(e) = enforcement.reload_unbound() {
                    eprintln!("failed to reload unbound: {e}");
                }
            }
            last_blocked = Some(is_blocked);
        }

        // Only count time when access is allowed and a blocked site is open right now.
        let on_target = !is_blocked && on_target_cdp;
        tracker.tick(config, storage, now, on_target, 1)?;

        if tick_count % 2 == 0 {
            broadcast_status(config, storage, &tracker, now, &ipc);
        }

        handle_free_time_prompt(config, storage, now, &ipc)?;

        tick_count += 1;
        if tick_count % 10 == 0 {
            let usage = storage.get_daily_usage(now.date_naive())?;
            println!("cdp-tracking... used={}s on_target={} blocked={}", usage.used_seconds, on_target, is_blocked);
        }
        thread::sleep(Duration::from_secs(1));
    }
}

fn broadcast_status(
    config: &Config,
    storage: &Storage,
    tracker: &SessionTracker,
    now: chrono::DateTime<chrono::Local>,
    ipc: &IpcServer,
) {
    let Ok(usage) = storage.get_daily_usage(now.date_naive()) else { return };
    let Ok(cooldown_end) = storage.get_cooldown_end() else { return };
    let free_time_granted = storage
        .get_free_time_grant(now.date_naive())
        .ok()
        .flatten()
        .unwrap_or(false);
    let Ok(result) = rules::evaluate(
        config,
        rules::RuleInputs { now, usage, cooldown_end, free_time_granted },
    ) else { return };
    let snap = StatusSnapshot {
        state: result.state,
        daily_used_seconds: result.daily_used_seconds,
        daily_quota_seconds: result.daily_quota_seconds,
        session_used_seconds: tracker.used_in_session(),
        session_limit_seconds: config.rules.continuous_limit_minutes * 60,
        cooldown_remaining_seconds: result.cooldown_remaining_seconds,
    };
    ipc.broadcast(&DaemonEvent::Status(snap)).ok();
}

fn handle_free_time_prompt(
    config: &Config,
    storage: &Storage,
    now: chrono::DateTime<chrono::Local>,
    ipc: &IpcServer,
) -> Result<()> {
    let today = now.date_naive();
    let in_evening = rules::in_window(&config.windows.free_time_evening, now)?;
    let in_break = rules::in_window(&config.windows.free_time_break, now)?;

    if !(in_evening || in_break) || storage.get_free_time_grant(today)?.is_some() {
        return Ok(());
    }

    let policy = if in_evening {
        &config.prompts.free_time_evening_prompt
    } else {
        &config.prompts.free_time_break_prompt
    };
    let default_grant = matches!(policy, PromptPolicy::DefaultYes);
    let prompt_id = if in_evening { "free_time_evening" } else { "free_time_break" };

    ipc.broadcast(&DaemonEvent::Prompt(PromptRequest {
        id: prompt_id.to_string(),
        title: "Free time?".to_string(),
        message: "Are you done with work for this session?".to_string(),
        timeout_seconds: config.prompts.prompt_timeout_seconds,
        default_grant,
    }))
    .ok();

    let deadline = chrono::Local::now()
        + chrono::Duration::seconds(config.prompts.prompt_timeout_seconds as i64);
    let granted = loop {
        if let Some(v) = ipc.poll_response(prompt_id) {
            break v;
        }
        if chrono::Local::now() >= deadline {
            break default_grant;
        }
        thread::sleep(Duration::from_millis(200));
    };

    storage.set_free_time_grant(today, granted)?;
    println!(
        "free-time grant for {}: {}",
        prompt_id,
        if granted { "yes" } else { "no" }
    );
    Ok(())
}

fn domain_matches_blocked(
    observed_domain: &str,
    blocked_domains: &std::collections::HashSet<String>,
) -> bool {
    if blocked_domains.contains(observed_domain) {
        return true;
    }

    blocked_domains.iter().any(|blocked| {
        observed_domain == format!("www.{blocked}") || observed_domain.ends_with(&format!(".{blocked}"))
    })
}

fn print_dns_test_assets(assets: &DnsTestAssets) {
    println!("dns test assets written");
    println!("output_dir: {}", assets.output_dir.display());
    println!("unbound_config: {}", assets.unbound_config_path.display());
    println!("blocklist: {}", assets.blocklist_path.display());
    println!("nft_rules: {}", assets.nft_rules_path.display());
    println!("resolver_port: {}", assets.resolver_port);
}
