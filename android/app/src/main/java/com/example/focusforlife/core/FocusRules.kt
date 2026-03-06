package com.example.focusforlife.core

import android.content.Context
import android.content.SharedPreferences
import com.example.focusforlife.logging.FocusLogger
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Centralized focus policy: tracks usage quota, per-app + per-domain stats,
 * continuous session limits, and time windows.
 */
object FocusRules {

    const val DOMAIN_PREFIX = "domain:"

    private const val PREF_NAME = "focus_usage"
    private const val KEY_DATE = "date"
    private const val KEY_MILLIS = "millis"
    private const val KEY_APP_SET = "apps"
    private const val KEY_APP_PREFIX = "app_"
    private const val KEY_SESSION_USED = "session_used"
    private const val KEY_LAST_ACTIVITY = "session_last_activity"
    private const val KEY_COOLDOWN_UNTIL = "cooldown_until"
    private const val KEY_HOURLY_USED = "hourly_used"
    private const val KEY_HOURLY_STAMP = "hourly_stamp"

    private const val DAILY_QUOTA_SECONDS = 60 * 60L // 1 hour shared quota
    private const val HOURLY_LIMIT_MS = 10 * 60 * 1000L // 10 minutes per hour
    private val HARD_BLOCK_START: LocalTime = LocalTime.of(23, 30)
    private val HARD_BLOCK_END: LocalTime = LocalTime.of(11, 0)

    private val lock = Any()
    @Volatile private var lastStatus: BlockStatus? = null

    enum class BlockStatus { NONE, HARD_WINDOW, COOLDOWN, QUOTA }

    fun ensureFreshDay(context: Context) {
        synchronized(lock) {
            val prefs = prefs(context)
            val today = LocalDate.now().toString()
            if (prefs.getString(KEY_DATE, null) != today) {
                val existingApps = prefs.getStringSet(KEY_APP_SET, emptySet()) ?: emptySet()
                val editor = prefs.edit()
                for (pkg in existingApps) {
                    editor.remove(appKey(pkg))
                }
                editor.putStringSet(KEY_APP_SET, mutableSetOf())
                    .putString(KEY_DATE, today)
                    .putLong(KEY_MILLIS, 0L)
                    .putLong(KEY_SESSION_USED, 0L)
                    .putLong(KEY_LAST_ACTIVITY, 0L)
                    .putLong(KEY_COOLDOWN_UNTIL, 0L)
                    .putLong(KEY_HOURLY_USED, 0L)
                    .putLong(KEY_HOURLY_STAMP, 0L)
                    .apply()
                FocusLogger.i("Daily usage reset for date=$today")
            }
        }
    }

    fun addUsageMillis(context: Context, sourceId: String, millis: Long) {
        if (millis <= 0) return
        synchronized(lock) {
            val prefs = prefs(context)
            ensureFreshDay(context)
            val total = prefs.getLong(KEY_MILLIS, 0L) + millis
            val perKey = appKey(sourceId)
            val perApp = prefs.getLong(perKey, 0L) + millis
            val existingApps = prefs.getStringSet(KEY_APP_SET, emptySet()) ?: emptySet()
            val updatedApps = existingApps.toMutableSet().apply { add(sourceId) }
            prefs.edit()
                .putLong(KEY_MILLIS, total)
                .putLong(perKey, perApp)
                .putStringSet(KEY_APP_SET, updatedApps)
                .apply()
            updateHourlyLocked(prefs, millis)
            if (millis >= 900) {
                FocusLogger.v("Usage +${millis}ms source=$sourceId total=$total")
            }
        }
    }

    fun getUsageSeconds(context: Context): Long {
        synchronized(lock) {
            ensureFreshDay(context)
            val millis = prefs(context).getLong(KEY_MILLIS, 0L)
            return TimeUnit.MILLISECONDS.toSeconds(millis)
        }
    }

    fun getPerAppUsageSeconds(context: Context): Map<String, Long> {
        synchronized(lock) {
            ensureFreshDay(context)
            val prefs = prefs(context)
            val apps = prefs.getStringSet(KEY_APP_SET, emptySet()) ?: emptySet()
            val result = mutableMapOf<String, Long>()
            for (pkg in apps) {
                val millis = prefs.getLong(appKey(pkg), 0L)
                if (millis > 0) {
                    result[pkg] = TimeUnit.MILLISECONDS.toSeconds(millis)
                }
            }
            return result
        }
    }

