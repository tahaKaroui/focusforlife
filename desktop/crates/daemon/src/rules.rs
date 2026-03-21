use anyhow::{bail, Result};
use chrono::{DateTime, Local, NaiveTime};
use ffl_shared::config::Config;
use ffl_shared::ipc::FocusState;

use crate::storage::DailyUsage;
use crate::tracker::seconds_until_next_hour;

#[derive(Debug, Clone)]
pub struct RuleInputs {
    pub now: DateTime<Local>,
    pub usage: DailyUsage,
    pub hourly_used_seconds: u32,
    pub free_time_granted: bool,
}

#[derive(Debug, Clone)]
pub struct RuleResult {
    pub state: FocusState,
    pub daily_quota_seconds: u32,
    pub daily_used_seconds: u32,
    pub cooldown_remaining_seconds: u32,
}

pub fn evaluate(config: &Config, input: RuleInputs) -> Result<RuleResult> {
    let daily_quota_seconds = config.rules.daily_quota_minutes * 60;
    let daily_used_seconds = input.usage.used_seconds;
    let hourly_limit_seconds = config.rules.hourly_limit_minutes * 60;

    // 1. Hard block window (sleep time).
    let hard_block = in_window(&config.windows.hard_block, input.now)?;
    if hard_block {
        return Ok(RuleResult {
            state: FocusState::BlockedHardWindow,
            daily_quota_seconds,
            daily_used_seconds,
            cooldown_remaining_seconds: 0,
        });
    }

    // 2. Daily quota exhausted.
    if daily_used_seconds >= daily_quota_seconds {
        return Ok(RuleResult {
            state: FocusState::BlockedQuota,
            daily_quota_seconds,
            daily_used_seconds,
            cooldown_remaining_seconds: 0,
        });
    }

    // 3. Hourly limit hit — cooldown until next hour boundary.
    if input.hourly_used_seconds >= hourly_limit_seconds {
        let remaining = seconds_until_next_hour(input.now);
        return Ok(RuleResult {
            state: FocusState::BlockedCooldown,
            daily_quota_seconds,
            daily_used_seconds,
            cooldown_remaining_seconds: remaining,
        });
    }

    // 4. During a granted free-time window, quota is suspended.
    let in_free_time = in_window(&config.windows.free_time_evening, input.now)?
        || in_window(&config.windows.free_time_break, input.now)?;
    if in_free_time && input.free_time_granted {
        return Ok(RuleResult {
            state: FocusState::Allowed,
            daily_quota_seconds,
            daily_used_seconds,
            cooldown_remaining_seconds: 0,
        });
    }

    Ok(RuleResult {
        state: FocusState::Allowed,
        daily_quota_seconds,
        daily_used_seconds,
        cooldown_remaining_seconds: 0,
    })
}

pub(crate) fn in_window(window: &ffl_shared::config::TimeWindow, now: DateTime<Local>) -> Result<bool> {
    let start = parse_hhmm(&window.start)?;
    let end = parse_hhmm(&window.end)?;
    let now_time = now.time();

    if start == end {
        bail!("window start and end cannot be equal");
    }

    if start < end {
        Ok(now_time >= start && now_time < end)
    } else {
        // spans midnight
        Ok(now_time >= start || now_time < end)
    }
}

fn parse_hhmm(value: &str) -> Result<NaiveTime> {
    let parts: Vec<&str> = value.split(':').collect();
    if parts.len() != 2 {
        bail!("expected HH:MM");
    }
    let hour: u32 = parts[0].parse()?;
    let minute: u32 = parts[1].parse()?;
    if hour > 23 || minute > 59 {
        bail!("time out of range");
    }
    if let Some(t) = NaiveTime::from_hms_opt(hour, minute, 0) {
        Ok(t)
    } else {
        bail!("invalid time")
    }
}
