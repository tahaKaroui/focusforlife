# Systemd Install Plan (High-Level)

## System services
- Install `ffl-daemon.service` and `ffl-watcher.service` as system units.
- Place binaries at `/usr/local/bin/ffl-daemon` and `/usr/local/bin/ffl-watcher`.

## User service
- Install `ffl-ui.service` as a user unit.
- Place binary at `/usr/local/bin/ffl-ui`.

## Resilience (and the deliberate off switch)

FocusForLife is built to be hard for your impulsive self to disable. Three
independent "resurrection" sources keep enforcement alive:

1. **systemd `Restart=always` with no rate limit** (`StartLimitIntervalSec=0`) on
   `ffl-daemon` and `ffl-watcher`, so a plain `kill` respawns instantly, forever.
2. **A mutual watchdog pair** — `ffl-watcher` revives and re-enables `ffl-daemon`
   every ~2s, and the daemon's guardian thread does the same for `ffl-watcher`.
   So `systemctl stop ffl-daemon` (or `disable`) is undone within seconds.
3. **`ffl-guardian.timer`** — a separate timer that re-starts both every ~15s,
   which defeats even "stop both in one command."

Install all of them:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now ffl-daemon ffl-watcher ffl-guardian.timer
```

> **Honest limit:** on a machine where you have root, nothing is truly
> bypass-proof (you can always boot to recovery). The goal is to raise the
> friction past your impulse threshold, not to be unbreakable.

**The deliberate off switch** (kept reliable on purpose — never auto-undone):

```bash
sudo systemctl disable --now ffl-guardian.timer ffl-daemon ffl-watcher
```

Disabling all three in one command stops them together, so nothing is left alive
to revive the others. That is the sanctioned, high-friction way to turn it off.

## Notes
- Exact hardening (capabilities, user/group, filesystem locks) will be refined after core functionality is stable.
- Keep logging via journald for early debugging.
