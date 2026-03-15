use std::fs;
use std::path::{Path, PathBuf};
use std::time::SystemTime;

use anyhow::{Context, Result};
use rusqlite::Connection;
use url::Url;

pub struct BrowserHistoryTracker {
    sources: Vec<HistorySource>,
}

impl BrowserHistoryTracker {
    pub fn discover(profile_root: &Path) -> Result<Self> {
        let mut sources = Vec::new();

        discover_chromium_sources(
            &profile_root.join(".config/BraveSoftware/Brave-Browser"),
            &mut sources,
        );
        discover_chromium_sources(
            &profile_root.join(".config/google-chrome"),
            &mut sources,
        );
        discover_chromium_sources(
            &profile_root.join(".config/chromium"),
            &mut sources,
        );
        discover_firefox_sources(
            &profile_root.join(".mozilla/firefox"),
            &mut sources,
        );

        let mut tracker = Self { sources };
        tracker.prime()?;
        Ok(tracker)
    }

    pub fn source_count(&self) -> usize {
        self.sources.len()
    }

    pub fn poll_domains(&mut self) -> Result<Vec<String>> {
        let mut domains = Vec::new();

        for source in &mut self.sources {
            if !source.path.exists() {
                continue;
            }

            match source.read_new_domains() {
                Ok(mut found) => domains.append(&mut found),
                Err(err) => {
                    eprintln!(
                        "browser history poll failed for {}: {err}",
                        source.path.display()
                    );
                }
            }
        }

        Ok(domains)
    }

    fn prime(&mut self) -> Result<()> {
        for source in &mut self.sources {
            if !source.path.exists() {
                continue;
            }

            match source.max_visit_time() {
                Ok(Some(value)) => source.last_seen_visit_time = value,
                Ok(None) => {}
                Err(err) => {
                    eprintln!(
                        "browser history prime failed for {}: {err}",
                        source.path.display()
                    );
                }
            }
        }
        Ok(())
    }
}

struct HistorySource {
    path: PathBuf,
    kind: HistoryKind,
    last_seen_visit_time: i64,
}

impl HistorySource {
    fn read_new_domains(&mut self) -> Result<Vec<String>> {
        let temp_path = temp_copy_path(&self.path);
        fs::copy(&self.path, &temp_path).with_context(|| {
            format!(
                "failed to copy browser history db: {}",
                self.path.display()
            )
        })?;
        // Copy WAL and SHM files so SQLite sees uncommitted recent writes.
        // Chromium uses WAL mode, so successful navigations land in the WAL
        // file first and only reach the main DB after a checkpoint.
        copy_wal_files(&self.path, &temp_path);

        let result = (|| -> Result<Vec<String>> {
            let conn = Connection::open(&temp_path)?;
            let mut domains = Vec::new();

            match self.kind {
                HistoryKind::Chromium => {
                    let mut stmt = conn.prepare(
                        "select url, last_visit_time
                         from urls
                         where last_visit_time > ?1
                         order by last_visit_time asc",
                    )?;
                    let rows = stmt.query_map([self.last_seen_visit_time], |row| {
                        let url: String = row.get(0)?;
                        let last_visit_time: i64 = row.get(1)?;
                        Ok((url, last_visit_time))
                    })?;

                    for row in rows {
                        let (url, visit_time) = row?;
                        self.last_seen_visit_time = self.last_seen_visit_time.max(visit_time);
                        if let Some(domain) = extract_domain(&url) {
                            domains.push(domain);
                        }
                    }
                }
                HistoryKind::Firefox => {
                    let mut stmt = conn.prepare(
                        "select url, last_visit_date
                         from moz_places
                         where last_visit_date is not null and last_visit_date > ?1
                         order by last_visit_date asc",
                    )?;
                    let rows = stmt.query_map([self.last_seen_visit_time], |row| {
                        let url: String = row.get(0)?;
                        let last_visit_time: i64 = row.get(1)?;
                        Ok((url, last_visit_time))
                    })?;

                    for row in rows {
                        let (url, visit_time) = row?;
                        self.last_seen_visit_time = self.last_seen_visit_time.max(visit_time);
                        if let Some(domain) = extract_domain(&url) {
                            domains.push(domain);
                        }
                    }
                }
            }

            Ok(domains)
        })();

        let _ = fs::remove_file(&temp_path);
        remove_wal_files(&temp_path);
        result
    }

