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

/**
 * The overlay's typeface (1.6): Atkinson Hyperlegible, drawn by the Braille Institute to be told
 * apart at a glance, from across a classroom. Loaded once; the system font if it can't be.
 */
internal object Fonts {
    private var regular: android.graphics.Typeface? = null
    private var bold: android.graphics.Typeface? = null

    fun regular(c: Context) = regular ?: load(c, R.font.atkinson_regular).also { regular = it }
    fun bold(c: Context) = bold ?: load(c, R.font.atkinson_bold).also { bold = it }

    private fun load(c: Context, res: Int) =
        runCatching { androidx.core.content.res.ResourcesCompat.getFont(c, res) }.getOrNull() ?: android.graphics.Typeface.DEFAULT
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
