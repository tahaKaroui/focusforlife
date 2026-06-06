package com.example.focusforlife.services

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import com.example.focusforlife.accessibility.AppBlockerAccessibilityService
import com.example.focusforlife.logging.FocusLogger

object AccessibilityUtils {

    fun isServiceEnabled(context: Context): Boolean {
        val expected = ComponentName(context, AppBlockerAccessibilityService::class.java)
        val flat = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(flat)
        while (splitter.hasNext()) {
            val component = ComponentName.unflattenFromString(splitter.next()) ?: continue
            if (component == expected) return true
        }
        return false
    }

    /**
     * Programmatically re-enables our accessibility service.
     * Requires WRITE_SECURE_SETTINGS, granted once via:
     *   adb shell pm grant com.example.focusforlife android.permission.WRITE_SECURE_SETTINGS
     */
    fun ensureServiceEnabled(context: Context): Boolean {
        if (isServiceEnabled(context)) return true
        return try {
            val component = ComponentName(context, AppBlockerAccessibilityService::class.java)
            val flat = component.flattenToString()
            val current = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            val newValue = if (current.isBlank()) flat else "$current:$flat"
            Settings.Secure.putString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                newValue
            )
            Settings.Secure.putInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                1
            )
            FocusLogger.i("Accessibility service re-enabled programmatically")
            true
        } catch (e: SecurityException) {
            FocusLogger.w("WRITE_SECURE_SETTINGS not granted — cannot auto-enable accessibility: ${e.message}")
            false
        }
    }
}