    fn max_visit_time(&self) -> Result<Option<i64>> {
        let temp_path = temp_copy_path(&self.path);
        fs::copy(&self.path, &temp_path).with_context(|| {
            format!(
                "failed to copy browser history db for priming: {}",
                self.path.display()
            )
        })?;
        copy_wal_files(&self.path, &temp_path);

        let result = (|| -> Result<Option<i64>> {
            let conn = Connection::open(&temp_path)?;
            let query = match self.kind {
                HistoryKind::Chromium => "select max(last_visit_time) from urls",
                HistoryKind::Firefox => {
                    "select max(last_visit_date) from moz_places where last_visit_date is not null"
                }
            };
            let value = conn.query_row(query, [], |row| row.get::<_, Option<i64>>(0))?;
            Ok(value)
        })();

        let _ = fs::remove_file(&temp_path);
        remove_wal_files(&temp_path);
        result
    }
}

#[derive(Clone, Copy)]
enum HistoryKind {
    Chromium,
    Firefox,
}

fn discover_chromium_sources(base_dir: &Path, sources: &mut Vec<HistorySource>) {
    if !base_dir.exists() {
        return;
    }

    if let Ok(entries) = fs::read_dir(base_dir) {
        for entry in entries.flatten() {
            let profile_dir = entry.path();
            if !profile_dir.is_dir() {
                continue;
            }
            let history_path = profile_dir.join("History");
            if history_path.exists() {
                sources.push(HistorySource {
                    path: history_path,
                    kind: HistoryKind::Chromium,
                    last_seen_visit_time: 0,
                });
            }
        }
    }
}

fn discover_firefox_sources(base_dir: &Path, sources: &mut Vec<HistorySource>) {
    if !base_dir.exists() {
        return;
    }

    if let Ok(entries) = fs::read_dir(base_dir) {
        for entry in entries.flatten() {
            let profile_dir = entry.path();
            if !profile_dir.is_dir() {
                continue;
            }
            let history_path = profile_dir.join("places.sqlite");
            if history_path.exists() {
                sources.push(HistorySource {
                    path: history_path,
                    kind: HistoryKind::Firefox,
                    last_seen_visit_time: 0,
                });
            }
        }
    }
}

fn extract_domain(raw_url: &str) -> Option<String> {
    let parsed = Url::parse(raw_url).ok()?;
    let host = parsed.host_str()?.trim().to_lowercase();
    if host.is_empty() {
        return None;
    }
    Some(host)
}

/// Copy the SQLite WAL and SHM companion files from `src` to `dst` (same
/// base name with `-wal` / `-shm` suffix appended).  Failures are silently
/// ignored — if the files don't exist there's nothing to copy.
fn copy_wal_files(src: &Path, dst: &Path) {
    for suffix in ["-wal", "-shm"] {
        let src_companion = PathBuf::from(format!("{}{suffix}", src.display()));
        let dst_companion = PathBuf::from(format!("{}{suffix}", dst.display()));
        if src_companion.exists() {
            let _ = fs::copy(&src_companion, &dst_companion);
        }
    }
}

fn remove_wal_files(path: &Path) {
    for suffix in ["-wal", "-shm"] {
        let companion = PathBuf::from(format!("{}{suffix}", path.display()));
        let _ = fs::remove_file(companion);
    }
}

fn temp_copy_path(source_path: &Path) -> PathBuf {
    let timestamp = SystemTime::now()
        .duration_since(SystemTime::UNIX_EPOCH)
        .map(|d| d.as_nanos())
        .unwrap_or(0);
    let file_name = source_path
        .file_name()
        .and_then(|value| value.to_str())
        .unwrap_or("history.sqlite");
    std::env::temp_dir().join(format!(
        "ffl-history-{}-{}",
        timestamp, file_name
    ))
}
