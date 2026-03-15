# Systemd Install Plan (High-Level)

## System services
- Install `ffl-daemon.service` and `ffl-watcher.service` as system units.
- Place binaries at `/usr/local/bin/ffl-daemon` and `/usr/local/bin/ffl-watcher`.

## User service
- Install `ffl-ui.service` as a user unit.
- Place binary at `/usr/local/bin/ffl-ui`.

## Notes
- Exact hardening (capabilities, user/group, filesystem locks) will be refined after core functionality is stable.
- Keep logging via journald for early debugging.
