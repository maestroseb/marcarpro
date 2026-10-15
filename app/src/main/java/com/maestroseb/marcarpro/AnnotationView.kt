package com.maestroseb.marcarpro

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.graphics.Typeface
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withMatrix
import androidx.input.motionprediction.MotionEventPredictor
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Transparent, full-screen ink surface.
 *
 * Input is routed by what touches the glass (see [classify]):
 *  - pen → the selected tool; fist, palm, eraser end or side button → erase
 *  - finger → selection gestures, or the app below (a tap on empty space); in "several
 *    pupils" mode fingers write instead, each with its own stroke
 *
 * Committed ink lives in a tiled bitmap layer ([InkLayer]) that is only repainted where it
 * changes (a full repaint only when the whole board changes: switching page, clearing, resizing).
 */
class AnnotationView(context: Context) : View(context) {

    /** Told whenever the ink changes (to save it). */
    fun interface Listener {
        fun onInkChanged()
    }

    var listener: Listener? = null
    var tool = Tool.PEN
    var inkColor = Color.BLACK
    var inkSize = dp(5f)

    /** Radius of the fist/palm eraser; the pen in eraser mode uses half of it, for precision. */
    var eraserSize = dp(48f)

    /** For boards without a stylus: fingers draw instead of selecting. */
    var fingerDraws = false

    /** "Several pupils" mode: every finger writes, whatever the board can detect. */
    var fingersWrite = false

    /** Hides the ink without deleting it; writing again shows it. */
    var inkHidden = false
        set(value) {
            if (field == value) return
            field = value
            if (value) {
                cancelInk()
                cancelGesture()
                clearSelection()
            }
            invalidate()
        }

    /** Called when new writing makes hidden ink visible again. */
    var onInkShown: (() -> Unit)? = null

    /** Called once, the first time a real stylus touches the glass. */
    var onStylusDetected: (() -> Unit)? = null

    /** A finger tap on empty space (with nothing selected) that belongs to the app underneath. */
    var onPassThroughTap: ((x: Float, y: Float) -> Unit)? = null

    /** A finger drag on empty space (e.g. a scroll), as screen-relative points and its duration. */
    var onPassThroughDrag: ((xs: FloatArray, ys: FloatArray, durationMs: Long) -> Unit)? = null

    /** Short notices for the teacher (e.g. "Copied"), as string resources. */
    var onMessage: ((Int) -> Unit)? = null

    /**
     * A long press on an empty spot of the whiteboard: the host looks in Android's clipboard (an
     * image copied in the browser, say) and either puts that at ([x], [y]) or calls [pasteCopied].
     * [copiedAt] is when our own Copy happened (0 if nothing), to keep whichever is newer.
     */
    var onPasteRequest: ((x: Float, y: Float, copiedAt: Long) -> Unit)? = null

    /**
     * The ring's "Lasso": the next stroke, pen or finger, draws a lasso (instead of ink, or of
     * moving the whiteboard) and selects what it surrounds; then everything is as before.
     */
    var lassoNext = false

    // The lasso in progress came from [lassoNext] (not from holding a finger still).
    private var lassoOnce = false

    /** Tells the host when this window gets (or loses) the keyboard focus. */
    var onFocus: ((Boolean) -> Unit)? = null

    /** When false the view only displays ink; the service also makes its window untouchable. */
    var annotating = false
        set(value) {
            if (field == value) return
            field = value
            if (!value) {
                cancelInk()
                cancelGesture()
                clearSelection()
            }
        }

    /**
     * One page's ink with its own undo history; the service keeps one per browser tab. A [paper]
     * board is the whiteboard: opaque, endless, seen through [camera] (world → screen), which a
     * finger pans and two fingers zoom.
     */
    internal class Board(onChanged: () -> Unit, val paper: Boolean = false) {
        val strokes = ArrayList<Stroke>()
        val history = History(strokes, onChanged)
        var nextGroup = 1
        val camera = Matrix()
    }

    private var board = newBoard()
    private val strokes get() = board.strokes
    private val history get() = board.history
    private val camera get() = board.camera
    private val inverse = Matrix()

