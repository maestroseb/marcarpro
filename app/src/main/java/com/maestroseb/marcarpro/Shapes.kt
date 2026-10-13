package com.maestroseb.marcarpro

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turns a freehand stroke into a clean shape: a closed stroke becomes a triangle, a rectangle
 * or an ellipse; a near-straight open stroke becomes a line. Anything else is left as ink.
 *
 * Closed strokes are judged by their convex hull, which shrugs off trembling, rounded corners
 * and an end that overshoots the start (counting corners did not):
 * - hull area / smallest enclosing rectangle ≈ 1 for a rectangle, π/4 for an ellipse, ½ for a triangle;
 * - largest triangle inside the hull / hull area ≈ 1 for a triangle, under ½ for the others.
 * The result is then tidied the way a teacher means it: nearly level sides become level, a
 * nearly square rectangle a square, a nearly round ellipse a circle.
 *
 * Cheap (once per stroke, a few hundred points) and pure Kotlin, so it is unit-tested.
 */
internal object ShapeRecognizer {

    enum class Kind { LINE, TRIANGLE, RECTANGLE, ELLIPSE }

    class Point(val x: Float, val y: Float)

    class Result(val kind: Kind, val points: List<Point>)

    fun recognize(input: List<Point>): Result? {
        if (input.size < 6) return null
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        for (p in input) {
            l = min(l, p.x); t = min(t, p.y); r = max(r, p.x); b = max(b, p.y)
        }
        val size = max(r - l, b - t)
        if (size <= 0f) return null

        if (!isClosed(input, size)) {
            if (maxDeviation(input) > size * LINE_TOLERANCE) return null
            val (a, z) = levelLine(input.first(), input.last())
            return Result(Kind.LINE, line(a, z, 24))
        }

        val hull = hull(input)
        if (hull.size < 3) return null
        val hullArea = area(hull)
        if (hullArea <= 0f) return null
        val box = minBox(hull)
        val rectFill = hullArea / (box.w * box.h)
        val tri = largestTriangle(hull)
        val triFill = area(tri) / hullArea

        return when {
            triFill > TRIANGLE_FILL && rectFill < ELLIPSE_FILL -> Result(Kind.TRIANGLE, polygon(tidyTriangle(tri)))
            rectFill > RECTANGLE_FILL -> Result(Kind.RECTANGLE, polygon(tidyRectangle(input, box)))
            rectFill > MIN_ELLIPSE_FILL -> Result(Kind.ELLIPSE, ellipse(input, box))
            else -> null
        }
    }

    /** Ends close together, or the end runs back over the start (a loop drawn a bit too far). */
    private fun isClosed(input: List<Point>, size: Float): Boolean {
        val first = input.first()
        if (hypot(first.x - input.last().x, first.y - input.last().y) < size * CLOSE_GAP) return true
        val from = (input.size * 0.75f).toInt()
        return (from until input.size).any { hypot(input[it].x - first.x, input[it].y - first.y) < size * OVERSHOOT_GAP }
    }

    // region Geometry

    private class Box(val cx: Float, val cy: Float, val angle: Double, val w: Float, val h: Float)

