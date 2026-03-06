package com.example.focusforlife.ui

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView
import com.example.focusforlife.logging.FocusLogger

/**
 * Fullscreen overlay that disables interaction when the blocker fires.
 */
class BlockedActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FocusLogger.init(this)
        FocusLogger.i("BlockedActivity shown")

        window.setFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        )

        val text = TextView(this).apply {
            text = "NO.\nGO BACK TO WORK."
            textSize = 28f
            setPadding(40, 200, 40, 200)
        }

        setContentView(text)
    }

    @Deprecated("onBackPressed is deprecated but kept to block exit from the blocker screen.")
    override fun onBackPressed() {
        // disabled on purpose
    }
}