    // Whiteboard: camera gestures (in screen pixels, from the untransformed event).
    private var raw: MotionEvent? = null
    private var panning = false
    private val cameraBase = Matrix()
    private var camX = 0f
    private var camY = 0f
    private var camDist = 1f
    private val view = RectF()
    private val visible = RectF()
    /** The whiteboard's background. */
    var paperStyle = Paper.DOTS
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }
    /** The whiteboard's paper colour; on a dark one black ink shows white (see [shown]). */
    var paperColor = PaperColor.WHITE
        set(value) {
            if (field == value) return
            field = value
            linePaint.color = if (value.dark) 0x2EFFFFFF else 0x241F2329
            staffPaint.color = if (value.dark) 0x5CFFFFFF else 0x4D1F2329
            gridPaint.color = if (value.dark) 0x40FFFFFF else 0x2E1F2329
            redrawLayer()
        }

    /** How [color] shows on this board: black ink turns white on a dark paper (it stays black). */
    private fun shown(color: Int): Int =
        if (board.paper && paperColor.dark && ColorUtils.calculateLuminance(color) < DARK_INK) {
            Color.argb(Color.alpha(color), 255, 255, 255)
        } else {
            color
        }

    private val linePaint = Paint().apply {
        color = 0x241F2329
        strokeWidth = dp(1.2f)
    }

    // Music staves: a little stronger than squares or lines, as on staff paper.
    private val staffPaint = Paint().apply {
        color = 0x4D1F2329
        strokeWidth = dp(1.2f)
    }
    private var gridLines = FloatArray(0)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x2E1F2329
        strokeWidth = dp(3.2f)
        strokeCap = Paint.Cap.ROUND
    }

    // Committed ink is rasterised once into this tiled layer; per frame only live ink is drawn.
    private val layer = InkLayer { c, s -> drawStroke(c, s, 1f) }
    private val lifted = LinkedHashSet<Stroke>()
    private val dirty = RectF()

    private class Fade(val stroke: Stroke, val start: Long)

    private val fades = ArrayList<Fade>()
    private val magic = ArrayList<Fade>()

    /**
     * MOVE/SCALE/ROTATE/PINCH act on [lifted] strokes through [liveMatrix] (cheap to draw) and
     * are applied to the strokes only when the finger lifts. DESELECT swallows a tap on empty
     * space that only cleared the selection.
     */
    private enum class Gesture { NONE, LASSO, MOVE, SCALE, ROTATE, PINCH, DESELECT, PAN }

    private var gesture = Gesture.NONE
    private var pointerId = NO_POINTER

    // Ink and erasers are tracked per pointer, so several pupils can write and rub out at once.
    private class Eraser(var x: Float, var y: Float, val radius: Float)

    /** A stroke being written, with the tool it was started with (the tool may change meanwhile). */
    private class Live(val stroke: Stroke, val tool: Tool) {
        var stale = false
    }

    private val inking = LinkedHashMap<Int, Live>()

    // Draws the pen's next few milliseconds ahead of the digitizer, so the ink doesn't trail the tip.
    // Created once attached (it reads the display), and left out if the device can't provide one.
    private var predictor: MotionEventPredictor? = null
    private val predictPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val erasing = LinkedHashMap<Int, Eraser>()
    private val eraseOps = ArrayList<History.Op>()
    private val grouper = WordGrouper(minUnit = dp(8f))
    private val lasso = Path()
    private var lastX = 0f
    private var lastY = 0f
    private var travelled = 0f
    private var downX = 0f
    private var downY = 0f
    private val selection = LinkedHashSet<Stroke>()
    private val selectionRect = RectF()

    // Selection gestures (see the region "Selection").
    private var pointerId2 = NO_POINTER
    private val liveMatrix = Matrix()
    private val pinchBase = Matrix()
    private var wasSelected = false
    private var moved = false
    private var menuShown = false
    private var pivotX = 0f
    private var pivotY = 0f
    private var startAngle = 0f
    private var startDist = 1f
    private var pinchX = 0f
    private var pinchY = 0f
    private val boxPts = FloatArray(10)
    private val boxScreen = FloatArray(10)
    private val boxPath = Path()
    private val tmpRect = RectF()
    private val velocity = VelocityTracker.obtain()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    /** A thrown object sliding on by itself; several can fly at once, each landing on its own. */
    private class Flight(val items: List<Stroke>, val matrix: Matrix, val bounds: RectF, val area: RectF, var vx: Float, var vy: Float) {
        var time = SystemClock.uptimeMillis()
    }

    private val flights = ArrayList<Flight>()

    // Strokes in the air: drawn through their flight's matrix, not from the layer, and not picked up.
    private val airborne = HashSet<Stroke>()
    private val flying get() = flights.isNotEmpty()
    private val flightStep = Runnable { stepFlights() }

    /** What the layer leaves out: the strokes in hand and the ones in the air. */
    private val offLayer = object : AbstractSet<Stroke>() {
        override val size get() = lifted.size + airborne.size
        override fun contains(element: Stroke) = element in lifted || element in airborne
        override fun iterator() = (lifted.asSequence() + airborne.asSequence()).iterator()
    }
    private val clipboard = ArrayList<Stroke>()
    private var copiedAt = 0L
    // A finger held still on empty space arms the lasso (multi-select) or, after Copy, pastes.
    private var lassoArmed = false
    private val armHold = Runnable { armLasso() }
    private val dragXs = FloatArray(MAX_DRAG_POINTS)
    private val dragYs = FloatArray(MAX_DRAG_POINTS)
    private var dragCount = 0
    private var dragStart = 0L

    // Selection menu: Copy, Duplicate, colours, Delete.
    private val menuRect = RectF()
    private val menuAnchor = RectF()
    private val menuRows = Array(4) { RectF() }
    private val swatchX = FloatArray(Presets.COLORS.size)

    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = Presets.MAGIC_GLOW
        maskFilter = BlurMaskFilter(dp(7f), BlurMaskFilter.Blur.NORMAL)
    }
    private val highlighterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val lassoShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x66000000
        strokeWidth = dp(3.5f)
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val lassoLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = dp(2f)
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val eraserFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x26FFFFFF }
    private val eraserShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x73000000
        strokeWidth = dp(3.5f)
    }
    private val eraserRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = dp(1.5f)
    }
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Tokens.ACCENT
        strokeWidth = dp(2f)
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(7f), dp(6f)), 0f)
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        setShadowLayer(dp(3f), 0f, dp(1f), 0x40000000)
    }
    private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Tokens.ACCENT
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
    }
    private val menuPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        setShadowLayer(dp(12f), 0f, dp(4f), 0x40000000)
    }
    private val menuText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = OrbStyle.LABEL
        textSize = context.sp(15f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val menuDivider = Paint().apply { color = 0x14000000 }
    private val swatchPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val swatchEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1f); color = 0x33000000 }
    private val menuIcons = intArrayOf(R.drawable.ic_copy, R.drawable.ic_duplicate, 0, R.drawable.ic_trash).map { res ->
        if (res == 0) null else ContextCompat.getDrawable(context, res)!!.mutate()
    }
    private val menuLabels = listOf(
        context.getString(R.string.menu_copy), context.getString(R.string.menu_duplicate), "", context.getString(R.string.delete),
    )

    /** Returns false when there was nothing to undo. */
    fun undo(): Boolean {
        stopFlight()
        cancelInk()
        cancelGesture()
        clearSelection()
        // Repaint only what the change touches: where it was before undoing, and where it is after.
        val area = RectF()
        history.peek()?.let { unionBounds(area, it) } ?: return false
        val change = history.undo() ?: return false
        unionBounds(area, change)
        val now = SystemClock.uptimeMillis()
        // Ink that disappears fades out instead of blinking away.
        when (change) {
            is History.Added -> fades.add(Fade(change.stroke, now))
            is History.Edited -> change.inserted.forEach { fades.add(Fade(it, now)) }
            else -> Unit
        }
        invalidateLayer(area)
        return true
    }

    private fun unionBounds(area: RectF, change: History.Change) {
        when (change) {
            is History.Added -> area.union(change.stroke.bounds)
            is History.Edited -> change.ops.forEach { area.union(it.stroke.bounds) }
            is History.Transformed -> change.strokes.forEach { area.union(it.bounds) }
            is History.Recolored -> change.strokes.forEach { area.union(it.bounds) }
        }
    }

    /** Returns false when the board was already empty. */
    fun clearAll(): Boolean {
        if (strokes.isEmpty()) return false
        stopFlight()
        cancelInk()
        cancelGesture()
        removeStrokes { true }
        return true
    }

    /** True when the board has no ink. */
    val isEmpty get() = strokes.isEmpty()

    /** The committed ink, encoded for [InkStore]. */
    fun encodeInk(): ByteArray = InkStore.encode(strokes)

    /** A board holding [saved] ink (e.g. read back from disk), ready for [showBoard]. */
    internal fun newBoard(saved: List<Stroke> = emptyList(), paper: Boolean = false) = Board({ notifyState() }, paper).also { b ->
        b.strokes.addAll(saved)
        b.nextGroup = (saved.maxOfOrNull { it.group } ?: 0) + 1
    }

    /**
     * Saved ink read back (off the main thread) for [b], which may already hold new writing: the
     * saved strokes go after it, so the undo history's positions stay right.
     */
    internal fun addSaved(b: Board, saved: List<Stroke>) {
        val base = b.nextGroup
        var top = 0
        for (s in saved) {
            s.group += base
            top = max(top, s.group)
        }
        b.strokes.addAll(saved)
        b.nextGroup = max(b.nextGroup, top + 1)
        if (b === board) {
            for (s in saved) invalidateLayer(s.bounds)
        }
    }

    /**
     * Puts a cutout of the screen on the whiteboard (the board on show): a little under everything
     * already on it (writing and cutouts), or near the top of what is on screen, and scrolls so that
     * it sits at the top with the paper free underneath to write. A finger moves it like ink. [data] is its PNG, if already made.
     * Returns the cutout, or null when the whiteboard isn't on show.
     */
    fun addPicture(image: Bitmap, data: ByteArray?): Stroke? {
        if (!board.paper || width == 0) return null
        visibleWorld()
        val margin = dp(PICTURE_MARGIN_DP) / zoom
        // As large as it was on screen, but never wider than most of the view, nor taller than half.
        val k = minOf(1f / zoom, visible.width() * 0.9f / image.width, visible.height() * 0.5f / image.height)
        val w = image.width * k
        val h = image.height * k
        val below = strokes.maxOfOrNull { it.bounds.bottom }
        val top = if (below != null) below + margin else visible.top + margin
        val left = visible.centerX() - w / 2
        val s = placeImage(image, data, left, top, w, h)
        // Scroll so the cutout sits at the top of the screen.
        val shift = (top - margin) - visible.top
        if (shift != 0f) {
            camera.postTranslate(0f, -shift * zoom)
            redrawLayer()
        }
        return s
    }

    /**
     * A pasted image, centred on ([x], [y]) in board coordinates and kept on screen: at its own
     * size in dp (pictures from the web are sized for a phone), at most 60 % of the view.
     */
    fun addPictureAt(image: Bitmap, data: ByteArray?, x: Float, y: Float): Stroke? {
        if (!board.paper || width == 0) return null
        visibleWorld()
        val k = minOf(resources.displayMetrics.density / zoom, visible.width() * 0.6f / image.width, visible.height() * 0.6f / image.height)
        val w = image.width * k
        val h = image.height * k
        val left = (x - w / 2).coerceIn(visible.left, max(visible.left, visible.right - w))
        val top = (y - h / 2).coerceIn(visible.top, max(visible.top, visible.bottom - h))
        return placeImage(image, data, left, top, w, h)
    }

    private fun placeImage(image: Bitmap, data: ByteArray?, left: Float, top: Float, w: Float, h: Float): Stroke {
        val s = Stroke.picture(image, data, left, top, left + w, top + h)
        s.group = board.nextGroup++
        clearSelection()
        insertStrokes(listOf(s))
        return s
    }

    /** Whether a finger on empty space may reach the app below (not on the PC, where it can't). */
    var passThrough = true

    /** True while nothing is being written, erased, moved or flying: safe to swap boards. */
    val isIdle get() = gesture == Gesture.NONE && inking.isEmpty() && erasing.isEmpty() && !flying

    /** Shows [next] instead of the current board, which is returned; the old ink fades out. */
    internal fun showBoard(next: Board): Board {
        val previous = board
        if (next === previous) return previous
        stopFlight()
        cancelInk()
        cancelGesture()
        clearSelection()
        val now = SystemClock.uptimeMillis()
        for (s in previous.strokes) fades.add(Fade(s, now))
        board = next
        lifted.clear()
        panning = false
        redrawLayer()
        return previous
    }

    // region Rendering

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w <= 0 || h <= 0) return
        layer.resize(w, h)
        redrawLayer()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        onFocus?.invoke(hasWindowFocus)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (predictor == null) predictor = runCatching { MotionEventPredictor.newInstance(this) }.getOrNull()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        velocity.recycle()
        layer.release()
    }

    private fun redrawLayer() {
        dirty.setEmpty()
        layer.redraw(strokes, offLayer, camera)
        invalidate()
    }

    /** The part of the board on screen, in board coordinates (all of it, off the whiteboard). */
    private fun visibleWorld() {
        view.set(0f, 0f, width.toFloat(), height.toFloat())
        camera.invert(inverse)
        inverse.mapRect(visible, view)
    }

    /** Draws a newly added stroke straight onto the layer (it goes on top of everything). */
    private fun drawOnLayer(s: Stroke) = layer.add(s, camera)

    /** Repaints only [area] of the layer, on the next frame (erasing touches a small spot). */
    private fun invalidateLayer(area: RectF) {
        if (area.isEmpty) return
        // A little wider than the path: the clip isn't anti-aliased, and edge pixels must clear too.
        dirty.union(area.left - DIRTY_MARGIN, area.top - DIRTY_MARGIN, area.right + DIRTY_MARGIN, area.bottom + DIRTY_MARGIN)
        invalidate()
    }

    private fun repaintDirty() {
        if (dirty.isEmpty) return
        layer.repaint(dirty, strokes, offLayer, camera)
        dirty.setEmpty()
    }



    private fun drawStroke(canvas: Canvas, s: Stroke, opacity: Float) {
        if (s.tool == InkTool.IMAGE) {
            drawPicture(canvas, s, opacity)
        } else if (s.tool == InkTool.PEN) {
            inkPaint.color = shown(s.color)
            inkPaint.alpha = (Color.alpha(s.color) * opacity).toInt()
            canvas.drawPath(s.path, inkPaint)
        } else {
            highlighterPaint.color = shown(s.color)
            highlighterPaint.alpha = (HIGHLIGHTER_ALPHA * opacity).toInt()
            highlighterPaint.strokeWidth = s.size * Stroke.HIGHLIGHTER_SCALE
            canvas.drawPath(s.path, highlighterPaint)
        }
    }

    /** A cutout, stretched into its four corners (it may have been moved, scaled or turned). */
    private fun drawPicture(canvas: Canvas, s: Stroke, opacity: Float) {
        val image = s.image ?: return
        val p = s.points
        if (p.size != 4) return
        pictureSrc[2] = image.width.toFloat()
        pictureSrc[4] = image.width.toFloat()
        pictureSrc[5] = image.height.toFloat()
        pictureSrc[7] = image.height.toFloat()
        for (i in 0 until 4) {
            pictureDst[2 * i] = p[i].x
            pictureDst[2 * i + 1] = p[i].y
        }
        pictureMatrix.setPolyToPoly(pictureSrc, 0, pictureDst, 0, 4)
        picturePaint.alpha = (255 * opacity).toInt()
        canvas.drawBitmap(image, pictureMatrix, picturePaint)
        canvas.drawPath(s.path, pictureEdge)
    }

    private val pictureSrc = FloatArray(8)
    private val pictureDst = FloatArray(8)
    private val pictureMatrix = Matrix()
    private val picturePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val pictureEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = 0x33000000
    }

    override fun onDraw(canvas: Canvas) {
        if (board.paper) drawPaper(canvas)
        // While the whiteboard moves, its ink is drawn directly (the layer is re-rasterised after).
        if (!panning) repaintDirty()
        if (!inkHidden && !panning) layer.draw(canvas)
        canvas.withMatrix(camera) {
            if (!inkHidden) {
                if (panning) {
                    visibleWorld()
                    for (s in strokes) if (s !in offLayer && RectF.intersects(s.bounds, visible)) drawStroke(canvas, s, 1f)
                }
                if (lifted.isNotEmpty()) canvas.withMatrix(liveMatrix) { for (s in lifted) drawStroke(this, s, 1f) }
                for (f in flights) canvas.withMatrix(f.matrix) { for (s in f.items) drawStroke(this, s, 1f) }
                drawFades(canvas)
            }
            for (live in inking.values) {
                // The outline is rebuilt once per frame, however many touch samples arrived since.
                if (live.stale) {
                    live.stale = false
                    live.stroke.rebuild()
                }
                if (live.tool == Tool.MAGIC) drawGlowing(canvas, live.stroke, 1f) else drawStroke(canvas, live.stroke, 1f)
            }
            drawPrediction(canvas)
            drawMagic(canvas)
            drawErasers(canvas)
            if (gesture == Gesture.LASSO && lassoArmed) drawLasso(canvas)
        }
        // The selection box and the menu are drawn in screen coordinates: on a zoomed whiteboard they
        // keep their size (and their handles stay as easy to grab).
        if (selection.isNotEmpty() && !inkHidden) drawSelection(canvas)
        if (selection.isNotEmpty() && menuShown && gesture == Gesture.NONE && !flying) drawMenu(canvas) else menuRect.setEmpty()
    }

    /**
     * White paper, plain or with a faint pattern ([paperStyle]: dots, squares or ruled lines) that
     * moves with it, so panning and zooming can be felt. A plain fill plus a few hundred dots or
     * lines: one cheap pass, not a full-screen texture.
     */
    private fun drawPaper(canvas: Canvas) {
        canvas.drawColor(paperColor.background)
        if (paperStyle == Paper.BLANK) return
        val k = zoom
        val step = dp(GRID_DP) * k
        if (step < dp(MIN_GRID_DP)) return // zoomed far out: the pattern would just be noise
        camera.mapPoints(origin, ZERO)
        val x0 = origin[0] - kotlin.math.floor(origin[0] / step) * step
        val y0 = origin[1] - kotlin.math.floor(origin[1] / step) * step
        val cols = (width / step).toInt() + 1
        val rows = (height / step).toInt() + 1
        if (paperStyle == Paper.DOTS) {
            val need = cols * rows * 2
            if (gridPoints.size < need) gridPoints = FloatArray(need)
            var n = 0
            for (r in 0 until rows) for (c in 0 until cols) {
                gridPoints[n++] = x0 + c * step
                gridPoints[n++] = y0 + r * step
            }
            canvas.drawPoints(gridPoints, 0, n, gridPaint)
            return
        }
        // Squares or ruled lines: one batch of segments, the vertical ones only for squares. Each
        // period holds one line, a pair (double ruling: the letters' body between them, a little
        // further apart) or the five lines of a music staff (with room between staves).
        val (period, offsets) = when (paperStyle) {
            Paper.DOUBLE -> step * DOUBLE_PERIOD to DOUBLE_LINES
            Paper.STAFF -> step * STAFF_PERIOD to STAFF_LINES
            else -> step to SINGLE_LINE
        }
        val ry0 = origin[1] - kotlin.math.floor(origin[1] / period) * period - period
        val ruled = (height / period).toInt() + 2
        val need = (ruled * offsets.size + cols) * 4
        if (gridLines.size < need) gridLines = FloatArray(need)
        var n = 0
        for (r in 0 until ruled) {
            val y = ry0 + r * period
            for (o in offsets) {
                val yy = y + o * step
                gridLines[n++] = 0f; gridLines[n++] = yy; gridLines[n++] = width.toFloat(); gridLines[n++] = yy
            }
        }
        if (paperStyle == Paper.GRID) for (c in 0 until cols) {
            val x = x0 + c * step
            gridLines[n++] = x; gridLines[n++] = 0f; gridLines[n++] = x; gridLines[n++] = height.toFloat()
        }
        canvas.drawLines(gridLines, 0, n, if (paperStyle == Paper.STAFF) staffPaint else linePaint)
    }

    private var gridPoints = FloatArray(0)
    private val origin = FloatArray(2)

    /**
     * A short extension of the one stroke being written, to where the pen is predicted to be. Never
     * stored: the next frame draws the real ink. Only plain pen ink (a translucent or glowing
     * extension would show its overlap; shapes are redrawn on lift anyway).
     */
    private fun drawPrediction(canvas: Canvas) {
        if (inking.size != 1) return
        val (id, live) = inking.entries.first()
        if (live.tool != Tool.PEN || live.stroke.points.size < 2) return
        val p = predictor?.predict() ?: return
        try {
            val i = p.findPointerIndex(id)
            if (i < 0) return
            val last = live.stroke.points.last()
            var px = p.getX(i)
            var py = p.getY(i)
            val d = hypot(px - last.x, py - last.y)
            val limit = dp(PREDICT_MAX_DP) / zoom
            if (d > limit) {
                px = last.x + (px - last.x) * limit / d
                py = last.y + (py - last.y) * limit / d
            }
            predictPaint.color = shown(live.stroke.color)
            predictPaint.strokeWidth = live.stroke.size * 0.9f
            canvas.drawLine(last.x, last.y, px, py, predictPaint)
        } finally {
            p.recycle()
        }
    }

    private fun drawFades(canvas: Canvas) {
        if (fades.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        val it = fades.iterator()
        while (it.hasNext()) {
            val f = it.next()
            val t = (now - f.start) / FADE_MS.toFloat()
            if (t >= 1f) it.remove() else drawStroke(canvas, f.stroke, (1f - t) * (1f - t))
        }
        if (fades.isNotEmpty()) postInvalidateOnAnimation()
    }

    /** Magic ink: fully visible for a moment, then it melts away. */
    private fun drawMagic(canvas: Canvas) {
        if (magic.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        val it = magic.iterator()
        while (it.hasNext()) {
            val m = it.next()
            val age = now - m.start
            val alpha = if (age <= Presets.MAGIC_HOLD_MS) 1f else 1f - (age - Presets.MAGIC_HOLD_MS) / Presets.MAGIC_FADE_MS.toFloat()
            if (alpha <= 0f) it.remove() else drawGlowing(canvas, m.stroke, alpha)
        }
        if (magic.isNotEmpty()) postInvalidateOnAnimation()
    }

    /** Neon look for magic ink: a soft halo under a bright core. */
    private fun drawGlowing(canvas: Canvas, s: Stroke, opacity: Float) {
        glowPaint.alpha = (200 * opacity).toInt()
        glowPaint.strokeWidth = s.size * 1.6f
        canvas.drawPath(s.path, glowPaint)
        drawStroke(canvas, s, opacity)
    }

    /** Dashed box with scale handles at the corners and a rotate handle on top; the menu on demand. */
    private fun drawSelection(canvas: Canvas) {
        // While the selection itself is being handled, the box follows it.
        val following = gesture != Gesture.NONE && lifted.isNotEmpty() && lifted.containsAll(selection)
        selectionBox(if (following) liveMatrix else null)
        val p = boxScreen
        camera.mapPoints(p, boxPts)
        boxPath.rewind()
        boxPath.moveTo(p[0], p[1])
        boxPath.lineTo(p[2], p[3])
        boxPath.lineTo(p[6], p[7])
        boxPath.lineTo(p[4], p[5])
        boxPath.close()
        canvas.drawPath(boxPath, boxPaint)
        // Stem and rotate handle (a round arrow inside a circle).
        val topX = (p[0] + p[2]) / 2f
        val topY = (p[1] + p[3]) / 2f
        canvas.drawLine(topX, topY, p[8], p[9], handleRing)
        val hr = dp(HANDLE_DP)
        for (k in 0 until 5) {
            canvas.drawCircle(p[2 * k], p[2 * k + 1], hr, handleFill)
            canvas.drawCircle(p[2 * k], p[2 * k + 1], hr, handleRing)
        }
        val ar = hr * 0.5f
        tmpRect.set(p[8] - ar, p[9] - ar, p[8] + ar, p[9] + ar)
        canvas.drawArc(tmpRect, -20f, 250f, false, handleRing)
    }

    /**
     * Fills [boxPts] with the selection's corners (top-left, top-right, bottom-left,
     * bottom-right) and its rotate handle, mapped through [m] when given.
     */
    private fun selectionBox(m: Matrix?) {
        val r = selectionRect
        boxPts[0] = r.left; boxPts[1] = r.top
        boxPts[2] = r.right; boxPts[3] = r.top
        boxPts[4] = r.left; boxPts[5] = r.bottom
        boxPts[6] = r.right; boxPts[7] = r.bottom
        // The stem keeps its length on screen whatever the whiteboard's zoom.
        boxPts[8] = r.centerX(); boxPts[9] = r.top - dp(ROTATE_STEM_DP) / zoom
        m?.mapPoints(boxPts)
    }

    private fun layoutMenu() {
        val w = dp(236f)
        val row = dp(48f)
        val pad = dp(8f)
        // Pictures have no colour: with only pictures selected, the colours row is left out.
        val colours = selection.any { it.tool != InkTool.IMAGE }
        val rows = if (colours) menuRows.size else menuRows.size - 1
        val h = pad * 2 + row * rows
        val gap = dp(16f)
        val margin = dp(8f)
        val sel = menuAnchor
        camera.mapRect(sel, selectionRect)
        var x = sel.right + gap
        var y = sel.top
        if (x + w > width - margin) x = sel.left - gap - w
        if (x < margin) {
            x = sel.left.coerceIn(margin, max(margin, width - w - margin))
            y = sel.bottom + gap
            if (y + h > height - margin) y = sel.top - gap - h
        }
        y = y.coerceIn(margin, max(margin, height - h - margin))
        menuRect.set(x, y, x + w, y + h)
        var top = y + pad
        menuRows.forEachIndexed { i, rr ->
            if (i == COLOUR_ROW && !colours) {
                rr.setEmpty()
            } else {
                rr.set(x, top, x + w, top + row)
                top += row
            }
        }
        val step = (w - dp(28f)) / swatchX.size
        for (i in swatchX.indices) swatchX[i] = x + dp(14f) + step * (i + 0.5f)
    }

    private fun drawMenu(canvas: Canvas) {
        layoutMenu()
        val radius = dp(18f)
        canvas.drawRoundRect(menuRect, radius, radius, menuPaint)
        val icon = dp(20f)
        menuRows.forEachIndexed { i, r ->
            if (r.isEmpty) return@forEachIndexed
            if (i == COLOUR_ROW) {
                canvas.drawRect(r.left + dp(14f), r.top, r.right - dp(14f), r.top + dp(1f), menuDivider)
                canvas.drawRect(r.left + dp(14f), r.bottom - dp(1f), r.right - dp(14f), r.bottom, menuDivider)
                val current = selection.firstOrNull { it.tool != InkTool.IMAGE }?.color
                Presets.COLORS.forEachIndexed { k, c ->
                    swatchPaint.color = c
                    canvas.drawCircle(swatchX[k], r.centerY(), dp(9.5f), swatchPaint)
                    if (c == Color.WHITE) canvas.drawCircle(swatchX[k], r.centerY(), dp(9.5f), swatchEdge)
                    if (c == current) canvas.drawCircle(swatchX[k], r.centerY(), dp(13f), handleRing)
                }
                return@forEachIndexed
            }
            val danger = i == DELETE_ROW
            val d = menuIcons[i] ?: return@forEachIndexed
            d.setTint(if (danger) OrbStyle.DANGER else OrbStyle.LABEL)
            val ix = r.left + dp(18f)
            val iy = r.centerY() - icon / 2f
            d.setBounds(ix.toInt(), iy.toInt(), (ix + icon).toInt(), (iy + icon).toInt())
            d.draw(canvas)
            menuText.color = if (danger) OrbStyle.DANGER else OrbStyle.LABEL
            val baseline = r.centerY() - (menuText.descent() + menuText.ascent()) / 2f
            canvas.drawText(menuLabels[i], ix + icon + dp(14f), baseline, menuText)
        }
    }

    private fun drawLasso(canvas: Canvas) {
        canvas.drawPath(lasso, lassoShadow)
        canvas.drawPath(lasso, lassoLine)
    }

    private fun drawErasers(canvas: Canvas) {
        for (r in erasing.values) {
            canvas.drawCircle(r.x, r.y, r.radius, eraserFill)
            canvas.drawCircle(r.x, r.y, r.radius, eraserShadow)
            canvas.drawCircle(r.x, r.y, r.radius, eraserRing)
        }
    }

    // endregion

    // region Input

    @android.annotation.SuppressLint("ClickableViewAccessibility") // performClick() is called from handle()
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!annotating) return false
        if (camera.isIdentity) return handle(e, e)
        // On a moved or zoomed whiteboard, the ink logic works in board coordinates.
        camera.invert(inverse)
        val world = MotionEvent.obtain(e)
        world.transform(inverse)
        try {
            return handle(world, e)
        } finally {
            world.recycle()
        }
    }

    /** [e] in board coordinates; [screen] the same event as it came (contact size, camera gestures). */
    private fun handle(e: MotionEvent, screen: MotionEvent): Boolean {
        raw = screen
        // When embedded (e.g. in a scrolling screen) the ink owns the gesture, not the scroller.
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            parent?.requestDisallowInterceptTouchEvent(true)
            velocity.clear()
            // A first finger down means nothing else is down: drop anything left from a stream
            // that never ended (e.g. the window stopped taking touches mid-gesture).
            if (inking.isNotEmpty() || erasing.isNotEmpty()) cancelInk()
            if (gesture != Gesture.NONE) cancelGesture()
        }
        velocity.addMovement(e)
        predictor?.record(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> pointerDown(e, e.actionIndex)
            MotionEvent.ACTION_MOVE -> pointerMove(e)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(e.actionIndex)
                when {
                    // Android 13 marks a pointer the system took back (e.g. a rejected palm): drop its ink.
                    id in inking && e.flags and MotionEvent.FLAG_CANCELED != 0 -> {
                        inking.remove(id)
                        invalidate()
                    }
                    id in inking -> endInk(e, e.actionIndex, id)
                    id in erasing -> endErase(id)
                    (id == pointerId || (id == pointerId2 && (gesture == Gesture.PINCH || gesture == Gesture.PAN))) &&
                        pointerUp(e, e.actionIndex) -> performClick()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelInk()
                cancelGesture()
            }
        }
        raw = null
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private enum class Contact { PEN, FINGER, ERASER }

    /**
     * Decides what touched the glass. Digitizers with an active pen report the tool type.
     * Infrared boards (like the ViewBoard's "IRtouch") report everything as a finger, so
     * there the contact size decides: a pen tip is tiny, a finger is medium, a fist or
     * palm is large. Thresholds were measured on the IFP7533.
     */
    private fun classify(e: MotionEvent, i: Int): Contact {
        when (e.getToolType(i)) {
            MotionEvent.TOOL_TYPE_ERASER -> return Contact.ERASER
            MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_MOUSE -> {
                val sideButton = e.buttonState and
                    (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_SECONDARY) != 0
                return if (sideButton) Contact.ERASER else Contact.PEN
            }
            MotionEvent.TOOL_TYPE_FINGER, MotionEvent.TOOL_TYPE_UNKNOWN -> Unit
        }
        if (isIrDigitizer(e.deviceId)) {
            val major = (raw ?: e).getTouchMajor(i)
            return when {
                major <= IR_PEN_MAX -> Contact.PEN
                major >= IR_PALM_MIN -> Contact.ERASER
                else -> Contact.FINGER
            }
        }
        return Contact.FINGER
    }

    // The device name is looked up once per device, not on every touch.
    private var irDeviceId = Int.MIN_VALUE
    private var irDevice = false

    private fun isIrDigitizer(deviceId: Int): Boolean {
        if (deviceId != irDeviceId) {
            irDeviceId = deviceId
            irDevice = android.view.InputDevice.getDevice(deviceId)?.name?.contains(IR_DIGITIZER, ignoreCase = true) == true
        }
        return irDevice
    }

    private fun pointerDown(e: MotionEvent, i: Int) {
        val x = e.getX(i)
        val y = e.getY(i)
        var contact = classify(e, i)
        if (contact == Contact.PEN && fingerDraws && e.getToolType(i) != MotionEvent.TOOL_TYPE_MOUSE) {
            // A pen just proved it exists: from now on fingers go back to selecting.
            fingerDraws = false
            onStylusDetected?.invoke()
        }
        if (contact == Contact.FINGER && (fingerDraws || fingersWrite)) contact = Contact.PEN
        // Hidden ink can't be erased or picked up; only new writing brings it back.
        if (inkHidden && contact != Contact.PEN) return

        // The selection menu takes any touch on it, pen or finger.
        // (It is laid out on screen, not on the whiteboard.)
        val sx = raw?.getX(i) ?: x
        val sy = raw?.getY(i) ?: y
        if (gesture == Gesture.NONE && !menuRect.isEmpty && menuRect.contains(sx, sy)) {
            onMenuTap(sx, sy)
            return
        }
        if (lassoNext && gesture == Gesture.NONE && inking.isEmpty() && erasing.isEmpty() && contact != Contact.ERASER) {
            beginLasso(e, i, x, y)
            return
        }
        // On the whiteboard a second finger on empty space zooms (and pans) the paper.
        if (board.paper && contact == Contact.FINGER && pointerId2 == NO_POINTER &&
            (gesture == Gesture.LASSO || gesture == Gesture.PAN) && !lassoArmed
        ) {
            startZoom(i)
            return
        }
        // A second finger on the object being handled turns the gesture into a pinch.
        if (contact == Contact.FINGER && pointerId2 == NO_POINTER &&
            (gesture == Gesture.MOVE || gesture == Gesture.SCALE || gesture == Gesture.ROTATE)
        ) {
            startPinch(e, i)
            return
        }
        if (gesture == Gesture.NONE) {
            // A finger catches what it touches (the rest keep flying); an eraser lands them all,
            // so nothing it rubs out is somewhere else.
            if (contact == Contact.FINGER) catchAt(x, y)
            else if (contact == Contact.ERASER || tool == Tool.ERASER) stopFlight()
        }

        val writing = inking.isNotEmpty() || erasing.isNotEmpty()
        // Palm rejection: while someone writes, a finger never interrupts. With one writer (the
        // teacher) nothing else starts either, so a resting palm can't rub out; with "several
        // pupils" every pen and every fist gets its own stroke or eraser.
        if (writing && (contact == Contact.FINGER || !fingersWrite)) return
        if (gesture != Gesture.NONE) {
            // A pen may take over from a finger gesture.
            if (contact == Contact.FINGER) return
            cancelGesture()
        }
        val id = e.getPointerId(i)
        if (contact == Contact.FINGER) pointerId = id
        when (contact) {
            Contact.PEN -> {
                requestUnbufferedDispatch(e)
                clearSelection()
                if (tool == Tool.ERASER) {
                    if (inkHidden) return
                    beginErase(id, x, y, eraserSize / 2f / zoom)
                } else {
                    beginInk(id, x, y, e.getPressure(i))
                }
            }
            Contact.ERASER -> {
                requestUnbufferedDispatch(e)
                clearSelection()
                // A fist or palm rubs out a generous, adjustable area, like a real board eraser.
                beginErase(id, x, y, eraserSize / zoom)
            }
            Contact.FINGER -> beginFinger(e, i, x, y)
        }
    }

    private fun pointerMove(e: MotionEvent) {
        moveInk(e)
        val i = e.findPointerIndex(pointerId)
        if (i < 0) return
        when (gesture) {
            Gesture.LASSO -> {
                val x = e.getX(i)
                val y = e.getY(i)
                travelled += hypot(x - lastX, y - lastY)
                lastX = x
                lastY = y
                if (lassoArmed) {
                    lasso.lineTo(x, y)
                    invalidate()
                } else {
                    if (travelled > touchSlop) removeCallbacks(armHold)
                    // On the whiteboard a finger that moves drags the paper.
                    if (board.paper && travelled > touchSlop) startPan(i) else recordDrag(x, y)
                }
            }
            Gesture.PAN -> movePan()
            Gesture.MOVE -> {
                val x = e.getX(i)
                val y = e.getY(i)
                if (!moved) {
                    if (hypot(x - downX, y - downY) < touchSlop) return
                    // Dragging a selected object keeps it selected; dragging another one just moves it.
                    moved = true
                    menuShown = false
                }
                liveMatrix.postTranslate(x - lastX, y - lastY)
                lastX = x
                lastY = y
                invalidate()
            }
            Gesture.SCALE -> {
                val k = (hypot(e.getX(i) - pivotX, e.getY(i) - pivotY) / startDist).coerceIn(MIN_SCALE, MAX_SCALE)
                liveMatrix.setScale(k, k, pivotX, pivotY)
                invalidate()
            }
            Gesture.ROTATE -> {
                val a = atan2(e.getY(i) - pivotY, e.getX(i) - pivotX)
                liveMatrix.setRotate(Math.toDegrees((a - startAngle).toDouble()).toFloat(), pivotX, pivotY)
                invalidate()
            }
            Gesture.PINCH -> {
                val j = e.findPointerIndex(pointerId2)
                if (j < 0) return
                val mx = (e.getX(i) + e.getX(j)) / 2f
                val my = (e.getY(i) + e.getY(j)) / 2f
                val d = hypot(e.getX(j) - e.getX(i), e.getY(j) - e.getY(i))
                val a = atan2(e.getY(j) - e.getY(i), e.getX(j) - e.getX(i))
                val k = (d / startDist).coerceIn(MIN_SCALE, MAX_SCALE)
                liveMatrix.set(pinchBase)
                liveMatrix.postScale(k, k, pinchX, pinchY)
                liveMatrix.postRotate(Math.toDegrees((a - startAngle).toDouble()).toFloat(), pinchX, pinchY)
                liveMatrix.postTranslate(mx - pinchX, my - pinchY)
                invalidate()
            }
            Gesture.DESELECT, Gesture.PAN, Gesture.NONE -> Unit
        }
    }

    /** Ends the active gesture; returns true when it was a plain tap on empty canvas. */
    private fun pointerUp(e: MotionEvent, i: Int): Boolean {
        var tapped = false
        when (gesture) {
            Gesture.LASSO -> {
                removeCallbacks(armHold)
                when {
                    lassoOnce -> {
                        lassoOnce = false
                        lassoNext = false
                        // A tap is no lasso: it just leaves the lasso.
                        if (travelled > touchSlop) selectWithLasso() else lasso.rewind()
                    }
                    lassoArmed && hypot(lastX - downX, lastY - downY) < touchSlop -> {
                        lasso.rewind()
                        val ask = onPasteRequest
                        if (board.paper && ask != null) ask(downX, downY, if (clipboard.isEmpty()) 0L else copiedAt) else pasteCopied(downX, downY)
                    }
                    lassoArmed -> selectWithLasso()
                    board.paper -> Unit // nothing underneath to tap or scroll
                    travelled < dp(12f) -> {
                        tapped = true
                        // Nothing was selected (or it would have been a DESELECT): the tap is for the app below.
                        onPassThroughTap?.invoke(lastX, lastY)
                    }
                    else -> {
                        // A plain drag over empty space belongs to the app below (a scroll, a swipe).
                        recordDrag(lastX, lastY)
                        onPassThroughDrag?.invoke(dragXs.copyOf(dragCount), dragYs.copyOf(dragCount), SystemClock.uptimeMillis() - dragStart)
                    }
                }
                lassoArmed = false
            }
            Gesture.MOVE -> endMove()
            Gesture.SCALE, Gesture.ROTATE, Gesture.PINCH -> {
                val items = lifted.toList()
                commitTransform()
                select(items)
            }
            Gesture.PAN -> endPan()
            Gesture.DESELECT, Gesture.NONE -> Unit
        }
        gesture = Gesture.NONE
        pointerId = NO_POINTER
        pointerId2 = NO_POINTER
        invalidate()
        return tapped
    }

    // region Whiteboard camera

    private fun startPan(i: Int) {
        removeCallbacks(armHold)
        gesture = Gesture.PAN
        panning = true
        val r = raw ?: return
        cameraBase.set(camera)
        camX = r.getX(i)
        camY = r.getY(i)
        pointerId2 = NO_POINTER
        invalidate()
    }

    private fun startZoom(i: Int) {
        val r = raw ?: return
        val first = r.findPointerIndex(pointerId)
        if (first < 0) return
        removeCallbacks(armHold)
        gesture = Gesture.PAN
        panning = true
        pointerId2 = r.getPointerId(i)
        cameraBase.set(camera)
        camX = (r.getX(first) + r.getX(i)) / 2f
        camY = (r.getY(first) + r.getY(i)) / 2f
        camDist = max(1f, hypot(r.getX(i) - r.getX(first), r.getY(i) - r.getY(first)))
        invalidate()
    }

    private fun movePan() {
        val r = raw ?: return
        val i = r.findPointerIndex(pointerId)
        if (i < 0) return
        camera.set(cameraBase)
        val j = if (pointerId2 == NO_POINTER) -1 else r.findPointerIndex(pointerId2)
        if (j < 0) {
            camera.postTranslate(r.getX(i) - camX, r.getY(i) - camY)
        } else {
            val mx = (r.getX(i) + r.getX(j)) / 2f
            val my = (r.getY(i) + r.getY(j)) / 2f
            val now = cameraBase.mapRadius(1f)
            val k = (hypot(r.getX(j) - r.getX(i), r.getY(j) - r.getY(i)) / camDist)
                .coerceIn(MIN_ZOOM / now, MAX_ZOOM / now)
            camera.postScale(k, k, camX, camY)
            camera.postTranslate(mx - camX, my - camY)
        }
        invalidate()
    }

    private fun endPan() {
        panning = false
        redrawLayer()
    }

    /** The whiteboard's zoom (1 = as written), for sizing new ink so it looks as chosen on screen. */
    private val zoom get() = camera.mapRadius(1f)

    // endregion

    private fun cancelGesture() {
        when (gesture) {
            Gesture.LASSO -> {
                removeCallbacks(armHold)
                lassoArmed = false
                lassoOnce = false // the ring's lasso stays ready for another try
                lasso.rewind()
            }
            Gesture.MOVE, Gesture.SCALE, Gesture.ROTATE, Gesture.PINCH -> commitTransform()
            Gesture.PAN -> endPan()
            Gesture.DESELECT, Gesture.NONE -> Unit
        }
        gesture = Gesture.NONE
        pointerId = NO_POINTER
        pointerId2 = NO_POINTER
        invalidate()
    }

    private fun beginInk(id: Int, x: Float, y: Float, pressure: Float) {
        if (inkHidden) {
            inkHidden = false
            onInkShown?.invoke()
        }
        val kind = if (tool == Tool.HIGHLIGHTER) InkTool.HIGHLIGHTER else InkTool.PEN
        val color = if (tool == Tool.MAGIC) Presets.MAGIC_COLOR else inkColor
        val stroke = Stroke(kind, color, inkSize / zoom, speedUnit = dp(14f) / zoom).also {
            it.startTime = SystemClock.uptimeMillis()
            it.add(x, y, pressure)
            it.rebuild()
        }
        inking[id] = Live(stroke, tool)
        invalidate()
    }

    /** Feeds every live pen and eraser in this event, including the samples batched in between. */
    private fun moveInk(e: MotionEvent) {
        if (inking.isEmpty() && erasing.isEmpty()) return
        var changed = false
        for ((id, live) in inking) {
            val i = e.findPointerIndex(id)
            if (i < 0) continue
            val s = live.stroke
            var grew = false
            for (h in 0 until e.historySize) {
                grew = s.add(e.getHistoricalX(i, h), e.getHistoricalY(i, h), e.getHistoricalPressure(i, h)) || grew
            }
            grew = s.add(e.getX(i), e.getY(i), e.getPressure(i)) || grew
            if (grew) {
                live.stale = true
                changed = true
            }
        }
        for ((id, r) in erasing) {
            val i = e.findPointerIndex(id)
            if (i < 0) continue
            for (h in 0 until e.historySize) eraseTo(r, e.getHistoricalX(i, h), e.getHistoricalY(i, h))
            eraseTo(r, e.getX(i), e.getY(i))
            changed = true
        }
        if (changed) invalidate()
    }

    private fun endInk(e: MotionEvent, i: Int, id: Int) {
        val live = inking.remove(id) ?: return
        val s = live.stroke
        s.add(e.getX(i), e.getY(i), e.getPressure(i))
        when (live.tool) {
            // Magic ink is never committed: it fades away on its own.
            Tool.MAGIC -> {
                s.finish()
                magic.add(Fade(s, SystemClock.uptimeMillis()))
                postInvalidateOnAnimation()
            }
            else -> {
                if (live.tool == Tool.SHAPE) {
                    ShapeRecognizer.recognize(s.points.map { ShapeRecognizer.Point(it.x, it.y) })?.let { shape ->
                        s.replacePoints(shape.points.map { it.x }, shape.points.map { it.y })
                        s.uniform = true
                    }
                }
                s.finish()
                s.endTime = SystemClock.uptimeMillis()
                s.group = if (live.tool == Tool.SHAPE) board.nextGroup++ else groupFor(s)
                strokes.add(s)
                drawOnLayer(s)
                history.record(History.Added(s))
            }
        }
        invalidate()
    }

    private fun beginErase(id: Int, x: Float, y: Float, radius: Float) {
        val r = Eraser(x, y, radius)
        erasing[id] = r
        eraseAt(x, y, radius)
        invalidate()
    }

    /** Ends one eraser; everything rubbed out while any eraser was down is one undo step. */
    private fun endErase(id: Int) {
        erasing.remove(id) ?: return
        if (erasing.isEmpty()) commitErase()
        invalidate()
    }

    /** Drops unfinished ink and lands erasers (e.g. the system cancelled the touch). */
    private fun cancelInk() {
        inking.clear()
        if (erasing.isNotEmpty()) {
            erasing.clear()
            commitErase()
        }
        invalidate()
    }

    private fun eraseTo(r: Eraser, x: Float, y: Float) {
        // Step along the path so fast swipes never skip over thin strokes.
        val d = hypot(x - r.x, y - r.y)
        val steps = max(1, (d / (r.radius / 2f)).toInt())
        val fromX = r.x
        val fromY = r.y
        for (k in 1..steps) {
            eraseAt(fromX + (x - fromX) * k / steps, fromY + (y - fromY) * k / steps, r.radius)
        }
        r.x = x
        r.y = y
    }

    /**
     * Rubs out only what is under the eraser: each touched stroke is replaced by the
     * pieces that survive. Under the eraser ink vanishes at once, like on a real board.
     */
    private fun eraseAt(x: Float, y: Float, radius: Float) {
        for (index in strokes.indices.reversed()) {
            val s = strokes[index]
            // A cutout isn't ink: the eraser passes over it (select it to delete it).
            if (s.tool == InkTool.IMAGE || !s.hits(x, y, radius)) continue
            invalidateLayer(s.bounds)
            val pieces = s.cut(x, y, radius)
            strokes.removeAt(index)
            eraseOps.add(History.Op(insert = false, index = index, stroke = s))
            pieces.forEachIndexed { k, piece ->
                strokes.add(index + k, piece)
                eraseOps.add(History.Op(insert = true, index = index + k, stroke = piece))
            }
        }
    }

    private fun commitErase() {
        if (eraseOps.isNotEmpty()) history.record(History.Edited(eraseOps.toList()))
        eraseOps.clear()
    }

    /** Assigns a finished stroke to a word: see [WordGrouper]. */
    private fun groupFor(s: Stroke): Int {
        // Only recent writing matters (the scale and the last few words), so skip the rest.
        // Strokes are mostly in writing order already: sort just the tail.
        // Cutouts aren't writing: a tick on one must not become part of it, nor its size set the scale.
        val placed = strokes.takeLast(2 * GROUP_WINDOW).filter { it.tool != InkTool.IMAGE }
            .sortedBy { it.endTime }.takeLast(GROUP_WINDOW)
            .map { WordGrouper.Placed(box(it), it.group) }
        return grouper.groupFor(box(s), placed) ?: board.nextGroup++
    }

    /** Extent of the pen's path, ignoring ink width, which would otherwise narrow the gaps. */
    private fun box(s: Stroke): WordGrouper.Box {
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        for (p in s.points) {
            l = minOf(l, p.x)
            t = minOf(t, p.y)
            r = maxOf(r, p.x)
            b = maxOf(b, p.y)
        }
        return WordGrouper.Box(l, t, r, b, s.startTime, s.endTime)
    }

    // endregion

    // region Selection

    /**
     * A finger on the board. On a selection's handles it scales or rotates; on ink it picks the
     * object up (a tap selects it, a second tap toggles its menu); on empty space it deselects,
     * or reaches the app below, or (long press) pastes what was copied.
     */
    private fun beginFinger(e: MotionEvent, i: Int, x: Float, y: Float) {
        pointerId = e.getPointerId(i)
        lastX = x
        lastY = y
        downX = x
        downY = y
        travelled = 0f
        moved = false
        if (selection.isNotEmpty()) {
            val handle = handleAt(x, y)
            if (handle == ROTATE_HANDLE) {
                pivotX = selectionRect.centerX()
                pivotY = selectionRect.centerY()
                startAngle = atan2(y - pivotY, x - pivotX)
                startTransform(selection, Gesture.ROTATE)
                return
            }
            if (handle >= 0) {
                // Scale around the opposite corner.
                selectionBox(null)
                val o = 3 - handle
                pivotX = boxPts[2 * o]
                pivotY = boxPts[2 * o + 1]
                startDist = max(1f, hypot(x - pivotX, y - pivotY))
                startTransform(selection, Gesture.SCALE)
                return
            }
            if (selectionRect.contains(x, y)) {
                wasSelected = true
                startTransform(selection, Gesture.MOVE)
                return
            }
        }
        val hit = strokes.lastOrNull { it !in airborne && it.hits(x, y, dp(16f) / zoom) }
        if (hit != null) {
            clearSelection()
            wasSelected = false
            startTransform(strokes.filter { it.group == hit.group }, Gesture.MOVE)
            return
        }
        if (selection.isNotEmpty()) {
            // With a selection, a tap on empty space only deselects: it never reaches the app below.
            clearSelection()
            gesture = Gesture.DESELECT
            return
        }
        gesture = Gesture.LASSO
        lassoArmed = false
        lasso.rewind()
        lasso.moveTo(x, y)
        dragCount = 0
        dragStart = SystemClock.uptimeMillis()
        recordDrag(x, y)
        postDelayed(armHold, LASSO_HOLD_MS)
    }

    /** Which handle is under ([x], [y]): a corner index (0..3), [ROTATE_HANDLE], or -1. */
    private fun handleAt(x: Float, y: Float): Int {
        selectionBox(null)
        val reach = dp(HANDLE_DP) * 2.2f / zoom // as wide on screen at any zoom
        if (hypot(x - boxPts[8], y - boxPts[9]) < reach) return ROTATE_HANDLE
        for (k in 0 until 4) if (hypot(x - boxPts[2 * k], y - boxPts[2 * k + 1]) < reach) return k
        return -1
    }

    private fun startTransform(items: Collection<Stroke>, kind: Gesture) {
        gesture = kind
        val picked = items.toList()
        lifted.clear()
        lifted.addAll(picked)
        liveMatrix.reset()
        // Lifted ink is drawn live; take it off the layer.
        for (s in picked) invalidateLayer(s.bounds)
    }

    private fun startPinch(e: MotionEvent, i: Int) {
        val first = e.findPointerIndex(pointerId)
        if (first < 0) return
        removeCallbacks(armHold)
        pointerId2 = e.getPointerId(i)
        if (gesture == Gesture.MOVE && !wasSelected) select(lifted.toList())
        menuShown = false
        moved = true
        pinchBase.set(liveMatrix)
        pinchX = (e.getX(first) + e.getX(i)) / 2f
        pinchY = (e.getY(first) + e.getY(i)) / 2f
        startDist = max(1f, hypot(e.getX(i) - e.getX(first), e.getY(i) - e.getY(first)))
        startAngle = atan2(e.getY(i) - e.getY(first), e.getX(i) - e.getX(first))
        gesture = Gesture.PINCH
        invalidate()
    }

    private fun endMove() {
        val items = lifted.toList()
        if (!moved) {
            commitTransform()
            // First tap selects (handles only); a tap on the selected object toggles its menu.
            if (wasSelected) {
                menuShown = !menuShown
            } else {
                select(items)
                menuShown = false
            }
            return
        }
        velocity.computeCurrentVelocity(1)
        val vx = velocity.getXVelocity(pointerId)
        val vy = velocity.getYVelocity(pointerId)
        // On a zoomed whiteboard the speed is in board units: compare it as seen on screen.
        if (hypot(vx, vy) * zoom > dp(FLING_DP_PER_MS) && ValueAnimator.areAnimatorsEnabled()) {
            // Thrown: it slides on, bouncing off the edges, and is let go.
            clearSelection()
            startFlight(vx, vy)
            return
        }
        commitTransform()
        if (wasSelected) select(items)
    }

    /** Applies the live transform to the lifted strokes as one undoable change. */
    private fun commitTransform() {
        val items = lifted.toList()
        for (s in items) invalidateLayer(s.bounds)
        if (!liveMatrix.isIdentity && items.isNotEmpty()) {
            val m = Matrix(liveMatrix)
            val scale = m.mapRadius(1f)
            for (s in items) s.transform(m, scale)
            history.record(History.Transformed(items, m, scale))
            for (s in items) invalidateLayer(s.bounds)
        }
        liveMatrix.reset()
        lifted.clear()
    }

    /** The object in hand is thrown: it flies on by itself, and the hand is free for the next one. */
    private fun startFlight(vx: Float, vy: Float) {
        val items = lifted.toList()
        val bounds = RectF()
        for (s in items) bounds.union(s.bounds)
        // It bounces off the edges of the screen (on the whiteboard, of the part of it on screen).
        visibleWorld()
        flights += Flight(items, Matrix(liveMatrix), bounds, RectF(visible), vx, vy)
        airborne.addAll(items)
        liveMatrix.reset()
        lifted.clear()
        if (flights.size == 1) postOnAnimation(flightStep)
    }

    private fun stepFlights() {
        if (!flying) return
        val now = SystemClock.uptimeMillis()
        val it = flights.iterator()
        while (it.hasNext()) {
            val f = it.next()
            val dt = min(32L, now - f.time).toFloat()
            f.time = now
            val m = f.matrix
            m.postTranslate(f.vx * dt, f.vy * dt)
            m.mapRect(tmpRect, f.bounds)
            val a = f.area
            if (tmpRect.left < a.left) { m.postTranslate(a.left - tmpRect.left, 0f); f.vx = abs(f.vx) * BOUNCE }
            if (tmpRect.right > a.right) { m.postTranslate(a.right - tmpRect.right, 0f); f.vx = -abs(f.vx) * BOUNCE }
            if (tmpRect.top < a.top) { m.postTranslate(0f, a.top - tmpRect.top); f.vy = abs(f.vy) * BOUNCE }
            if (tmpRect.bottom > a.bottom) { m.postTranslate(0f, a.bottom - tmpRect.bottom); f.vy = -abs(f.vy) * BOUNCE }
            val k = exp(-dt / FLIGHT_DECAY_MS)
            f.vx *= k
            f.vy *= k
            if (hypot(f.vx, f.vy) * zoom < dp(FLIGHT_STOP_DP_PER_MS)) {
                it.remove()
                land(f)
            }
        }
        invalidate()
        if (flying) postOnAnimation(flightStep)
    }

    /** Puts a thrown object down where it is now, as one undoable move. */
    private fun land(f: Flight) {
        airborne.removeAll(f.items.toSet())
        for (s in f.items) invalidateLayer(s.bounds)
        if (!f.matrix.isIdentity && f.items.isNotEmpty()) {
            val scale = f.matrix.mapRadius(1f)
            for (s in f.items) s.transform(f.matrix, scale)
            history.record(History.Transformed(f.items, Matrix(f.matrix), scale))
            for (s in f.items) invalidateLayer(s.bounds)
        }
    }

    /** Lands anything still flying, so what is saved is where it shows. */
    fun settle() = stopFlight()

    /** Lands every thrown object where it is now. */
    private fun stopFlight() {
        if (!flying) return
        removeCallbacks(flightStep)
        val all = flights.toList()
        flights.clear()
        for (f in all) land(f)
        invalidate()
    }

    /** A finger on a flying object catches it (it lands there and can be picked up); the others fly on. */
    private fun catchAt(x: Float, y: Float) {
        val f = flights.lastOrNull { f ->
            f.matrix.mapRect(tmpRect, f.bounds)
            tmpRect.contains(x, y)
        } ?: return
        flights.remove(f)
        land(f)
        if (!flying) removeCallbacks(flightStep)
        invalidate()
    }

    private fun onMenuTap(x: Float, y: Float) {
        val row = menuRows.indexOfFirst { it.contains(x, y) }
        val items = selection.toList()
        if (items.isEmpty()) return
        when (row) {
            COPY_ROW -> {
                clipboard.clear()
                items.mapTo(clipboard) { it.copy() }
                copiedAt = System.currentTimeMillis()
                // Let go of the selection, so the next long press on empty space pastes straight away.
                clearSelection()
                onMessage?.invoke(R.string.copied)
            }
            DUPLICATE_ROW -> {
                val off = dp(28f)
                val copies = items.map { it.copy() }
                val group = board.nextGroup++
                for (c in copies) {
                    c.group = group
                    c.translate(off, off)
                }
                insertStrokes(copies)
                select(copies)
                menuShown = false
            }
            COLOUR_ROW -> {
                val k = swatchX.indices.minByOrNull { abs(swatchX[it] - x) } ?: return
                val colour = Presets.COLORS[k]
                // Only the ink takes the colour; pictures keep theirs.
                val ink = items.filter { it.tool != InkTool.IMAGE }
                if (ink.isEmpty()) return
                val before = IntArray(ink.size) { ink[it].color }
                for (s in ink) {
                    s.color = colour
                    invalidateLayer(s.bounds)
                }
                history.record(History.Recolored(ink, before))
            }
            DELETE_ROW -> removeStrokes { it in selection }
        }
        invalidate()
    }

    /** The ring's lasso: this pointer (pen or finger) draws it straight away. */
    private fun beginLasso(e: MotionEvent, i: Int, x: Float, y: Float) {
        clearSelection()
        pointerId = e.getPointerId(i)
        gesture = Gesture.LASSO
        lassoArmed = true
        lassoOnce = true
        lasso.rewind()
        lasso.moveTo(x, y)
        lastX = x
        lastY = y
        downX = x
        downY = y
        travelled = 0f
        invalidate()
    }

    /** The finger stayed still on empty space: from here a drag draws a lasso (or, released, pastes). */
    private fun armLasso() {
        if (gesture != Gesture.LASSO || travelled > touchSlop) return
        lassoArmed = true
        lasso.rewind()
        lasso.moveTo(lastX, lastY)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }

    /** Keeps up to [MAX_DRAG_POINTS] samples of a drag, thinning them when it gets long. */
    private fun recordDrag(x: Float, y: Float) {
        if (dragCount == MAX_DRAG_POINTS) {
            for (k in 0 until MAX_DRAG_POINTS / 2) {
                dragXs[k] = dragXs[2 * k]
                dragYs[k] = dragYs[2 * k]
            }
            dragCount = MAX_DRAG_POINTS / 2
        }
        dragXs[dragCount] = x
        dragYs[dragCount] = y
        dragCount++
    }

    /** Long press on empty space after Copy: the copied ink lands centred under the finger (board coordinates). */
    fun pasteCopied(x: Float, y: Float) {
        if (clipboard.isEmpty()) return
        tmpRect.setEmpty()
        for (s in clipboard) tmpRect.union(s.bounds)
        val group = board.nextGroup++
        val copies = clipboard.map { it.copy() }
        for (c in copies) {
            c.group = group
            c.translate(x - tmpRect.centerX(), y - tmpRect.centerY())
        }
        insertStrokes(copies)
        select(copies)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun insertStrokes(items: List<Stroke>) {
        val ops = items.map { s ->
            strokes.add(s)
            History.Op(insert = true, index = strokes.lastIndex, stroke = s)
        }
        history.record(History.Edited(ops))
        // Added on top of everything: draw them straight onto the layer.
        for (s in items) drawOnLayer(s)
        invalidate()
    }

    private fun selectWithLasso() {
        lasso.close()
        val b = RectF()
        lasso.computeBounds(b, true)
        val region = Region().apply {
            setPath(lasso, Region(b.left.toInt() - 1, b.top.toInt() - 1, b.right.toInt() + 1, b.bottom.toInt() + 1))
        }
        val picked = strokes.filter { s ->
            s !in airborne && RectF.intersects(b, s.bounds) &&
                s.points.count { region.contains(it.x.toInt(), it.y.toInt()) } * 2 >= s.points.size
        }
        lasso.rewind()
        // Lassoing part of a word takes the whole word.
        val groups = picked.mapTo(HashSet()) { it.group }
        if (picked.isEmpty()) clearSelection() else select(strokes.filter { it.group in groups })
    }

    private fun select(items: List<Stroke>) {
        if (items.isEmpty()) {
            clearSelection()
            return
        }
        selection.clear()
        selection.addAll(items)
        selectionRect.setEmpty()
        for (s in items) selectionRect.union(s.bounds)
        selectionRect.inset(-dp(10f) / zoom, -dp(10f) / zoom)
        invalidate()
    }

    private fun clearSelection() {
        menuShown = false
        menuRect.setEmpty()
        if (selection.isEmpty()) return
        selection.clear()
        selectionRect.setEmpty()
        invalidate()
    }

    /** Removes matching strokes as one undoable change; they fade out rather than vanish. */
    private fun removeStrokes(predicate: (Stroke) -> Boolean) {
        val ops = ArrayList<History.Op>()
        val now = SystemClock.uptimeMillis()
        for (index in strokes.indices.reversed()) {
            val s = strokes[index]
            if (predicate(s)) {
                strokes.removeAt(index)
                ops.add(History.Op(insert = false, index = index, stroke = s))
                fades.add(Fade(s, now))
                invalidateLayer(s.bounds)
            }
        }
        if (ops.isEmpty()) return
        selection.clear()
        selectionRect.setEmpty()
        menuShown = false
        menuRect.setEmpty()
        history.record(History.Edited(ops))
    }

    private fun notifyState() {
        listener?.onInkChanged()
    }

    // endregion

    private companion object {
        const val NO_POINTER = -1

        // Infrared digitizer calibration (raw touch-major units, measured on the IFP7533):
        // pen tip ≈ 1.4, finger ≈ 3.2, fist ≈ 27–40.
        const val IR_DIGITIZER = "IRtouch"
        const val IR_PEN_MAX = 2.2f
        const val IR_PALM_MIN = 15f
        const val FADE_MS = 220L
        const val LASSO_HOLD_MS = 450L
        const val MAX_DRAG_POINTS = 64
        const val GROUP_WINDOW = 96

        // Selection handles and gestures.
        const val HANDLE_DP = 11f
        const val ROTATE_STEM_DP = 34f
        const val ROTATE_HANDLE = 4
        const val MIN_SCALE = 0.15f
        const val MAX_SCALE = 8f
        const val FLING_DP_PER_MS = 0.9f
        const val FLIGHT_STOP_DP_PER_MS = 0.03f
        const val FLIGHT_DECAY_MS = 700f
        const val BOUNCE = 0.72f
        const val COPY_ROW = 0
        const val DUPLICATE_ROW = 1
        const val COLOUR_ROW = 2
        const val DELETE_ROW = 3
        const val HIGHLIGHTER_ALPHA = 96
        const val DIRTY_MARGIN = 2f
        const val PREDICT_MAX_DP = 24f

        // Whiteboard.
        const val GRID_DP = 36f

        /** Double ruling: a pair of lines every 1.5 grid steps, half a step apart. */
        const val DOUBLE_PERIOD = 1.5f
        val SINGLE_LINE = floatArrayOf(0f)
        val DOUBLE_LINES = floatArrayOf(0f, 0.5f) // the second line half a square below
        // A staff: five lines 0.45 squares apart (16 dp: room for handwritten notes), every 3.5 squares.
        const val STAFF_PERIOD = 3.5f
        val STAFF_LINES = floatArrayOf(0f, 0.45f, 0.9f, 1.35f, 1.8f)

        /** Below this luminance ink counts as black (and shows white on a dark paper). */
        const val DARK_INK = 0.05

        /** Space above a cutout on the whiteboard, and between stacked cutouts. */
        const val PICTURE_MARGIN_DP = 24f
        const val MIN_GRID_DP = 12f
        val ZERO = floatArrayOf(0f, 0f)
        const val MIN_ZOOM = 0.2f
        const val MAX_ZOOM = 5f
    }
}
