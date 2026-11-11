package com.maestroseb.marcarpro

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import androidx.core.graphics.withMatrix
import androidx.core.graphics.withTranslation
import kotlin.math.max
import kotlin.math.min

/**
 * Committed ink, rasterised once, in tiles of [TILE] px.
 *
 * On the ViewBoard (Mali-G52 at 4K) what matters is how much is drawn per frame and how much goes
 * up to the GPU when the ink changes: only tiles holding ink are drawn, and of each only the part
 * that holds any (an empty board costs nothing); a tile without ink has no bitmap at all; and after
 * a change only the tiles it touched (256 KB each) are uploaded again.
 *
 * Strokes are in board coordinates; [camera] maps them to the screen (identity except on the
 * whiteboard).
 */
internal class InkLayer(private val drawInk: (Canvas, Stroke) -> Unit) {

    private class Tile(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        var bitmap: Bitmap? = null
        var canvas: Canvas? = null

        /** The part of the tile, on screen, that may hold ink: only that much is drawn. */
        val ink = Rect()

        fun canvas(): Canvas = canvas ?: createBitmap(right - left, bottom - top).let { b ->
            bitmap = b
            Canvas(b).also { canvas = it }
        }

        fun free() {
            bitmap?.recycle()
            bitmap = null
            canvas = null
            ink.setEmpty()
        }

        /** Ink was drawn within [area] of the screen (it reaches [EDGE] px past its outline). */
        fun inked(area: Rect) {
            val l = max(area.left - EDGE, left)
            val t = max(area.top - EDGE, top)
            val r = min(area.right + EDGE, right)
            val b = min(area.bottom + EDGE, bottom)
            if (l < r && t < b) ink.union(l, t, r, b)
        }
    }

    private var tiles = emptyList<Tile>()
    private val inverse = Matrix()
    private val screen = RectF()
    private val world = RectF()
    private val clip = Rect()
    private val part = Rect()

    fun resize(w: Int, h: Int) {
        release()
        tiles = buildList {
            for (top in 0 until h step TILE) for (left in 0 until w step TILE) {
                add(Tile(left, top, minOf(left + TILE, w), minOf(top + TILE, h)))
            }
        }
    }

    fun release() {
        for (t in tiles) t.free()
    }

    /** Rasterises everything again (page switch, clear, a moved whiteboard). */
    fun redraw(strokes: List<Stroke>, skip: Set<Stroke>, camera: Matrix) {
        camera.invert(inverse)
        for (t in tiles) {
            screen.set(t.left.toFloat(), t.top.toFloat(), t.right.toFloat(), t.bottom.toFloat())
            inverse.mapRect(world, screen)
            if (strokes.none { it !in skip && RectF.intersects(it.bounds, world) }) {
                t.free() // nothing here any more: give the memory back
                continue
            }
            val c = t.canvas()
            t.bitmap?.eraseColor(Color.TRANSPARENT)
            t.ink.setEmpty()
            c.withTranslation(-t.left.toFloat(), -t.top.toFloat()) {
                withMatrix(camera) {
                    for (s in strokes) if (s !in skip && RectF.intersects(s.bounds, world)) {
                        drawInk(this, s)
                        place(s, camera)
                        t.inked(clip)
                    }
                }
            }
        }
    }

    /** Sets [clip] to where [s] shows on screen. A text's letters may reach a little past its block. */
    private fun place(s: Stroke, camera: Matrix) {
        camera.mapRect(screen, s.bounds)
        if (s.tool == InkTool.TEXT) {
            val reach = camera.mapRadius(s.size) * TEXT_REACH
            screen.inset(-reach, -reach)
        }
        screen.roundOut(clip)
    }

    /** Draws a newly added stroke on top of the rest. */
    fun add(s: Stroke, camera: Matrix) {
        place(s, camera)
        for (t in tiles) {
            if (!clip.intersects(t.left, t.top, t.right, t.bottom)) continue
            t.canvas().withTranslation(-t.left.toFloat(), -t.top.toFloat()) {
                withMatrix(camera) { drawInk(this, s) }
            }
            t.inked(clip)
        }
    }

    /**
     * Puts ink that is already rasterised ([ink], its corner at [left], [top] of the screen, with
     * something in it only within [area]) over what the layer holds. An empty tile it covers
     * exactly keeps the bitmap itself (nothing is copied, and the GPU already has it): then this
     * returns true and the bitmap is the layer's.
     */
    fun put(ink: Bitmap, left: Int, top: Int, area: Rect): Boolean {
        val right = left + ink.width
        val bottom = top + ink.height
        var kept = false
        for (t in tiles) {
            if (left >= t.right || right <= t.left || top >= t.bottom || bottom <= t.top) continue
            if (t.bitmap == null && left == t.left && top == t.top && right == t.right && bottom == t.bottom) {
                t.bitmap = ink
                t.canvas = Canvas(ink)
                kept = true
            } else {
                t.canvas().drawBitmap(ink, (left - t.left).toFloat(), (top - t.top).toFloat(), null)
            }
            t.inked(area)
        }
        return kept
    }

    /** Clears and redraws only [dirty] (board coordinates), e.g. where the eraser passed. */
    fun repaint(dirty: RectF, strokes: List<Stroke>, skip: Set<Stroke>, camera: Matrix) {
        camera.mapRect(screen, dirty)
        screen.roundOut(clip)
        for (t in tiles) {
            if (!clip.intersects(t.left, t.top, t.right, t.bottom)) continue
            val hasInk = strokes.any { it !in skip && RectF.intersects(it.bounds, dirty) }
            if (t.bitmap == null && !hasInk) continue
            t.canvas().withTranslation(-t.left.toFloat(), -t.top.toFloat()) {
                withClip(clip) {
                    drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                    withMatrix(camera) {
                        for (s in strokes) if (s !in skip && RectF.intersects(s.bounds, dirty)) drawInk(this, s)
                    }
                }
            }
            if (hasInk) t.inked(clip)
        }
    }

    /** Draws the tiles that hold ink, each as far as its ink goes. */
    fun draw(canvas: Canvas, paint: android.graphics.Paint? = null) {
        for (t in tiles) {
            val b = t.bitmap ?: continue
            if (t.ink.isEmpty) continue
            part.set(t.ink.left - t.left, t.ink.top - t.top, t.ink.right - t.left, t.ink.bottom - t.top)
            canvas.drawBitmap(b, part, t.ink, paint)
        }
    }

    companion object {
        const val TILE = 256

        /** Ink shows a little past its bounds (anti-aliasing, a picture's edge). */
        private const val EDGE = 2

        /** How far past its block a text may show (an accent on a capital, a tail), in letter sizes. */
        private const val TEXT_REACH = 0.25f
    }
}
