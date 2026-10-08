package com.magnify.app

import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View

/** Central place for all vibration feedback (respects the system touch-feedback setting). */
object Haptics {
    @Volatile var enabled = true
    private var last = 0L
    private fun go(v: View?, c: Int) { if (enabled) v?.performHapticFeedback(c) }

    fun click(v: View?) = go(v, HapticFeedbackConstants.CONTEXT_CLICK)

    fun tick(v: View?) {
        val now = SystemClock.uptimeMillis()
        if (now - last < 35) return
        last = now
        go(v, HapticFeedbackConstants.CLOCK_TICK)
    }

    fun confirm(v: View?) =
        go(v, if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS)

    fun limit(v: View?) =
        go(v, if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)

    fun heavy(v: View?) = go(v, HapticFeedbackConstants.LONG_PRESS)
}
