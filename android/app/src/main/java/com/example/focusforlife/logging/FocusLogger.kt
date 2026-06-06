package com.example.focusforlife.logging

import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.io.File
import com.example.focusforlife.BuildConfig

/**
 * Lightweight diagnostics logger that writes to Logcat + app-local file.
 */
object FocusLogger {

    private const val TAG = "FocusDiag"
    private const val MAX_BUFFER_LINES = 400
    private const val MAX_LOG_BYTES = 512 * 1024
    private val buffer = ArrayDeque<String>(MAX_BUFFER_LINES)
    private val lock = Any()
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "FocusLogger").apply { isDaemon = true }
    }

    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
    }

    fun v(message: String) {
        log(Log.VERBOSE, "V", message, null, verboseOnly = true)
    }

    fun d(message: String) {
        log(Log.DEBUG, "D", message, null)
    }

    fun i(message: String) {
        log(Log.INFO, "I", message, null)
    }

    fun w(message: String, throwable: Throwable? = null) {
        log(Log.WARN, "W", message, throwable)
    }

    fun e(message: String, throwable: Throwable? = null) {
        log(Log.ERROR, "E", message, throwable)
    }

    fun recentLines(): List<String> {
        synchronized(lock) {
            return buffer.toList()
        }
    }

    private fun log(
        priority: Int,
        level: String,
        message: String,
        throwable: Throwable?,
        verboseOnly: Boolean = false
    ) {
        if (verboseOnly && !BuildConfig.DEBUG) return
        val timestamp = timeFormat.format(Date())
        val threadName = Thread.currentThread().name
        val line = "$timestamp [$threadName] $level $message"

        synchronized(lock) {
            if (buffer.size >= MAX_BUFFER_LINES) {
                buffer.removeFirst()
            }
            buffer.addLast(line)
        }

        Log.println(priority, TAG, line)
        if (throwable != null) {
            Log.println(priority, TAG, Log.getStackTraceString(throwable))
        }

        val context = appContext ?: return
        executor.execute {
            appendToFile(context, line, throwable)
        }
    }

    private fun appendToFile(context: Context, line: String, throwable: Throwable?) {
        try {
            val file = File(context.filesDir, "focus.log")
            if (file.exists() && file.length() > MAX_LOG_BYTES) {
                file.writeText("Log truncated at ${timeFormat.format(Date())}\n")
            }
            file.appendText(line + "\n")
            if (throwable != null) {
                file.appendText(Log.getStackTraceString(throwable) + "\n")
            }
        } catch (_: Exception) {
            // Best-effort logging; ignore file errors.
        }
    }
}
