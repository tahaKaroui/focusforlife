package dev.focusforlife.android.ui

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import dev.focusforlife.android.R
import dev.focusforlife.android.logging.FocusLogger

/**
 * Fullscreen overlay that disables interaction when the blocker fires.
 * Shows for a few seconds, then dismisses itself back to the launcher.
 */
class BlockedActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FocusLogger.init(this)
        FocusLogger.i("BlockedActivity shown")

        window.setFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        )
        window.decorView.setBackgroundColor(INK_DEEP)

        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val urbanist = ResourcesCompat.getFont(this, R.font.urbanist)

        val logo = ImageView(this).apply {
            setImageResource(R.drawable.ic_ffl_logo)
            layoutParams = LinearLayout.LayoutParams(dp(96), dp(96)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(28)
            }
        }
        val no = TextView(this).apply {
            text = "NO."
            textSize = 52f
            typeface = urbanist
            setTextColor(BRAND_ORANGE)
            gravity = Gravity.CENTER
            paint.isFakeBoldText = true
        }
        val message = TextView(this).apply {
            text = "GO BACK TO WORK."
            textSize = 22f
            typeface = urbanist
            setTextColor(CREAM)
            letterSpacing = 0.12f
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(8), dp(24), 0)
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(logo)
            addView(no)
            addView(message)
        }
        setContentView(layout)

        handler.postDelayed({ finish() }, AUTO_DISMISS_MS)
    }

    override fun onStart() {
        super.onStart()
        showing = true
    }

    override fun onStop() {
        showing = false
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    @Deprecated("onBackPressed is deprecated but kept to block exit from the blocker screen.")
    override fun onBackPressed() {
        // disabled on purpose
    }

    companion object {
        private const val AUTO_DISMISS_MS = 3_500L
        private const val INK_DEEP = 0xFF081D24.toInt()
        private const val BRAND_ORANGE = 0xFFF2A33C.toInt()
        private const val CREAM = 0xFFF6F1E7.toInt()

        @Volatile private var showing = false
        fun isShowing(): Boolean = showing
    }
}
