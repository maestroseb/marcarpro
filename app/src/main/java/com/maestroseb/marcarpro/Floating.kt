package com.maestroseb.marcarpro

import android.annotation.SuppressLint
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import kotlin.math.hypot

/** The screen size, for keeping floating windows on it. */
internal fun WindowManager.screenSize(): Point =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        currentWindowMetrics.bounds.let { Point(it.width(), it.height()) }
    } else {
        Point().also {
            @Suppress("DEPRECATION")
            defaultDisplay.getRealSize(it)
        }
    }

/**
 * A small overlay window positioned by its centre. [core] is the radius that must stay on
 * screen ([coreRight] towards the right edge, if something sticks out that way); the window
 * itself may be larger (the open ring) and spill over the edges.
 */
internal class Floater(
    private val wm: WindowManager,
    val view: View,
    size: Int,
    private val core: Int,
    flags: Int = 0,
    private val coreRight: Int = core,
) {
    val params = WindowManager.LayoutParams(
        size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or flags,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    var cx = 0
        private set
    var cy = 0
        private set
    private var attached = false

    // Asked once: on Android 11+ every ask is a call to the window manager, and dragging clamps per frame.
    private val screen = wm.screenSize()

    fun attach(x: Int, y: Int) {
        cx = x
        cy = y
        clamp()
        place()
        wm.addView(view, params)
        attached = true
    }

    fun detach() {
        if (attached) runCatching { wm.removeView(view) }
        attached = false
    }

    /**
     * Lets touches through (while the window is empty) or catches them again. Letting them
     * through, the window goes [hideAfter] ms later, once its closing animation is over.
     */
    fun setTouchable(touchable: Boolean, hideAfter: Long = HIDE_AFTER_MS) {
        val flags = if (touchable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        if (flags == params.flags) return
        params.flags = flags
        if (attached) wm.updateViewLayout(view, params)
        // An empty window must be GONE, not just transparent: Android 12+ blocks touches that pass
        // through another app's visible window (keyboards and apps under it stopped responding).
        view.removeCallbacks(hide)
        if (touchable) view.visibility = View.VISIBLE else view.postDelayed(hide, hideAfter)
    }

    private val hide = Runnable {
        if (params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0) view.visibility = View.GONE
    }

    fun moveTo(x: Int, y: Int) {
        cx = x
        cy = y
        clamp()
        place()
    }

    /**
     * Puts the window just off the top-left corner, still showing. For small windows that come and
     * go often: hiding one gives it a new surface each time it comes back, and the panel showed
     * that surface's first frame as a grey square until the next draw.
     */
    fun park() {
        params.x = -params.width - PARK_GAP
        params.y = -params.height - PARK_GAP
        if (attached) wm.updateViewLayout(view, params)
    }

    private companion object {
        const val HIDE_AFTER_MS = 400L
        const val PARK_GAP = 16
    }

    /** The window's area on screen. */
    fun bounds(): android.graphics.Rect = android.graphics.Rect(params.x, params.y, params.x + params.width, params.y + params.height)

    private fun clamp() {
        cx = cx.coerceIn(core, (screen.x - coreRight).coerceAtLeast(core))
        cy = cy.coerceIn(core, (screen.y - core).coerceAtLeast(core))
    }

    private fun place() {
        params.x = cx - params.width / 2
        params.y = cy - params.height / 2
        if (attached) wm.updateViewLayout(view, params)
    }
}

/**
 * Touch handling for floating buttons: a drag moves the window, a short tap and a long press
 * each do their own thing. A press that starts to move is a drag (or a slide, see [onSlide]),
 * never a tap or a long press.
 */
internal class FloatGestures(
    private val floater: Floater,
    private val longPressMs: Long = 550L,
    private val onTap: () -> Unit,
    private val onLongPress: (() -> Unit)? = null,
    /** Asked at touch-down: false makes this press a tap however long it lasts. */
    private val longPressEnabled: () -> Boolean = { true },
    private val onPressChanged: ((pressing: Boolean) -> Unit)? = null,
    private val onDragStart: (() -> Unit)? = null,
    /** [cancelled] when the system took the gesture away (not a real drop). */
    private val onDragEnd: ((x: Int, y: Int, cancelled: Boolean) -> Unit)? = null,
    private val onDragMove: ((x: Int, y: Int) -> Unit)? = null,
    /**
     * Asked at touch-down: true makes a moving press a slide (the window stays put): [onSlide]
     * follows the finger in screen pixels and gets `end` when it lifts (NaN if cancelled).
     */
    private val slideEnabled: () -> Boolean = { false },
    private val onSlide: ((x: Float, y: Float, end: Boolean) -> Unit)? = null,
) : View.OnTouchListener {
    private val handler = Handler(Looper.getMainLooper())
    private var downX = 0f
    private var downY = 0f
    private var startCx = 0
    private var startCy = 0
    private var dragging = false
    private var sliding = false
    private var slid = false
    private var longFired = false
    private val longPress = Runnable {
        longFired = true
        onPressChanged?.invoke(false)
        onLongPress?.invoke()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, e: MotionEvent): Boolean {
        // Twice the usual slop: on the IR board a finger held still still wanders a little.
        val slop = 2 * ViewConfiguration.get(v.context).scaledTouchSlop
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX
                downY = e.rawY
                startCx = floater.cx
                startCy = floater.cy
                dragging = false
                sliding = onSlide != null && slideEnabled()
                slid = false
                longFired = false
                v.isPressed = true
                onPressChanged?.invoke(true)
                if (onLongPress != null && longPressEnabled()) handler.postDelayed(longPress, longPressMs)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (sliding) {
                    if (!slid && hypot(dx, dy) > slop) {
                        slid = true
                        handler.removeCallbacks(longPress)
                        v.isPressed = false
                        onPressChanged?.invoke(false)
                    }
                    if (slid) onSlide?.invoke(e.rawX, e.rawY, false)
                    return true
                }
                if (!dragging && !longFired && hypot(dx, dy) > slop) {
                    dragging = true
                    handler.removeCallbacks(longPress)
                    v.isPressed = false
                    onPressChanged?.invoke(false)
                    onDragStart?.invoke()
                    // Follow from here, so the window doesn't jump by the touch slop.
                    downX = e.rawX
                    downY = e.rawY
                    return true
                }
                if (dragging) {
                    floater.moveTo(startCx + (e.rawX - downX).toInt(), startCy + (e.rawY - downY).toInt())
                    onDragMove?.invoke(floater.cx, floater.cy)
                }
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPress)
                v.isPressed = false
                when {
                    slid -> onSlide?.invoke(e.rawX, e.rawY, true)
                    dragging -> onDragEnd?.invoke(floater.cx, floater.cy, false)
                    !longFired -> {
                        onPressChanged?.invoke(false)
                        v.performClick()
                        onTap()
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPress)
                v.isPressed = false
                if (!longFired) onPressChanged?.invoke(false)
                if (slid) onSlide?.invoke(Float.NaN, Float.NaN, true)
                if (dragging) onDragEnd?.invoke(floater.cx, floater.cy, true)
            }
        }
        return true
    }
}
