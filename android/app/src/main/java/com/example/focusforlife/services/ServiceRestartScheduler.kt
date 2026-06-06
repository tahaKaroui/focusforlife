package com.example.focusforlife.services

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.focusforlife.logging.FocusLogger
import java.util.concurrent.TimeUnit

object ServiceRestartScheduler {
    private const val RESTART_DELAY_MS = 2_000L

    fun schedule(context: Context, serviceClass: Class<*>, action: String? = null) {
        val intent = Intent(context, serviceClass).apply {
            if (action != null) this.action = action
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentImmutableFlag()
        val requestCode = serviceClass.name.hashCode() xor (action?.hashCode() ?: 0)
        val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(context, requestCode, intent, flags)
        } else {
            PendingIntent.getService(context, requestCode, intent, flags)
        }
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = System.currentTimeMillis() + RESTART_DELAY_MS
        val canUseExactAlarm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }

        if (canUseExactAlarm) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                } else {
                    alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                }
                return
            } catch (e: SecurityException) {
                FocusLogger.w("Exact alarm denied; falling back to WorkManager.", e)
            }
        } else {
            FocusLogger.w("Exact alarm not allowed; falling back to WorkManager.")
        }

        scheduleWorkFallback(context, serviceClass, action)
    }

    private fun scheduleWorkFallback(context: Context, serviceClass: Class<*>, action: String?) {
        val uniqueName = "restart:${serviceClass.name}:${action ?: ""}"
        val data = workDataOf(
            RestartServiceWorker.KEY_SERVICE_CLASS to serviceClass.name,
            RestartServiceWorker.KEY_ACTION to (action ?: "")
        )
        val request = OneTimeWorkRequestBuilder<RestartServiceWorker>()
            .setInitialDelay(RESTART_DELAY_MS, TimeUnit.MILLISECONDS)
            .setInputData(data)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(uniqueName, ExistingWorkPolicy.REPLACE, request)
    }

    private fun pendingIntentImmutableFlag(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
    }
}
