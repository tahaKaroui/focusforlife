use anyhow::Result;
use chrono::{DateTime, Datelike, Local, Timelike};

use crate::storage::Storage;

/// Tracks usage within hourly buckets. Resets at each clock-hour boundary.
/// Matches the Android FocusRules.kt and Linux daemon hourly model.
pub struct HourlyTracker {
    current_stamp: u64,
    used_seconds: u32,
}

impl HourlyTracker {
    pub fn new() -> Self {
        Self {
            current_stamp: 0,
            used_seconds: 0,
        }
    }

    /// Restore hourly bucket state from the DB after a daemon restart.
    pub fn restore_from_storage(storage: &Storage) -> Result<Self> {
        let (stamp, used) = storage.get_hourly_bucket()?;
        Ok(Self {
            current_stamp: stamp,
            used_seconds: used,
        })
    }

    pub fn tick(
        &mut self,
        storage: &Storage,
        now: DateTime<Local>,
        on_target: bool,
        delta_seconds: u32,
    ) -> Result<()> {
        let stamp = hour_stamp(now);

        // Hour rolled over — reset the bucket.
        if stamp != self.current_stamp {
            self.current_stamp = stamp;
            self.used_seconds = 0;
        }

        if on_target {
            self.used_seconds = self.used_seconds.saturating_add(delta_seconds);
            storage.add_daily_usage_seconds(now.date_naive(), delta_seconds)?;
            storage.set_hourly_bucket(stamp, self.used_seconds)?;
        }

        Ok(())
    }

    pub fn hourly_used_seconds(&self) -> u32 {
        self.used_seconds
    }

    pub fn current_hour_stamp(&self) -> u64 {
        self.current_stamp
    }
}

/// Unique identifier for a clock hour: year * 1_000_000 + day_of_year * 100 + hour.
/// Must be identical on Linux, Android, and Windows for cross-device sync to work.
pub fn hour_stamp(now: DateTime<Local>) -> u64 {
    let year = now.year() as u64;
    let day = now.ordinal() as u64;
    let hour = now.hour() as u64;
    year * 1_000_000 + day * 100 + hour
}

/// Seconds remaining until the next clock-hour boundary.
pub fn seconds_until_next_hour(now: DateTime<Local>) -> u32 {
    let secs = now.second();
    let mins = now.minute();
    3600 - (mins * 60 + secs)
}
