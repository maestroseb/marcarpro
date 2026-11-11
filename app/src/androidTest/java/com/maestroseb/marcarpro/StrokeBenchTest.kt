package com.maestroseb.marcarpro

import android.content.Intent
import android.graphics.PointF
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * How much a frame costs while a stroke is being written, by the stroke's shape and size, and what
 * the frames after the pen lifts cost (the lift itself, and the ink drying into the layer). Not part of the normal run: pass `-e bench 1` to
 * `am instrument`, then read the lines tagged BENCH in logcat (times in ms: median / 90 % / worst).
 *
 * ui = input + draw on the main thread; sync = uploading changed bitmaps; issue = Skia drawing;
 * swap = handing the buffer over; total = from the frame's vsync to done.
 */
class StrokeBenchTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefs = Prefs(context)
    private val board = File(context.filesDir, PageKeys.fileName(PageKeys.GENERAL))
    private val kept = File(context.cacheDir, "board.before-bench")
    private val whiteboard = File(context.filesDir, PageKeys.fileName(PageKeys.WHITEBOARD))
    private val keptWhiteboard = File(context.cacheDir, "whiteboard.before-bench")
    private val currentBoard = prefs.currentBoard
    private val quickPage = prefs.quickPage
    private val quickDay = prefs.quickDay
    private var on = false
    private var orbX = 0
    private var orbY = 0

    @Before
    fun fresh() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("bench") == "1")
        on = true
        stopLayer()
        if (board.exists()) board.renameTo(kept)
        if (whiteboard.exists()) whiteboard.renameTo(keptWhiteboard)
        val size = context.getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds
        orbX = size.width() - (64 * context.resources.displayMetrics.density).toInt()
        orbY = (size.height() * 0.4f).toInt()
        prefs.savePosition("orb", orbX, orbY)
        prefs.currentBoard = null
        prefs.quickPage = 0
        prefs.quickDay = 0
    }

    @After
    fun restore() {
        if (!on) return
        stopLayer()
        board.delete()
        if (kept.exists()) kept.renameTo(board)
        whiteboard.delete()
        if (keptWhiteboard.exists()) keptWhiteboard.renameTo(whiteboard)
        prefs.currentBoard = currentBoard
        prefs.quickPage = quickPage
        prefs.quickDay = quickDay
        prefs.savePosition("orb", 1860, 420) // where the other tests leave it
    }

    @Test
    fun strokes() {
        val size = context.getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds
        val w = size.width().toFloat()
        val h = size.height().toFloat()
        val u = w / 3840f // shapes are laid out for the panel's 3840 × 2160 and scaled to this screen
        Log.i(TAG, "screen ${size.width()}x${size.height()}")
        val shapes = linkedMapOf(
            "small" to List(400) { k -> PointF(u * (600 + (k % 40) * 6f), u * (500 + (k / 40) * 24f + if (k % 80 < 40) 0f else 12f)) },
            "word" to List(400) { k -> PointF(u * (400 + k * 3.5f), u * (900 + 180 * sin(k * 2 * PI / 50).toFloat())) },
            "circle" to List(400) { k -> PointF(u * (1900 + 800 * cos(k * 2 * PI / 400).toFloat()), u * (1080 + 800 * sin(k * 2 * PI / 400).toFloat())) },
            "diagonal" to List(400) { k -> PointF(u * (300 + k * 8f), u * (300 + k * 4f)) },
            "spiral" to List(1200) { k -> PointF(u * (1900 + (100 + k * 0.7f) * 1.6f * cos(k * 2 * PI / 150).toFloat()), u * (1080 + (100 + k * 0.7f) * sin(k * 2 * PI / 150).toFloat())) },
        )
        for ((name, shape) in shapes) {
            stopLayer()
            board.delete()
            startWriting()
            measure(name, shape)
        }
        // A board already full of writing: every inked tile is drawn again each frame.
        stopLayer()
        board.delete()
        startWriting()
        for (row in 0 until 6) for (col in 0 until 5) {
            stroke(List(60) { k -> PointF(u * (120 + col * 700 + k * 9f), u * (200 + row * 330 + 90 * sin(k * 2 * PI / 20).toFloat())) }, pause = 2)
        }
        Thread.sleep(300)
        measure("small, 30 words still wet", shapes.getValue("small"))
        Thread.sleep(600)
        measure("small on a full board", shapes.getValue("small"))
        // The whiteboard: paper and its ruling under the ink.
        stopLayer()
        startLayer()
        context.startService(Intent(context, OverlayService::class.java).setAction(OverlayService.ACTION_WHITEBOARD))
        Thread.sleep(1500)
        measure("small on the whiteboard", shapes.getValue("small"))
        measure("circle on the whiteboard", shapes.getValue("circle"))
    }

    private fun measure(name: String, shape: List<PointF>) {
        stroke(shape, pause = 8, beforeUp = { report("$name live") }, afterDown = { reset() })
        Thread.sleep(400)
        report("$name lift")
        // A moment later the ink dries into the layer.
        reset()
        Thread.sleep(1200)
        report("$name dry")
    }

    private fun stroke(shape: List<PointF>, pause: Long, afterDown: () -> Unit = {}, beforeUp: () -> Unit = {}) {
        val ui = instrumentation.uiAutomation
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS })
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, p: PointF) {
            val c = arrayOf(MotionEvent.PointerCoords().apply { x = p.x; y = p.y; pressure = 0.5f; size = 0.05f; touchMajor = 6f; touchMinor = 6f })
            val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 1, props, c, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            ui.injectInputEvent(e, true)
            e.recycle()
        }
        send(MotionEvent.ACTION_DOWN, shape[0])
        for (k in 1 until shape.size) {
            Thread.sleep(pause)
            send(MotionEvent.ACTION_MOVE, shape[k])
            if (k == 8) afterDown()
        }
        beforeUp()
        reset()
        send(MotionEvent.ACTION_UP, shape.last())
    }

    private fun reset() {
        shell("dumpsys gfxinfo ${context.packageName} reset")
    }

    private fun report(name: String) {
        val dump = shell("dumpsys gfxinfo ${context.packageName} framestats")
        val at = dump.indexOf("Marcar Pro ink")
        if (at < 0) {
            Log.i(TAG, "$name: no ink window in gfxinfo")
            return
        }
        val from = dump.indexOf("---PROFILEDATA---", at)
        val to = dump.indexOf("---PROFILEDATA---", from + 10)
        if (from < 0 || to < 0) return
        val lines = dump.substring(from, to).lines().drop(1).filter { it.isNotBlank() }
        val head = lines.first().split(',')
        fun col(n: String) = head.indexOf(n)
        val rows = lines.drop(1).map { it.split(',') }.filter { r -> r.size >= head.size - 1 && r[col("Flags")] == "0" && r[col("FrameCompleted")] != "0" }
            .map { r -> LongArray(head.size) { i -> r.getOrNull(i)?.toLongOrNull() ?: 0L } }
        if (rows.isEmpty()) {
            Log.i(TAG, "$name: no frames")
            return
        }
        fun stat(label: String, a: String, b: String): String {
            val v = rows.map { (it[col(b)] - it[col(a)]) / 1e6 }.sorted()
            return "$label %.1f/%.1f/%.1f".format(v[v.size / 2], v[(v.size * 9) / 10], v.last())
        }
        Log.i(
            TAG,
            "%-26s n=%3d  %s  %s  %s  %s  %s".format(
                name, rows.size,
                stat("ui", "HandleInputStart", "SyncQueued"), stat("sync", "SyncStart", "IssueDrawCommandsStart"),
                stat("issue", "IssueDrawCommandsStart", "SwapBuffers"), stat("swap", "SwapBuffers", "FrameCompleted"),
                stat("total", "IntendedVsync", "FrameCompleted"),
            ),
        )
    }

    private fun shell(cmd: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(cmd)).bufferedReader().use { it.readText() }

    private fun startLayer() {
        androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, OverlayService::class.java))
        val until = SystemClock.uptimeMillis() + 5000
        while (!OverlayService.running && SystemClock.uptimeMillis() < until) Thread.sleep(100)
        Thread.sleep(1500)
    }

    /** The layer starts on the cursor: a tap on the orb brings the pen. */
    private fun startWriting() {
        startLayer()
        val ui = instrumentation.uiAutomation
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, orbX.toFloat(), orbY.toFloat(), 0)
            e.source = InputDevice.SOURCE_TOUCHSCREEN
            ui.injectInputEvent(e, true)
            e.recycle()
            Thread.sleep(40)
        }
        Thread.sleep(800)
    }

    private fun stopLayer() {
        context.stopService(Intent(context, OverlayService::class.java))
        val until = SystemClock.uptimeMillis() + 3000
        while (OverlayService.running && SystemClock.uptimeMillis() < until) Thread.sleep(50)
    }

    private companion object {
        const val TAG = "BENCH"
    }
}
