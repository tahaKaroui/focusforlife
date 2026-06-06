package com.example.focusforlife.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.example.focusforlife.R
import com.example.focusforlife.ui.MainActivity

object FocusForegroundNotifications {
    const val CHANNEL_ID = "focus_guard"
    const val ALERT_CHANNEL_ID = "focus_alert"
    const val VPN_NOTIFICATION_ID = 1001
    const val OVERLAY_NOTIFICATION_ID = 1002
    const val ACCESSIBILITY_ALERT_ID = 1003

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val guardChannel = NotificationChannel(
            CHANNEL_ID,
            "FocusForLife Guard",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps focus enforcement running in the background."
        }
        val alertChannel = NotificationChannel(
            ALERT_CHANNEL_ID,
            "FocusForLife Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Critical alerts requiring user action."
        }
        manager.createNotificationChannel(guardChannel)
        manager.createNotificationChannel(alertChannel)
    }

    fun postAccessibilityDisabledAlert(context: Context) {
        ensureChannel(context)
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentImmutableFlag()
        val pending = PendingIntent.getActivity(context, 99, intent, flags)
        val notification = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("FocusForLife is OFF!")
            .setContentText("Tap to re-enable the accessibility service.")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("The blocker stopped after reboot. Tap → Accessibility → Installed apps → FocusForLife → turn on."))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setContentIntent(pending)
            .setAutoCancel(false)
            .setOngoing(true)
            .build()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(ACCESSIBILITY_ALERT_ID, notification)
    }

    fun cancelAccessibilityAlert(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(ACCESSIBILITY_ALERT_ID)
    }

    fun buildVpnNotification(context: Context, status: String): Notification {
        return baseBuilder(context)
            .setContentTitle("Focus VPN active")
            .setContentText(status)
            .setOngoing(true)
            .build()
    }

    fun buildOverlayNotification(context: Context): Notification {
        return baseBuilder(context)
            .setContentTitle("Focus overlay active")
            .setContentText("Session timer visible")
            .setOngoing(true)
            .build()
    }

    private fun baseBuilder(context: Context): NotificationCompat.Builder {
        val intent = Intent(context, MainActivity::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentImmutableFlag()
        val pendingIntent = PendingIntent.getActivity(context, 0, intent, flags)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
    }

    private fun pendingIntentImmutableFlag(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
    }
}
