package com.maestroseb.marcarpro

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * What a stroke is drawn with; an [IMAGE] is a picture (a screen cutout) placed on the board and a
 * [TEXT] a typed text. New kinds go at the end: the saved files keep the position.
 */
enum class InkTool { PEN, HIGHLIGHTER, IMAGE, TEXT }

/** One sampled input point, in view pixels. */
class InkPoint(var x: Float, var y: Float, val pressure: Float)

/**
 * A single ink stroke. Its geometry ([path], [bounds]) is derived from [points] by
 * [StrokeGeometry] and cached, so rendering never has to recompute it.
 *
 * @param speedUnit distance (px) between samples that counts as a "fast" stroke; pass a
 * dp-based value so the simulated pressure feels the same on every screen density.
 */
class Stroke(val tool: InkTool, color: Int, size: Float, val speedUnit: Float) {
    /** Can change later from the selection menu. */
    var color = color
        internal set

    /** Nominal width; scaling a selection scales it too. */
    var size = size
        private set

    val points = ArrayList<InkPoint>()
    val path = Path()
    val bounds = RectF()

    /** Strokes written close together in quick succession share a group (e.g. a word). */
    var group = 0

    /** Constant width (shapes): no speed-based thinning or taper. */
    var uniform = false

    /** Uptime (ms) when the pen touched down and lifted; used to group strokes into words. */
    var startTime = 0L
    var endTime = 0L

    var finished = false
        private set

    /** True once the digitizer reports pressure that genuinely varies within this stroke. */
    var hasRealPressure = false
        internal set

    /**
     * An [InkTool.IMAGE]'s picture, drawn into the four [points] (its corners, clockwise from the
     * top left), so moving, scaling and rotating it is the same as for ink. [imageData] is the
     * picture as saved (PNG), made once.
     */
    var image: Bitmap? = null
        private set
    var imageData: ByteArray? = null

    /**
     * An [InkTool.TEXT]'s words, drawn into the four [points] like a picture, so it moves, scales
     * and turns as ink does. [size] is its letter size. [body] is its layout, made when first drawn.
     */
    var text: String? = null
        private set
    internal var body: TextBody? = null

    /** Whether this is a block with four corners, not a line of ink. */
    private val isBlock get() = tool == InkTool.IMAGE || tool == InkTool.TEXT

    /** Half of the widest visual width; used for hit testing. */
    val reach: Float
        get() = when (tool) {
            InkTool.HIGHLIGHTER -> size * HIGHLIGHTER_SCALE / 2f
            InkTool.IMAGE, InkTool.TEXT -> 0f
            InkTool.PEN -> size / 2f
        }

    private var minPressure = Float.MAX_VALUE
    private var maxPressure = -Float.MAX_VALUE

    /** Adds a sample; returns false when it is too close to the previous one to matter. */
    fun add(x: Float, y: Float, pressure: Float): Boolean {
        // Many boards report a constant pressure (e.g. 0.5); only a varying one is "real".
        if (pressure > 0f) {
            minPressure = minOf(minPressure, pressure)
            maxPressure = maxOf(maxPressure, pressure)
            if (maxPressure - minPressure > 0.05f) hasRealPressure = true
        }
        val last = points.lastOrNull()
        if (last != null && hypot(x - last.x, y - last.y) < MIN_SPACING) return false
        points.add(InkPoint(x, y, pressure))
        return true
    }

    fun rebuild() = StrokeGeometry.build(this)

    fun finish() {
        finished = true
        rebuild()
    }

