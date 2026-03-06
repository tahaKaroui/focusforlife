package com.example.focusforlife.core

import java.util.concurrent.atomic.AtomicReference
import com.example.focusforlife.logging.FocusLogger

/**
 * Tracks which package is currently in the foreground so other components
 * know whether a browser is actively on screen.
 */
object FocusForegroundTracker {

    private val currentPackage = AtomicReference<String?>(null)

    fun update(packageName: String?) {
        val previous = currentPackage.getAndSet(packageName)
        if (previous != packageName) {
            FocusLogger.v("Foreground package changed: $previous -> $packageName")
        }
    }

    fun currentPackage(): String? = currentPackage.get()

    fun isBrowserActive(): Boolean {
        val pkg = currentPackage()
        return pkg != null && FocusTargets.browserPackageSet.contains(pkg)
    }
}
