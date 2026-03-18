package com.example.focusforlife.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.focusforlife.logging.FocusLogger

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            return
        }
        FocusLogger.init(context)
        FocusLogger.i("Boot receiver fired: $action")
        val overlayIntent = Intent(context, FocusOverlayService::class.java)
        val vpnIntent = Intent(context, FocusVpnService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(overlayIntent)
            context.startForegroundService(vpnIntent)
        } else {
            context.startService(overlayIntent)
            context.startService(vpnIntent)
        }
    }
}