    /**
     * Partial erase: returns the pieces of this stroke that survive a circular eraser at
     * ([cx], [cy]) of [radius]. The centreline is densified first so a cut always lands
     * where the eraser actually passed, even across widely spaced samples.
     */
    fun cut(cx: Float, cy: Float, radius: Float): List<Stroke> {
        val cutRadius = radius + reach * 0.5f
        val step = max(MIN_SPACING, radius / 4f)
        val pieces = ArrayList<Stroke>()
        var run = ArrayList<InkPoint>()
        fun flush() {
            if (run.size >= 2) pieces.add(piece(run))
            run = ArrayList()
        }
        fun visit(p: InkPoint) = if (hypot(p.x - cx, p.y - cy) < cutRadius) flush() else run.add(p)

        visit(InkPoint(points[0].x, points[0].y, points[0].pressure))
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val n = max(1, (hypot(b.x - a.x, b.y - a.y) / step).toInt())
            for (k in 1..n) {
                val t = k / n.toFloat()
                visit(InkPoint(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.pressure + (b.pressure - a.pressure) * t))
            }
        }
        flush()
        return pieces
    }

    private fun piece(run: List<InkPoint>) = Stroke(tool, color, size, speedUnit).also {
        it.points.addAll(run)
        it.image = image
        it.imageData = imageData
        it.text = text
        it.group = group
        it.startTime = startTime
        it.endTime = endTime
        it.hasRealPressure = hasRealPressure
        it.uniform = uniform
        it.finish()
    }

    /** Replaces the sampled points (e.g. with a recognised shape). */
    fun replacePoints(xs: List<Float>, ys: List<Float>) {
        points.clear()
        for (i in xs.indices) points.add(InkPoint(xs[i], ys[i], 1f))
    }

    /** An independent copy (same look, same group), e.g. for duplicate and paste. */
    fun copy(): Stroke = piece(points.map { InkPoint(it.x, it.y, it.pressure) })

    /**
     * Moves, scales and/or rotates the stroke; [scale] is the matrix's uniform scale factor,
     * applied to the ink width too. Plain moves skip rebuilding the geometry.
     */
    fun transform(m: Matrix, scale: Float) {
        m.getValues(values)
        if (values[Matrix.MSCALE_X] == 1f && values[Matrix.MSCALE_Y] == 1f &&
            values[Matrix.MSKEW_X] == 0f && values[Matrix.MSKEW_Y] == 0f
        ) {
            translate(values[Matrix.MTRANS_X], values[Matrix.MTRANS_Y])
            return
        }
        val xy = FloatArray(points.size * 2)
        points.forEachIndexed { i, p ->
            xy[2 * i] = p.x
            xy[2 * i + 1] = p.y
        }
        m.mapPoints(xy)
        points.forEachIndexed { i, p ->
            p.x = xy[2 * i]
            p.y = xy[2 * i + 1]
        }
        size *= scale
        rebuild()
    }

    fun translate(dx: Float, dy: Float) {
        for (p in points) {
            p.x += dx
            p.y += dy
        }
        path.offset(dx, dy)
        bounds.offset(dx, dy)
    }

    /** True when a circle of [radius] around ([x], [y]) touches the visible ink. */
    fun hits(x: Float, y: Float, radius: Float): Boolean {
        if (x < bounds.left - radius || x > bounds.right + radius ||
            y < bounds.top - radius || y > bounds.bottom + radius
        ) return false
        if (isBlock) return insideQuad(x, y)
        val r = radius + reach
        if (points.size == 1) return hypot(x - points[0].x, y - points[0].y) <= r
        for (i in 1 until points.size) {
            if (distanceToSegment(x, y, points[i - 1], points[i]) <= r) return true
        }
        return false
    }

    /** Whether ([x], [y]) is on the picture (its corners may be rotated). */
    private fun insideQuad(x: Float, y: Float): Boolean {
        if (points.size != 4) return false
        var sign = 0
        for (i in 0 until 4) {
            val a = points[i]
            val b = points[(i + 1) % 4]
            val cross = (b.x - a.x) * (y - a.y) - (b.y - a.y) * (x - a.x)
            val s = if (cross > 0) 1 else if (cross < 0) -1 else 0
            if (s != 0) {
                if (sign != 0 && s != sign) return false
                sign = s
            }
        }
        return true
    }

    companion object {
        private val values = FloatArray(9)

        /** A picture filling the rectangle ([left], [top]) – ([right], [bottom]). */
        fun picture(image: Bitmap, data: ByteArray?, left: Float, top: Float, right: Float, bottom: Float): Stroke =
            Stroke(InkTool.IMAGE, 0, 0f, 1f).apply {
                this.image = image
                imageData = data
                points.add(InkPoint(left, top, 1f))
                points.add(InkPoint(right, top, 1f))
                points.add(InkPoint(right, bottom, 1f))
                points.add(InkPoint(left, bottom, 1f))
                finish()
            }

        /** A text with its corners at ([x], [y]), ([x] + [u] · [w], …): [u] and [v] are the unit sides. */
        fun text(text: String, color: Int, size: Float, x: Float, y: Float, w: Float, h: Float, u: PointF, v: PointF): Stroke =
            Stroke(InkTool.TEXT, color, size, 1f).apply {
                this.text = text
                points.add(InkPoint(x, y, 1f))
                points.add(InkPoint(x + u.x * w, y + u.y * w, 1f))
                points.add(InkPoint(x + u.x * w + v.x * h, y + u.y * w + v.y * h, 1f))
                points.add(InkPoint(x + v.x * h, y + v.y * h, 1f))
                finish()
            }

        /** Rebuilds a saved text (its corners are added by the reader). */
        internal fun savedText(text: String, color: Int, size: Float) = Stroke(InkTool.TEXT, color, size, 1f).apply {
            this.text = text
        }

        /** Rebuilds a saved picture (its corners are added by the reader). */
        internal fun savedPicture(image: Bitmap, data: ByteArray) = Stroke(InkTool.IMAGE, 0, 0f, 1f).apply {
            this.image = image
            imageData = data
        }

        const val HIGHLIGHTER_SCALE = 3.2f
        private const val MIN_SPACING = 1.5f
    }
}

