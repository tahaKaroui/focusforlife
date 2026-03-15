# UI Contract (v1)

This defines the messages between daemon and UI.

## Daemon -> UI
- `DaemonEvent::Status`
  - Snapshot of current focus state and remaining time.
- `DaemonEvent::Prompt`
  - Request for a user decision (e.g., 21:00 free-time gate).

## UI -> Daemon
- `UiEvent::PromptResponse`
  - User response to a prompt.

## Serialization
- JSON via serde.
- Message types are tagged with `type` and `snake_case` names.

See `crates/shared/src/ipc.rs` for canonical structs.
