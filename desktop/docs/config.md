# Config Format (v1)

Format: TOML

Example: `config/example.toml`

## Notes
- Times are local, 24-hour "HH:MM".
- Windows can span midnight (e.g., 23:00–11:00).
- `free_time_evening_prompt` and `free_time_break_prompt` accept: `ask`, `default_yes`, `default_no`.
- `activity_grace_seconds` defines how long a DNS hit keeps a session active.
