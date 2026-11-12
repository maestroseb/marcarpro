package com.maestroseb.marcarpro

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
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
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withClip
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withMatrix
import androidx.core.graphics.withTranslation
import androidx.input.motionprediction.MotionEventPredictor
import kotlin.math.PI
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
 * Ink being written, and just written, is "wet": it shows in a grid of small views of its own
 * ([WetCell]), so that writing only ever repaints the squares under the pen.
 */
class AnnotationView(context: Context) : ViewGroup(context) {

    init {
        // A view group that draws: the board itself; its children are only the wet cells.
        setWillNotDraw(false)
    }

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
                dry()
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

    /** The ring's "Text": the next touch, pen or finger, only says where it goes ([onTextPlace]). */
    var textNext = false

    /** Where a new text goes, on screen. */
    var onTextPlace: ((x: Float, y: Float) -> Unit)? = null

    /** "Edit" in the selection menu. */
    var onTextEdit: ((Stroke) -> Unit)? = null

    /** A text is being typed: the board doesn't write, and a touch on it ends the typing. */
    var typing = false

    /** A touch on the board while [typing]. */
    var onTypingTouch: (() -> Unit)? = null

    // The touch going on started while typing: all of it is ignored, even once the typing is over.
    private var swallowing = false

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
    /** The board on screen. */
    internal val currentBoard get() = board
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
            bodyPaint.color = staffPaint.color
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
    // The two middle lines of a Montessori row: darker and a little thicker, to see the letters' body at a glance.
    private val bodyPaint = Paint().apply {
        color = 0x4D1F2329
        strokeWidth = dp(2f)
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

    /** Ink on its way out; [shift] (screen px) slides it aside as it goes, when the page turns. */
    private class Fade(val stroke: Stroke, val start: Long, val shift: Float = 0f)

    /** Cleared ink being swept off, right to left, like a duster across the board. */
    private class Sweep(val strokes: List<Stroke>, val start: Long)

    private var sweep: Sweep? = null

    // A page turn: the new page's ink slides in from the side it comes from, as the old one leaves.
    private var turnStart = 0L
    private var turnShift = 0f
    private val turnPaint = Paint()

    private val fades = ArrayList<Fade>()
    private val magic = ArrayList<Fade>()

    /**
     * MOVE/SCALE/ROTATE/PINCH act on [lifted] strokes through [liveMatrix] (cheap to draw) and
     * are applied to the strokes only when the finger lifts. DESELECT swallows a tap on empty
     * space that only cleared the selection.
     */
    private enum class Gesture { NONE, LASSO, MOVE, SCALE, ROTATE, PINCH, DESELECT, PAN, PLACE }

    private var gesture = Gesture.NONE
    private var pointerId = NO_POINTER

    // Ink and erasers are tracked per pointer, so several pupils can write and rub out at once.
    private class Eraser(var x: Float, var y: Float, val base: Float, var at: Long) {
        var radius = base
        var speed = 0f
    }

    /**
     * A stroke being written by pointer [id], with the tool it was started with (the tool may change
     * meanwhile). A plain pen line is [piecewise]: what the pen leaves behind no longer changes and
     * is laid down once, in pieces; only its end is worked out and drawn again every frame.
     */
    private class Live(val id: Int, val stroke: Stroke, val tool: Tool, val piecewise: Boolean) {
        /** New samples arrived since it was last brought up to date on screen. */
        var stale = false

        /** How far the outline is laid down in pieces: up to the cut after this point (0: none of it). */
        var laid = 0
        var pieces = 0

        /** What the pieces' widths came from: the pen's own pressure, or its speed. */
        var pressure = false

        /** The outline from the last piece to the pen. */
        val tip = Path()

        /** Drawn whole, not as [tip]: a dot so far, or ink that can't be laid down in pieces. */
        var whole = true

        /** The cells its end is drawn in, as columns [c0]..[c1] and rows [r0]..[r1]; none while [c0] < 0. */
        var c0 = -1
        var c1 = -1
        var r0 = -1
        var r1 = -1

        /** A short line on to where the pen is predicted to be, from the stroke's last point. */
        var predicted = false
        var fromX = 0f
        var fromY = 0f
        var toX = 0f
        var toY = 0f

        /** Lifted and committed: its ink stays wet until it dries into the layer. */
        var done = false
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
    private class Flight(
        val items: List<Stroke>, val matrix: Matrix, val bounds: RectF, val area: RectF, var vx: Float, var vy: Float,
        /** The throw this belongs to: everything it knocks away lands as one undo step. */
        val chain: Chain,
        /** Words it lay on when it set off: it slides over them instead of knocking them away. */
        val ignore: HashSet<Int>,
    ) {
        var time = SystemClock.uptimeMillis()
        /** This frame's time step, in ms. */
        var dt = 0f
        /** Heavier the bigger it is (its box's area, with a floor so a thin line still weighs something). */
        val mass = kotlin.math.max(bounds.width() * bounds.height(), 1f)
        /** How hard it is to turn: a box's moment of inertia about its centre. */
        val inertia = mass * (bounds.width() * bounds.width() + bounds.height() * bounds.height()) / 12f
        /** Turning, in degrees per ms: a knock off its centre sets it spinning a little. */
        var spin = 0f
        val now = RectF()
    }

    private class Chain {
        val changes = ArrayList<History.Change>()
        var flying = 0
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
    private val menuRows = Array(5) { RectF() }
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
    private val leaderLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = dp(1.5f)
        pathEffect = DashPathEffect(floatArrayOf(dp(7f), dp(5f)), 0f)
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
        typeface = Fonts.bold(context)
    }
    private val menuDivider = Paint().apply { color = 0x14000000 }
    private val swatchPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val swatchEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1f); color = 0x33000000 }
    private val menuIcons = intArrayOf(R.drawable.ic_text, R.drawable.ic_copy, R.drawable.ic_duplicate, 0, R.drawable.ic_trash).map { res ->
        if (res == 0) null else ContextCompat.getDrawable(context, res)!!.mutate()
    }
    private val menuLabels = listOf(
        context.getString(R.string.menu_edit), context.getString(R.string.menu_copy), context.getString(R.string.menu_duplicate), "",
        context.getString(R.string.delete),
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
            is History.Several -> change.changes.forEach { unionBounds(area, it) }
        }
    }

    /** Returns false when the board was already empty. */
    fun clearAll(): Boolean {
        if (strokes.isEmpty()) return false
        stopFlight()
        cancelInk()
        cancelGesture()
        removeStrokes(sweep = strokes.size <= SWEEP_MAX_STROKES) { true }
        return true
    }

    /** True when the board has no ink. */
    val isEmpty get() = strokes.isEmpty()

    /** The committed ink, encoded for [InkStore]. */
    fun encodeInk(): List<ByteArray> = InkStore.encodeParts(strokes)

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

    /** True while nothing is being written, erased, moved or flying: safe to swap boards. */
    val isIdle get() = gesture == Gesture.NONE && inking.isEmpty() && erasing.isEmpty() && !flying

    /**
     * Shows [next] instead of the current board, which is returned; the old ink fades out. Turning
     * a page ([direction] 1 forwards, -1 back), the old ink also slides away and the new comes in
     * from the other side, over paper that stays put.
     */
    internal fun showBoard(next: Board, direction: Int = 0): Board {
        val previous = board
        if (next === previous) return previous
        stopFlight()
        cancelInk()
        cancelGesture()
        clearSelection()
        val now = SystemClock.uptimeMillis()
        val shift = -direction * width * TURN_SHIFT
        for (s in previous.strokes) fades.add(Fade(s, now, shift))
        turnStart = if (direction != 0) now else 0L
        turnShift = -shift
        board = next
        lifted.clear()
        panning = false
        redrawLayer()
        return previous
    }

    // region Rendering

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w <= 0 || h <= 0) return
        dry(keep = false) // everything is drawn again below
        buildCells(w, h)
        layer.resize(w, h)
        redrawLayer()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (cell in cells) cell.layout(cell.x0, cell.y0, cell.x1, cell.y1)
    }

    /** Every touch is the board's: the wet cells only draw. */
    override fun onInterceptTouchEvent(ev: MotionEvent) = true

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
        removeCallbacks(liveStep)
        liveQueued = false
        removeCallbacks(dryStep)
        velocity.recycle()
        layer.release()
        wet.clear()
        for (cell in cells) cell.release(recycle = true)
    }

    private fun redrawLayer() {
        dry(keep = false) // the wet ink is drawn here with all the rest
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
    private fun drawOnLayer(s: Stroke) {
        dry() // what was written before it goes under it
        layer.add(s, camera)
    }

    /** Repaints only [area] of the layer, on the next frame (erasing touches a small spot). */
    private fun invalidateLayer(area: RectF) {
        dry() // the layer is about to be repainted from the strokes: all of them must be in it
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



    /**
     * A small picture of this board's ink, all of it fitted in, on its paper colour (the list of
     * boards shows it: a board named after its date is told apart by what is on it). Null if empty.
     */
    private var thumbOutline = 0f

    fun thumbnail(w: Int, h: Int): android.graphics.Bitmap? {
        if (strokes.isEmpty() || w <= 0 || h <= 0) return null
        val all = RectF()
        for (s in strokes) all.union(s.bounds)
        // A single word isn't blown up to fill the thumbnail: at least a third of the screen shows.
        val minW = maxOf(width, 1) / 3f
        val minH = minW * h / w
        if (all.width() < minW) all.inset(-(minW - all.width()) / 2, 0f)
        if (all.height() < minH) all.inset(0f, -(minH - all.height()) / 2)
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(paperColor.background)
        val scale = minOf(w / all.width(), h / all.height()) * THUMB_FILL
        c.translate(w / 2f, h / 2f)
        c.scale(scale, scale)
        c.translate(-all.centerX(), -all.centerY())
        // Scaled down, ink would be a hairline: every pen line gets an outline about 1 px wide.
        thumbOutline = THUMB_LINE_PX / scale
        try {
            for (s in strokes) drawStroke(c, s, 1f)
        } finally {
            thumbOutline = 0f
        }
        return bmp
    }

    /**
     * What of [list] is worth sharing, in board coordinates: all its ink with a margin, and at least
     * the screen's size (a few words keep the page they were written on). Null if there is no ink.
     */
    internal fun exportArea(list: List<Stroke>): RectF? {
        if (list.isEmpty()) return null
        val all = RectF(list[0].bounds)
        for (s in list) all.union(s.bounds)
        all.inset(-dp(EXPORT_MARGIN_DP), -dp(EXPORT_MARGIN_DP))
        val minW = maxOf(width, 1).toFloat()
        val minH = maxOf(height, 1).toFloat()
        if (all.width() < minW) all.inset(-(minW - all.width()) / 2, 0f)
        if (all.height() < minH) all.inset(0f, -(minH - all.height()) / 2)
        return all
    }

    /**
     * Draws a page for sharing onto [canvas]: this board's paper and [list], with [area] (board
     * coordinates) filling a canvas [scale] times its size. Main thread only (it shares the paints).
     */
    internal fun drawExport(canvas: Canvas, list: List<Stroke>, area: RectF, scale: Float) {
        val m = Matrix()
        m.setTranslate(-area.left, -area.top)
        m.postScale(scale, scale)
        drawPaper(canvas, m, area.width() * scale, area.height() * scale, minStep = 0f)
        canvas.withMatrix(m) { for (s in list) drawStroke(this, s, 1f) }
    }

    private fun drawStroke(canvas: Canvas, s: Stroke, opacity: Float) {
        if (s.tool == InkTool.IMAGE) {
            drawPicture(canvas, s, opacity)
        } else if (s.tool == InkTool.TEXT) {
            drawText(canvas, s, opacity)
        } else if (s.tool == InkTool.PEN) {
            inkPaint.color = shown(s.color)
            inkPaint.alpha = (Color.alpha(s.color) * opacity).toInt()
            if (thumbOutline > 0f) {
                inkPaint.style = Paint.Style.FILL_AND_STROKE
                inkPaint.strokeWidth = thumbOutline
                canvas.drawPath(s.path, inkPaint)
                inkPaint.style = Paint.Style.FILL
                inkPaint.strokeWidth = 0f
            } else {
                canvas.drawPath(s.path, inkPaint)
            }
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
        if (s.points.size != 4) return
        mapBlock(s, image.width.toFloat(), image.height.toFloat())
        picturePaint.alpha = (255 * opacity).toInt()
        canvas.drawBitmap(image, pictureMatrix, picturePaint)
        canvas.drawPath(s.path, pictureEdge)
    }

    /** A typed text, in its four corners like a cutout. */
    private fun drawText(canvas: Canvas, s: Stroke, opacity: Float) {
        val text = s.text ?: return
        if (s.points.size != 4) return
        val body = s.body ?: TextBody(context, text, s.size).also { s.body = it }
        mapBlock(s, body.width, body.height)
        body.paint.color = shown(s.color)
        body.paint.alpha = (255 * opacity).toInt()
        canvas.withMatrix(pictureMatrix) { body.layout.draw(this) }
    }

    // Sets [pictureMatrix] to take a w × h rectangle to the block's corners.
    private fun mapBlock(s: Stroke, w: Float, h: Float) {
        pictureSrc[2] = w
        pictureSrc[4] = w
        pictureSrc[5] = h
        pictureSrc[7] = h
        for (i in 0 until 4) {
            pictureDst[2 * i] = s.points[i].x
            pictureDst[2 * i + 1] = s.points[i].y
        }
        pictureMatrix.setPolyToPoly(pictureSrc, 0, pictureDst, 0, 4)
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
        // Part of the paper: ink written over it covers it.
        drawVersionMark(canvas)
        // While the whiteboard moves, its ink is drawn directly (the layer is re-rasterised after).
        if (!panning) repaintDirty()
        if (!inkHidden && !panning) drawLayer(canvas)
        if (!inkHidden) drawSweep(canvas)
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
            // (What is being written is drawn by the wet cells, over all of this.)
            drawMagic(canvas)
            drawErasers(canvas)
            if (gesture == Gesture.LASSO && lassoArmed) drawLasso(canvas)
        }
        drawTextLeader(canvas)
        // The selection box and the menu are drawn in screen coordinates: on a zoomed whiteboard they
        // keep their size (and their handles stay as easy to grab).
        if (selection.isNotEmpty() && !inkHidden) drawSelection(canvas)
        if (selection.isNotEmpty() && menuShown && gesture == Gesture.NONE && !flying) drawMenu(canvas) else menuRect.setEmpty()
        if (warmFrames > 0) warmUp(canvas)
    }

    /** What the version mark in the whiteboard's corner says, and how. */
    enum class VersionMark { IDLE, CHECKING, CURRENT, NEW, OFFLINE }

    var versionText = ""
        private set
    var versionState = VersionMark.IDLE
        private set
    var onVersionTap: (() -> Unit)? = null

    fun showVersion(text: String, state: VersionMark) {
        versionText = text
        versionState = state
        if (board.paper) invalidate()
    }

    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Fonts.regular(context)
        textSize = dp(MARK_TEXT_DP)
        textAlign = Paint.Align.CENTER
    }
    private val markFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val markRect = RectF()

    private fun onMark(x: Float, y: Float): Boolean {
        if (markRect.isEmpty) return false
        val slack = dp(MARK_SLACK_DP)
        return x >= markRect.left - slack && x <= markRect.right + slack && y >= markRect.top - slack && y <= markRect.bottom + slack
    }

    /**
     * The installed version, small and faint in the whiteboard's bottom right corner, like a
     * colophon; a tap looks for a newer one, and one waiting turns it into an "Install" pill.
     * Drawn here, in the ink's own window: another window would be one more layer to compose.
     */
    private fun drawVersionMark(canvas: Canvas) {
        if (!board.paper || versionText.isEmpty()) {
            markRect.setEmpty()
            return
        }
        val dark = paperColor.dark
        val pill = versionState == VersionMark.NEW
        markPaint.color = when (versionState) {
            VersionMark.NEW -> Color.WHITE
            VersionMark.CURRENT -> if (dark) MARK_CURRENT_DARK else MARK_CURRENT
            VersionMark.OFFLINE -> if (dark) MARK_OFFLINE_DARK else MARK_OFFLINE
            else -> if (dark) MARK_FAINT_DARK else MARK_FAINT
        }
        val pad = dp(if (pill) 10f else 6f)
        val h = dp(MARK_HEIGHT_DP)
        val right = width - dp(MARK_MARGIN_DP)
        val bottom = height - dp(MARK_MARGIN_DP) / 2
        markRect.set(right - markPaint.measureText(versionText) - 2 * pad, bottom - h, right, bottom)
        // The paper's own colour behind the words, so ruled lines don't run through them.
        markFill.color = if (pill) OrbStyle.TEAL else paperColor.background
        canvas.drawRoundRect(markRect, h / 2, h / 2, markFill)
        canvas.drawText(versionText, markRect.centerX(), markRect.centerY() - (markPaint.descent() + markPaint.ascent()) / 2, markPaint)
    }

    /**
     * On the panel, the first stroke after the app starts waited 150–285 ms while the GPU built its
     * programs for ink (paths, translucent ink, glow, pictures). They are built now instead, before
     * anyone writes: one of each drawn in a corner at 1/100 opacity, which no eye can tell apart.
     */
    private var warmFrames = WARM_FRAMES
    private val warmBitmap by lazy { createBitmap(8, 8).apply { eraseColor(Color.BLACK) } }
    private val warmMatrix = Matrix()

    private fun warmUp(canvas: Canvas) {
        warmFrames--
        val d = dp(4f)
        for (tool in listOf(InkTool.PEN, InkTool.HIGHLIGHTER)) {
            val s = Stroke(tool, Color.BLACK, d, 1f)
            for (k in 0..6) s.add(d + k * d, d + (k % 2) * d, 0.5f + k * 0.05f)
            s.finish()
            drawStroke(canvas, s, WARM_OPACITY)
            if (tool == InkTool.PEN) drawGlowing(canvas, s, WARM_OPACITY)
        }
        warmMatrix.setRotate(7f)
        warmMatrix.postTranslate(d, d)
        picturePaint.alpha = (255 * WARM_OPACITY).toInt()
        canvas.drawBitmap(warmBitmap, warmMatrix, picturePaint)
        if (warmFrames > 0) postInvalidateOnAnimation()
    }

    /**
     * White paper, plain or with a faint pattern ([paperStyle]: dots, squares or ruled lines) that
     * moves with it, so panning and zooming can be felt. A plain fill plus a few hundred dots or
     * lines: one cheap pass, not a full-screen texture.
     */
    private fun drawPaper(canvas: Canvas, cam: Matrix = camera, w: Float = width.toFloat(), h: Float = height.toFloat(), minStep: Float = dp(MIN_GRID_DP)) {
        canvas.drawColor(paperColor.background)
        if (paperStyle == Paper.BLANK) return
        val k = cam.mapRadius(1f)
        val step = dp(GRID_DP) * k
        if (step < minStep) return // zoomed far out: the pattern would just be noise
        cam.mapPoints(origin, ZERO)
        val x0 = origin[0] - kotlin.math.floor(origin[0] / step) * step
        val y0 = origin[1] - kotlin.math.floor(origin[1] / step) * step
        val cols = (w / step).toInt() + 1
        val rows = (h / step).toInt() + 1
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
            Paper.MONTESSORI -> step * MONTESSORI_PERIOD to MONTESSORI_LINES
            Paper.STAFF -> step * STAFF_PERIOD to STAFF_LINES
            else -> step to SINGLE_LINE
        }
        val ry0 = origin[1] - kotlin.math.floor(origin[1] / period) * period - period
        val ruled = (h / period).toInt() + 2
        var strong = 0
        val need = (ruled * offsets.size + cols) * 4
        if (gridLines.size < need) gridLines = FloatArray(need)
        var n = 0
        // Montessori: the two middle lines (the letters' body) go in a second, darker batch.
        val strongFrom = if (paperStyle == Paper.MONTESSORI) MONTESSORI_STRONG else offsets.size
        for (pass in 0..1) {
            if (pass == 1) strong = n
            for (r in 0 until ruled) {
                val y = ry0 + r * period
                for (i in offsets.indices) {
                    if ((i >= strongFrom) != (pass == 1)) continue
                    val yy = y + offsets[i] * step
                    gridLines[n++] = 0f; gridLines[n++] = yy; gridLines[n++] = w; gridLines[n++] = yy
                }
            }
        }
        if (paperStyle == Paper.GRID) for (c in 0 until cols) {
            val x = x0 + c * step
            gridLines[n++] = x; gridLines[n++] = 0f; gridLines[n++] = x; gridLines[n++] = h
        }
        if (paperStyle == Paper.MONTESSORI) {
            canvas.drawLines(gridLines, 0, strong, linePaint)
            canvas.drawLines(gridLines, strong, n - strong, bodyPaint)
            return
        }
        canvas.drawLines(gridLines, 0, n, if (paperStyle == Paper.STAFF) staffPaint else linePaint)
    }

    private var gridPoints = FloatArray(0)
    private val origin = FloatArray(2)

    private fun drawFades(canvas: Canvas) {
        if (fades.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        val it = fades.iterator()
        while (it.hasNext()) {
            val f = it.next()
            val t = (now - f.start) / FADE_MS.toFloat()
            when {
                t >= 1f -> it.remove()
                f.shift == 0f -> drawStroke(canvas, f.stroke, (1f - t) * (1f - t))
                else -> canvas.withTranslation(f.shift * TURN_EASE.getInterpolation(t) / zoom, 0f) {
                    drawStroke(this, f.stroke, (1f - t) * (1f - t))
                }
            }
        }
        if (fades.isNotEmpty()) postInvalidateOnAnimation()
    }

    /** The committed ink; during a page turn it slides into place and comes up from transparent. */
    private fun drawLayer(canvas: Canvas) {
        if (turnStart == 0L) {
            layer.draw(canvas)
            return
        }
        val t = (SystemClock.uptimeMillis() - turnStart) / TURN_MS.toFloat()
        if (t >= 1f) {
            turnStart = 0L
            layer.draw(canvas)
            return
        }
        val e = TURN_EASE.getInterpolation(t)
        turnPaint.alpha = (255 * e).toInt()
        canvas.withTranslation(turnShift * (1f - e), 0f) { layer.draw(this, turnPaint) }
        postInvalidateOnAnimation()
    }

    /**
     * Cleared ink going off under a moving edge, from right to left. The edge is soft: a few bands
     * just before it are already half gone. Only clipping, no extra full-screen layer: on the
     * panel every frame redraws the whole 4K window.
     */
    private fun drawSweep(canvas: Canvas) {
        val sw = sweep ?: return
        val t = (SystemClock.uptimeMillis() - sw.start) / SWEEP_MS.toFloat()
        if (t >= 1f) {
            sweep = null
            return
        }
        val soft = width * SWEEP_SOFT
        val edge = width + soft - (width + 2 * soft) * SWEEP_EASE.getInterpolation(t)
        val band = soft / SWEEP_BANDS
        for (i in 0..SWEEP_BANDS) {
            // Band 0 is everything left of the soft edge, fully there; then fainter and fainter.
            val left = if (i == 0) 0f else edge - soft + (i - 1) * band
            val right = edge - soft + i * band
            if (right <= 0f) continue
            val alpha = 1f - i / (SWEEP_BANDS + 1f)
            canvas.withClip(left, 0f, right, height.toFloat()) {
                withMatrix(camera) { for (s in sw.strokes) drawStroke(this, s, alpha) }
            }
        }
        postInvalidateOnAnimation()
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
        // A text on its own can be corrected.
        val editable = selection.singleOrNull()?.tool == InkTool.TEXT
        val rows = menuRows.size - (if (colours) 0 else 1) - (if (editable) 0 else 1)
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
            if ((i == COLOUR_ROW && !colours) || (i == EDIT_ROW && !editable)) {
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

    // region Wet ink

    /**
     * A square of the board, as a view of its own, for the ink that is still wet: what is being
     * written, and what was written a moment ago and isn't in the [layer] yet. A frame only repaints
     * the views that changed, so writing costs the squares under the pen. With the whole board in
     * one view, every sample of the pen repainted the whole 4K window (on the ViewBoard, most of
     * what a frame costs the GPU) and worked out and rasterised the whole stroke again, slower the
     * longer it got.
     *
     * Ink that no longer changes is rasterised once into [ink]; per frame only the strokes' ends
     * ([tips]) are drawn as paths. The squares are the layer's tiles ([InkLayer.TILE]), so dry ink
     * goes over to it as it is.
     */
    private inner class WetCell(val x0: Int, val y0: Int, val x1: Int, val y1: Int) : View(this@AnnotationView.context) {
        /** The ink here that no longer changes (null: none), and the part of the screen it covers. */
        var ink: Bitmap? = null
            private set
        val inked = Rect()
        private var inkCanvas: Canvas? = null
        private val part = Rect()

        /** What [ink] holds, oldest first: to draw it again if some of it has to go. */
        private val pieces = ArrayList<Piece>()

        /** Strokes whose end, still changing under the pen, crosses this square. */
        val tips = ArrayList<Live>()

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onDraw(canvas: Canvas) {
            val b = ink
            if (b != null && !inked.isEmpty) {
                part.set(inked.left - x0, inked.top - y0, inked.right - x0, inked.bottom - y0)
                canvas.drawBitmap(b, part, part, null)
            }
            if (tips.isEmpty()) return
            canvas.translate(-x0.toFloat(), -y0.toFloat())
            canvas.concat(camera)
            for (k in tips.indices) drawTip(canvas, tips[k])
        }

        fun add(piece: Piece) {
            pieces.add(piece)
            paint(piece)
            invalidate()
        }

        private fun paint(piece: Piece) {
            val c = inkCanvas ?: createBitmap(x1 - x0, y1 - y0).let { b ->
                ink = b
                Canvas(b).also { inkCanvas = it }
            }
            c.withTranslation(-x0.toFloat(), -y0.toFloat()) {
                withMatrix(camera) { drawPiece(this, piece) }
            }
            val a = piece.area
            val l = max(a.left, x0)
            val t = max(a.top, y0)
            val r = min(a.right, x1)
            val bottom = min(a.bottom, y1)
            if (l < r && t < bottom) inked.union(l, t, r, bottom)
        }

        /** Takes out [owner]'s pieces (with null, those of every stroke already lifted); true if it held any. */
        fun take(owner: Live?): Boolean {
            var kept = 0
            for (k in pieces.indices) {
                val p = pieces[k]
                val goes = if (owner == null) p.owner.done else p.owner === owner
                if (!goes) pieces[kept++] = p
            }
            if (kept == pieces.size) return false
            pieces.subList(kept, pieces.size).clear()
            if (pieces.isEmpty()) {
                release(recycle = true)
            } else {
                // What stays is drawn again.
                ink?.eraseColor(Color.TRANSPARENT)
                inked.setEmpty()
                for (k in pieces.indices) paint(pieces[k])
            }
            invalidate()
            return true
        }

        /** Lets go of the rasterised ink (it is the layer's now, or nobody's): the square is clean again. */
        fun release(recycle: Boolean) {
            if (recycle) ink?.recycle()
            ink = null
            inkCanvas = null
            inked.setEmpty()
            pieces.clear()
            invalidate()
        }
    }

    /**
     * A stretch of wet ink that no longer changes: [path], or all of its stroke when that is null.
     * [area] is the part of the screen it covers.
     */
    private class Piece(val owner: Live, val path: Path?, val area: Rect)

    private var cells = emptyArray<WetCell>()
    private var cellCols = 0
    private var cellRows = 0

    /** Strokes lifted and committed whose ink still shows from the cells, oldest first. */
    private val wet = ArrayList<Live>()
    private var liveQueued = false
    private val liveStep = Runnable { stepLive() }
    private val dryStep = Runnable { dry() }
    private val reach = RectF()

    private fun buildCells(w: Int, h: Int) {
        for (cell in cells) cell.release(recycle = true)
        removeAllViewsInLayout()
        cellCols = (w + CELL - 1) / CELL
        cellRows = (h + CELL - 1) / CELL
        cells = Array(cellCols * cellRows) { k ->
            val x = k % cellCols * CELL
            val y = k / cellCols * CELL
            WetCell(x, y, min(x + CELL, w), min(y + CELL, h)).also { addViewInLayout(it, -1, generateDefaultLayoutParams(), true) }
        }
        // A stroke being written starts again on the new cells.
        for (live in inking.values) {
            live.laid = 0
            live.pieces = 0
            live.c0 = -1
            wake(live)
        }
    }

    /** [live] has new samples: it is brought up to date once, on the next frame, however many arrive. */
    private fun wake(live: Live) {
        live.stale = true
        if (liveQueued) return
        liveQueued = true
        postOnAnimation(liveStep)
    }

    private fun stepLive() {
        liveQueued = false
        for (live in inking.values) {
            if (!live.stale) continue
            live.stale = false
            lay(live)
        }
    }

    /**
     * Brings [live] up to date on screen: lays down, in pieces, the part of its outline that new
     * samples can no longer change, and works out its end again.
     */
    private fun lay(live: Live) {
        if (cells.isEmpty()) return
        val s = live.stroke
        val n = if (live.piecewise) StrokeGeometry.measure(s) else 0
        live.whole = n == 0
        if (n == 0) {
            // A dot so far, or a highlighter or magic line: drawn whole.
            s.rebuild()
            reach.set(s.bounds)
            if (live.tool == Tool.MAGIC) reach.inset(-(dp(GLOW_REACH_DP) + s.size), -(dp(GLOW_REACH_DP) + s.size))
        } else {
            if (s.hasRealPressure != live.pressure) {
                // The pen turned out to tell its pressure: every width changes, so it is laid again.
                live.pressure = s.hasRealPressure
                takePieces(live)
            }
            val length = PIECE_REACH / zoom
            while (true) {
                val to = StrokeGeometry.pieceEnd(n, live.laid, length)
                if (to == 0) break
                val path = Path()
                StrokeGeometry.cut(path, live.laid, to, PIECE_OVERLAP / zoom)
                path.computeBounds(reach, true)
                place(live, path, reach)
                live.laid = to
            }
            StrokeGeometry.cut(live.tip, live.laid, overlap = PIECE_OVERLAP / zoom)
            live.tip.computeBounds(reach, true)
        }
        predict(live)
        camera.mapRect(reach)
        spread(live)
    }

    /**
     * A short extension of the one stroke being written, to where the pen is predicted to be. Never
     * stored: the next frame draws the real ink. Only plain pen ink (a translucent or glowing
     * extension would show its overlap; shapes are redrawn on lift anyway).
     */
    private fun predict(live: Live) {
        live.predicted = false
        val s = live.stroke
        if (inking.size != 1 || live.tool != Tool.PEN || s.points.size < 2) return
        val p = predictor?.predict() ?: return
        try {
            val i = p.findPointerIndex(live.id)
            if (i < 0) return
            val last = s.points.last()
            var px = p.getX(i)
            var py = p.getY(i)
            val d = hypot(px - last.x, py - last.y)
            val limit = dp(PREDICT_MAX_DP) / zoom
            if (d > limit) {
                px = last.x + (px - last.x) * limit / d
                py = last.y + (py - last.y) * limit / d
            }
            live.predicted = true
            live.fromX = last.x
            live.fromY = last.y
            live.toX = px
            live.toY = py
            reach.union(min(last.x, px) - s.size, min(last.y, py) - s.size, max(last.x, px) + s.size, max(last.y, py) + s.size)
        } finally {
            p.recycle()
        }
    }

    /** Lays down a piece of [live] ([path], or the whole stroke) that covers [area] of the board. */
    private fun place(live: Live, path: Path?, area: RectF) {
        if (cells.isEmpty()) return
        camera.mapRect(tmpRect, area)
        val on = Rect()
        tmpRect.roundOut(on)
        on.inset(-CELL_EDGE, -CELL_EDGE)
        val piece = Piece(live, path, on)
        for (r in row(on.top)..row(on.bottom)) for (c in column(on.left)..column(on.right)) cells[r * cellCols + c].add(piece)
        live.pieces++
    }

    /** Draws [live]'s end in the cells under [reach] (on screen), repainting those and the ones it left. */
    private fun spread(live: Live) {
        val c0 = column((reach.left - CELL_EDGE).toInt())
        val c1 = column((reach.right + CELL_EDGE).toInt() + 1)
        val r0 = row((reach.top - CELL_EDGE).toInt())
        val r1 = row((reach.bottom + CELL_EDGE).toInt() + 1)
        val was = live.c0 >= 0
        if (was) for (r in live.r0..live.r1) for (c in live.c0..live.c1) {
            if (c in c0..c1 && r in r0..r1) continue
            val cell = cells[r * cellCols + c]
            cell.tips.remove(live)
            cell.invalidate()
        }
        for (r in r0..r1) for (c in c0..c1) {
            val cell = cells[r * cellCols + c]
            if (!was || c !in live.c0..live.c1 || r !in live.r0..live.r1) cell.tips.add(live)
            cell.invalidate()
        }
        live.c0 = c0
        live.c1 = c1
        live.r0 = r0
        live.r1 = r1
    }

    private fun column(x: Int) = Math.floorDiv(x, CELL).coerceIn(0, cellCols - 1)

    private fun row(y: Int) = Math.floorDiv(y, CELL).coerceIn(0, cellRows - 1)

    /** [live]'s end is no longer drawn. */
    private fun leave(live: Live) {
        if (live.c0 < 0) return
        for (r in live.r0..live.r1) for (c in live.c0..live.c1) {
            val cell = cells.getOrNull(r * cellCols + c) ?: continue
            cell.tips.remove(live)
            cell.invalidate()
        }
        live.c0 = -1
    }

    private fun takePieces(live: Live) {
        if (live.pieces > 0) for (cell in cells) cell.take(live)
        live.pieces = 0
        live.laid = 0
    }

    /** Takes [live] off the screen altogether (cancelled, faded away or turned into a shape). */
    private fun unlay(live: Live) {
        leave(live)
        takePieces(live)
    }

    /**
     * [live] has just been lifted and committed: its ink stays where it is, wet, with its end as the
     * lift leaves it. Nothing else is repainted: the next stroke starts on a board that is free.
     */
    private fun settle(live: Live) {
        val s = live.stroke
        if (cells.isEmpty()) {
            // (No board to show it on yet: straight into the layer.)
            drawOnLayer(s)
            return
        }
        live.done = true
        live.predicted = false
        leave(live)
        if (s.hasRealPressure != live.pressure) {
            // The last sample showed that the pen tells its pressure: every width changes, so the
            // pieces go and the outline is laid whole.
            live.pressure = s.hasRealPressure
            takePieces(live)
        }
        val n = if (live.piecewise) StrokeGeometry.measure(s) else 0
        if (n == 0) {
            place(live, null, s.bounds)
        } else {
            val path = Path()
            StrokeGeometry.cut(path, live.laid, overlap = PIECE_OVERLAP / zoom)
            path.computeBounds(reach, true)
            place(live, path, reach)
        }
        wet.add(live)
    }

    /** Lets the wet ink dry once the writing has stopped for a moment. */
    private fun dryLater() {
        removeCallbacks(dryStep)
        if (wet.isNotEmpty() && inking.isEmpty()) postDelayed(dryStep, DRY_MS)
    }

    /**
     * The ink written last goes into the [layer] with the rest ([keep] false: only off the cells,
     * the layer is being drawn again anyway). It costs a frame that repaints the whole board, so it
     * waits for a pause in the writing, or for anything else that needs the ink in the layer
     * (rubbing out, picking up, moving the paper…).
     */
    private fun dry(keep: Boolean = true) {
        removeCallbacks(dryStep)
        if (wet.isEmpty()) return
        if (inking.isEmpty()) {
            // Nobody is writing: what the cells hold is all committed, and goes over as it is.
            for (cell in cells) {
                val ink = cell.ink ?: continue
                cell.release(recycle = !(keep && layer.put(ink, cell.x0, cell.y0, cell.inked)))
            }
        } else {
            // Someone is: their ink stays in the cells, and the rest is drawn into the layer.
            if (keep) for (live in wet) layer.add(live.stroke, camera)
            for (cell in cells) cell.take(null)
        }
        wet.clear()
        invalidate()
    }

    private fun drawPiece(canvas: Canvas, piece: Piece) {
        val s = piece.owner.stroke
        val path = piece.path
        if (path == null) {
            drawStroke(canvas, s, 1f)
        } else {
            inkPaint.color = shown(s.color)
            canvas.drawPath(path, inkPaint)
        }
    }

    private fun drawTip(canvas: Canvas, live: Live) {
        val s = live.stroke
        when {
            live.tool == Tool.MAGIC -> drawGlowing(canvas, s, 1f)
            live.whole -> drawStroke(canvas, s, 1f)
            else -> {
                inkPaint.color = shown(s.color)
                canvas.drawPath(live.tip, inkPaint)
            }
        }
        if (live.predicted) {
            predictPaint.color = shown(s.color)
            predictPaint.strokeWidth = s.size * 0.9f
            canvas.drawLine(live.fromX, live.fromY, live.toX, live.toY, predictPaint)
        }
    }

    // endregion

    // region Input

    @android.annotation.SuppressLint("ClickableViewAccessibility") // performClick() is called from handle()
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!annotating) return false
        // A touch that starts on the version mark is a tap on it, unless it moves off as a stroke.
        val held = markDown
        if (held != null) {
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> if (hypot(e.x - held.x, e.y - held.y) <= touchSlop) return true
                MotionEvent.ACTION_UP -> {
                    markDown = null
                    held.recycle()
                    onVersionTap?.invoke()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    markDown = null
                    held.recycle()
                    return true
                }
            }
            // It became a stroke (or a second finger came): the ink gets the touch from its start.
            markDown = null
            onInk(held)
            held.recycle()
        } else if (e.actionMasked == MotionEvent.ACTION_DOWN && board.paper && onMark(e.x, e.y)) {
            markDown = MotionEvent.obtain(e)
            return true
        }
        return onInk(e)
    }

    private var markDown: MotionEvent? = null

    private fun onInk(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            swallowing = typing
            if (typing) onTypingTouch?.invoke()
        }
        if (swallowing) return true
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
                        inking.remove(id)?.let { unlay(it) }
                        dryLater()
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
        if (textNext && gesture == Gesture.NONE && inking.isEmpty() && erasing.isEmpty() && contact != Contact.ERASER) {
            clearSelection()
            pointerId = e.getPointerId(i)
            gesture = Gesture.PLACE
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
                    beginErase(id, x, y, eraserSize / 2f / zoom, e.eventTime)
                } else {
                    beginInk(id, x, y, e.getPressure(i))
                }
            }
            Contact.ERASER -> {
                requestUnbufferedDispatch(e)
                clearSelection()
                // A fist or palm rubs out a generous, adjustable area, like a real board eraser.
                beginErase(id, x, y, eraserSize / zoom, e.eventTime)
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
            Gesture.DESELECT, Gesture.PAN, Gesture.PLACE, Gesture.NONE -> Unit
        }
    }

    /** Ends the active gesture; returns true when it was a plain tap on empty canvas. */
    private fun pointerUp(e: MotionEvent, i: Int): Boolean {
        var tapped = false
        when (gesture) {
            Gesture.LASSO -> {
                removeCallbacks(armHold)
                when {
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
            Gesture.PLACE -> {
                textNext = false
                onTextPlace?.invoke(raw?.getX(i) ?: e.getX(i), raw?.getY(i) ?: e.getY(i))
            }
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
        dry() // the wet ink is rasterised for where the paper is now
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
        dry()
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
                lasso.rewind()
            }
            Gesture.MOVE, Gesture.SCALE, Gesture.ROTATE, Gesture.PINCH -> commitTransform()
            Gesture.PAN -> endPan()
            Gesture.DESELECT, Gesture.PLACE, Gesture.NONE -> Unit
        }
        gesture = Gesture.NONE
        pointerId = NO_POINTER
        pointerId2 = NO_POINTER
        invalidate()
    }

    private fun showInk() {
        if (!inkHidden) return
        inkHidden = false
        onInkShown?.invoke()
    }

    private fun beginInk(id: Int, x: Float, y: Float, pressure: Float) {
        showInk()
        val kind = if (tool == Tool.HIGHLIGHTER) InkTool.HIGHLIGHTER else InkTool.PEN
        val color = if (tool == Tool.MAGIC) Presets.MAGIC_COLOR else inkColor
        val stroke = Stroke(kind, color, inkSize / zoom, speedUnit = dp(14f) / zoom).also {
            it.startTime = SystemClock.uptimeMillis()
            it.add(x, y, pressure)
            it.rebuild()
        }
        val live = Live(id, stroke, tool, piecewise = kind == InkTool.PEN && tool != Tool.MAGIC && Color.alpha(color) == 255)
        inking[id] = live
        removeCallbacks(dryStep) // writing goes on: what is wet stays as it is
        wake(live)
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
            if (grew) wake(live)
        }
        for ((id, r) in erasing) {
            val i = e.findPointerIndex(id)
            if (i < 0) continue
            for (h in 0 until e.historySize) eraseTo(r, e.getHistoricalX(i, h), e.getHistoricalY(i, h), e.getHistoricalEventTime(h))
            eraseTo(r, e.getX(i), e.getY(i), e.eventTime)
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
                unlay(live)
                postInvalidateOnAnimation()
            }
            else -> {
                val shape = if (live.tool == Tool.SHAPE) ShapeRecognizer.recognize(s.points.map { ShapeRecognizer.Point(it.x, it.y) }) else null
                if (shape != null) {
                    s.replacePoints(shape.points.map { it.x }, shape.points.map { it.y })
                    s.uniform = true
                }
                s.finish()
                s.endTime = SystemClock.uptimeMillis()
                s.group = if (live.tool == Tool.SHAPE) board.nextGroup++ else groupFor(s)
                strokes.add(s)
                val c = shape?.center
                if (shape == null) {
                    // It stays as written, and where it is: wet, until it dries into the layer.
                    history.record(History.Added(s))
                    settle(live)
                } else {
                    // The drawing gives way to the shape made out of it.
                    unlay(live)
                    drawOnLayer(s)
                    if (c == null) {
                        history.record(History.Added(s))
                    } else {
                        // A circle comes with its centre: a dot of its own (it can be rubbed out on its
                        // own), in the circle's group (it moves with it); one undo removes both.
                        val dot = Stroke(s.tool, s.color, s.size * CENTER_DOT, s.speedUnit).also {
                            it.add(c.x, c.y, 1f)
                            it.uniform = true
                            it.finish()
                            it.endTime = s.endTime
                            it.group = s.group
                        }
                        strokes.add(dot)
                        drawOnLayer(dot)
                        history.record(History.Edited(listOf(
                            History.Op(insert = true, index = strokes.lastIndex - 1, stroke = s),
                            History.Op(insert = true, index = strokes.lastIndex, stroke = dot),
                        )))
                    }
                    invalidate()
                }
            }
        }
        dryLater()
    }

    private fun beginErase(id: Int, x: Float, y: Float, radius: Float, time: Long) {
        dry() // rubbing out, picking up and tapping act on the layer
        val r = Eraser(x, y, radius, time)
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
        for (live in inking.values) unlay(live)
        inking.clear()
        dryLater()
        if (erasing.isNotEmpty()) {
            erasing.clear()
            commitErase()
        }
        invalidate()
    }

    /**
     * A hand sweeping fast means a big area is going: the eraser grows with the speed, at once,
     * and shrinks back gently, so careful rubbing keeps the size the teacher chose.
     */
    private fun growEraser(r: Eraser, moved: Float, time: Long) {
        val dt = time - r.at
        if (dt <= 0) return
        r.at = time
        val speed = moved * zoom / dp(1f) / dt // dp per ms on screen, whatever the zoom
        r.speed += (speed - r.speed) * ERASER_SPEED_SMOOTHING
        val push = ((r.speed - ERASER_SLOW) / (ERASER_FAST - ERASER_SLOW)).coerceIn(0f, 1f)
        val target = min(r.base * (1f + (ERASER_GROWTH - 1f) * push), max(r.base, dp(ERASER_MAX_DP) / zoom))
        r.radius += (target - r.radius) * (if (target > r.radius) ERASER_GROW_RATE else ERASER_SHRINK_RATE)
    }

    private fun eraseTo(r: Eraser, x: Float, y: Float, time: Long) {
        // Step along the path so fast swipes never skip over thin strokes.
        val d = hypot(x - r.x, y - r.y)
        growEraser(r, d, time)
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
            // A text can't be rubbed out letter by letter: it goes whole (undo brings it back).
            val pieces = if (s.tool == InkTool.TEXT) emptyList() else s.cut(x, y, radius)
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
        val placed = strokes.takeLast(2 * GROUP_WINDOW).filter { it.tool != InkTool.IMAGE && it.tool != InkTool.TEXT }
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
        dry()
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
        val m = Matrix(liveMatrix)
        val at = RectF().also { m.mapRect(it, bounds) }
        launch(items, m, bounds, RectF(visible), vx, vy, Chain(), at)
        liveMatrix.reset()
        lifted.clear()
    }

    /** Sets [items] flying from where [m] puts them; [at] is where that is on the board. */
    private fun launch(items: List<Stroke>, m: Matrix, bounds: RectF, area: RectF, vx: Float, vy: Float, chain: Chain, at: RectF) {
        val ignore = HashSet<Int>()
        for (s in strokes) if (s !in offLayer && touches(at, s)) ignore += s.group
        flights += Flight(items, m, bounds, area, vx, vy, chain, ignore)
        chain.flying++
        airborne.addAll(items)
        if (flights.size == 1) postOnAnimation(flightStep)
    }

    private fun stepFlights() {
        if (!flying) return
        val now = SystemClock.uptimeMillis()
        // A fast throw moves further in a frame than a thin line is wide: in a few smaller steps,
        // each checked for knocks, it can't jump over anything.
        var fastest = 0f
        for (f in flights) {
            f.dt = min(32L, now - f.time).toFloat()
            f.time = now
            fastest = kotlin.math.max(fastest, hypot(f.vx, f.vy) * f.dt)
        }
        val steps = kotlin.math.ceil(fastest / dp(FLIGHT_SUBSTEP_DP)).toInt().coerceIn(1, MAX_SUBSTEPS)
        repeat(steps) {
            moveFlights(steps)
            collide()
        }
        val left = flights.iterator()
        while (left.hasNext()) {
            val f = left.next()
            if (hypot(f.vx, f.vy) * zoom < dp(FLIGHT_STOP_DP_PER_MS)) {
                left.remove()
                land(f)
            }
        }
        invalidate()
        if (flying) postOnAnimation(flightStep)
    }

    /** Moves every flight by a [parts]-th of its frame, bouncing off the area's edges. */
    private fun moveFlights(parts: Int) {
        for (f in flights) {
            val dt = f.dt / parts
            val m = f.matrix
            m.postTranslate(f.vx * dt, f.vy * dt)
            if (f.spin != 0f) {
                m.mapRect(tmpRect, f.bounds)
                m.postRotate(f.spin * dt, tmpRect.centerX(), tmpRect.centerY())
                f.spin *= exp(-dt / FLIGHT_DECAY_MS)
            }
            m.mapRect(tmpRect, f.bounds)
            val a = f.area
            if (tmpRect.left < a.left) { m.postTranslate(a.left - tmpRect.left, 0f); f.vx = abs(f.vx) * BOUNCE }
            if (tmpRect.right > a.right) { m.postTranslate(a.right - tmpRect.right, 0f); f.vx = -abs(f.vx) * BOUNCE }
            if (tmpRect.top < a.top) { m.postTranslate(0f, a.top - tmpRect.top); f.vy = abs(f.vy) * BOUNCE }
            if (tmpRect.bottom > a.bottom) { m.postTranslate(0f, a.bottom - tmpRect.bottom); f.vy = -abs(f.vy) * BOUNCE }
            val k = exp(-dt / FLIGHT_DECAY_MS)
            f.vx *= k
            f.vy *= k
        }
    }

    /**
     * Objects in the air knock into each other and into what lies on the board, as billiard balls
     * would: along the side where their boxes meet, each by its weight (its box's area), losing a
     * little. A word on the board is one object (its strokes share a group), as are a picture, a
     * text and a shape. Boxes only: a few rectangle tests per stroke and frame, and a hit word
     * leaves the layer once, as a throw does.
     */
    private fun collide() {
        for (f in flights) f.matrix.mapRect(f.now, f.bounds)
        // Two in the air.
        for (i in flights.indices) for (j in i + 1 until flights.size) {
            val a = flights[i]
            val b = flights[j]
            if (RectF.intersects(a.now, b.now)) bounce(a, b)
        }
        // One in the air and one lying still.
        if (flights.size >= MAX_FLIGHTS) return
        for (f in flights.toList()) {
            val hit = strokes.firstOrNull { s ->
                s !in offLayer && s !in selection && s.group !in f.ignore && touches(f.now, s)
            } ?: continue
            val word = strokes.filter { it.group == hit.group && it !in offLayer && it !in selection }
            if (word.size > MAX_KNOCKED_STROKES) continue
            val box = RectF()
            for (s in word) box.union(s.bounds)
            visibleWorld()
            val area = RectF(visible).apply { union(box) } // it may lie partly off screen: don't snap it in
            val chain = f.chain
            launch(word, Matrix(), box, area, 0f, 0f, chain, box)
            invalidateLayer(box) // it leaves the layer: drawn through its flight from now on
            val g = flights.last()
            g.now.set(box)
            bounce(f, g)
            if (hypot(g.vx, g.vy) * zoom < dp(FLIGHT_STOP_DP_PER_MS)) {
                // Too heavy to budge: it stays, and the thrown one comes back off it.
                flights.remove(g)
                airborne.removeAll(word.toSet())
                chain.flying--
                invalidateLayer(box)
            }
            if (flights.size >= MAX_FLIGHTS) return
        }
    }

    /** Whether [s] really reaches into [r], not just its box (a word's box is mostly empty). */
    private fun touches(r: RectF, s: Stroke): Boolean {
        if (!RectF.intersects(r, s.bounds)) return false
        if (s.tool == InkTool.IMAGE || s.tool == InkTool.TEXT) return true
        val pad = s.reach
        val l = r.left - pad
        val t = r.top - pad
        val rr = r.right + pad
        val b = r.bottom + pad
        val pts = s.points
        if (pts.size == 1) return pts[0].x in l..rr && pts[0].y in t..b
        // Along the line between points, not just at them: a shape's corners or a fast stroke's
        // points can lie far apart, and an object crossing between them must still hit.
        for (i in 1 until pts.size) if (segmentHits(pts[i - 1], pts[i], l, t, rr, b)) return true
        return false
    }

    /** Whether the segment [p]–[q] reaches into the box (Liang–Barsky clipping). */
    private fun segmentHits(p: InkPoint, q: InkPoint, l: Float, t: Float, r: Float, b: Float): Boolean {
        if (kotlin.math.max(p.x, q.x) < l || kotlin.math.min(p.x, q.x) > r ||
            kotlin.math.max(p.y, q.y) < t || kotlin.math.min(p.y, q.y) > b) return false
        val dx = q.x - p.x
        val dy = q.y - p.y
        var lo = 0f
        var hi = 1f
        fun clip(den: Float, num: Float): Boolean {
            if (den == 0f) return num >= 0f
            val k = num / den
            if (den < 0f) { if (k > hi) return false; if (k > lo) lo = k } else { if (k < lo) return false; if (k < hi) hi = k }
            return true
        }
        return clip(-dx, p.x - l) && clip(dx, r - p.x) && clip(-dy, p.y - t) && clip(dy, b - p.y) && lo <= hi
    }

    /** A collision between [a] and [b], whose boxes overlap: pushes them apart and trades speed. */
    private fun bounce(a: Flight, b: Flight) {
        val ra = a.now
        val rb = b.now
        val ox = min(ra.right, rb.right) - kotlin.math.max(ra.left, rb.left)
        val oy = min(ra.bottom, rb.bottom) - kotlin.math.max(ra.top, rb.top)
        if (ox <= 0f || oy <= 0f) return
        // Where they touch: the middle of the overlap.
        val hitX = (kotlin.math.max(ra.left, rb.left) + min(ra.right, rb.right)) / 2f
        val hitY = (kotlin.math.max(ra.top, rb.top) + min(ra.bottom, rb.bottom)) / 2f
        val ax = hitX - ra.centerX(); val ay = hitY - ra.centerY()
        val bx = hitX - rb.centerX(); val by = hitY - rb.centerY()
        // The side where they meet is the one they overlap least across.
        val alongX = ox < oy
        val sign = if (alongX) (if (rb.centerX() >= ra.centerX()) 1f else -1f) else (if (rb.centerY() >= ra.centerY()) 1f else -1f)
        val va = (if (alongX) a.vx else a.vy) * sign
        val vb = (if (alongX) b.vx else b.vy) * sign
        val total = a.mass + b.mass
        // Apart at once, each by the other's share of the weight.
        val gap = (if (alongX) ox else oy) * sign
        val ka = b.mass / total
        val kb = a.mass / total
        if (alongX) {
            a.matrix.postTranslate(-gap * ka, 0f); b.matrix.postTranslate(gap * kb, 0f)
        } else {
            a.matrix.postTranslate(0f, -gap * ka); b.matrix.postTranslate(0f, gap * kb)
        }
        a.matrix.mapRect(a.now, a.bounds)
        b.matrix.mapRect(b.now, b.bounds)
        if (va - vb <= 0f) return // already moving apart
        val na = (va * (a.mass - RESTITUTION * b.mass) + (1 + RESTITUTION) * b.mass * vb) / total
        val nb = (vb * (b.mass - RESTITUTION * a.mass) + (1 + RESTITUTION) * a.mass * va) / total
        if (alongX) { a.vx = na * sign; b.vx = nb * sign } else { a.vy = na * sign; b.vy = nb * sign }
        // The push b got, along the hit: off its centre, it turns each of them a little (r × J / I).
        val j = (nb - vb) * b.mass
        val jx = if (alongX) j * sign else 0f
        val jy = if (alongX) 0f else j * sign
        val deg = (180f / PI.toFloat()) * SPIN
        b.spin = (b.spin + (bx * jy - by * jx) / b.inertia * deg).coerceIn(-MAX_SPIN, MAX_SPIN)
        a.spin = (a.spin - (ax * jy - ay * jx) / a.inertia * deg).coerceIn(-MAX_SPIN, MAX_SPIN)
        // What it was knocked by no longer counts as lying under it.
        b.ignore.removeAll(a.items.mapTo(HashSet()) { it.group })
    }

    /** Puts a thrown object down where it is now; a throw and all it knocked away undo as one. */
    private fun land(f: Flight) {
        airborne.removeAll(f.items.toSet())
        for (s in f.items) invalidateLayer(s.bounds)
        if (!f.matrix.isIdentity && f.items.isNotEmpty()) {
            val scale = f.matrix.mapRadius(1f)
            for (s in f.items) s.transform(f.matrix, scale)
            f.chain.changes += History.Transformed(f.items, Matrix(f.matrix), scale)
            for (s in f.items) invalidateLayer(s.bounds)
        }
        f.chain.flying--
        if (f.chain.flying == 0 && f.chain.changes.isNotEmpty()) {
            val c = f.chain.changes
            history.record(if (c.size == 1) c[0] else History.Several(c.toList()))
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
            EDIT_ROW -> {
                clearSelection()
                onTextEdit?.invoke(items[0])
            }
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

    // region Text

    /** How [color] shows on this board (see [shown]), for what stands in for ink while typing. */
    fun inkShown(color: Int) = shown(color)

    /** A new text, its top left corner at ([x], [y]) on screen and its letters [size] px tall there. */
    fun addText(text: String, color: Int, size: Float, x: Float, y: Float) {
        val at = toBoard(x, y)
        val s = textStroke(text, color, size / zoom, at[0], at[1], PointF(1f, 0f), PointF(0f, 1f))
        s.group = board.nextGroup++
        clearSelection()
        showInk()
        insertStrokes(listOf(s))
    }

    /** Takes [s] off the board while it is corrected; [endTextEdit] puts it back. */
    fun beginTextEdit(s: Stroke) {
        airborne.add(s)
        invalidateLayer(s.bounds)
        invalidate()
    }

    /** Where [s]'s top left corner is on screen, and its letter size there. */
    fun textOnScreen(s: Stroke): FloatArray {
        val p = floatArrayOf(s.points[0].x, s.points[0].y)
        camera.mapPoints(p)
        return floatArrayOf(p[0], p[1], s.size * zoom)
    }

    /**
     * The correction of [s] is over: with [text] empty it goes, with null (or nothing changed) it
     * stays as it was. The new one keeps its turn: it starts from ([x], [y]) along the same sides.
     */
    fun endTextEdit(s: Stroke, text: String?, color: Int, size: Float, x: Float, y: Float) {
        airborne.remove(s)
        invalidateLayer(s.bounds)
        val index = strokes.indexOf(s)
        val before = textOnScreen(s)
        val same = text == s.text && color == s.color && size == before[2] && x == before[0] && y == before[1]
        if (text == null || index < 0 || same) return
        if (text.isEmpty()) {
            removeStrokes { it === s }
            return
        }
        val p = s.points
        val w = hypot(p[1].x - p[0].x, p[1].y - p[0].y)
        val h = hypot(p[3].x - p[0].x, p[3].y - p[0].y)
        val u = PointF((p[1].x - p[0].x) / w, (p[1].y - p[0].y) / w)
        val v = PointF((p[3].x - p[0].x) / h, (p[3].y - p[0].y) / h)
        val at = toBoard(x, y)
        val next = textStroke(text, color, size / zoom, at[0], at[1], u, v)
        next.group = s.group
        strokes[index] = next
        history.record(History.Edited(listOf(History.Op(false, index, s), History.Op(true, index, next))))
        invalidateLayer(next.bounds)
    }

    private fun textStroke(text: String, color: Int, size: Float, x: Float, y: Float, u: PointF, v: PointF): Stroke {
        val body = TextBody(context, text, size)
        return Stroke.text(text, color, size, x, y, body.width, body.height, u, v).also { it.body = body }
    }

    private fun toBoard(x: Float, y: Float): FloatArray {
        val p = floatArrayOf(x, y)
        camera.invert(inverse)
        inverse.mapPoints(p)
        return p
    }

    /** The dashed line from the writing card to where the text goes, or none (null). */
    fun setTextLeader(line: FloatArray?) {
        if (line.contentEquals(textLeader)) return
        textLeader = line
        invalidate()
    }

    private var textLeader: FloatArray? = null
    private val leaderPath = Path()

    private fun drawTextLeader(canvas: Canvas) {
        val l = textLeader ?: return
        leaderPath.rewind()
        leaderPath.moveTo(l[0], l[1])
        leaderPath.lineTo(l[2], l[3])
        canvas.drawPath(leaderPath, lassoShadow)
        canvas.drawPath(leaderPath, leaderLine)
    }

    // endregion

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

    /**
     * Removes matching strokes as one undoable change; they fade out rather than vanish, or are
     * swept off ([sweep], clearing the board).
     */
    private fun removeStrokes(sweep: Boolean = false, predicate: (Stroke) -> Boolean) {
        val ops = ArrayList<History.Op>()
        val now = SystemClock.uptimeMillis()
        for (index in strokes.indices.reversed()) {
            val s = strokes[index]
            if (predicate(s)) {
                strokes.removeAt(index)
                ops.add(History.Op(insert = false, index = index, stroke = s))
                if (!sweep) fades.add(Fade(s, now))
                invalidateLayer(s.bounds)
            }
        }
        if (ops.isEmpty()) return
        if (sweep) this.sweep = Sweep(ops.map { it.stroke }.asReversed(), now)
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
        private const val THUMB_FILL = 0.86f
        private const val THUMB_LINE_PX = 1.4f
        const val NO_POINTER = -1

        // Infrared digitizer calibration (raw touch-major units, measured on the IFP7533):
        // pen tip ≈ 1.4, finger ≈ 3.2, fist ≈ 27–40.
        const val IR_DIGITIZER = "IRtouch"
        const val IR_PEN_MAX = 2.2f

        // The eraser's speed in dp per ms: a careful rub stays under SLOW, a sweep of the arm passes FAST.
        const val ERASER_SLOW = 0.35f
        const val ERASER_FAST = 1.3f
        const val ERASER_GROWTH = 3f
        const val ERASER_MAX_DP = 220f
        const val ERASER_SPEED_SMOOTHING = 0.3f
        const val ERASER_GROW_RATE = 0.35f
        const val ERASER_SHRINK_RATE = 0.06f
        const val IR_PALM_MIN = 15f
        const val FADE_MS = 220L

        // Page turn: how far the ink slides (a share of the width) and for how long.
        const val TURN_SHIFT = 0.06f
        const val TURN_MS = 380L
        val TURN_EASE = android.view.animation.PathInterpolator(0.22f, 0.9f, 0.24f, 1f)

        // Clear-all sweep: its length, the soft edge (share of the width) and its bands. A board
        // with very much ink just fades, as before (the sweep draws it several times a frame).
        const val SWEEP_MS = 650L
        const val SWEEP_SOFT = 0.12f
        const val SWEEP_BANDS = 3
        const val SWEEP_MAX_STROKES = 2000
        val SWEEP_EASE = android.view.animation.PathInterpolator(0.5f, 0f, 0.3f, 1f)
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

        /** Objects knocking into each other keep this much of their speed along the hit. */
        const val RESTITUTION = 0.6f
        /** Share of the true turn a knock gives (a whole one looked wild on handwriting). */
        const val SPIN = 0.25f
        /** At most this fast a knocked object turns, in degrees per ms (a full turn in 3 s). */
        const val MAX_SPIN = 0.12f
        /** At most this many objects in the air at once: past it, what lies on the board stays put. */
        const val MAX_FLIGHTS = 16
        const val FLIGHT_SUBSTEP_DP = 12f
        const val MAX_SUBSTEPS = 6
        /** A drawing bigger than this (in strokes) is too heavy to knock away. */
        const val MAX_KNOCKED_STROKES = 400
        const val EDIT_ROW = 0
        const val COPY_ROW = 1
        const val DUPLICATE_ROW = 2
        const val COLOUR_ROW = 3
        const val DELETE_ROW = 4
        const val HIGHLIGHTER_ALPHA = 96
        const val WARM_FRAMES = 2
        const val WARM_OPACITY = 0.011f // the least that still draws: alpha 1–2 of 255
        const val MARK_TEXT_DP = 12f
        const val MARK_HEIGHT_DP = 24f
        const val MARK_MARGIN_DP = 12f
        const val MARK_SLACK_DP = 8f
        const val MARK_FAINT = 0xFF8A9099.toInt()
        const val MARK_FAINT_DARK = 0x80FFFFFF.toInt()
        const val MARK_CURRENT = 0xFF2F6E6B.toInt()
        const val MARK_CURRENT_DARK = 0xFF9FD8D4.toInt()
        const val MARK_OFFLINE = 0xFFA32D2D.toInt()
        const val MARK_OFFLINE_DARK = 0xFFF5B3AC.toInt()
        const val DIRTY_MARGIN = 2f
        const val PREDICT_MAX_DP = 24f

        // Wet ink, in cells that are the layer's tiles.
        const val CELL = InkLayer.TILE
        /** Ink reaches a little past its outline (anti-aliasing): that much more of the screen is its. */
        const val CELL_EDGE = 2
        /** A piece of a fast stroke is about this long on screen: it fits in a cell or two. */
        const val PIECE_REACH = CELL * 0.75f
        /** Pieces overlap by this much of line, on screen. */
        const val PIECE_OVERLAP = 6f
        /** Wet ink dries after this long without writing. */
        const val DRY_MS = 900L
        const val GLOW_REACH_DP = 16f

        // Whiteboard.
        const val GRID_DP = 36f

        /** Double ruling: a pair of lines every 1.5 grid steps, half a step apart. */
        const val DOUBLE_PERIOD = 1.5f
        val SINGLE_LINE = floatArrayOf(0f)
        val DOUBLE_LINES = floatArrayOf(0f, 0.5f) // the second line half a square below
        // Montessori ruling: a thin pair between rows, then the band for ascenders (0.68), the letters'
        // body (0.5 squares, as in the double ruling) and the band for descenders (0.68): the
        // proportions of the school notebooks' "Montessori 3,5".
        const val MONTESSORI_PERIOD = 1.93f
        val MONTESSORI_LINES = floatArrayOf(0f, 0.07f, 0.75f, 1.25f)
        /** From this line on (the two middle ones) a Montessori row is drawn darker, to stand out. */
        const val MONTESSORI_STRONG = 2
        // A staff: five lines 0.45 squares apart (16 dp: room for handwritten notes), every 3.5 squares.
        const val STAFF_PERIOD = 3.5f
        val STAFF_LINES = floatArrayOf(0f, 0.45f, 0.9f, 1.35f, 1.8f)

        /** A circle's centre dot, as wide as this many times the circle's line. */
        const val CENTER_DOT = 2.4f

        /** Below this luminance ink counts as black (and shows white on a dark paper). */
        const val DARK_INK = 0.05

        /** Space above a cutout on the whiteboard, and between stacked cutouts. */
        const val PICTURE_MARGIN_DP = 24f
        const val MIN_GRID_DP = 12f
        /** Room round the ink on a shared page. */
        const val EXPORT_MARGIN_DP = 32f
        val ZERO = floatArrayOf(0f, 0f)
        const val MIN_ZOOM = 0.2f
        const val MAX_ZOOM = 5f
    }
}
