package com.maestroseb.marcarpro

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** What a stroke is drawn with; an [IMAGE] is a picture (a screen cutout) placed on the board. */
enum class InkTool { PEN, HIGHLIGHTER, IMAGE }

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

    /** Half of the widest visual width; used for hit testing. */
    val reach: Float
        get() = when (tool) {
            InkTool.HIGHLIGHTER -> size * HIGHLIGHTER_SCALE / 2f
            InkTool.IMAGE -> 0f
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
        if (tool == InkTool.IMAGE) return insideQuad(x, y)
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
            InkTool.IMAGE -> {
                // The picture's outline, through its corners.
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
        side(path, xs, ys, n, forward = true, exact = s.uniform)
    }

    private fun outline(s: Stroke, path: Path, sc: Scratch) {
        val n = s.points.size
        val buf = sc.buf
        val xs = buf[0]
        val ys = buf[1]
        smooth(s.points, xs, ys, exact = s.uniform)
        val radii = buf[2]
        radii(s, xs, ys, n, radii)
        if (n == 1 || isDot(xs, ys, n)) {
            var r = 0f
            for (i in 0 until n) r = max(r, radii[i])
            path.addCircle(xs[0], ys[0], r, Path.Direction.CW)
            return
        }

        val lx = buf[3]
        val ly = buf[4]
        val rx = buf[5]
        val ry = buf[6]
        var nx = 0f
        var ny = 0f
        var startNx = 0f
        var startNy = 0f
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
                startNx = nx
                startNy = ny
            }
            lx[i] = xs[i] + nx * radii[i]
            ly[i] = ys[i] + ny * radii[i]
            rx[i] = xs[i] - nx * radii[i]
            ry[i] = ys[i] - ny * radii[i]
        }

        // Left side forward, round end cap, right side back, round start cap.
        path.moveTo(lx[0], ly[0])
        side(path, lx, ly, n, forward = true, exact = s.uniform)
        path.arcTo(oval(sc, xs[n - 1], ys[n - 1], radii[n - 1]), degrees(ny, nx), -180f, false)
        side(path, rx, ry, n, forward = false, exact = s.uniform)
        path.arcTo(oval(sc, xs[0], ys[0], radii[0]), degrees(startNy, startNx) + 180f, -180f, false)
        path.close()
    }

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

    /** One side of the outline: smooth midpoint curves, or straight segments for an [exact] shape. */
    private fun side(path: Path, xs: FloatArray, ys: FloatArray, n: Int, forward: Boolean, exact: Boolean) {
        if (forward) {
            for (i in 1 until n - 1) {
                if (exact) path.lineTo(xs[i], ys[i]) else path.quadTo(xs[i], ys[i], (xs[i] + xs[i + 1]) / 2f, (ys[i] + ys[i + 1]) / 2f)
            }
            path.lineTo(xs[n - 1], ys[n - 1])
        } else {
            for (i in n - 2 downTo 1) {
                if (exact) path.lineTo(xs[i], ys[i]) else path.quadTo(xs[i], ys[i], (xs[i] + xs[i - 1]) / 2f, (ys[i] + ys[i - 1]) / 2f)
            }
            path.lineTo(xs[0], ys[0])
        }
    }

    private fun isDot(xs: FloatArray, ys: FloatArray, n: Int): Boolean {
        for (i in 1 until n) if (hypot(xs[i] - xs[0], ys[i] - ys[0]) > 1f) return false
        return true
    }

    private fun oval(sc: Scratch, cx: Float, cy: Float, r: Float) = sc.capOval.apply { set(cx - r, cy - r, cx + r, cy + r) }

    private fun degrees(y: Float, x: Float) = Math.toDegrees(atan2(y, x).toDouble()).toFloat()
}
