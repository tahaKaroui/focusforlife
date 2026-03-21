use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Config {
    pub rules: Rules,
    pub windows: Windows,
    pub prompts: Prompts,
    pub logging: Logging,
    #[serde(default)]
    pub sync: Sync,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Sync {
    /// Firebase Realtime Database URL.
    pub firebase_db_url: String,
    /// This device's ID (e.g., "linux", "windows").
    pub device_id: String,
    /// How often to push/pull sync state (seconds).
    pub sync_interval_seconds: u32,
}

impl Default for Sync {
    fn default() -> Self {
        Self {
            firebase_db_url: String::new(),
            device_id: "linux".to_string(),
            sync_interval_seconds: 10,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Rules {
    /// Daily quota across all blocked targets (minutes).
    pub daily_quota_minutes: u32,
    /// Per-hour usage limit before cooldown until next hour (minutes).
    pub hourly_limit_minutes: u32,
    /// Grace window after a hit to consider user "on target" (seconds).
    pub activity_grace_seconds: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Windows {
    /// Hard block window (sleep time), local time.
    pub hard_block: TimeWindow,
    /// Evening free-time window, local time.
    pub free_time_evening: TimeWindow,
    /// Afternoon break free-time window, local time.
    pub free_time_break: TimeWindow,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Prompts {
    /// Evening free-time gate prompt behavior at window start (21:00).
    pub free_time_evening_prompt: PromptPolicy,
    /// Afternoon break free-time gate prompt behavior at window start (15:00).
    pub free_time_break_prompt: PromptPolicy,
    /// Seconds to wait for response before applying default.
    pub prompt_timeout_seconds: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Logging {
    /// Log level (e.g., "info", "debug").
    pub level: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TimeWindow {
    /// Start time in HH:MM (24h).
    pub start: String,
    /// End time in HH:MM (24h). Can span midnight.
    pub end: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum PromptPolicy {
    /// Show a prompt; "yes" enables free time.
    Ask,
    /// Treat no response as yes.
    DefaultYes,
    /// Treat no response as no.
    DefaultNo,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            rules: Rules {
                daily_quota_minutes: 60,
                hourly_limit_minutes: 10,
                activity_grace_seconds: 30,
            },
            windows: Windows {
                hard_block: TimeWindow {
                    start: "23:00".to_string(),
                    end: "11:00".to_string(),
                },
                free_time_evening: TimeWindow {
                    start: "21:00".to_string(),
                    end: "23:00".to_string(),
                },
                free_time_break: TimeWindow {
                    start: "15:00".to_string(),
                    end: "16:00".to_string(),
                },
            },
            prompts: Prompts {
                free_time_evening_prompt: PromptPolicy::DefaultYes,
                free_time_break_prompt: PromptPolicy::DefaultYes,
                prompt_timeout_seconds: 60,
            },
            logging: Logging {
                level: "info".to_string(),
            },
            sync: Sync {
                firebase_db_url: String::new(),
                device_id: "linux".to_string(),
                sync_interval_seconds: 10,
            },
        }
    }
}
