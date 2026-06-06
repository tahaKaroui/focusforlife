package com.example.focusforlife.core

import android.content.Context
import com.example.focusforlife.logging.FocusLogger
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Stores a local PIN and manages delayed-disable requests.
 */
object FocusLockManager {

    private const val PREF_NAME = "focus_lock"
    private const val KEY_PIN_HASH = "pin_hash"
    private const val KEY_DISABLE_UNTIL = "disable_until"
    private const val KEY_MAINTENANCE_UNTIL = "maintenance_until"
    private const val DISABLE_DELAY_MS = 10 * 60 * 1000L
    private const val MAINTENANCE_WINDOW_MS = 10 * 60 * 1000L

    fun hasPin(context: Context): Boolean =
        prefs(context).getString(KEY_PIN_HASH, null)?.isNotBlank() == true

    fun setPin(context: Context, pin: String) {
        prefs(context).edit().putString(KEY_PIN_HASH, hash(pin)).apply()
        FocusLogger.i("PIN set/updated")
    }

    fun verifyPin(context: Context, pin: String): Boolean {
        val stored = prefs(context).getString(KEY_PIN_HASH, null) ?: return false
        val matches = stored == hash(pin)
        if (!matches) {
            FocusLogger.w("PIN verification failed")
        }
        return matches
    }

    fun requestDisable(context: Context) {
        val until = System.currentTimeMillis() + DISABLE_DELAY_MS
        prefs(context).edit().putLong(KEY_DISABLE_UNTIL, until).apply()
        FocusLogger.i("Disable requested; readyAt=$until")
    }

    fun clearDisableRequest(context: Context) {
        prefs(context).edit().putLong(KEY_DISABLE_UNTIL, 0L).apply()
        FocusLogger.i("Disable request cleared")
    }

    fun disableRemainingSeconds(context: Context): Long {
        val until = prefs(context).getLong(KEY_DISABLE_UNTIL, 0L)
        val diff = until - System.currentTimeMillis()
        return TimeUnit.MILLISECONDS.toSeconds(diff.coerceAtLeast(0L))
    }

    fun isDisableReady(context: Context): Boolean {
        val until = prefs(context).getLong(KEY_DISABLE_UNTIL, 0L)
        return until > 0L && System.currentTimeMillis() >= until
    }

    fun startMaintenanceWindow(context: Context) {
        val until = System.currentTimeMillis() + MAINTENANCE_WINDOW_MS
        prefs(context).edit().putLong(KEY_MAINTENANCE_UNTIL, until).apply()
        FocusLogger.i("Maintenance window started; until=$until")
    }

    fun clearMaintenanceWindow(context: Context) {
        prefs(context).edit().putLong(KEY_MAINTENANCE_UNTIL, 0L).apply()
        FocusLogger.i("Maintenance window cleared")
    }

    fun isMaintenanceActive(context: Context): Boolean {
        val until = prefs(context).getLong(KEY_MAINTENANCE_UNTIL, 0L)
        return until > 0L && System.currentTimeMillis() < until
    }

    fun maintenanceRemainingSeconds(context: Context): Long {
        val until = prefs(context).getLong(KEY_MAINTENANCE_UNTIL, 0L)
        val diff = until - System.currentTimeMillis()
        return TimeUnit.MILLISECONDS.toSeconds(diff.coerceAtLeast(0L))
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private fun hash(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(pin.toByteArray(Charsets.UTF_8))
        return bytes.joinToString(separator = "") { "%02x".format(it) }
    }
}
