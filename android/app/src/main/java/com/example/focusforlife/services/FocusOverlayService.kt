package com.example.focusforlife.services

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import com.example.focusforlife.R
import com.example.focusforlife.core.FocusRules
import com.example.focusforlife.logging.FocusLogger
import kotlin.math.abs

/**
 * Always-on overlay showing the remaining session/daily time.
 */
class FocusOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: android.view.View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var isExpanded = false
    private var isMinimized = false
    private var stopRequested = false
    private var started = false
    private val handler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            updateOverlay()
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        FocusLogger.init(this)
        FocusForegroundNotifications.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRequested = true
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) {
            startForeground(
                FocusForegroundNotifications.OVERLAY_NOTIFICATION_ID,
                FocusForegroundNotifications.buildOverlayNotification(this)
            )
            running = true
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            createOverlay()
            handler.post(updateRunnable)
            started = true
            FocusLogger.i("Overlay service started")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
        running = false
        FocusLogger.i("Overlay service stopped")
        if (!stopRequested) {
            ServiceRestartScheduler.schedule(this, FocusOverlayService::class.java)
        }
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!stopRequested) {
            FocusLogger.w("Overlay task removed; scheduling restart")
            ServiceRestartScheduler.schedule(this, FocusOverlayService::class.java)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createOverlay() {
        if (overlayView != null) return
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.overlay_focus_status, null)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 16
        params.y = 120
        windowManager?.addView(view, params)
        overlayView = view
        overlayParams = params
        setExpanded(view, false)
        setMinimized(view, false)
        attachDragHandler(view) { toggleMinimized() }
        updateOverlay()
    }

    private fun removeOverlay() {
        overlayView?.let {
            windowManager?.removeView(it)
        }
        overlayView = null
        overlayParams = null
    }

    private fun updateOverlay() {
        val view = overlayView ?: return
        FocusRules.ensureFreshDay(this)
        val hourlyLeft = FocusRules.sessionRemainingSeconds(this)
        val dailyLeft = FocusRules.remainingSeconds(this)
        val bubbleLabel = view.findViewById<TextView>(R.id.overlayBubbleLabel)
        val bubbleTime = view.findViewById<TextView>(R.id.overlayBubbleTime)
        bubbleLabel.text = "HOUR"
        bubbleTime.text = format(hourlyLeft)

        view.findViewById<TextView>(R.id.overlayTitle).text = "TIME LEFT"
        view.findViewById<TextView>(R.id.overlayBody).text =
            "Hourly: ${format(hourlyLeft)}\nDaily: ${format(dailyLeft)}"
    }

    private fun format(seconds: Long): String {
        val mins = seconds / 60
        val secs = seconds % 60
        return "%02d:%02d".format(mins, secs)
    }

    private fun toggleExpanded() {
        val view = overlayView ?: return
        setExpanded(view, !isExpanded)
    }

    private fun toggleMinimized() {
        val view = overlayView ?: return
        setMinimized(view, !isMinimized)
    }

    private fun setExpanded(view: android.view.View, expanded: Boolean) {
        isExpanded = expanded
        val bubble = view.findViewById<android.view.View>(R.id.overlayBubble)
        val expandedView = view.findViewById<android.view.View>(R.id.overlayExpanded)
        val minimizedView = view.findViewById<android.view.View>(R.id.overlayMinimized)
        if (expanded) {
            isMinimized = false
        }
        bubble.visibility =
            if (expanded || isMinimized) android.view.View.GONE else android.view.View.VISIBLE
        minimizedView.visibility =
            if (isMinimized && !expanded) android.view.View.VISIBLE else android.view.View.GONE
        expandedView.visibility = if (expanded) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun setMinimized(view: android.view.View, minimized: Boolean) {
        isMinimized = minimized
        val bubble = view.findViewById<android.view.View>(R.id.overlayBubble)
        val minimizedView = view.findViewById<android.view.View>(R.id.overlayMinimized)
        val expandedView = view.findViewById<android.view.View>(R.id.overlayExpanded)
        if (minimized) {
            isExpanded = false
        }
        bubble.visibility = if (minimized) android.view.View.GONE else android.view.View.VISIBLE
        minimizedView.visibility = if (minimized) android.view.View.VISIBLE else android.view.View.GONE
        expandedView.visibility = android.view.View.GONE
    }

    private fun attachDragHandler(view: android.view.View, onClick: () -> Unit) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var moved = false
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        view.setOnTouchListener { _, event ->
            val params = overlayParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    moved = false
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        moved = true
                        params.x = initialX + dx.toInt()
                        params.y = initialY + dy.toInt()
                        windowManager?.updateViewLayout(view, params)
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        onClick()
                    }
                    true
                }
                else -> false
            }
        }
    }

    companion object {
        const val ACTION_STOP = "com.example.focusforlife.action.STOP_OVERLAY"
        @Volatile private var running: Boolean = false
        fun isRunning(): Boolean = running
    }
}
