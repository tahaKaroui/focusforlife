# FocusForLife Change Archive

## 2026-01-16
Recent changes:
- Overlay redesigned into a draggable bubble with tap-to-expand details. (files: app/src/main/java/com/example/focusforlife/services/FocusOverlayService.kt, app/src/main/res/layout/overlay_focus_status.xml)
- Overlay now shows only hourly remaining + daily remaining timers. (files: app/src/main/java/com/example/focusforlife/services/FocusOverlayService.kt, app/src/main/res/layout/overlay_focus_status.xml)
- Hourly (10-minute) usage tracking now uses per-hour buckets (resets at :00). (files: app/src/main/java/com/example/focusforlife/core/FocusRules.kt)
- Browser timing moved to Accessibility URL-bar detection; DNS no longer adds usage time. (files: app/src/main/java/com/example/focusforlife/accessibility/AppBlockerAccessibilityService.kt, app/src/main/java/com/example/focusforlife/services/FocusVpnService.kt)
- VPN stop now uses an explicit stop action + logging. (files: app/src/main/java/com/example/focusforlife/services/FocusVpnService.kt, app/src/main/java/com/example/focusforlife/ui/MainActivity.kt, app/src/main/java/com/example/focusforlife/logging/FocusLogger.kt)
- Stabilize hourly (10-minute) timer reset on the hour and tighten overlay timer display. (files: app/src/main/java/com/example/focusforlife/core/FocusRules.kt, app/src/main/java/com/example/focusforlife/services/FocusOverlayService.kt, app/src/main/res/layout/overlay_focus_status.xml)
- Ensure VPN stop button path logs + actually stops service. (files: app/src/main/java/com/example/focusforlife/ui/MainActivity.kt, app/src/main/java/com/example/focusforlife/services/FocusVpnService.kt, app/src/main/java/com/example/focusforlife/logging/FocusLogger.kt)