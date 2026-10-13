package com.maestroseb.marcarpro

import com.maestroseb.marcarpro.ShapeRecognizer.Kind
import com.maestroseb.marcarpro.ShapeRecognizer.Point
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/** Freehand shapes with a trembling hand, the way a pupil draws them on the board. */
class ShapeRecognizerTest {

    private val rnd = Random(7)
    private fun jitter(amount: Float = 8f) = (rnd.nextFloat() - 0.5f) * amount

    private fun side(ax: Float, ay: Float, bx: Float, by: Float, n: Int, j: Float = 8f) =
        (0 until n).map { i -> Point(ax + (bx - ax) * i / n + jitter(j), ay + (by - ay) * i / n + jitter(j)) }

    private fun kindOf(points: List<Point>) = ShapeRecognizer.recognize(points)?.kind

    /** A circle that wobbles in radius and runs a bit past where it started. */
    private fun handCircle(cx: Float, cy: Float, r: Float, sweep: Double = 1.08) = (0..80).map { i ->
        val a = i / 80.0 * 2 * PI * sweep
        val rr = r * (1 + 0.06f * sin(a * 3).toFloat()) + jitter(6f)
        Point(cx + (cos(a) * rr).toFloat(), cy + (sin(a) * rr).toFloat())
    }

    @Test
    fun `wobbly shapes are recognised in many attempts`() {
        repeat(200) {
            val start = rnd.nextFloat() * 6.28f
            val circle = (0..60).map { i ->
                val a = start + i / 60.0 * 2 * PI * 1.03
                Point(300 + (cos(a) * 100).toFloat() + jitter(), 200 + (sin(a) * 80).toFloat() + jitter())
            }
            val rect = side(0f, 0f, 200f, 0f, 20) + side(200f, 0f, 200f, 120f, 14) +
                side(200f, 120f, 0f, 120f, 20) + side(0f, 120f, 0f, 6f, 14)
            val triangle = side(0f, 150f, 90f, 0f, 18) + side(90f, 0f, 180f, 150f, 18) + side(180f, 150f, 6f, 146f, 18)
            val line = side(0f, 0f, 300f, 40f, 30)

            assertEquals(Kind.ELLIPSE, kindOf(circle))
            assertEquals(Kind.RECTANGLE, kindOf(rect))
            assertEquals(Kind.TRIANGLE, kindOf(triangle))
            assertEquals(Kind.LINE, kindOf(line))
        }
    }

    @Test
    fun `a hand circle becomes a true circle`() {
        repeat(50) {
            val result = ShapeRecognizer.recognize(handCircle(400f, 300f, 150f))!!
            assertEquals(Kind.ELLIPSE, result.kind)
            val ring = result.points.dropLast(1) // the last point closes the loop onto the first
            val cx = ring.map { it.x }.average().toFloat()
            val cy = ring.map { it.y }.average().toFloat()
            val radii = result.points.map { hypot(it.x - cx, it.y - cy) }
            assertTrue("round", radii.max() - radii.min() < 2f)
            assertTrue("about the drawn size", radii.average() in 130.0..160.0)
        }
    }

    @Test
    fun `a sloppy square with rounded corners becomes a level square`() {
        repeat(50) {
            // Slightly tilted, 210×196, corners cut, the end running past the start.
            val tilt = Math.toRadians(5.0)
            fun p(x: Float, y: Float) = Point(
                (x * cos(tilt) - y * sin(tilt)).toFloat() + 500, (x * sin(tilt) + y * cos(tilt)).toFloat() + 300,
            )
            val raw = side(15f, 0f, 195f, 0f, 20) + side(210f, 15f, 210f, 181f, 18) +
                side(195f, 196f, 15f, 196f, 20) + side(0f, 181f, 0f, 15f, 18) + side(15f, 0f, 60f, 0f, 5)
            val result = ShapeRecognizer.recognize(raw.map { p(it.x, it.y) })!!
            assertEquals(Kind.RECTANGLE, result.kind)
            val xs = result.points.map { it.x }
            val ys = result.points.map { it.y }
            val w = xs.max() - xs.min()
            val h = ys.max() - ys.min()
            assertTrue("square", abs(w - h) < 1f)
            // Level: every point sits on one of the four sides of the bounding box.
            assertTrue("level", result.points.all { q ->
                minOf(abs(q.x - xs.min()), abs(q.x - xs.max()), abs(q.y - ys.min()), abs(q.y - ys.max())) < 0.5f
            })
        }
    }

    @Test
    fun `a clearly tilted rectangle keeps its tilt`() {
        val tilt = Math.toRadians(30.0)
        fun p(x: Float, y: Float) = Point((x * cos(tilt) - y * sin(tilt)).toFloat(), (x * sin(tilt) + y * cos(tilt)).toFloat())
        val raw = side(0f, 0f, 300f, 0f, 24) + side(300f, 0f, 300f, 120f, 12) +
            side(300f, 120f, 0f, 120f, 24) + side(0f, 120f, 0f, 4f, 12)
        val result = ShapeRecognizer.recognize(raw.map { p(it.x, it.y) })!!
        assertEquals(Kind.RECTANGLE, result.kind)
        val ys = result.points.map { it.y }
        assertTrue("not flattened", ys.max() - ys.min() > 200f)
    }

    @Test
    fun `a nearly level line becomes level`() {
        val result = ShapeRecognizer.recognize(side(0f, 0f, 400f, 30f, 30, j = 4f))!!
        assertEquals(Kind.LINE, result.kind)
        assertTrue(abs(result.points.first().y - result.points.last().y) < 0.5f)
    }

    @Test
    fun `an open scribble stays as ink`() {
        val wave = (0 until 40).map { i -> Point(i * 8f, (sin(i / 3.0) * 40).toFloat()) }
        assertNull(kindOf(wave))
    }
}
