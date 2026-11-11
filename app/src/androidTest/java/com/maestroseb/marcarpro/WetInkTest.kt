package com.maestroseb.marcarpro

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * A stroke being written is laid down in pieces, each rasterised once ([StrokeGeometry.cut]): the
 * pieces together must look like the stroke drawn whole, with no seam or gap where they meet.
 */
class WetInkTest {

    @Test
    fun handwritingLaidInPiecesLooksLikeTheWholeStroke() {
        // Loops that cross, a fast run back and a sharp turn.
        val points = buildList {
            for (k in 0 until 260) {
                val t = k / 40f
                add(PointF(120f + k * 2.2f + 60f * sin(t * 2.1f), 300f + 90f * sin(t * 3.3f) + 40f * cos(t * 1.3f)))
            }
            for (k in 1..40) add(PointF(690f - k * 9f, 420f + k * 3f))
            for (k in 1..60) add(PointF(330f + k * 1.5f, 540f - abs(30 - k) * 5f))
        }
        for (size in listOf(6f, 14f, 30f)) check("handwriting", points, size)
    }

    @Test
    fun aZigzagWithAThickPenLeavesNoGapsBetweenPieces() {
        // Sharp turns back every few points: the sides of the outline fold over at each.
        val points = List(400) { k -> PointF(100f + k * 1.6f, 320f + (if (k / 14 % 2 == 0) k % 14 else 14 - k % 14) * 9f) }
        for (size in listOf(15f, 36f)) check("zigzag", points, size)
    }

    @Test
    fun aFastLineLaidInPiecesLooksLikeTheWholeStroke() {
        // Samples far apart, as when the arm sweeps across the board.
        val points = List(120) { k -> PointF(60f + k * 6f, 320f + 220f * sin(k / 9f)) }
        for (size in listOf(9f, 15f)) check("fast", points, size)
    }

    @Test
    fun aSweepOfTheArmIsLaidInPiecesAsItGoes() {
        // Samples some 50 px apart: pieces go by length, not by count, so only its end is drawn again.
        val points = List(60) { k -> PointF(40f + k * 12.5f, 320f + 250f * sin(k / 3.2f)) }
        check("sweep", points, 15f, pieces = 8)
    }

    @Test
    fun tightCurlsStillCoverWhatTheWholeStrokeCovers() {
        // Colouring in with small circles and a thick pen: the outline never runs straight, so the
        // pieces are cut where they must. What matters is that no hole is left.
        val points = List(700) { k -> PointF(200f + k * 0.6f + 12f * cos(k / 3f), 320f + 12f * sin(k / 3f)) }
        check("curls", points, 36f, exact = false)
    }

    private fun check(name: String, points: List<PointF>, size: Float, exact: Boolean = true, pieces: Int = 3) {
        val least = pieces
        val s = Stroke(InkTool.PEN, Color.BLACK, size, speedUnit = 42f)
        val pieces = ArrayList<Path>()
        var laid = 0
        // As the board does while the pen moves: after each sample, lay what can't change any more.
        for (p in points) {
            s.add(p.x, p.y, 0.5f)
            val n = StrokeGeometry.measure(s)
            while (n > 0) {
                val to = StrokeGeometry.pieceEnd(n, laid, REACH)
                if (to == 0) break
                pieces += Path().also { StrokeGeometry.cut(it, laid, to, OVERLAP) }
                laid = to
            }
        }
        // The pen lifts: the end tapers off, and is the last piece.
        s.finish()
        assertTrue("$name: a long stroke is laid in several pieces (${pieces.size})", pieces.size >= least)
        assertTrue(StrokeGeometry.measure(s) > 0)
        pieces += Path().also { StrokeGeometry.cut(it, laid, overlap = OVERLAP) }

        val whole = draw(listOf(s.path))
        val parts = draw(pieces)
        fun lighter(i: Int) = (whole[i] ushr 24) - (parts[i] ushr 24)
        var inked = 0
        var off = 0
        var seam = 0
        for (y in 1 until H - 1) for (x in 1 until W - 1) {
            val i = y * W + x
            if (whole[i] ushr 24 > 0) inked++
            if (abs(lighter(i)) > 16) off++
            // A seam or a sliver is a run of pixels the pieces leave much lighter. (A lone one is where
            // two soft edges meet inside a pixel: drawn apart they add up to a little less.)
            if (lighter(i) > SEAM && (lighter(i - 1) > SEAM || lighter(i + 1) > SEAM || lighter(i - W) > SEAM || lighter(i + W) > SEAM)) seam++
        }
        assertTrue("$name: there is ink", inked > 3000)
        assertEquals("$name, size $size: no seam or gap between pieces", 0, seam)
        // Where pieces overlap, their soft edges add up a little: a few pixels along the edge.
        if (exact) assertTrue("$name, size $size: $off of $inked pixels differ", off < inked / 50)
    }

    private fun draw(paths: List<Path>): IntArray {
        val bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        for (p in paths) canvas.drawPath(p, paint)
        return IntArray(W * H).also { bitmap.getPixels(it, 0, W, 0, 0, W, H) }
    }

    private companion object {
        const val W = 820
        const val H = 640

        /** A piece of a fast stroke: this much of line. And how much pieces overlap. */
        const val REACH = 192f
        const val OVERLAP = 6f

        /** Lighter by more than this (of 255) is no longer two soft edges adding up. */
        const val SEAM = 60
    }
}
