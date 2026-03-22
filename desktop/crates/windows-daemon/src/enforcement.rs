/// Windows domain enforcement via the hosts file.
///
/// Blocked domains are injected as "0.0.0.0 <domain>" entries between
/// two marker comments. When blocking is lifted the block is removed and
/// `ipconfig /flushdns` is called so cached 0.0.0.0 entries don't linger.
///
/// Use 0.0.0.0 (not 127.0.0.1 and not NXDOMAIN): Brave/Chrome treats
/// NXDOMAIN as a resolver failure and retries via DNS-over-HTTPS, bypassing
/// the block. A null-address answer is valid so DoH fallback is never triggered.
///
/// Requires the process to run as Administrator (Task Scheduler "Run as Admin").

use anyhow::{Context, Result};
use std::fs;

const HOSTS_PATH: &str = r"C:\Windows\System32\drivers\etc\hosts";
const BLOCK_START: &str = "# --- FocusForLife start ---";
const BLOCK_END: &str = "# --- FocusForLife end ---";

pub struct Enforcement;

impl Enforcement {
    pub fn new() -> Self {
        Self
    }

    /// Inject or replace the FocusForLife block in the hosts file.
    /// Pass an empty slice to remove all entries (unblock).
    pub fn apply_blocklist(&self, domains: &[String]) -> Result<()> {
        let existing = fs::read_to_string(HOSTS_PATH)
            .unwrap_or_default();
        let base = strip_ffl_block(&existing);

        let mut new_content = base;
        if !domains.is_empty() {
            // Ensure a blank line before our block.
            if !new_content.ends_with('\n') {
                new_content.push('\n');
            }
            new_content.push('\n');
            new_content.push_str(BLOCK_START);
            new_content.push('\n');
            for domain in domains {
                let domain = domain.trim().to_lowercase();
                if domain.is_empty() || domain.starts_with('#') {
                    continue;
                }
                new_content.push_str(&format!("0.0.0.0 {domain}\n"));
                new_content.push_str(&format!("0.0.0.0 www.{domain}\n"));
            }
            new_content.push_str(BLOCK_END);
            new_content.push('\n');
        }

        // Atomic write: write to a temp file then rename.
        let tmp_path = format!("{HOSTS_PATH}.ffl.tmp");
        fs::write(&tmp_path, &new_content)
            .with_context(|| format!("failed to write temp hosts file: {tmp_path}"))?;
        fs::rename(&tmp_path, HOSTS_PATH)
            .with_context(|| "failed to rename temp hosts file")?;

        // Flush the Windows DNS resolver cache so 0.0.0.0 doesn't linger
        // after we unblock, and so new entries take effect immediately.
        let _ = std::process::Command::new("ipconfig")
            .arg("/flushdns")
            .output();

        Ok(())
    }
}

/// Remove everything between (and including) our start/end markers.
fn strip_ffl_block(content: &str) -> String {
    let mut result = String::with_capacity(content.len());
    let mut in_block = false;
    for line in content.lines() {
        let trimmed = line.trim();
        if trimmed == BLOCK_START {
            in_block = true;
            continue;
        }
        if trimmed == BLOCK_END {
            in_block = false;
            continue;
        }
        if !in_block {
            result.push_str(line);
            result.push('\n');
        }
    }
    // Remove trailing blank lines added by previous runs.
    result.trim_end_matches('\n').to_string() + "\n"
}
