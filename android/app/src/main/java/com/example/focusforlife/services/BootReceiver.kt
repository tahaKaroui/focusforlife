package com.example.focusforlife.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.focusforlife.logging.FocusLogger
import com.example.focusforlife.ui.MainActivity

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

        // Try to re-enable automatically (works if WRITE_SECURE_SETTINGS was granted via ADB).
        val autoEnabled = AccessibilityUtils.ensureServiceEnabled(context)

        val overlayIntent = Intent(context, FocusOverlayService::class.java)
        val vpnIntent = Intent(context, FocusVpnService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(overlayIntent)
            context.startForegroundService(vpnIntent)
        } else {
            context.startService(overlayIntent)
            context.startService(vpnIntent)
        }

        if (!autoEnabled && !AccessibilityUtils.isServiceEnabled(context)) {
            FocusLogger.w("Accessibility disabled after reboot — launching setup")
            // Reset the MIUI setup flag so MainActivity re-opens autostart on next launch.
            context.getSharedPreferences("ffl_setup", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("miui_autostart_done", false)
                .putBoolean("battery_opt_done", false)
                .apply()
            // Open app so the user is guided through setup automatically.
            val mainIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(mainIntent)
        }
    }
}
