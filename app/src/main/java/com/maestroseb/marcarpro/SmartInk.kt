package com.maestroseb.marcarpro

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo

/**
 * SMART panels: SMART Ink's annotation pops up over the apps, on top of Marcar Pro. Its master
 * switch («Capas de anotación») lives in Settings › SMART › Annotation, a screen no other app may
 * open and whose content accessibility services can't read (verified on a SMART MX V5: the window
 * is listed, its root comes back null). So this only takes the teacher there, pressing «SMART»
 * and «Annotation» in Settings through the accessibility service; the teacher flips the switch.
 * Main thread.
 */
internal class SmartInk(private val context: Context, private val relay: TouchRelayService) {

    private val handler = Handler(Looper.getMainLooper())
    private var step = Step.SMART
    private var stepSince = 0L
    private var done: ((Boolean) -> Unit)? = null

    private enum class Step { SMART, ANNOTATION }

    /** The annotation entry's possible titles: the screen's own label, then the usual ones. */
    private val annotationTitles by lazy {
        listOfNotNull(
            runCatching {
                context.packageManager.getActivityInfo(ComponentName(SMART_INK, ANNOTATION_SCREEN), 0).loadLabel(context.packageManager).toString()
            }.getOrNull()?.takeIf { it.isNotBlank() },
            "Anotación", "Annotation",
        ).distinct()
    }

    /** Opens Settings and walks to SMART › Annotation; [done] says whether it got there. */
    fun open(done: (Boolean) -> Unit) {
        this.done = done
        next(Step.SMART)
        context.startActivity(
            Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        handler.postDelayed(::tick, TICK_MS)
    }

    private fun next(s: Step) {
        step = s
        stepSince = SystemClock.uptimeMillis()
    }

    private fun tick() {
        if (done == null) return
        if (SystemClock.uptimeMillis() - stepSince > STEP_TIMEOUT_MS) return finish(false)
        when (step) {
            Step.SMART -> if (press("SMART")) next(Step.ANNOTATION)
            // The annotation screen shows as its own window, titled like the entry.
            Step.ANNOTATION -> if (relay.showsWindow(annotationTitles)) return finish(true) else annotationTitles.any { press(it) }
        }
        handler.postDelayed(::tick, TICK_MS)
    }

    /** Presses the Settings row showing exactly [text]. */
    private fun press(text: String): Boolean {
        val node = relay.exact(SETTINGS, text).firstOrNull() ?: return false
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        return target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    private fun finish(ok: Boolean) {
        val callback = done ?: return
        done = null
        handler.removeCallbacksAndMessages(null)
        callback(ok)
    }

    companion object {
        const val SMART_INK = "com.smarttech.smartink"
        private const val ANNOTATION_SCREEN = "com.smarttech.smartink.annotation_settings.AnnotationSettingsActivity"
        private const val SETTINGS = "com.android.settings"
        private const val TICK_MS = 400L
        private const val STEP_TIMEOUT_MS = 6000L
    }
}
