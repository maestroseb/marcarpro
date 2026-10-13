package com.maestroseb.marcarpro

import android.content.Context
import android.util.TypedValue
import android.view.InputDevice
import android.view.View
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation

internal fun Context.dp(value: Float): Float = value * resources.displayMetrics.density
internal fun Context.dpi(value: Float): Int = (dp(value) + 0.5f).toInt()
internal fun Context.sp(value: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

internal fun View.dp(value: Float): Float = context.dp(value)
internal fun View.dpi(value: Float): Int = context.dpi(value)

/** Design tokens for the overlay UI, which is built in code rather than XML. */
internal object Tokens {
    const val ACCENT = 0xFF0A84FF.toInt()
}

/** Springy press feedback: shrinks on touch, bounces back on release. */
internal class PressSpring(view: View) {
    private val x = SpringAnimation(view, DynamicAnimation.SCALE_X, 1f)
    private val y = SpringAnimation(view, DynamicAnimation.SCALE_Y, 1f)

    init {
        for (s in listOf(x, y)) {
            s.spring.stiffness = 900f
            s.spring.dampingRatio = 0.5f
        }
    }

    fun press(down: Boolean) {
        val target = if (down) 0.86f else 1f
        x.animateToFinalPosition(target)
        y.animateToFinalPosition(target)
    }
}

/** True when any connected input device declares stylus support. */
internal fun hasStylusHardware(): Boolean = InputDevice.getDeviceIds().any { id ->
    InputDevice.getDevice(id)?.supportsSource(InputDevice.SOURCE_STYLUS) == true
}
