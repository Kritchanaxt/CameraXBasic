package com.android.example.cameraxbasic.utils

import android.os.Build
import android.view.DisplayCutout
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.ImageButton
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Milliseconds used for UI animations */
const val ANIMATION_FAST_MILLIS = 50L
const val ANIMATION_SLOW_MILLIS = 100L

/**
 * Simulate a button click, including a small delay while it is being pressed to trigger the
 * appropriate animations.
 */
fun ImageButton.simulateClick(delay: Long = ANIMATION_FAST_MILLIS) {
    performClick()
    isPressed = true
    invalidate()
    postDelayed({
        invalidate()
        isPressed = false
    }, delay)
}

/**
 * Pad top and bottom of the view to avoid [DisplayCutout]
 */
@RequiresApi(Build.VERSION_CODES.P)
fun View.padWithDisplayCutout() {

    /** Helper display cutout callback */
    fun cutoutCallback(cutout: DisplayCutout) {
        setPadding(
            cutout.safeInsetLeft,
            cutout.safeInsetTop,
            cutout.safeInsetRight,
            cutout.safeInsetBottom
        )
    }

    // Set padding for DisplayCutout
    post {
        display?.let {
            val cutout = it.cutout
            if (cutout != null) {
                cutoutCallback(cutout)
            }
        }
    }
}

/**
 * Extension function to show an [AlertDialog] in immersive mode (full screen).
 */
fun AlertDialog.showImmersive() {
    // Set the dialog to not focusable. This makes the dialog
    // non-interactive for a short little while so that we can
    // make the dialog full screen.
    window?.setFlags(
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
    )

    // Make sure that the dialog's window is in full screen
    window?.let { hideSystemUI(it) }

    // Show the dialog while still in immersive mode
    show()

    // Set the dialog to focusable again
    window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
}

/**
 * Helper function to hide system UI.
 */
private fun hideSystemUI(window: Window) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    } else {
        // For api level 29 and before, set deprecated systemUiVisibility to the combination of all
        // flags required to put activity into immersive mode.
        @Suppress("DEPRECATION")
        val fullscreenFlags =
            View.SYSTEM_UI_FLAG_LOW_PROFILE or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = fullscreenFlags
    }
}