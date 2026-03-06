Very important: Maintain this file as the canonical task context, updating it immediately when scope, decisions, or referenced files change.
# FocusForLife - Session Context (RAM)

Updated: 2026-01-16

Current task:
- App/services still stop when force-closed from Recents; add foreground services + restart scheduling. (files: app/src/main/AndroidManifest.xml, app/src/main/java/com/example/focusforlife/services/FocusVpnService.kt, app/src/main/java/com/example/focusforlife/services/FocusOverlayService.kt, app/src/main/java/com/example/focusforlife/services/FocusForegroundNotifications.kt, app/src/main/java/com/example/focusforlife/services/ServiceRestartScheduler.kt, app/src/main/java/com/example/focusforlife/ui/MainActivity.kt)
- User can turn off the app very easily by turning it off from accessiblity which defies the whole reason behind the app.
- Need user rebuild + toggle Accessibility OFF/ON after config change. (files: app/src/main/res/xml/accessibility_config.xml, app/src/main/java/com/example/focusforlife/accessibility/AppBlockerAccessibilityService.kt)


Issues/ Problems not fixed yet:
- Confirm URL-bar IDs for Brave/Chrome are stable on this device/versions. (files: app/src/main/java/com/example/focusforlife/accessibility/AppBlockerAccessibilityService.kt, app/src/main/java/com/example/focusforlife/core/FocusTargets.kt)
- Rebuild/reinstall and toggle Accessibility OFF/ON after config changes. (files: app/src/main/res/xml/accessibility_config.xml, app/src/main/java/com/example/focusforlife/accessibility/AppBlockerAccessibilityService.kt)
- Validate VPN stop logs + service shutdown. (files: app/src/main/java/com/example/focusforlife/services/FocusVpnService.kt, app/src/main/java/com/example/focusforlife/ui/MainActivity.kt, app/src/main/java/com/example/focusforlife/logging/FocusLogger.kt)
- If URL detection fails, add a temporary Accessibility node dump to capture Brave/Chrome URL bar IDs. (files: app/src/main/java/com/example/focusforlife/accessibility/AppBlockerAccessibilityService.kt)
- VPN can still fail on some networks; Private DNS must be Off while testing. (files: app/src/main/java/com/example/focusforlife/services/FocusVpnService.kt)

Notes:
- Gradle build may fail in sandbox due to ~/.gradle permissions; user builds locally. (files: build.gradle.kts, gradle.properties)
