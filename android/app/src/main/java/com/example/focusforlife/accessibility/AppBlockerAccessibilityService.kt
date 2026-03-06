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

    private var activePackage: String? = null
    private var lastActiveTimestamp: Long = 0L
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
        FocusForegroundTracker.update(packageName)
        FocusLogger.v("A11y event type=$eventType pkg=$packageName")

        if (browserPackages.contains(packageName)) {
            val blockedDomain = extractBlockedDomain(packageName)
            updateBrowserTracking(packageName, blockedDomain)
        } else if (activeBrowserDomain != null && packageName != activeBrowserPackage) {
            finalizeBrowserUsage()
        }

        if (!blockedApps.contains(packageName)) {
            if (activePackage != null && packageName != activePackage) {
                finalizeActiveUsage()
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
            FocusLogger.i("Started tracking $packageName")
        }
    }

    private fun tickActiveUsage() {
        val now = System.currentTimeMillis()
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
    }

    private fun finalizeActiveUsage() {
        if (activePackage == null || lastActiveTimestamp == 0L) {
            activePackage = null
            lastActiveTimestamp = 0L
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
    }
}
