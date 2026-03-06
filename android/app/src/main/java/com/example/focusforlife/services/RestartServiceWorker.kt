package com.example.focusforlife.services

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.example.focusforlife.logging.FocusLogger

class RestartServiceWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {

    override fun doWork(): Result {
        val className = inputData.getString(KEY_SERVICE_CLASS).orEmpty()
        val action = inputData.getString(KEY_ACTION).orEmpty()
        if (className.isBlank()) {
            FocusLogger.w("RestartServiceWorker missing service class.")
            return Result.failure()
        }

        val serviceClass = try {
            Class.forName(className)
        } catch (e: ClassNotFoundException) {
            FocusLogger.w("RestartServiceWorker class not found: $className", e)
            return Result.failure()
        }

        val intent = Intent(applicationContext, serviceClass).apply {
            if (action.isNotBlank()) this.action = action
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(intent)
            } else {
                applicationContext.startService(intent)
            }
            Result.success()
        } catch (e: Exception) {
            FocusLogger.w("RestartServiceWorker failed to start service: $className", e)
            Result.retry()
        }
    }

    companion object {
        const val KEY_SERVICE_CLASS = "service_class"
        const val KEY_ACTION = "service_action"
    }
}
