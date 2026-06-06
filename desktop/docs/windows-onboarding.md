# FocusForLife — Windows App (Phase 3)

## Context

FocusForLife is a cross-device distraction blocker. Linux (Rust) and Android
(Kotlin) are already done and syncing in real time via Firebase Realtime
Database. This doc tells you everything you need to start the Windows app from
scratch.

---

## Shared contract all platforms must respect

### Timer model
| Parameter | Value |
|---|---|
| Daily quota | 3600 s (1 h) shared across all devices |
| Hourly bucket | 600 s (10 min) per clock-hour; resets at :00 |
| Hourly cooldown | If bucket exhausted → block until next :00 |
| Hard-block window | 23:30 → 11:00 (no access at all) |
| Block priority | hard-window > daily-quota > hourly-cooldown > allowed |

### hour_stamp formula (must be identical on all platforms)
```
hour_stamp = year × 1_000_000 + day_of_year × 100 + hour
```
Example: 2026-03-22 17:00 CET (day 81) → `2026 * 1_000_000 + 81 * 100 + 17 = 2_026_008_117`

### Firebase Realtime Database
- **URL:** your project's RTDB URL, e.g. `https://<project>-default-rtdb.<region>.firebasedatabase.app`
- **Auth:** shared email/password account; rules lock `/users/{uid}` to its owner (see `docs/firebase-setup.md`)
- **Schema:**
  ```
  /users/{uid}/devices/{device_id}/
    date                 "2026-03-22"          (local date string)
    daily_seconds        497                   (long)
    hourly_used_seconds  73                    (long)
    hourly_stamp         2026008117            (long, hour_stamp formula above)
  ```
- **Device IDs in use:** `linux`, `android` → use `windows`
- **Sync logic:**
  - Every N seconds: PUT your own node, GET all other nodes
  - Sum `daily_seconds` from nodes whose `date` == today
  - Sum `hourly_used_seconds` from nodes whose `hourly_stamp` == current stamp
  - `combined_daily = local + remote_sum`; `combined_hourly = local + remote_sum`
  - Enforce limits against combined values

---

## What the Windows app must do

1. **Track the focused browser tab** — detect when the user is on a blocked site
2. **Block domains** — prevent access when quota/cooldown kicks in
3. **Sync with Firebase** — share usage with Linux and Android in real time
4. **Run as a background service** — start on boot, no tray icon required

---

## Step-by-step build plan

### Step 1 — Repo & branch
```
git clone https://github.com/tahaKaroui/focusforlife.git
cd focusforlife
git checkout -b feature/windows-app
```
The Windows app lives in a new crate inside the existing Rust workspace
(`crates/windows-daemon`) so it can share `crates/shared` (config, IPC types,
timer logic).

### Step 2 — Cargo workspace
Add to the root `Cargo.toml`:
```toml
[workspace]
members = [
  "crates/daemon",
  "crates/shared",
  "crates/ui",
  "crates/watcher",
  "crates/windows-daemon",   # new
]
```
Create `crates/windows-daemon/Cargo.toml`:
```toml
[package]
name = "ffl-windows"
version = "0.1.0"
edition = "2021"

[dependencies]
ffl-shared = { path = "../shared" }
anyhow = "1"
toml = "0.8"
serde = { version = "1", features = ["derive"] }
serde_json = "1"
ureq = "2"
chrono = { version = "0.4", features = ["clock"] }
windows = { version = "0.58", features = [
  "Win32_Foundation",
  "Win32_UI_Accessibility",       # focused window title
  "Win32_System_Registry",        # hosts file path
  "Win32_Networking_WinSock",
] }
```

### Step 3 — Blocked sites list
Reuse `config/blocked-domains.txt` from the repo root — same file, same format.

### Step 4 — Domain blocking
Windows doesn't have a local DNS resolver like unbound. Options in order of
robustness:

**Option A (recommended): hosts file**
- Path: `C:\Windows\System32\drivers\etc\hosts`
- Append `0.0.0.0 youtube.com` etc. when blocking; remove when lifting
- Requires running as Administrator — schedule the service with elevated perms
- Use `0.0.0.0` not `127.0.0.1` (same reason as Linux: avoids DoH retry)

