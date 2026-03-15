use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Config {
    pub rules: Rules,
    pub windows: Windows,
    pub prompts: Prompts,
    pub logging: Logging,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Rules {
    /// Daily quota across all blocked targets (minutes).
    pub daily_quota_minutes: u32,
    /// Continuous limit before cooldown (minutes).
    pub continuous_limit_minutes: u32,
    /// Cooldown duration after continuous limit (minutes).
    pub cooldown_minutes: u32,
    /// Idle tolerance before ending a session (seconds).
    pub idle_tolerance_seconds: u32,
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
                continuous_limit_minutes: 10,
                cooldown_minutes: 60,
                idle_tolerance_seconds: 90,
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
        }
    }
}
