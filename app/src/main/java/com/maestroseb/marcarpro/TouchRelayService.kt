package com.maestroseb.marcarpro

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Hands finger touches that land on empty space to the app underneath the ink layer.
 *
 * An app's overlay window either receives every touch or none, so the layer catches them all
 * and replays the ones meant for the app below as accessibility gestures. This is what lets the
 * pen keep writing while a finger opens the browser, folders or menus. (Routing touches live,
 * through touch exploration, was tried and left the ViewBoard almost without touch: CLAUDE.md.)
 *
 * It takes the screenshot for "cut out to the whiteboard" ([screenshot]), only when asked.
 *
 * It also tells the layer which page is on screen: in a browser, the address in its address
 * bar (so each tab keeps its own ink); anywhere else, the general board. Only that one field
 * is read, a moment after the screen changes, and the address never leaves the device.
 */
class TouchRelayService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val check = Runnable {
        checkAt = 0L
        checkPage()
    }
    private var checkAt = 0L

    /** The page currently on screen (see [PageKeys]). */
    var page = PageKeys.GENERAL
        private set

    override fun onServiceConnected() {
        instance = this
        checkPage()
        onConnected?.invoke()
    }

    override fun onDestroy() {
        handler.removeCallbacks(check)
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg == packageName) return
        val switched = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        if (!switched) {
            // In a browser a new address or tab shows up as changed text; videos and animations
            // change other things all the time and are ignored.
            if (pkg !in PageKeys.BROWSERS) return
            if (event.contentChangeTypes and AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT == 0) return
        }
        // Throttle, don't debounce: a page that never stops changing must still be looked at.
        val at = SystemClock.uptimeMillis() + if (switched) SWITCH_SETTLE_MS else LOAD_SETTLE_MS
        if (checkAt != 0L && checkAt <= at) return
        handler.removeCallbacks(check)
        checkAt = at
        handler.postAtTime(check, at)
    }

    private fun checkPage() {
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        // Our own windows, the keyboard and the system shade don't change the page.
        if (pkg == packageName || pkg in TRANSIENT) return
        val next = if (pkg in PageKeys.BROWSERS) {
            val bar = root.findAccessibilityNodeInfosByViewId("$pkg:id/url_bar").firstOrNull() ?: return
            // While the teacher types in the bar, the page hasn't changed yet.
            if (bar.isFocused) return
            // A new tab shows the bar's hint ("Search or type URL"): a blank page, the general board.
            if (bar.isShowingHintText) PageKeys.GENERAL else PageKeys.fromAddressBar(bar.text) ?: return
        } else {
            PageKeys.GENERAL
        }
        if (next != page) {
            page = next
            onPageChanged?.invoke(next)
        }
    }

    override fun onInterrupt() = Unit

    /** The on-screen windows of [pkg], top first. */
    private fun rootsOf(pkg: String): List<AccessibilityNodeInfo> =
        (runCatching { windows }.getOrNull().orEmpty().mapNotNull { it.root } + listOfNotNull(rootInActiveWindow))
            .filter { it.packageName?.toString() == pkg }

    /** Whether [pkg] shows [text] anywhere on screen (a dialog's title, say). */
    fun shows(pkg: String, text: String): Boolean =
        rootsOf(pkg).any { root -> root.findAccessibilityNodeInfosByText(text).any { it.isVisibleToUser } }

    /**
     * Presses a button of [pkg]'s window as a finger would: found by its view id, or by its
     * [label]. Returns false if it isn't on screen.
     */
    fun press(pkg: String, viewId: String?, label: String?): Boolean {
        for (root in rootsOf(pkg)) {
            val node = viewId?.let { id -> root.findAccessibilityNodeInfosByViewId("$pkg:id/$id").firstOrNull { it.isVisibleToUser } }
                ?: label?.let { l -> root.findAccessibilityNodeInfosByText(l).firstOrNull { it.isVisibleToUser && it.text?.toString() == l } }
                ?: continue
            // The label may sit inside the clickable part.
            var target: AccessibilityNodeInfo? = node
            while (target != null && !target.isClickable) target = target.parent
            if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
        }
        return false
    }

    /**
     * What is on screen now, as a plain bitmap, or null (Android 10 and older, or refused).
     * Called back on the main thread.
     */
    fun screenshot(done: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            done(null)
            return
        }
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val buffer = result.hardwareBuffer
                // A hardware bitmap can't be cut or saved: copy it to a normal one.
                val shot = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                buffer.close()
                done(shot)
            }

            override fun onFailure(errorCode: Int) = done(null)
        })
    }

    /** Replays a tap (or a long press, when [durationMs] is long) at screen coordinates. */
    fun tap(x: Float, y: Float, durationMs: Long = TAP_MS, done: () -> Unit) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        val sent = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = done()
            override fun onCancelled(gestureDescription: GestureDescription?) = done()
        }, null)
        if (!sent) done()
    }

    /**
     * Replays a finger drag (a scroll, a swipe) along the given screen points, taking about as
     * long as the original so lists scroll the same distance, or fling when it was quick.
     */
    fun drag(xs: FloatArray, ys: FloatArray, durationMs: Long, done: () -> Unit) {
        if (xs.size < 2) {
            done()
            return
        }
        val path = Path().apply {
            moveTo(xs[0], ys[0])
            for (k in 1 until xs.size) lineTo(xs[k], ys[k])
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(MIN_DRAG_MS, MAX_DRAG_MS)))
            .build()
        val sent = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = done()
            override fun onCancelled(gestureDescription: GestureDescription?) = done()
        }, null)
        if (!sent) done()
    }

    companion object {
        private const val TAP_MS = 40L
        private const val MIN_DRAG_MS = 60L
        private const val MAX_DRAG_MS = 2000L
        private const val SWITCH_SETTLE_MS = 150L
        private const val LOAD_SETTLE_MS = 400L
        private val TRANSIENT = setOf(
            "com.android.systemui",
            "com.google.android.inputmethod.latin",
            "com.android.inputmethod.latin",
        )

        @Volatile
        var instance: TouchRelayService? = null
            private set

        /** Called (on the main thread) when the teacher turns the service on. */
        var onConnected: (() -> Unit)? = null

        /** Called on the main thread when the page on screen changes. */
        var onPageChanged: ((String) -> Unit)? = null
    }
}
