package com.example.focusforlife.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.focusforlife.ui.BlockedActivity
import com.example.focusforlife.core.FocusForegroundTracker
import com.example.focusforlife.core.FocusLockManager
import com.example.focusforlife.core.FocusRules
import com.example.focusforlife.core.FocusTargets
import com.example.focusforlife.logging.FocusLogger

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
    private var lastActiveTimestamp: Long = 0L
    private var pendingStopDeadline: Long = 0L
    private var pendingStopPackage: String? = null
    private var activeBrowserDomain: String? = null
    private var activeBrowserPackage: String? = null
    private var lastBrowserTimestamp: Long = 0L
    private val usageHandler = Handler(Looper.getMainLooper())
    private val usageRunnable = object : Runnable {
        override fun run() {
            tickActiveUsage()
            usageHandler.postDelayed(this, USAGE_TICK_MS)
        }
    }

    override fun onServiceConnected() {
        FocusLogger.init(this)
        FocusRules.ensureFreshDay(this)
        FocusLogger.i("Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
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
        if (shouldBlockUninstallDialog(packageName) ||
            shouldBlockFocusTogglePage(packageName) ||
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
            val blockedDomain = extractBlockedDomain(packageName)
            updateBrowserTracking(packageName, blockedDomain)
        } else if (activeBrowserDomain != null && packageName != activeBrowserPackage) {
            finalizeBrowserUsage()
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

    override fun onDestroy() {
        FocusLogger.w("Accessibility service destroyed")
        finalizeActiveUsage()
        finalizeBrowserUsage()
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
                if (isTransientPackage(rootPackage)) {
                    schedulePendingStop()
                } else {
                    finalizeActiveUsage()
                }
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
        val viewId = "$packageName:id/url_bar"
        val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
        if (!nodes.isNullOrEmpty()) {
            val text = nodes.firstOrNull()?.text?.toString()
            if (!text.isNullOrBlank()) return text
        }
        val fallback = root.findAccessibilityNodeInfosByViewId("$packageName:id/omnibox_url_bar")
        if (!fallback.isNullOrEmpty()) {
            val text = fallback.firstOrNull()?.text?.toString()
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    companion object {
        private const val USAGE_TICK_MS = 1_000L
        private const val PENDING_STOP_GRACE_MS = 1_800L
        private val TRANSIENT_PACKAGES = setOf(
            "com.android.systemui",
            "miui.systemui.plugin",
            "com.mi.android.globallauncher",
            "com.mi.appfinder"
        )
    }
}