private fun distanceToSegment(px: Float, py: Float, a: InkPoint, b: InkPoint): Float {
    val vx = b.x - a.x
    val vy = b.y - a.y
    val len2 = vx * vx + vy * vy
    if (len2 == 0f) return hypot(px - a.x, py - a.y)
    val t = (((px - a.x) * vx + (py - a.y) * vy) / len2).coerceIn(0f, 1f)
    return hypot(px - (a.x + t * vx), py - (a.y + t * vy))
}

/**
 * Turns sampled points into renderable geometry.
 *
 * Pen ink is a filled outline whose width follows pressure — real pressure when the
 * digitizer provides it, otherwise simulated from speed (fast strokes thin out, like a
 * real nib) — with round caps and a gentle taper. Highlighter ink is a constant-width
 * centreline. Both are smoothed with midpoint quadratic curves, and the last point is
 * always the raw pen position so the ink never trails behind the tip.
 */
internal object StrokeGeometry {
    private const val STREAMLINE = 0.35f
    private const val TAPER_POINTS = 5

    /**
     * Buffers grown as needed and reused: geometry is rebuilt every frame while writing. One set
     * per thread (saved ink is decoded on the IO thread). 0–1 smoothed centreline, 2 radii, 3–6
     * the outline's two sides.
     */
    private class Scratch {
        var cap = 0
        var buf = Array(7) { FloatArray(0) }
        val capOval = RectF()

        // The outline last worked out by [sides]: how many points, and the way it faces at each end.
        var n = 0
        var startNx = 0f
        var startNy = 0f
        var endNx = 0f
        var endNy = 0f

        fun ensure(n: Int) {
            if (n <= cap) return
            cap = max(n, cap * 2).coerceAtLeast(64)
            buf = Array(7) { FloatArray(cap) }
        }
    }

    private val scratch = ThreadLocal.withInitial { Scratch() }

    fun build(s: Stroke) {
        val path = s.path
        path.rewind()
        if (s.points.isEmpty()) {
            s.bounds.setEmpty()
            return
        }
        val sc = scratch.get()!!
        sc.ensure(s.points.size)
        when (s.tool) {
            InkTool.PEN -> outline(s, path, sc)
            InkTool.HIGHLIGHTER -> centerline(s, path, sc)
            InkTool.IMAGE, InkTool.TEXT -> {
                // The block's outline, through its corners.
                path.moveTo(s.points[0].x, s.points[0].y)
                for (i in 1 until s.points.size) path.lineTo(s.points[i].x, s.points[i].y)
                path.close()
            }
        }
        path.computeBounds(s.bounds, true)
        if (s.tool == InkTool.HIGHLIGHTER) s.bounds.inset(-s.reach, -s.reach)
    }

