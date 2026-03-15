use std::process::Command;
use std::thread;
use std::time::Duration;

fn main() {
    println!("ffl-watcher starting (check interval: 30s)");
    loop {
        if let Err(e) = check_and_repair() {
            eprintln!("watcher error: {e}");
        }
        thread::sleep(Duration::from_secs(30));
    }
}

fn check_and_repair() -> anyhow::Result<()> {
    // Check 1: is the resolver (unbound) running?
    let out = Command::new("systemctl")
        .args(["is-active", "unbound"])
        .output()?;
    if out.stdout.trim_ascii() != b"active" {
        eprintln!("unbound not active — restarting ffl-resolver");
        Command::new("systemctl")
            .args(["restart", "ffl-resolver"])
            .status()?;
    }

    // Check 2: does nftables have the focusforlife chain loaded?
    let out = Command::new("nft").args(["list", "ruleset"]).output()?;
    let ruleset = String::from_utf8_lossy(&out.stdout);
    if !ruleset.contains("focusforlife") {
        eprintln!("focusforlife nftables chain missing — restarting ffl-firewall");
        Command::new("systemctl")
            .args(["restart", "ffl-firewall"])
            .status()?;
    }

    Ok(())
}