**Option B: DNS redirect via NRPT (Network Rule Policy Table)**
- More robust than hosts file but complex to set up programmatically

Stick with Option A for now.

### Step 5 — Focused tab detection (same hierarchy as Linux)

**Primary — ActivityWatch** (if installed)
- AW is cross-platform. If running: `GET http://127.0.0.1:5600/api/0/buckets/`
- Find bucket with prefix `aw-watcher-web`
- `GET /api/0/buckets/{id}/events?limit=1`
- Check `timestamp + duration` is within 60 s (staleness guard)
- Extract domain from `data.url`

**Secondary — CDP (Chrome DevTools Protocol)**
- Brave/Chrome: already configured with `--remote-debugging-port=9222`
  (add `--remote-debugging-port=9222` to the browser shortcut if not already)
- Get active window title: `GetForegroundWindow()` + `GetWindowText()`
- Strip browser suffix (` - Brave`, ` - Google Chrome`, etc.)
- Match against CDP tab list at `http://localhost:9222/json`
- Returns exact focused tab domain

**Tertiary — WinAPI window class check**
- `GetForegroundWindow()` → `GetClassName()` → check for `Chrome_WidgetWin_1`
  (Brave, Chrome, Edge all use this class)
- If browser is focused → check all CDP tabs for blocked domains

### Step 6 — Firebase sync
Use plain HTTP (same as Linux, no SDK needed):
```rust
// Push
ureq::put(&format!("{db_url}/devices/windows.json"))
    .send_json(serde_json::json!({
        "date": today,
        "daily_seconds": daily_used,
        "hourly_used_seconds": hourly_used,
        "hourly_stamp": hour_stamp,
    }))?;

// Pull
let body = ureq::get(&format!("{db_url}/devices.json")).call()?.into_string()?;
// iterate over all keys except "windows", sum daily/hourly from matching nodes
```

### Step 7 — Windows service
Use the `windows-service` crate or run as a scheduled task with "Run as
Administrator" and "Run whether user is logged on or not".

Simplest for a personal machine: Task Scheduler → trigger "At startup" →
action `ffl-windows.exe --config C:\ProgramData\FocusForLife\config.toml`.

### Step 8 — Config file
Copy `config/example.toml` as a starting point.
Place at `C:\ProgramData\FocusForLife\config.toml`.
Fill in the `[sync]` section with your own Firebase values (see
`docs/firebase-setup.md`); leave it empty to run standalone:
```toml
[sync]
firebase_db_url  = "https://<project>-default-rtdb.<region>.firebasedatabase.app"
firebase_api_key = "<web-api-key>"
firebase_email   = "<shared-account-email>"
firebase_password = "<shared-account-password>"
device_id = "windows"
sync_interval_seconds = 10
```

---

## Files to copy / create on Windows

| What | Where |
|---|---|
| `ffl-windows.exe` (compiled) | `C:\ProgramData\FocusForLife\` |
| `config/blocked-domains.txt` | `C:\ProgramData\FocusForLife\blocked-domains.txt` |
| `config/example.toml` (edit device_id) | `C:\ProgramData\FocusForLife\config.toml` |
| Brave policy JSON (disable DoH) | `C:\Program Files\BraveSoftware\Brave-Browser\Application\policies\managed\focusforlife.json` |

Brave policy JSON content (same as Linux):
```json
{"DnsOverHttpsMode": "off"}
```

---

## Key gotchas from Linux/Android experience

- **DoH bypass**: Brave retries NXDOMAIN via DNS-over-HTTPS. Use `0.0.0.0` in
  the hosts file (not an error code) and disable DoH via policy JSON.
- **Cache flush after unblocking**: After removing hosts file entries, flush
  the Windows DNS cache: `ipconfig /flushdns`. Otherwise 0.0.0.0 lingers.
- **Stale AW events**: AW returns the last event ever, not "current". Reject
  events whose `timestamp + duration` is older than 60 s.
- **hour_stamp must match exactly**: Linux, Android, and Windows must use the
  identical formula or cross-device hourly sync breaks silently.
- **Daily reset**: Reset local counters when date string changes (midnight).
  Remote counters reset naturally because the `date` field won't match today.
- **google-services.json**: Not needed on Windows (no Firebase Android SDK).
  Use REST API directly.