    /** Steadies a hand-drawn line; a recognised shape ([exact]) is drawn as computed, corners and all. */
    private fun smooth(pts: List<InkPoint>, xs: FloatArray, ys: FloatArray, exact: Boolean) {
        val n = pts.size
        val keep = if (exact) 0f else STREAMLINE
        xs[0] = pts[0].x
        ys[0] = pts[0].y
        for (i in 1 until n) {
            xs[i] = xs[i - 1] + (pts[i].x - xs[i - 1]) * (1f - keep)
            ys[i] = ys[i - 1] + (pts[i].y - ys[i - 1]) * (1f - keep)
        }
        xs[n - 1] = pts[n - 1].x
        ys[n - 1] = pts[n - 1].y
    }

    private fun centerline(s: Stroke, path: Path, sc: Scratch) {
        val n = s.points.size
        val buf = sc.buf
        val xs = buf[0]
        val ys = buf[1]
        smooth(s.points, xs, ys, exact = s.uniform)
        path.moveTo(xs[0], ys[0])
        if (n == 1) {
            path.lineTo(xs[0] + 0.01f, ys[0])
            return
        }
        for (i in 1 until n - 1) {
            if (s.uniform) path.lineTo(xs[i], ys[i]) else path.quadTo(xs[i], ys[i], (xs[i] + xs[i + 1]) / 2f, (ys[i] + ys[i + 1]) / 2f)
        }
        path.lineTo(xs[n - 1], ys[n - 1])
    }

    private fun outline(s: Stroke, path: Path, sc: Scratch) {
        if (sides(s, sc)) {
            emit(path, sc, 0, END, exact = s.uniform)
            return
        }
        // A dot: a circle as wide as the widest point.
        val n = sc.n
        val radii = sc.buf[2]
        var r = 0f
        for (i in 0 until n) r = max(r, radii[i])
        path.addCircle(sc.buf[0][0], sc.buf[1][0], r, Path.Direction.CW)
    }

    /**
     * Works out [s]'s steadied centreline, its width at each point and the two sides of its
     * outline, into [sc]. False when it is no more than a dot (it has no sides).
     */
    private fun sides(s: Stroke, sc: Scratch): Boolean {
        val n = s.points.size
        val buf = sc.buf
        val xs = buf[0]
        val ys = buf[1]
        smooth(s.points, xs, ys, exact = s.uniform)
        val radii = buf[2]
        radii(s, xs, ys, n, radii)
        sc.n = n
        if (n == 1 || isDot(xs, ys, n)) return false

        val lx = buf[3]
        val ly = buf[4]
        val rx = buf[5]
        val ry = buf[6]
        var nx = 0f
        var ny = 0f
        for (i in 0 until n) {
            val a = max(i - 1, 0)
            val b = min(i + 1, n - 1)
            val dx = xs[b] - xs[a]
            val dy = ys[b] - ys[a]
            val len = hypot(dx, dy)
            if (len > 1e-3f) {
                nx = -dy / len
                ny = dx / len
            }
            if (i == 0) {
                sc.startNx = nx
                sc.startNy = ny
            }
            lx[i] = xs[i] + nx * radii[i]
            ly[i] = ys[i] + ny * radii[i]
            rx[i] = xs[i] - nx * radii[i]
            ry[i] = ys[i] - ny * radii[i]
        }
        sc.endNx = nx
        sc.endNy = ny
        return true
    }

