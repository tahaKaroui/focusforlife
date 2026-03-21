use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum DaemonEvent {
    Status(StatusSnapshot),
    Prompt(PromptRequest),
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum UiEvent {
    PromptResponse(PromptResponse),
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StatusSnapshot {
    pub state: FocusState,
    pub daily_used_seconds: u32,
    pub daily_quota_seconds: u32,
    pub hourly_used_seconds: u32,
    pub hourly_limit_seconds: u32,
    pub cooldown_remaining_seconds: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum FocusState {
    Allowed,
    BlockedHardWindow,
    BlockedCooldown,
    BlockedQuota,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PromptRequest {
    pub id: String,
    pub title: String,
    pub message: String,
    pub timeout_seconds: u32,
    pub default_grant: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PromptResponse {
    pub id: String,
    pub accepted: bool,
}