    /** Convex hull (monotone chain). */
    private fun hull(pts: List<Point>): List<Point> {
        val s = pts.sortedWith(compareBy({ it.x }, { it.y }))
        fun cross(o: Point, a: Point, b: Point) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
        val lower = ArrayList<Point>()
        for (p in s) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower.last(), p) <= 0) lower.removeAt(lower.lastIndex)
            lower.add(p)
        }
        val upper = ArrayList<Point>()
        for (p in s.asReversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper.last(), p) <= 0) upper.removeAt(upper.lastIndex)
            upper.add(p)
        }
        return lower.dropLast(1) + upper.dropLast(1)
    }

    private fun area(poly: List<Point>): Float {
        var a = 0f
        for (i in poly.indices) {
            val p = poly[i]
            val q = poly[(i + 1) % poly.size]
            a += p.x * q.y - q.x * p.y
        }
        return abs(a) / 2f
    }

    /** Smallest rectangle around the hull: one of its sides lies on a hull edge. */
    private fun minBox(hull: List<Point>): Box {
        var best: Box? = null
        for (i in hull.indices) {
            val p = hull[i]
            val q = hull[(i + 1) % hull.size]
            val box = boxAt(hull, atan2((q.y - p.y).toDouble(), (q.x - p.x).toDouble()))
            if (best == null || box.w * box.h < best.w * best.h) best = box
        }
        return best!!
    }

    /** The rectangle around [pts] whose sides run at [angle] (and at right angles to it). */
    private fun boxAt(pts: List<Point>, angle: Double): Box {
        val c = cos(angle).toFloat()
        val s = sin(angle).toFloat()
        var minU = Float.MAX_VALUE
        var maxU = -Float.MAX_VALUE
        var minV = Float.MAX_VALUE
        var maxV = -Float.MAX_VALUE
        for (p in pts) {
            val u = p.x * c + p.y * s
            val v = -p.x * s + p.y * c
            minU = min(minU, u); maxU = max(maxU, u); minV = min(minV, v); maxV = max(maxV, v)
        }
        val mu = (minU + maxU) / 2
        val mv = (minV + maxV) / 2
        return Box(mu * c - mv * s, mu * s + mv * c, angle, maxU - minU, maxV - minV)
    }

    private fun largestTriangle(hull: List<Point>): List<Point> {
        // The hull of a trembling stroke can have many vertices; a sample is plenty.
        val h = if (hull.size > MAX_HULL) List(MAX_HULL) { hull[it * hull.size / MAX_HULL] } else hull
        var best = listOf(h[0], h[1], h[2])
        var bestA = 0f
        for (i in h.indices) for (j in i + 1 until h.size) for (k in j + 1 until h.size) {
            val a = abs((h[j].x - h[i].x) * (h[k].y - h[i].y) - (h[k].x - h[i].x) * (h[j].y - h[i].y))
            if (a > bestA) { bestA = a; best = listOf(h[i], h[j], h[k]) }
        }
        return best
    }

    /** [angle] turned to the nearest multiple of 90° when it is within [SNAP_DEGREES] of it. */
    private fun snap(angle: Double): Double {
        val quarter = PI / 2
        val nearest = round(angle / quarter) * quarter
        return if (abs(angle - nearest) <= Math.toRadians(SNAP_DEGREES)) nearest else angle
    }

    // endregion

    // region Tidying

    /** A nearly level (or upright) segment made exactly so, about its midpoint. */
    private fun levelLine(a: Point, b: Point): Pair<Point, Point> {
        val angle = atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())
        val snapped = snap(angle)
        if (snapped == angle) return a to b
        val len = hypot(b.x - a.x, b.y - a.y)
        val mx = (a.x + b.x) / 2
        val my = (a.y + b.y) / 2
        val dx = (cos(snapped) * len / 2).toFloat()
        val dy = (sin(snapped) * len / 2).toFloat()
        return Point(mx - dx, my - dy) to Point(mx + dx, my + dy)
    }

    private fun tidyRectangle(input: List<Point>, box: Box): List<Point> {
        val angle = snap(box.angle)
        val b = if (angle == box.angle) box else boxAt(input, angle)
        var w = b.w
        var h = b.h
        // Nearly square → square.
        if (abs(w - h) / max(w, h) < SQUARE_TOLERANCE) {
            w = (w + h) / 2
            h = w
        }
        val c = cos(b.angle).toFloat()
        val s = sin(b.angle).toFloat()
        return listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f).map { (u, v) ->
            val x = u * w / 2
            val y = v * h / 2
            Point(b.cx + x * c - y * s, b.cy + x * s + y * c)
        }
    }

    private fun tidyTriangle(tri: List<Point>): List<Point> {
        val p = tri.toMutableList()
        // Level one side that is nearly horizontal or vertical (the base, usually).
        for (i in 0..2) {
            val a = p[i]
            val b = p[(i + 1) % 3]
            val (na, nb) = levelLine(a, b)
            if (na !== a) {
                p[i] = na
                p[(i + 1) % 3] = nb
                break
            }
        }
        return p
    }

    private fun ellipse(input: List<Point>, box: Box): List<Point> {
        val angle = snap(box.angle)
        val b = if (angle == box.angle) box else boxAt(input, angle)
        var a = b.w / 2
        var c = b.h / 2
        // The box hugs the outermost wobbles; size the ellipse by the stroke's average reach.
        val cs = cos(b.angle).toFloat()
        val sn = sin(b.angle).toFloat()
        var sum = 0f
        for (p in input) {
            val dx = p.x - b.cx
            val dy = p.y - b.cy
            val u = (dx * cs + dy * sn) / a
            val v = (-dx * sn + dy * cs) / c
            sum += sqrt(u * u + v * v)
        }
        val k = (sum / input.size).coerceIn(0.85f, 1f)
        a *= k
        c *= k
        // Nearly round → circle.
        if (min(a, c) / max(a, c) > 1 - CIRCLE_TOLERANCE) {
            a = (a + c) / 2
            c = a
        }
        return (0..STEPS_ELLIPSE).map { i ->
            val t = i / STEPS_ELLIPSE.toDouble() * 2 * PI
            val x = (cos(t) * a).toFloat()
            val y = (sin(t) * c).toFloat()
            Point(b.cx + x * cs - y * sn, b.cy + x * sn + y * cs)
        }
    }

    // endregion

    /** How far the farthest point strays from the straight line between the stroke's ends. */
    private fun maxDeviation(pts: List<Point>): Float {
        val a = pts.first()
        val b = pts.last()
        val len = hypot(b.x - a.x, b.y - a.y).takeIf { it > 0f } ?: 1f
        var maxD = 0f
        for (p in pts) {
            val d = abs((b.y - a.y) * p.x - (b.x - a.x) * p.y + b.x * a.y - b.y * a.x) / len
            if (d > maxD) maxD = d
        }
        return maxD
    }

    private fun line(a: Point, b: Point, steps: Int) =
        (0..steps).map { i -> Point(a.x + (b.x - a.x) * i / steps, a.y + (b.y - a.y) * i / steps) }

    /**
     * Closed outline through the vertices. It starts and ends halfway along the first side, so
     * the stroke's round caps meet on a straight stretch and every corner stays sharp.
     */
    private fun polygon(vertices: List<Point>): List<Point> {
        val n = vertices.size
        val mid = Point((vertices[0].x + vertices[1].x) / 2, (vertices[0].y + vertices[1].y) / 2)
        val out = ArrayList<Point>()
        out.addAll(line(mid, vertices[1], 8))
        for (i in 1..n) out.addAll(line(vertices[i % n], vertices[(i + 1) % n], 16).drop(1))
        // The last side ran to vertex 1; cut it back to the midpoint.
        repeat(8) { out.removeAt(out.lastIndex) }
        return out
    }

    private const val CLOSE_GAP = 0.3f
    private const val OVERSHOOT_GAP = 0.12f
    private const val LINE_TOLERANCE = 0.08f
    private const val RECTANGLE_FILL = 0.86f
    private const val ELLIPSE_FILL = 0.7f
    private const val MIN_ELLIPSE_FILL = 0.66f
    private const val TRIANGLE_FILL = 0.8f
    private const val MAX_HULL = 48
    private const val SNAP_DEGREES = 10.0
    private const val SQUARE_TOLERANCE = 0.12f
    private const val CIRCLE_TOLERANCE = 0.15f
    private const val STEPS_ELLIPSE = 96
}