    /**
     * The outline between two cuts across the stroke: left side forward, across (or round the end
     * cap), right side back, across (or round the start cap). A cut goes after point [from] or
     * [to], half-way to the next one, which is where the curves of both sides join: so a stretch
     * follows exactly the same curves as the whole outline. 0 is the very start and [END] the end.
     * With [skip] (a share, under 1) it starts that far along its first curve instead of at the cut.
     */
    private fun emit(path: Path, sc: Scratch, from: Int, to: Int, exact: Boolean, skip: Float = 0f) {
        val n = sc.n
        val buf = sc.buf
        val xs = buf[0]
        val ys = buf[1]
        val radii = buf[2]
        val lx = buf[3]
        val ly = buf[4]
        val rx = buf[5]
        val ry = buf[6]
        val last = if (to == END) n - 2 else to
        val first = from + 1
        val partly = from > 0 && skip > 0f
        val u = 1f - skip
        when {
            from == 0 -> path.moveTo(lx[0], ly[0])
            partly -> {
                // From the cut after [from] the curve goes round l[first] to the next cut: the last of it.
                val ax = (lx[from] + lx[first]) / 2f
                val ay = (ly[from] + ly[first]) / 2f
                val bx = (lx[first] + lx[first + 1]) / 2f
                val by = (ly[first] + ly[first + 1]) / 2f
                path.moveTo(u * u * ax + 2f * u * skip * lx[first] + skip * skip * bx, u * u * ay + 2f * u * skip * ly[first] + skip * skip * by)
                path.quadTo(u * lx[first] + skip * bx, u * ly[first] + skip * by, bx, by)
            }
            else -> path.moveTo((lx[from] + lx[first]) / 2f, (ly[from] + ly[first]) / 2f)
        }
        val whole = if (partly) first + 1 else first
        for (i in whole..last) {
            if (exact) path.lineTo(lx[i], ly[i]) else path.quadTo(lx[i], ly[i], (lx[i] + lx[i + 1]) / 2f, (ly[i] + ly[i + 1]) / 2f)
        }
        if (to == END) {
            path.lineTo(lx[n - 1], ly[n - 1])
            path.arcTo(oval(sc, xs[n - 1], ys[n - 1], radii[n - 1]), degrees(sc.endNy, sc.endNx), -180f, false)
        } else {
            path.lineTo((rx[to] + rx[to + 1]) / 2f, (ry[to] + ry[to + 1]) / 2f)
        }
        for (i in last downTo whole) {
            if (exact) path.lineTo(rx[i], ry[i]) else path.quadTo(rx[i], ry[i], (rx[i] + rx[i - 1]) / 2f, (ry[i] + ry[i - 1]) / 2f)
        }
        if (partly) {
            // Back along the right side's first curve, as far as where the left side started.
            val ax = (rx[from] + rx[first]) / 2f
            val ay = (ry[from] + ry[first]) / 2f
            val bx = (rx[first] + rx[first + 1]) / 2f
            val by = (ry[first] + ry[first + 1]) / 2f
            path.quadTo(
                bx + (rx[first] - bx) * u, by + (ry[first] - by) * u,
                u * u * ax + 2f * u * skip * rx[first] + skip * skip * bx, u * u * ay + 2f * u * skip * ry[first] + skip * skip * by,
            )
        }
        if (from == 0) {
            path.lineTo(rx[0], ry[0])
            path.arcTo(oval(sc, xs[0], ys[0], radii[0]), degrees(sc.startNy, sc.startNx) + 180f, -180f, false)
        }
        path.close()
    }

    /**
     * Works out pen stroke [s]'s outline without building its path, so that [cut] can give it in
     * stretches: a stroke being written is drawn in pieces, each rasterised once, and only its end
     * is drawn again every frame. Returns its number of points, or 0 when it is a dot (it can't be cut).
     */
    fun measure(s: Stroke): Int {
        if (s.points.isEmpty()) return 0
        val sc = scratch.get()!!
        sc.ensure(s.points.size)
        return if (sides(s, sc)) sc.n else 0
    }

    /**
     * After [measure] found [n] points in a stroke being written whose outline is already [laid]
     * down as far as the cut after that point (0: none of it): where the next piece of it ends, or
     * 0 while there isn't a whole piece that new samples can no longer change. The last points
     * still move: the line is steadied as it goes, and tapers off when the pen lifts.
     *
     * A piece is a number of points or, when the pen flies and they are far apart, [reach] of line:
     * what is left to draw again each frame stays short however fast the stroke. And it ends where
     * the line runs on calmly: in a tight turn the sides of the outline fold over themselves, and
     * two pieces meeting there would leave a sliver between them.
     */
    fun pieceEnd(n: Int, laid: Int, reach: Float): Int {
        val settled = n - 1 - KEEP_POINTS
        val longest = laid + MAX_PIECE_POINTS
        val sc = scratch.get()!!
        val xs = sc.buf[0]
        val ys = sc.buf[1]
        var length = 0f
        for (to in laid + 1..min(settled, longest)) {
            length += hypot(xs[to] - xs[to - 1], ys[to] - ys[to - 1])
            val enough = to - laid >= PIECE_POINTS || (to - laid >= MIN_PIECE_POINTS && length >= reach)
            if (enough && calm(sc, to)) return to
        }
        // Nowhere yet: wait for more of the line, but not for ever (a scribble of tight curls never calms).
        return if (settled >= longest) longest else 0
    }

