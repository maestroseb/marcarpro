package com.maestroseb.marcarpro

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PointF
import android.os.SystemClock
import android.provider.MediaStore
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Drives the ink layer with real multi-finger gestures (adb `input` can only inject one
 * pointer). Each test leaves a screenshot in the app's external files dir for review:
 * `adb pull /sdcard/Android/data/com.maestroseb.marcarpro/files/`.
 */
class MultiTouchTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefs = Prefs(context)

    private val board = File(context.filesDir, PageKeys.fileName(PageKeys.GENERAL))
    private val kept = File(context.cacheDir, "board.before-test")
    private val fingerMode = prefs.fingerMode
    private val stylusSeen = prefs.stylusSeen
    private val whiteboard = File(context.filesDir, PageKeys.fileName(PageKeys.WHITEBOARD))
    private val keptWhiteboard = File(context.cacheDir, "whiteboard.before-test")
    // The whiteboard tests write the quick board's first page: that is the page that must open.
    private val currentBoard = prefs.currentBoard
    private val quickPage = prefs.quickPage
    private val quickDay = prefs.quickDay

    @Before
    fun freshLayer() {
        // Each test starts its own layer, with an empty board and the settings it sets. The
        // teacher's board is put aside and comes back afterwards (these run on the real panel).
        stopLayer()
        if (board.exists()) board.renameTo(kept)
        if (whiteboard.exists()) whiteboard.renameTo(keptWhiteboard)
        // The orb at the right edge, out of the way of the gestures (a manual test may have moved it).
        prefs.savePosition("orb", 1860, 420)
        prefs.currentBoard = null
        prefs.quickPage = 0
        prefs.quickDay = 0 // today: the quick board doesn't empty itself mid-test
    }

    @After
    fun restore() {
        stopLayer()
        board.delete()
        if (kept.exists()) kept.renameTo(board)
        whiteboard.delete()
        if (keptWhiteboard.exists()) keptWhiteboard.renameTo(whiteboard)
        prefs.fingerMode = fingerMode
        prefs.stylusSeen = stylusSeen
        prefs.currentBoard = currentBoard
        prefs.quickPage = quickPage
        prefs.quickDay = quickDay
    }

    private fun stopLayer() {
        context.stopService(Intent(context, OverlayService::class.java))
        val until = SystemClock.uptimeMillis() + 3000
        while (OverlayService.running && SystemClock.uptimeMillis() < until) Thread.sleep(50)
    }

    @Test
    fun severalPupilsWriteAtOnce() {
        prefs.fingerMode = FingerMode.WRITE
        startLayer()
        gesture(
            listOf(
                wave(200f, 260f, 700f),
                wave(200f, 520f, 700f),
                List(STEPS + 1) { k -> PointF(950f + k * 14f, 300f + k * 14f) },
            ),
        )
        shot("several_pupils")
    }

    @Test
    fun twoErasersRubOutAtOnce() {
        prefs.fingerMode = FingerMode.WRITE
        startLayer()
        // Three long lines, then two erasers (pen erasers, as the IR fist can't be faked) cross them together.
        for (y in listOf(300f, 450f, 600f)) {
            gesture(listOf(List(STEPS + 1) { k -> PointF(300f + k * 40f, y) }), tool = MotionEvent.TOOL_TYPE_STYLUS)
        }
        gesture(
            listOf(
                List(STEPS + 1) { k -> PointF(600f, 220f + k * 15f) },
                List(STEPS + 1) { k -> PointF(1200f, 220f + k * 15f) },
            ),
            tool = MotionEvent.TOOL_TYPE_ERASER,
        )
        shot("two_erasers")
        prefs.fingerMode = FingerMode.APPS
    }

    @Test
    fun pinchScalesAndRotatesTheSelection() {
        prefs.fingerMode = FingerMode.APPS
        startLayer()
        // A circle drawn with a stylus, selected with a finger tap, then pinched open and twisted.
        gesture(listOf(List(STEPS + 1) { k -> ring(800f, 450f, 120f, k * 2 * PI / STEPS) }), tool = MotionEvent.TOOL_TYPE_STYLUS)
        gesture(listOf(List(3) { PointF(920f, 450f) }))
        Thread.sleep(400)
        shot("pinch_before")
        gesture(
            listOf(
                List(STEPS + 1) { k -> ring(800f, 450f, 60f + k * 4f, PI + k * 0.02) },
                List(STEPS + 1) { k -> ring(800f, 450f, 60f + k * 4f, k * 0.02) },
            ),
        )
        shot("pinch_after")
    }

    @Test
    fun whiteboardPansAndZooms() {
        prefs.fingerMode = FingerMode.APPS
        startLayer()
        context.startService(Intent(context, OverlayService::class.java).setAction(OverlayService.ACTION_WHITEBOARD))
        Thread.sleep(800)
        // A circle with the pen, then two fingers on empty paper spread apart: the paper zooms in.
        gesture(listOf(List(STEPS + 1) { k -> ring(700f, 450f, 100f, k * 2 * PI / STEPS) }), tool = MotionEvent.TOOL_TYPE_STYLUS)
        shot("whiteboard_before")
        gesture(
            listOf(
                List(STEPS + 1) { k -> PointF(400f - k * 4f, 800f) },
                List(STEPS + 1) { k -> PointF(500f + k * 4f, 800f) },
            ),
        )
        // Written after zooming, it must land right under the pen.
        gesture(listOf(List(STEPS + 1) { k -> PointF(1200f + k * 10f, 300f) }), tool = MotionEvent.TOOL_TYPE_STYLUS)
        shot("whiteboard_zoomed")
        // One finger drags the paper back to the left.
        gesture(listOf(List(STEPS + 1) { k -> PointF(1500f - k * 20f, 900f) }))
        shot("whiteboard_panned")
    }

    @Test
    fun aCutoutMovesWithAFinger() {
        prefs.fingerMode = FingerMode.APPS
        prefs.stylusSeen = true // fingers select and move; only the pen writes
        // A whiteboard holding one cutout at a known place (the camera starts unmoved).
        val image = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF3B5BDB.toInt()) }
        InkStore.write(whiteboard, InkStore.encode(listOf(Stroke.picture(image, null, 600f, 300f, 900f, 500f))))
        startLayer()
        context.startService(Intent(context, OverlayService::class.java).setAction(OverlayService.ACTION_WHITEBOARD))
        // The saved whiteboard is read in the background: give it time to come back.
        Thread.sleep(3000)
        // A finger on the cutout drags it 400 px right and 200 px down.
        gesture(listOf(List(STEPS + 1) { k -> PointF(750f + k * 400f / STEPS, 400f + k * 200f / STEPS) }))
        Thread.sleep(600)
        shot("cutout_moved")
        stopLayer() // saves the whiteboard
        val picture = InkStore.read(whiteboard).single { it.tool == InkTool.IMAGE }
        assertEquals(1000f, picture.bounds.left, 30f)
        assertEquals(500f, picture.bounds.top, 30f)
    }

    @Test
    fun aCutoutCanBeThrownOnTheWhiteboard() {
        prefs.fingerMode = FingerMode.APPS
        prefs.stylusSeen = true
        val image = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF3B5BDB.toInt()) }
        InkStore.write(whiteboard, InkStore.encode(listOf(Stroke.picture(image, null, 600f, 300f, 900f, 500f))))
        startLayer()
        context.startService(Intent(context, OverlayService::class.java).setAction(OverlayService.ACTION_WHITEBOARD))
        Thread.sleep(3000)
        // A quick flick to the right: the finger goes 480 px, the cutout slides on after it is let go.
        gesture(listOf(List(9) { k -> PointF(750f + k * 60f, 400f) }))
        Thread.sleep(2500)
        shot("cutout_thrown")
        stopLayer()
        val picture = InkStore.read(whiteboard).single { it.tool == InkTool.IMAGE }
        // Let go at left = 1080, it slid on (and bounced off the right edge): it is elsewhere, still on screen.
        assertTrue("slid on, now at ${picture.bounds.left}", abs(picture.bounds.left - 1080f) > 100f)
        assertTrue("stayed on screen", picture.bounds.left >= -1f && picture.bounds.right <= 1921f)
    }

    @Test
    fun anImageCopiedInAndroidIsPastedOnTheWhiteboard() {
        prefs.fingerMode = FingerMode.APPS
        prefs.stylusSeen = true
        // An image "copied in the browser": a picture in the media store, on the clipboard by its URI.
        val image = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFFE8590C.toInt()) }
        val resolver = context.contentResolver
        val uri = resolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "marcarpro-paste-test.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            },
        )!!
        try {
            resolver.openOutputStream(uri)!!.use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            instrumentation.runOnMainSync {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newUri(resolver, "test", uri))
            }
            startLayer()
            context.startService(Intent(context, OverlayService::class.java).setAction(OverlayService.ACTION_WHITEBOARD))
            Thread.sleep(3000)
            // Press and hold an empty spot, then let go.
            gesture(listOf(List(50) { PointF(500f, 800f) }))
            Thread.sleep(1500)
            shot("image_pasted")
            stopLayer()
        } finally {
            resolver.delete(uri, null, null)
        }
        val picture = InkStore.read(whiteboard).single { it.tool == InkTool.IMAGE }
        assertEquals(500f, picture.bounds.centerX(), 30f)
        assertEquals(800f, picture.bounds.centerY(), 30f)
        assertEquals(600f, picture.bounds.width(), 30f) // 400 px at 1.5 (dp → px on this screen)
    }

    @Test
    fun aPictureMenuHasNoColoursAndDeletes() {
        openWhiteboardWithPicture()
        tap(750f, 400f) // selects
        tap(750f, 400f) // menu: right of the picture, from y = 300 (rows of 48 dp = 72 px, after 8 dp = 12 px)
        shot("picture_menu")
        // Without the colours row, Delete is the third row.
        tap(1000f, 300f + 12f + 72f * 2.5f)
        stopLayer()
        assertTrue("the picture was deleted", InkStore.read(whiteboard).none { it.tool == InkTool.IMAGE })
    }

    @Test
    fun aCopiedPictureIsPasted() {
        openWhiteboardWithPicture()
        tap(750f, 400f)
        tap(750f, 400f)
        tap(1000f, 300f + 12f + 72f * 0.5f) // Copy
        // Press and hold an empty spot, then let go: the copy lands there.
        gesture(listOf(List(50) { PointF(500f, 800f) }))
        Thread.sleep(1500)
        shot("picture_pasted")
        stopLayer()
        val pictures = InkStore.read(whiteboard).filter { it.tool == InkTool.IMAGE }
        assertEquals(2, pictures.size)
        assertEquals(500f, pictures.last().bounds.centerX(), 30f)
        assertEquals(800f, pictures.last().bounds.centerY(), 30f)
    }

    @Test
    fun twoThrownPicturesFlyAtOnce() {
        prefs.fingerMode = FingerMode.APPS
        prefs.stylusSeen = true
        val blue = Bitmap.createBitmap(200, 150, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF3B5BDB.toInt()) }
        val green = Bitmap.createBitmap(200, 150, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF2F9E44.toInt()) }
        InkStore.write(
            whiteboard,
            InkStore.encode(listOf(Stroke.picture(blue, null, 300f, 300f, 500f, 450f), Stroke.picture(green, null, 300f, 650f, 500f, 800f))),
        )
        startLayer()
        context.startService(Intent(context, OverlayService::class.java).setAction(OverlayService.ACTION_WHITEBOARD))
        Thread.sleep(3000)
        // Two gentle flicks to the right, one after the other: the first must not stop when the second is thrown.
        gesture(listOf(List(9) { k -> PointF(400f + k * 26f, 375f) }))
        gesture(listOf(List(9) { k -> PointF(400f + k * 26f, 725f) }))
        Thread.sleep(4000)
        shot("two_thrown")
        stopLayer()
        val (first, second) = InkStore.read(whiteboard).filter { it.tool == InkTool.IMAGE }.sortedBy { it.bounds.top }
        // Let go at left ≈ 508; caught by the second throw it would stop near 900.
        assertTrue("the first slid on, now at ${first.bounds.left}", first.bounds.left > 1150f)
        assertTrue("the second slid on, now at ${second.bounds.left}", second.bounds.left > 1150f)
    }

    @Test
    fun aThrownPictureKnocksAnotherAway() {
        prefs.fingerMode = FingerMode.APPS
        prefs.stylusSeen = true
        val blue = Bitmap.createBitmap(200, 150, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF3B5BDB.toInt()) }
        val green = Bitmap.createBitmap(200, 150, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF2F9E44.toInt()) }
        InkStore.write(
            whiteboard,
            InkStore.encode(listOf(
                Stroke.picture(blue, null, 300f, 400f, 500f, 550f).apply { group = 1 },
                Stroke.picture(green, null, 1000f, 400f, 1200f, 550f).apply { group = 2 },
            )),
        )
        startLayer()
        context.startService(Intent(context, OverlayService::class.java).setAction(OverlayService.ACTION_WHITEBOARD))
        Thread.sleep(3000)
        // A quick flick to the right, straight at the green one.
        gesture(listOf(List(9) { k -> PointF(400f + k * 50f, 475f) }))
        Thread.sleep(3500)
        shot("knocked_away")
        stopLayer()
        val (first, second) = InkStore.read(whiteboard).filter { it.tool == InkTool.IMAGE }.sortedBy { it.bounds.left }
        // As heavy as each other: the thrown one all but stops, the green one takes the speed on.
        assertTrue("the green one was knocked on, now at ${second.bounds.left}", second.bounds.left > 1150f)
        assertTrue("they don't overlap", first.bounds.right <= second.bounds.left + 2f)
    }

    @Test
    fun aThrownPictureHitsAStraightLineBetweenItsEnds() {
        prefs.fingerMode = FingerMode.APPS
        prefs.stylusSeen = true
        val blue = Bitmap.createBitmap(200, 150, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF3B5BDB.toInt()) }
        // A ruler-straight line: two points only, both far above and below where the picture passes.
        val line = Stroke(InkTool.PEN, 0xFF1F2329.toInt(), 6f, 1f).apply {
            add(1000f, 200f, 1f)
            add(1000f, 750f, 1f)
            uniform = true
            finish()
            group = 2
        }
        InkStore.write(
            whiteboard,
            InkStore.encode(listOf(Stroke.picture(blue, null, 300f, 400f, 500f, 550f).apply { group = 1 }, line)),
        )
        startLayer()
        context.startService(Intent(context, OverlayService::class.java).setAction(OverlayService.ACTION_WHITEBOARD))
        Thread.sleep(3000)
        gesture(listOf(List(9) { k -> PointF(400f + k * 50f, 475f) }))
        Thread.sleep(3500)
        shot("line_hit")
        stopLayer()
        val saved = InkStore.read(whiteboard)
        val picture = saved.first { it.tool == InkTool.IMAGE }
        val hitLine = saved.first { it.tool == InkTool.PEN }
        assertTrue("the line was knocked on, now at ${hitLine.bounds.left}", hitLine.bounds.left > 1010f)
        assertTrue("the picture didn't go through it", picture.bounds.right <= hitLine.bounds.left + 2f)
    }

    @Test
    fun aKnockOffCentreTurnsIt() {
        prefs.fingerMode = FingerMode.APPS
        prefs.stylusSeen = true
        val blue = Bitmap.createBitmap(200, 150, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF3B5BDB.toInt()) }
        val green = Bitmap.createBitmap(200, 150, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF2F9E44.toInt()) }
        InkStore.write(
            whiteboard,
            InkStore.encode(listOf(
                Stroke.picture(blue, null, 300f, 400f, 500f, 550f).apply { group = 1 },
                // Lower than the thrown one: hit near its top edge.
                Stroke.picture(green, null, 1000f, 500f, 1200f, 650f).apply { group = 2 },
            )),
        )
        startLayer()
        context.startService(Intent(context, OverlayService::class.java).setAction(OverlayService.ACTION_WHITEBOARD))
        Thread.sleep(3000)
        gesture(listOf(List(9) { k -> PointF(400f + k * 50f, 475f) }))
        Thread.sleep(3500)
        shot("knock_turns")
        stopLayer()
        val green2 = InkStore.read(whiteboard).filter { it.tool == InkTool.IMAGE }.maxBy { it.bounds.left }
        val (tl, tr) = green2.points
        assertTrue("it turned: top edge from (${tl.x}, ${tl.y}) to (${tr.x}, ${tr.y})", abs(tr.y - tl.y) > 5f)
    }

    @Test
    fun aWordIsKnockedAwayWhole() {
        prefs.fingerMode = FingerMode.APPS
        prefs.stylusSeen = true
        val blue = Bitmap.createBitmap(200, 150, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF3B5BDB.toInt()) }
        fun letter(x: Float) = Stroke(InkTool.PEN, android.graphics.Color.BLACK, 6f, 14f).apply {
            for (k in 0..10) add(x + k * 4f, 420f + k * 10f, 0.5f)
            finish()
            group = 3
        }
        InkStore.write(
            whiteboard,
            InkStore.encode(listOf(Stroke.picture(blue, null, 300f, 400f, 500f, 550f).apply { group = 1 }, letter(1000f), letter(1080f))),
        )
        startLayer()
        context.startService(Intent(context, OverlayService::class.java).setAction(OverlayService.ACTION_WHITEBOARD))
        Thread.sleep(3000)
        gesture(listOf(List(9) { k -> PointF(400f + k * 50f, 475f) }))
        Thread.sleep(3500)
        shot("word_knocked")
        stopLayer()
        val (a, b) = InkStore.read(whiteboard).filter { it.tool == InkTool.PEN }.sortedBy { it.bounds.left }
        assertTrue("the word was knocked on, now at ${a.bounds.left}", a.bounds.left > 1050f)
        assertEquals("its letters moved together", 80f, b.bounds.left - a.bounds.left, 1f)
    }

    @Test
    fun aZoomedPictureCanBeResizedByItsCorner() {
        prefs.fingerMode = FingerMode.APPS
        prefs.stylusSeen = true
        val image = Bitmap.createBitmap(100, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF3B5BDB.toInt()) }
        InkStore.write(whiteboard, InkStore.encode(listOf(Stroke.picture(image, null, 700f, 700f, 800f, 760f))))
        startLayer()
        context.startService(Intent(context, OverlayService::class.java).setAction(OverlayService.ACTION_WHITEBOARD))
        Thread.sleep(3000)
        // Two fingers spread around the picture: the whiteboard zooms ×2 about (750, 730).
        gesture(listOf(
            List(STEPS + 1) { k -> PointF(650f - k * 100f / STEPS, 730f) },
            List(STEPS + 1) { k -> PointF(850f + k * 100f / STEPS, 730f) },
        ))
        Thread.sleep(500)
        tap(750f, 730f) // selects: on screen it now spans 650..850 × 670..790
        // The bottom-right handle sits 10 dp (15 px on screen) outside the corner, whatever the zoom.
        gesture(listOf(List(STEPS + 1) { k -> PointF(865f + k * 100f / STEPS, 805f + k * 50f / STEPS) }))
        Thread.sleep(600)
        shot("zoomed_resize")
        stopLayer()
        val picture = InkStore.read(whiteboard).single { it.tool == InkTool.IMAGE }
        assertTrue("resized to ${picture.bounds.width()}", picture.bounds.width() > 130f)
    }

    private fun openWhiteboardWithPicture() {
        prefs.fingerMode = FingerMode.APPS
        prefs.stylusSeen = true
        val image = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF3B5BDB.toInt()) }
        InkStore.write(whiteboard, InkStore.encode(listOf(Stroke.picture(image, null, 600f, 300f, 900f, 500f))))
        startLayer()
        context.startService(Intent(context, OverlayService::class.java).setAction(OverlayService.ACTION_WHITEBOARD))
        Thread.sleep(3000)
    }

    @Test
    fun inkLooksTheSameWetAndOnceDry() {
        openWhiteboard()
        val paper = grab()
        // A long line of loops with the pen: it is laid down in pieces while it is written.
        gesture(listOf(List(260) { k -> PointF(300f + k * 4f, 520f + 140f * sin(k * 2 * PI / 52).toFloat()) }), tool = MotionEvent.TOOL_TYPE_STYLUS)
        val wet = grab() // just lifted: the ink still shows from its wet cells
        Thread.sleep(1800)
        val dry = grab() // by now it is in the layer
        var ink = 0
        var changed = 0
        for (y in 330 until 710) for (x in 250 until 1400) {
            if (differ(dry, paper, x, y, 120)) ink++
            if (differ(wet, dry, x, y, 6)) changed++
        }
        assertTrue("the line is on the board ($ink px)", ink > 4000)
        assertEquals("drying doesn't change a pixel", 0, changed)
        stopLayer()
        assertEquals(1, InkStore.read(whiteboard).count { it.tool == InkTool.PEN })
    }

    @Test
    fun inkWrittenOnAZoomedBoardLooksTheSameWetAndOnceDry() {
        openWhiteboard()
        // Two fingers spread on the empty paper: it zooms in. Then a long line with the pen.
        gesture(
            listOf(
                List(STEPS + 1) { k -> PointF(900f - k * 4f, 850f) },
                List(STEPS + 1) { k -> PointF(1000f + k * 4f, 850f) },
            ),
        )
        Thread.sleep(400)
        val paper = grab()
        gesture(listOf(List(200) { k -> PointF(300f + k * 5f, 450f + 120f * sin(k * 2 * PI / 50).toFloat()) }), tool = MotionEvent.TOOL_TYPE_STYLUS)
        val wet = grab()
        Thread.sleep(1800)
        val dry = grab()
        var ink = 0
        var changed = 0
        for (y in 280 until 620) for (x in 250 until 1400) {
            if (differ(dry, paper, x, y, 120)) ink++
            if (differ(wet, dry, x, y, 6)) changed++
        }
        assertTrue("the line is on the board ($ink px)", ink > 4000)
        assertEquals("drying doesn't change a pixel", 0, changed)
    }

    @Test
    fun wetInkIsRubbedOutLikeAnyOther() {
        openWhiteboard()
        val paper = grab()
        // Two lines, and at once (both still wet) an eraser across the first.
        gesture(listOf(List(STEPS + 1) { k -> PointF(300f + k * 30f, 400f) }), tool = MotionEvent.TOOL_TYPE_STYLUS)
        gesture(listOf(List(STEPS + 1) { k -> PointF(300f + k * 30f, 600f) }), tool = MotionEvent.TOOL_TYPE_STYLUS)
        gesture(listOf(List(STEPS + 1) { k -> PointF(750f, 300f + k * 7f) }), tool = MotionEvent.TOOL_TYPE_ERASER)
        Thread.sleep(500) // the rubbed-out ink fades
        val after = grab()
        assertTrue("the first line is cut where the eraser passed", !differ(after, paper, 750, 400, 60))
        assertTrue("and stays either side", differ(after, paper, 500, 400, 120) && differ(after, paper, 1000, 400, 120))
        assertTrue("the second line is whole", differ(after, paper, 750, 600, 120))
        stopLayer()
        assertEquals("the rubbed line is in two, the other whole", 3, InkStore.read(whiteboard).count { it.tool == InkTool.PEN })
    }

    @Test
    fun undoTakesBackInkThatIsStillWet() {
        openWhiteboard()
        val paper = grab()
        gesture(listOf(List(STEPS + 1) { k -> PointF(300f + k * 30f, 400f) }), tool = MotionEvent.TOOL_TYPE_STYLUS)
        gesture(listOf(List(STEPS + 1) { k -> PointF(300f + k * 30f, 600f) }), tool = MotionEvent.TOOL_TYPE_STYLUS)
        // A long press on the orb undoes the last line.
        gesture(listOf(List(60) { PointF(1845f, 420f) }))
        Thread.sleep(600)
        val after = grab()
        assertTrue("the first line stays", differ(after, paper, 750, 400, 120))
        assertTrue("the second is gone", !differ(after, paper, 750, 600, 60))
        stopLayer()
        assertEquals(1, InkStore.read(whiteboard).count { it.tool == InkTool.PEN })
    }

    @Test
    fun aCancelledStrokeLeavesNothingBehind() {
        openWhiteboard()
        val paper = grab()
        // A long stroke (already laid in pieces) that the system takes back before it ends.
        gesture(listOf(List(120) { k -> PointF(300f + k * 8f, 500f) }), tool = MotionEvent.TOOL_TYPE_STYLUS, cancel = true, midway = {
            assertTrue("it shows while it is written", differ(grab(), paper, 500, 500, 120))
        })
        Thread.sleep(300)
        val after = grab()
        var left = 0
        for (y in 460 until 540) for (x in 280 until 1280) if (differ(after, paper, x, y, 60)) left++
        assertEquals("nothing of it is left on the board", 0, left)
        stopLayer()
        assertTrue("nor saved", !whiteboard.exists() || InkStore.read(whiteboard).isEmpty())
    }

    /** Whether the two screenshots differ at a pixel by more than [by] (the three channels added up). */
    private fun differ(a: Bitmap, b: Bitmap, x: Int, y: Int, by: Int): Boolean {
        val p = a.getPixel(x, y)
        val q = b.getPixel(x, y)
        return abs((p shr 16 and 0xFF) - (q shr 16 and 0xFF)) + abs((p shr 8 and 0xFF) - (q shr 8 and 0xFF)) + abs((p and 0xFF) - (q and 0xFF)) > by
    }

    private fun openWhiteboard() {
        prefs.fingerMode = FingerMode.APPS
        prefs.stylusSeen = true
        startLayer()
        context.startService(Intent(context, OverlayService::class.java).setAction(OverlayService.ACTION_WHITEBOARD))
        Thread.sleep(1500)
    }

    private fun grab(): Bitmap = instrumentation.uiAutomation.takeScreenshot()!!

    private fun tap(x: Float, y: Float) {
        gesture(listOf(listOf(PointF(x, y), PointF(x, y))))
        Thread.sleep(400)
    }

    private fun startLayer() {
        // Straight to the layer (the launcher would also show the setup card over it).
        androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, OverlayService::class.java))
        val until = SystemClock.uptimeMillis() + 5000
        while (!OverlayService.running && SystemClock.uptimeMillis() < until) Thread.sleep(100)
        Thread.sleep(1500)
    }

    private fun wave(x0: Float, y0: Float, x1: Float) =
        List(STEPS + 1) { k -> PointF(x0 + (x1 - x0) * k / STEPS, y0 + sin(k * 2 * PI / STEPS).toFloat() * 40f) }

    private fun ring(cx: Float, cy: Float, r: Float, a: Double) =
        PointF(cx + cos(a).toFloat() * r, cy + sin(a).toFloat() * r)

    /** Plays several pointer paths at the same time (all the same length). */
    private fun gesture(
        paths: List<List<PointF>>, tool: Int = MotionEvent.TOOL_TYPE_FINGER, cancel: Boolean = false, midway: () -> Unit = {},
    ) {
        val ui = instrumentation.uiAutomation
        val n = paths.size
        val props = Array(n) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = tool } }
        val down = SystemClock.uptimeMillis()
        var time = down
        fun coords(step: Int, count: Int) = Array(count) { i ->
            MotionEvent.PointerCoords().apply {
                x = paths[i][step].x
                y = paths[i][step].y
                pressure = 0.5f
                size = 0.05f
                touchMajor = 6f
                touchMinor = 6f
            }
        }
        fun send(action: Int, count: Int, step: Int) {
            val e = MotionEvent.obtain(
                down, time, action, count, props.copyOf(count).requireNoNulls(), coords(step, count),
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
            )
            ui.injectInputEvent(e, true)
            e.recycle()
        }
        send(MotionEvent.ACTION_DOWN, 1, 0)
        for (k in 1 until n) send(MotionEvent.ACTION_POINTER_DOWN or (k shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), k + 1, 0)
        for (step in 1 until paths[0].size) {
            time += 16
            Thread.sleep(16)
            send(MotionEvent.ACTION_MOVE, n, step)
            if (step == paths[0].size * 3 / 4) midway()
        }
        val last = paths[0].size - 1
        time += 16
        for (k in n - 1 downTo 1) send(MotionEvent.ACTION_POINTER_UP or (k shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), k + 1, last)
        send(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, 1, last)
        Thread.sleep(300)
    }

    private fun shot(name: String) {
        Thread.sleep(400)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val dir = context.getExternalFilesDir(null) ?: return
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        const val STEPS = 30
    }
}
