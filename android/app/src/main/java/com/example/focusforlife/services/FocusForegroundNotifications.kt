package com.example.focusforlife.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.focusforlife.R
import com.example.focusforlife.ui.MainActivity

object FocusForegroundNotifications {
    const val CHANNEL_ID = "focus_guard"
    const val VPN_NOTIFICATION_ID = 1001
    const val OVERLAY_NOTIFICATION_ID = 1002

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "FocusForLife Guard",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps focus enforcement running in the background."
        }
        manager.createNotificationChannel(channel)
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
