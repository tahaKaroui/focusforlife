use anyhow::Result;
use chrono::{DateTime, Local, Duration};

use crate::storage::Storage;
use ffl_shared::config::Config;

#[derive(Debug, Clone, Copy)]
pub enum EndedBy {
    Idle,
    Limit,
    Forced,
}

pub struct SessionTracker {
    session_start: Option<DateTime<Local>>,
    last_seen: Option<DateTime<Local>>,
    used_in_session: u32,
}

impl SessionTracker {
    pub fn new() -> Self {
        Self {
            session_start: None,
            last_seen: None,
            used_in_session: 0,
        }
    }

    /// Restore an in-progress session from the DB after a daemon restart.
    pub fn restore_from_storage(storage: &Storage) -> Result<Self> {
        if let Some((start_at, used_seconds)) = storage.load_current_session()? {
            Ok(Self {
                session_start: Some(start_at),
                last_seen: Some(chrono::Local::now()),
                used_in_session: used_seconds,
            })
        } else {
            Ok(Self::new())
        }
    }

    pub fn tick(
        &mut self,
        config: &Config,
        storage: &Storage,
        now: DateTime<Local>,
        on_target: bool,
        delta_seconds: u32,
    ) -> Result<()> {
        if on_target {
            if self.session_start.is_none() {
                self.session_start = Some(now);
                self.used_in_session = 0;
                storage.increment_sessions_count(now.date_naive())?;
            }
            self.last_seen = Some(now);
            self.used_in_session = self.used_in_session.saturating_add(delta_seconds);
            storage.add_daily_usage_seconds(now.date_naive(), delta_seconds)?;
            storage.save_current_session(self.session_start.unwrap(), self.used_in_session)?;

            let limit_seconds = config.rules.continuous_limit_minutes * 60;
            if self.used_in_session >= limit_seconds {
                let end_at = now;
                if let Some(start_at) = self.session_start {
                    storage.record_session(start_at, end_at, self.used_in_session, "limit")?;
                }
                storage.clear_current_session()?;
                let cooldown_end = now + Duration::minutes(config.rules.cooldown_minutes as i64);
                storage.set_cooldown_end(cooldown_end)?;
                self.session_start = None;
                self.last_seen = None;
            }
            return Ok(());
        }

        if let Some(last_seen) = self.last_seen {
            let idle_seconds = (now - last_seen).num_seconds().max(0) as u32;
            if idle_seconds >= config.rules.idle_tolerance_seconds {
                let end_at = now;
                if let Some(start_at) = self.session_start {
                    storage.record_session(start_at, end_at, self.used_in_session, "idle")?;
                }
                storage.clear_current_session()?;
                self.reset();
            }
        }
        Ok(())
    }

    pub fn used_in_session(&self) -> u32 {
        self.used_in_session
    }

    pub fn force_end(&mut self, storage: &Storage, now: DateTime<Local>) -> Result<()> {
        if let Some(start_at) = self.session_start {
            storage.record_session(start_at, now, self.used_in_session, "forced")?;
        }
        storage.clear_current_session()?;
        self.reset();
        Ok(())
    }

    fn reset(&mut self) {
        self.session_start = None;
        self.last_seen = None;
        self.used_in_session = 0;
    }
}