    /** Whether both sides of the outline move forward with the line around the cut after point [k]. */
    private fun calm(sc: Scratch, k: Int): Boolean {
        val buf = sc.buf
        val xs = buf[0]
        val ys = buf[1]
        for (i in k - 2..k + 1) {
            val dx = xs[i + 1] - xs[i]
            val dy = ys[i + 1] - ys[i]
            val least = CALM_ADVANCE * (dx * dx + dy * dy)
            if ((buf[3][i + 1] - buf[3][i]) * dx + (buf[4][i + 1] - buf[4][i]) * dy <= least) return false
            if ((buf[5][i + 1] - buf[5][i]) * dx + (buf[6][i + 1] - buf[6][i]) * dy <= least) return false
        }
        return true
    }

    /**
     * After [measure]: the stretch of that stroke's outline from where it is [laid] to the cut
     * after point [to] ([END]: to its end), into [path]. It starts [overlap] before where it is
     * laid: pieces overlap a little, so no seam shows between them (and only a little: where two
     * overlap, their soft edges add up).
     */
    fun cut(path: Path, laid: Int, to: Int = END, overlap: Float) {
        path.rewind()
        val sc = scratch.get()!!
        if (laid == 0) {
            emit(path, sc, 0, to, exact = false)
            return
        }
        // The curve that ends at the cut starts at the cut before it: that far apart, along the line.
        val xs = sc.buf[0]
        val ys = sc.buf[1]
        val length = hypot(xs[laid + 1] - xs[laid - 1], ys[laid + 1] - ys[laid - 1]) / 2f
        emit(path, sc, laid - 1, to, exact = false, skip = if (length > overlap) 1f - overlap / length else 0f)
    }

    /** For [cut]: as far as the stroke goes. */
    const val END = -1
    private const val PIECE_POINTS = 24
    private const val MIN_PIECE_POINTS = 4
    private const val MAX_PIECE_POINTS = 96
    private const val KEEP_POINTS = 8

    /** Each side must move on at least this share of what the line does, for a cut to go there. */
    private const val CALM_ADVANCE = 0.25f

    private fun radii(s: Stroke, xs: FloatArray, ys: FloatArray, n: Int, out: FloatArray) {
        val base = s.size / 2f
        if (s.uniform) {
            out.fill(base, 0, n)
            return
        }
        if (s.hasRealPressure) {
            var p = s.points[0].pressure
            for (i in 0 until n) {
                p += (s.points[i].pressure - p) * 0.5f
                out[i] = base * (0.3f + 0.85f * p.coerceIn(0f, 1f))
            }
        } else {
            // Simulated pressure: slow strokes swell slightly, fast ones thin out. The mean
            // width stays at the size the teacher picked.
            var p = 0.7f
            for (i in 0 until n) {
                val d = if (i == 0) 0f else hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
                val speed = (d / s.speedUnit).coerceIn(0f, 1f)
                p += (1f - 0.6f * speed - p) * 0.3f
                out[i] = base * (0.65f + 0.45f * p)
            }
        }
        val t = min(TAPER_POINTS, (n - 1) / 2)
        for (i in 0 until t) {
            val k = 0.55f + 0.45f * (i + 1) / (t + 1)
            out[i] *= k
            if (s.finished) out[n - 1 - i] *= k
        }
    }

    private fun isDot(xs: FloatArray, ys: FloatArray, n: Int): Boolean {
        for (i in 1 until n) if (hypot(xs[i] - xs[0], ys[i] - ys[0]) > 1f) return false
        return true
    }

    private fun oval(sc: Scratch, cx: Float, cy: Float, r: Float) = sc.capOval.apply { set(cx - r, cy - r, cx + r, cy + r) }

    private fun degrees(y: Float, x: Float) = Math.toDegrees(atan2(y, x).toDouble()).toFloat()
}
