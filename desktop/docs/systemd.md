# Systemd Services (v1)

## Services
- ffl-daemon.service
  - Runs the root daemon that enforces rules, manages DNS/firewall, and tracks usage.
- ffl-watcher.service
  - Lightweight watchdog that verifies enforcement state and restores if drift is detected.
- ffl-ui.service (optional user service)
  - Always-on-top overlay and prompt handling.

## Boot flow (high-level)
1. ffl-daemon starts at boot (system service).
2. ffl-watcher starts after ffl-daemon.
3. ffl-ui starts at user login.

## Health signals
- Daemon emits heartbeats and status for UI consumption.
- Watcher checks expected DNS/firewall state on a fixed interval.

## Logs
- System logs via journald for daemon/watcher.
- UI logs in user session.
