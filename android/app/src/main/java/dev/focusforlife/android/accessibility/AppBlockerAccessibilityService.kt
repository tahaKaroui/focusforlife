package dev.focusforlife.android.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.focusforlife.android.ui.BlockedActivity
import dev.focusforlife.android.core.FocusForegroundTracker
import dev.focusforlife.android.core.FocusLockManager
import dev.focusforlife.android.core.FocusRules
import dev.focusforlife.android.core.FocusTargets
import dev.focusforlife.android.logging.FocusLogger

/**
 * Accessibility-based guard that tracks usage time for blocked apps and kicks
 * the user to BlockedActivity whenever windows/quota rule is violated.
 */
class AppBlockerAccessibilityService : AccessibilityService() {

    private val blockedApps = FocusTargets.blockedAppSet
    private val browserPackages = FocusTargets.browserPackageSet
    private val defaultLauncherPackage: String? by lazy {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName
    }

    private var activePackage: String? = null
    private var lastBlockMs: Long = 0L
    private var lastActiveTimestamp: Long = 0L
    private var pendingStopDeadline: Long = 0L
    private var pendingStopPackage: String? = null
    private var activeBrowserDomain: String? = null
    private var activeBrowserPackage: String? = null
    private var lastBrowserTimestamp: Long = 0L
    private var lastUrlCheckMs: Long = 0L
    private val usageHandler = Handler(Looper.getMainLooper())
    private val usageRunnable = object : Runnable {
        override fun run() {
            // Never let an exception escape to the framework: a single crash here
            // marks the service "Not working" until it is manually toggled.
            try {
                tickActiveUsage()
            } catch (t: Throwable) {
                FocusLogger.e("tickActiveUsage crashed (suppressed)", t)
            }
            usageHandler.postDelayed(this, USAGE_TICK_MS)
        }
    }

    override fun onServiceConnected() {
        connected = true
        try {
            FocusLogger.init(this)
            FocusRules.ensureFreshDay(this)
            dev.focusforlife.android.core.FocusSync.startListening()
            dev.focusforlife.android.services.FocusForegroundNotifications.cancelAccessibilityAlert(this)
            FocusLogger.i("Accessibility service connected")
        } catch (t: Throwable) {
            FocusLogger.e("onServiceConnected crashed (suppressed)", t)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            handleAccessibilityEvent(event)
        } catch (t: Throwable) {
            FocusLogger.e("onAccessibilityEvent crashed (suppressed)", t)
        }
    }

