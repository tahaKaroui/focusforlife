package com.example.focusforlife.admin

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import com.example.focusforlife.core.FocusLockManager
import com.example.focusforlife.logging.FocusLogger

object DeviceOwnerController {

    fun isDeviceOwner(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isDeviceOwnerApp(context.packageName)
    }

    fun applyPolicies(context: Context) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!dpm.isDeviceOwnerApp(context.packageName)) return

        val admin = ComponentName(context, FocusAdminReceiver::class.java)
        val inMaintenance = FocusLockManager.isMaintenanceActive(context)
        if (inMaintenance) {
            clearRestrictions(dpm, admin)
        } else {
            applyRestrictions(dpm, admin)
        }
        dpm.setLockTaskPackages(admin, arrayOf(context.packageName))
    }

    fun enforceLockTask(activity: Activity) {
        val dpm = activity.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!dpm.isDeviceOwnerApp(activity.packageName)) return
        if (FocusLockManager.isMaintenanceActive(activity)) return
        try {
            activity.startLockTask()
            FocusLogger.i("Lock task started")
        } catch (e: IllegalStateException) {
            FocusLogger.w("Failed to start lock task", e)
        }
    }

    fun exitLockTask(activity: Activity) {
        try {
            activity.stopLockTask()
            FocusLogger.i("Lock task stopped")
        } catch (_: Exception) {
            // Ignore if not in lock task.
        }
    }

    private fun applyRestrictions(dpm: DevicePolicyManager, admin: ComponentName) {
        dpm.addUserRestriction(admin, RESTRICTION_CONFIG_ACCESSIBILITY)
        dpm.addUserRestriction(admin, RESTRICTION_APPS_CONTROL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            dpm.addUserRestriction(admin, RESTRICTION_CONFIG_SETTINGS)
        }
        FocusLogger.i("Device owner restrictions applied")
    }

    private fun clearRestrictions(dpm: DevicePolicyManager, admin: ComponentName) {
        dpm.clearUserRestriction(admin, RESTRICTION_CONFIG_ACCESSIBILITY)
        dpm.clearUserRestriction(admin, RESTRICTION_APPS_CONTROL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            dpm.clearUserRestriction(admin, RESTRICTION_CONFIG_SETTINGS)
        }
        FocusLogger.i("Device owner restrictions cleared")
    }

    private const val RESTRICTION_CONFIG_ACCESSIBILITY = "no_config_accessibility"
    private const val RESTRICTION_APPS_CONTROL = "no_control_apps"
    private const val RESTRICTION_CONFIG_SETTINGS = "no_config_settings"
}
