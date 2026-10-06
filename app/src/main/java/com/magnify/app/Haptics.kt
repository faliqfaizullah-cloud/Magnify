package com.magnify.app

import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View

/** Central place for all vibration feedback (respects the system touch-feedback setting). */
object Haptics {
    private var last = 0L
    private fun go(v: View?, c: Int) { v?.performHapticFeedback(c) }

    /** Light tap: buttons, chips, toggles. */
    fun click(v: View?) = go(v, HapticFeedbackConstants.CONTEXT_CLICK)

    /** Very light tick for continuous gestures (zoom steps, ruler). Rate-limited. */
    fun tick(v: View?) {
        val now = SystemClock.uptimeMillis()
        if (now - last < 35) return
        last = now
        go(v, HapticFeedbackConstants.CLOCK_TICK)
    }

    /** Success: photo captured, text found. */
    fun confirm(v: View?) =
        go(v, if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS)

    /** Hitting min/max zoom. */
    fun limit(v: View?) =
        go(v, if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)

    /** Strong press: shutter. */
    fun heavy(v: View?) = go(v, HapticFeedbackConstants.LONG_PRESS)
}