    private fun handleAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName == null) return
        val eventType = event.eventType
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        ) {
            return
        }

        FocusRules.ensureFreshDay(this)
        tickActiveUsage()

        val packageName = event.packageName.toString()
        // Ignore our own windows (block screen, dashboard): reacting to them
        // re-triggers blocking logic against ourselves.
        if (packageName == applicationContext.packageName) return
        if (shouldBlockUninstallDialog(packageName) ||
            shouldBlockFocusTogglePage(packageName) ||
            shouldBlockDisplayOverOtherAppsPage(packageName) ||
            shouldBlockBatterySaverPage(packageName) ||
            shouldBlockMiuiPermissionsPage(packageName) ||
            shouldBlockForceStopDialog(packageName) ||
            shouldBlockClearDataSheet(packageName) ||
            shouldBlockLauncherUninstallDialog(packageName)
        ) {
            FocusLogger.w("FocusForLife toggle page detected ($packageName); blocking.")
            blockNow()
            return
        }

        FocusForegroundTracker.update(packageName)
        FocusLogger.v("A11y event type=$eventType pkg=$packageName")

        if (packageName == activePackage) {
            clearPendingStop()
        }

        if (browserPackages.contains(packageName)) {
            val isHighFreqEvent = eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            val now = System.currentTimeMillis()
            val shouldCheckUrl = !isHighFreqEvent || (now - lastUrlCheckMs >= URL_CHECK_INTERVAL_MS)
            if (shouldCheckUrl) {
                lastUrlCheckMs = now
            }
            val blockedDomain = if (shouldCheckUrl) extractBlockedDomain(packageName) else null
            if (blockedDomain != null || !isHighFreqEvent) {
                updateBrowserTracking(packageName, blockedDomain)
            }
        } else if (activeBrowserDomain != null && packageName != activeBrowserPackage) {
            // Use the actual root window to decide; during fullscreen the browser is
            // still the root even if system packages fire events alongside it.
            val rootPkg = rootInActiveWindow?.packageName?.toString()
            if (rootPkg == null || !browserPackages.contains(rootPkg)) {
                finalizeBrowserUsage()
            }
        }

        if (!blockedApps.contains(packageName)) {
            if (activePackage != null && packageName != activePackage) {
                if (isTransientPackage(packageName)) {
                    schedulePendingStop()
                } else {
                    finalizeActiveUsage()
                }
            }
            return
        }

        if (shouldBlockNow()) {
            finalizeActiveUsage()
            FocusLogger.i("Blocking package $packageName (status=${FocusRules.blockStatus(applicationContext)})")
            blockNow()
            return
        }

        if (activePackage != packageName) {
            activePackage = packageName
            lastActiveTimestamp = System.currentTimeMillis()
            startUsageTicker()
            clearPendingStop()
            FocusLogger.i("Started tracking $packageName")
        }
    }

    private fun tickActiveUsage() {
        val now = System.currentTimeMillis()
        if (pendingStopDeadline > 0L && now >= pendingStopDeadline) {
            val trackingPackage = activePackage
            val currentPackage = FocusForegroundTracker.currentPackage()
            if (trackingPackage != null && trackingPackage == pendingStopPackage) {
                if (currentPackage == null || currentPackage != trackingPackage) {
                    FocusLogger.i("Pending stop elapsed; finalizing $trackingPackage")
                    finalizeActiveUsage()
                }
            }
            clearPendingStop()
        }
        val trackingPackage = activePackage
        if (trackingPackage != null) {
            if (lastActiveTimestamp == 0L) {
                lastActiveTimestamp = now
            } else {
                val delta = now - lastActiveTimestamp
                if (delta > 0) {
                    FocusRules.addUsageMillis(applicationContext, trackingPackage, delta)
                    lastActiveTimestamp = now
                    if (shouldBlockNow()) {
                        finalizeActiveUsage()
                        blockNow()
                    }
                }
            }
        }

        val trackingDomain = activeBrowserDomain
        if (trackingDomain != null) {
            if (lastBrowserTimestamp == 0L) {
                lastBrowserTimestamp = now
            } else {
                val delta = now - lastBrowserTimestamp
                if (delta > 0) {
                    FocusRules.addUsageMillis(
                        applicationContext,
                        "${FocusRules.DOMAIN_PREFIX}$trackingDomain",
                        delta
                    )
                    lastBrowserTimestamp = now
                    if (shouldBlockNow()) {
                        finalizeBrowserUsage()
                        blockNow()
                    }
                }
            }
        }

        reconcileForegroundFromRoot()

        // Defeat the "minimize into a MIUI floating/freeform window" bypass: rootInActiveWindow
        // only sees the focused window, so a blocked app/site shoved into a small window keeps
        // playing. Once over quota, scan ALL visible windows and kick if a blocked target is up.
        // Skip while the block screen is up: the kicked app often lingers in the window list
        // during MIUI's transition animation and would re-trigger an immediate second kick.
        if (!BlockedActivity.isShowing() && shouldBlockNow() && isBlockedTargetVisibleAcrossWindows()) {
            finalizeActiveUsage()
            finalizeBrowserUsage()
            FocusLogger.i("Blocking via cross-window scan (floating/minimized target)")
            blockNow()
        }
    }

    /** True if any visible window (not just the active one) shows a blocked app or domain. */
    private fun isBlockedTargetVisibleAcrossWindows(): Boolean {
        val wins = try { windows } catch (e: Exception) { null } ?: return false
        for (w in wins) {
            val root = w.root ?: continue
            val pkg = root.packageName?.toString() ?: continue
            if (blockedApps.contains(pkg)) return true
            if (browserPackages.contains(pkg)) {
                val urlText = findUrlText(root, pkg)?.trim()
                val domain = urlText?.let { extractDomain(it) }
                if (domain != null && FocusTargets.matchesBlockedDomain(domain)) return true
            }
        }
        return false
    }

    private fun finalizeActiveUsage() {
        if (activePackage == null || lastActiveTimestamp == 0L) {
            activePackage = null
            lastActiveTimestamp = 0L
            clearPendingStop()
            stopUsageTickerIfIdle()
            return
        }
        val now = System.currentTimeMillis()
        val delta = now - lastActiveTimestamp
        if (delta > 0) {
            FocusRules.addUsageMillis(applicationContext, activePackage!!, delta)
        }
        FocusLogger.i("Stopped tracking ${activePackage}")
        activePackage = null
        lastActiveTimestamp = 0L
        clearPendingStop()
        stopUsageTickerIfIdle()
    }

    private fun finalizeBrowserUsage() {
        if (activeBrowserDomain == null || lastBrowserTimestamp == 0L) {
            activeBrowserDomain = null
            activeBrowserPackage = null
            lastBrowserTimestamp = 0L
            stopUsageTickerIfIdle()
            return
        }
        val now = System.currentTimeMillis()
        val delta = now - lastBrowserTimestamp
        if (delta > 0) {
            FocusRules.addUsageMillis(
                applicationContext,
                "${FocusRules.DOMAIN_PREFIX}${activeBrowserDomain}",
                delta
            )
        }
        FocusLogger.i("Stopped tracking browser domain ${activeBrowserDomain}")
        activeBrowserDomain = null
        activeBrowserPackage = null
        lastBrowserTimestamp = 0L
        stopUsageTickerIfIdle()
    }

    private fun updateBrowserTracking(packageName: String, blockedDomain: String?) {
        if (blockedDomain == null) {
            // If we're still in the same browser package, the URL bar is temporarily
            // hidden (e.g. fullscreen video). Keep tracking the last known blocked domain
            // so fullscreen YouTube/Instagram can't bypass the timer.
            if (activeBrowserDomain != null && activeBrowserPackage == packageName) {
                return
            }
            if (activeBrowserDomain != null) {
                finalizeBrowserUsage()
            }
            return
        }
        if (shouldBlockNow()) {
            finalizeBrowserUsage()
            FocusLogger.i("Blocking browser domain $blockedDomain (status=${FocusRules.blockStatus(applicationContext)})")
            blockNow()
            return
        }
        if (activeBrowserDomain != blockedDomain || activeBrowserPackage != packageName) {
            finalizeBrowserUsage()
            activeBrowserDomain = blockedDomain
            activeBrowserPackage = packageName
            lastBrowserTimestamp = System.currentTimeMillis()
            startUsageTicker()
            FocusLogger.i("Started tracking browser domain $blockedDomain pkg=$packageName")
        }
    }

    private fun blockNow() {
        // Debounce: events keep firing during the HOME transition and the block
        // screen launch. Re-triggering on every one of them used to press HOME
        // again (hiding the block screen we just opened), causing a black
        // flashing loop where the message never stayed on screen.
        val now = System.currentTimeMillis()
        if (BlockedActivity.isShowing() || now - lastBlockMs < BLOCK_DEBOUNCE_MS) {
            return
        }
        lastBlockMs = now
        performGlobalAction(GLOBAL_ACTION_HOME)
        FocusLogger.i("Block screen shown.")
        val intent = Intent(this, BlockedActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun shouldBlockNow(): Boolean {
        return FocusRules.shouldDenyAccess(applicationContext)
    }

    override fun onInterrupt() {
        FocusLogger.w("Accessibility service interrupted")
        finalizeActiveUsage()
        finalizeBrowserUsage()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        connected = false
        FocusLogger.w("Accessibility service unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        connected = false
        FocusLogger.w("Accessibility service destroyed")
        try {
            finalizeActiveUsage()
            finalizeBrowserUsage()
        } catch (t: Throwable) {
            FocusLogger.e("onDestroy cleanup crashed (suppressed)", t)
        }
        super.onDestroy()
    }

    private fun startUsageTicker() {
        usageHandler.removeCallbacks(usageRunnable)
        usageHandler.postDelayed(usageRunnable, USAGE_TICK_MS)
    }

    private fun stopUsageTickerIfIdle() {
        if (activePackage == null && activeBrowserDomain == null) {
            usageHandler.removeCallbacks(usageRunnable)
        }
    }

    private fun schedulePendingStop() {
        val trackingPackage = activePackage ?: return
        if (pendingStopPackage == trackingPackage) return
        pendingStopPackage = trackingPackage
        pendingStopDeadline = System.currentTimeMillis() + PENDING_STOP_GRACE_MS
        FocusLogger.i("Pending stop scheduled for $trackingPackage")
    }

    private fun clearPendingStop() {
        pendingStopPackage = null
        pendingStopDeadline = 0L
    }

    private fun isTransientPackage(packageName: String): Boolean {
        if (TRANSIENT_PACKAGES.contains(packageName)) return true
        return defaultLauncherPackage == packageName
    }

    private fun shouldBlockFocusTogglePage(packageName: String): Boolean {
        if (FocusLockManager.isMaintenanceActive(this)) return false
        if (packageName != "com.android.settings") return false
        val root = rootInActiveWindow ?: return false
        val titleNodes = root.findAccessibilityNodeInfosByViewId("com.android.settings:id/action_bar_title_expand")
        val titleText = titleNodes?.firstOrNull()?.text?.toString().orEmpty()
        val switchNodes = root.findAccessibilityNodeInfosByViewId("android:id/switch_widget")
        val titleMatches = titleText.contains("FocusForLife", ignoreCase = true) ||
            titleText.contains("Focus For Life", ignoreCase = true)
        return titleMatches && !switchNodes.isNullOrEmpty()
    }

    private fun shouldBlockDisplayOverOtherAppsPage(packageName: String): Boolean {
        if (FocusLockManager.isMaintenanceActive(this)) return false
        if (packageName != "com.android.settings") return false
        val root = rootInActiveWindow ?: return false
        val titleNodes = root.findAccessibilityNodeInfosByViewId("com.android.settings:id/action_bar_title_expand")
        val titleText = titleNodes?.firstOrNull()?.text?.toString().orEmpty()
        if (!titleText.contains("Display over other apps", ignoreCase = true)) return false
        val switchNodes = root.findAccessibilityNodeInfosByViewId("com.android.settings:id/switchWidget")
        return !switchNodes.isNullOrEmpty()
    }

    private fun hasText(root: AccessibilityNodeInfo, text: String): Boolean {
        val nodes = root.findAccessibilityNodeInfosByText(text)
        return !nodes.isNullOrEmpty()
    }

    private fun shouldBlockUninstallDialog(packageName: String): Boolean {
        if (FocusLockManager.isMaintenanceActive(this)) return false
        if (packageName != "com.miui.securitycenter") return false
        val root = rootInActiveWindow ?: return false
        val uninstallTitle = "com.miui.securitycenter:id/alertTitle"
        val messageId = "com.miui.securitycenter:id/message"
        val titleNodes = root.findAccessibilityNodeInfosByViewId(uninstallTitle)
        val messageNodes = root.findAccessibilityNodeInfosByViewId(messageId)
        val titleText = titleNodes?.firstOrNull()?.text?.toString().orEmpty()
        val messageText = messageNodes?.firstOrNull()?.text?.toString().orEmpty()
        val titleMatches = titleText.contains("FocusForLife", ignoreCase = true) ||
            titleText.contains("Focus For Life", ignoreCase = true)
        val messageMatches = messageText.contains("app data", ignoreCase = true) ||
            messageText.contains("données", ignoreCase = true) ||
            messageText.contains("datos", ignoreCase = true)
        return titleMatches && messageMatches
    }

    private fun shouldBlockBatterySaverPage(packageName: String): Boolean {
        if (FocusLockManager.isMaintenanceActive(this)) return false
        if (packageName != "com.miui.securitycenter") return false
        val root = rootInActiveWindow ?: return false
        val titleNodes = root.findAccessibilityNodeInfosByViewId("com.miui.securitycenter:id/action_bar_title_expand")
        val titleText = titleNodes?.firstOrNull()?.text?.toString().orEmpty()
        val appTitleNodes = root.findAccessibilityNodeInfosByViewId("com.miui.securitycenter:id/title")
        val appTitleText = appTitleNodes?.firstOrNull()?.text?.toString().orEmpty()
        val isBatteryDetails = titleText.contains("Battery details", ignoreCase = true)
        val isFocusEntry = appTitleText.contains("FocusForLife", ignoreCase = true) ||
            appTitleText.contains("Focus For Life", ignoreCase = true)
        val hasRadio = !root.findAccessibilityNodeInfosByViewId("android:id/checkbox").isNullOrEmpty()
        return isBatteryDetails && isFocusEntry && hasRadio
    }

    private fun shouldBlockMiuiPermissionsPage(packageName: String): Boolean {
        if (FocusLockManager.isMaintenanceActive(this)) return false
        if (packageName != "com.miui.securitycenter") return false
        val root = rootInActiveWindow ?: return false
        val titleNodes = root.findAccessibilityNodeInfosByViewId("com.miui.securitycenter:id/action_bar_title_expand")
        val titleText = titleNodes?.firstOrNull()?.text?.toString().orEmpty()
        val titleMatches = titleText.contains("FocusForLife", ignoreCase = true) ||
            titleText.contains("Focus For Life", ignoreCase = true)
        val hasPermissionAction =
            !root.findAccessibilityNodeInfosByViewId("com.miui.securitycenter:id/action").isNullOrEmpty()
        return titleMatches && hasPermissionAction
    }

    private fun shouldBlockForceStopDialog(packageName: String): Boolean {
        if (FocusLockManager.isMaintenanceActive(this)) return false
        if (packageName != "com.miui.securitycenter") return false
        val root = rootInActiveWindow ?: return false
        val titleNodes = root.findAccessibilityNodeInfosByViewId("com.miui.securitycenter:id/alertTitle")
        val messageNodes = root.findAccessibilityNodeInfosByViewId("com.miui.securitycenter:id/message")
        if (titleNodes.isNullOrEmpty() || messageNodes.isNullOrEmpty()) return false
        val titleText = titleNodes.firstOrNull()?.text?.toString().orEmpty()
        val messageText = messageNodes.firstOrNull()?.text?.toString().orEmpty()
        val titleMatches = titleText.contains("Force stop", ignoreCase = true) ||
            titleText.contains("Forcer l'arrêt", ignoreCase = true) ||
            titleText.contains("Forzar detención", ignoreCase = true)
        val messageMatches = messageText.contains("force stop", ignoreCase = true) ||
            messageText.contains("misbehave", ignoreCase = true)
        return titleMatches && messageMatches
    }

    private fun shouldBlockClearDataSheet(packageName: String): Boolean {
        if (FocusLockManager.isMaintenanceActive(this)) return false
        if (packageName != "com.miui.securitycenter") return false
        val root = rootInActiveWindow ?: return false
        val sheetMessageId = "com.miui.securitycenter:id/action_sheet_message"
        val cancelButtonId = "com.miui.securitycenter:id/action_sheet_cancel_button"
        val listItemId = "android:id/text1"
        val messageNodes = root.findAccessibilityNodeInfosByViewId(sheetMessageId)
        val cancelNodes = root.findAccessibilityNodeInfosByViewId(cancelButtonId)
        val itemNodes = root.findAccessibilityNodeInfosByViewId(listItemId)
        if (messageNodes.isNullOrEmpty() || cancelNodes.isNullOrEmpty() || itemNodes.isNullOrEmpty()) {
            return false
        }
        val messageText = messageNodes.firstOrNull()?.text?.toString().orEmpty()
        val hasClearData = messageText.contains("Clear data", ignoreCase = true) ||
            messageText.contains("Clear all data", ignoreCase = true)
        return hasClearData
    }

    private fun shouldBlockLauncherUninstallDialog(packageName: String): Boolean {
        if (FocusLockManager.isMaintenanceActive(this)) return false
        if (packageName != "com.mi.android.globallauncher") return false
        val root = rootInActiveWindow ?: return false
        val titleNodes = root.findAccessibilityNodeInfosByViewId("com.mi.android.globallauncher:id/title")
        val descNodes = root.findAccessibilityNodeInfosByViewId("com.mi.android.globallauncher:id/description_text")
        if (titleNodes.isNullOrEmpty() || descNodes.isNullOrEmpty()) return false
        val titleText = titleNodes.firstOrNull()?.text?.toString().orEmpty()
        val descText = descNodes.firstOrNull()?.text?.toString().orEmpty()
        val titleMatches = titleText.contains("FocusForLife", ignoreCase = true) ||
            titleText.contains("Focus For Life", ignoreCase = true)
        val descMatches = descText.contains("app data", ignoreCase = true) ||
            descText.contains("données", ignoreCase = true) ||
            descText.contains("datos", ignoreCase = true)
        return titleMatches && descMatches
    }


    private fun reconcileForegroundFromRoot() {
        val rootPackage = rootInActiveWindow?.packageName?.toString() ?: return
        if (rootPackage == activePackage) return

        FocusForegroundTracker.update(rootPackage)

        if (!blockedApps.contains(rootPackage)) {
            if (activePackage != null) {
                // Always use a pending stop (grace period) rather than immediately
                // finalizing; MIUI overlays and notification peeks frequently appear
                // as the root window for one tick and would otherwise kill the timer.
                schedulePendingStop()
            }
            return
        }

        if (shouldBlockNow()) {
            finalizeActiveUsage()
            blockNow()
            return
        }

        activePackage = rootPackage
        lastActiveTimestamp = System.currentTimeMillis()
        startUsageTicker()
        clearPendingStop()
        FocusLogger.i("Started tracking $rootPackage (root fallback)")
    }

    private fun extractBlockedDomain(packageName: String): String? {
        val root = rootInActiveWindow ?: return null
        val urlText = findUrlText(root, packageName) ?: return null
        val url = urlText.trim()
        val domain = extractDomain(url) ?: return null
        return if (FocusTargets.matchesBlockedDomain(domain)) {
            FocusTargets.normalizeDomain(domain)
        } else {
            null
        }
    }

    private fun extractDomain(rawUrl: String): String? {
        val parsed = if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
            Uri.parse(rawUrl)
        } else {
            Uri.parse("https://$rawUrl")
        }
        val host = parsed.host ?: return null
        return host.trim('.')
    }

    private fun findUrlText(root: AccessibilityNodeInfo, packageName: String): String? {
        // "web_bottom_url" is Mi Browser's bottom omnibox (shows e.g. "m.youtube.com").
        // Looking it up by id is O(1) and not subject to the depth cap in findUrlInTree.
        listOf(
            "url_bar", "omnibox_url_bar", "url_field", "url_text",
            "web_bottom_url", "web_bottom_url_click"
        ).forEach { id ->
            val nodes = root.findAccessibilityNodeInfosByViewId("$packageName:id/$id")
            if (!nodes.isNullOrEmpty()) {
                val text = nodes.firstOrNull()?.text?.toString()
                if (!text.isNullOrBlank()) return text
            }
        }
        return findUrlInTree(root, 0)
    }

    private fun findUrlInTree(node: AccessibilityNodeInfo, depth: Int): String? {
        if (depth > 10) return null
        val cls = node.className?.toString()
        if (cls == "android.widget.EditText" || cls == "android.widget.TextView") {
            val text = node.text?.toString()
            if (!text.isNullOrBlank()) {
                val domain = extractDomain(text)
                if (domain != null && FocusTargets.matchesBlockedDomain(domain)) return text
            }
        }
        for (i in 0 until node.childCount) {
            val result = findUrlInTree(node.getChild(i) ?: continue, depth + 1)
            if (result != null) return result
        }
        return null
    }

    companion object {
        /**
         * True only while the framework has this service bound. Survives nothing:
         * a process kill (MIUI "clear all") resets it to false while the secure
         * setting still lists the service; that mismatch is the zombie state the
         * overlay watchdog repairs via AccessibilityUtils.forceRebind().
         */
        @Volatile private var connected = false
        fun isConnected(): Boolean = connected

        private const val USAGE_TICK_MS = 1_000L
        private const val BLOCK_DEBOUNCE_MS = 3_000L
        private const val PENDING_STOP_GRACE_MS = 1_800L
        private const val URL_CHECK_INTERVAL_MS = 500L
        private val TRANSIENT_PACKAGES = setOf(
            "com.android.systemui",
            "miui.systemui.plugin",
            "com.mi.android.globallauncher",
            "com.mi.appfinder"
        )
    }
}
