# FocusForLife - Core Context (ROM)

Goal: Build a strict Android focus-enforcement app that blocks specified apps/domains and enforces time-based usage limits, minimizing distractions.

Core behavior:
- Block listed apps via AccessibilityService when any block rule triggers.
- Block listed domains via a local DNS VPN when block rules trigger; track domain usage when allowed.
- Enforce daily quota, continuous session limit, cooldown, and a hard time window.
- Show an always-on overlay with remaining session/daily time.
- Use a local PIN + device admin to delay or prevent disabling.

Rules (current constants in code):
- Daily quota: 60 minutes shared across targets.
- Continuous limit: 10 minutes, then 1 hour cooldown.
- Hard block window: 23:30 to 11:00.

Key components:
- App blocking: `app/src/main/java/com/example/focusforlife/accessibility/AppBlockerAccessibilityService.kt`
- DNS blocking VPN: `app/src/main/java/com/example/focusforlife/services/FocusVpnService.kt`
- Rules + usage tracking: `app/src/main/java/com/example/focusforlife/core/FocusRules.kt`
- Target lists: `app/src/main/java/com/example/focusforlife/core/FocusTargets.kt`
- Overlay: `app/src/main/java/com/example/focusforlife/services/FocusOverlayService.kt`
- Block screen: `app/src/main/java/com/example/focusforlife/ui/BlockedActivity.kt`
- PIN/disable delay: `app/src/main/java/com/example/focusforlife/core/FocusLockManager.kt`
- Dashboard UI: `app/src/main/java/com/example/focusforlife/ui/MainActivity.kt`
- Diagnostics logging: `app/src/main/java/com/example/focusforlife/logging/FocusLogger.kt`

Non-goals:
- No cloud backend.
- No soft reminders; enforcement is intentionally strict.