    fun remainingSeconds(context: Context): Long {
        val used = getUsageSeconds(context)
        return (DAILY_QUOTA_SECONDS - used).coerceAtLeast(0)
    }

    fun sessionRemainingSeconds(context: Context): Long {
        synchronized(lock) {
            ensureFreshDay(context)
            val prefs = prefs(context)
            val now = System.currentTimeMillis()
            val currentStamp = hourStamp(now)
            val storedStamp = prefs.getLong(KEY_HOURLY_STAMP, 0L)
            val used = if (storedStamp == currentStamp) {
                prefs.getLong(KEY_HOURLY_USED, 0L)
            } else {
                0L
            }
            return TimeUnit.MILLISECONDS.toSeconds((HOURLY_LIMIT_MS - used).coerceAtLeast(0L))
        }
    }

    fun nextLockWindowSeconds(context: Context): Long {
        val sessionRemaining = sessionRemainingSeconds(context)
        val dailyRemaining = remainingSeconds(context)
        return minOf(sessionRemaining, dailyRemaining)
    }

    fun cooldownRemainingSeconds(context: Context): Long {
        synchronized(lock) {
            ensureFreshDay(context)
            val now = System.currentTimeMillis()
            val currentStamp = hourStamp(now)
            val storedStamp = prefs(context).getLong(KEY_HOURLY_STAMP, 0L)
            if (storedStamp != currentStamp) {
                return 0L
            }
            val nextHour = nextHourEpochMillis(now)
            val diff = nextHour - now
            return TimeUnit.MILLISECONDS.toSeconds(diff.coerceAtLeast(0L))
        }
    }

    fun isQuotaExceeded(context: Context): Boolean = getUsageSeconds(context) >= DAILY_QUOTA_SECONDS

    fun isHardBlocked(now: LocalTime = LocalTime.now()): Boolean {
        return now.isAfter(HARD_BLOCK_START) || now.isBefore(HARD_BLOCK_END)
    }

    fun hardBlockStart(): LocalTime = HARD_BLOCK_START
    fun hardBlockEnd(): LocalTime = HARD_BLOCK_END

    fun blockStatus(context: Context): BlockStatus {
        ensureFreshDay(context)
        val hourlyRemaining = sessionRemainingSeconds(context)
        val status = when {
            isHardBlocked() -> BlockStatus.HARD_WINDOW
            isQuotaExceeded(context) -> BlockStatus.QUOTA
            hourlyRemaining <= 0L -> BlockStatus.COOLDOWN
            else -> BlockStatus.NONE
        }
        if (status != lastStatus) {
            lastStatus = status
            FocusLogger.i("Block status changed to $status")
        }
        return status
    }

    fun shouldDenyAccess(context: Context): Boolean = blockStatus(context) != BlockStatus.NONE

    private fun updateHourlyLocked(prefs: SharedPreferences, millis: Long) {
        val now = System.currentTimeMillis()
        val currentStamp = hourStamp(now)
        val storedStamp = prefs.getLong(KEY_HOURLY_STAMP, 0L)
        var hourlyUsed = prefs.getLong(KEY_HOURLY_USED, 0L)
        if (storedStamp != currentStamp) {
            hourlyUsed = 0L
            FocusLogger.i("Hourly usage reset at hour boundary")
        }
        hourlyUsed += millis
        prefs.edit()
            .putLong(KEY_HOURLY_USED, hourlyUsed)
            .putLong(KEY_HOURLY_STAMP, currentStamp)
            .apply()
    }

    private fun hourStamp(now: Long): Long {
        val zone = ZoneId.systemDefault()
        val time = Instant.ofEpochMilli(now).atZone(zone)
        return (time.year.toLong() * 1_000_000L) + (time.dayOfYear.toLong() * 100L) + time.hour.toLong()
    }

    private fun nextHourEpochMillis(now: Long): Long {
        val zone = ZoneId.systemDefault()
        val time = Instant.ofEpochMilli(now).atZone(zone).plusHours(1)
        val nextHour = time.withMinute(0).withSecond(0).withNano(0)
        return nextHour.toInstant().toEpochMilli()
    }

    private fun appKey(sourceId: String) = "$KEY_APP_PREFIX$sourceId"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

}
