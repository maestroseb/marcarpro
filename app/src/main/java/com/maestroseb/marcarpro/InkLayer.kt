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

/**
 * Committed ink, rasterised once, in tiles of [TILE] px.
 *
 * On the ViewBoard (Mali-G52 at 4K, no buffer age) every frame redraws the whole window, so what
 * matters is how much is drawn per frame: only tiles holding ink are drawn (an empty board costs
 * nothing), and a tile that doesn't hold ink has no bitmap at all. After a stroke only the tiles
 * it touched change, so only those (about 1 MB each) go up to the GPU again, instead of a 33 MB
 * full-screen bitmap.
 *
 * Strokes are in board coordinates; [camera] maps them to the screen (identity except on the
 * whiteboard).
 */
internal class InkLayer(private val drawInk: (Canvas, Stroke) -> Unit) {

    private class Tile(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        var bitmap: Bitmap? = null
        var canvas: Canvas? = null

        fun canvas(): Canvas = canvas ?: createBitmap(right - left, bottom - top).let { b ->
            bitmap = b
            Canvas(b).also { canvas = it }
        }

        fun free() {
            bitmap?.recycle()
            bitmap = null
            canvas = null
        }
    }

    private var tiles = emptyList<Tile>()
    private val inverse = Matrix()
    private val screen = RectF()
    private val world = RectF()
    private val clip = Rect()

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
            c.withTranslation(-t.left.toFloat(), -t.top.toFloat()) {
                withMatrix(camera) {
                    for (s in strokes) if (s !in skip && RectF.intersects(s.bounds, world)) drawInk(this, s)
                }
            }
        }
    }

    /** Draws a newly added stroke on top of the rest. */
    fun add(s: Stroke, camera: Matrix) {
        camera.mapRect(screen, s.bounds)
        screen.roundOut(clip)
        for (t in tiles) {
            if (!clip.intersects(t.left, t.top, t.right, t.bottom)) continue
            t.canvas().withTranslation(-t.left.toFloat(), -t.top.toFloat()) {
                withMatrix(camera) { drawInk(this, s) }
            }
        }
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
        }
    }

    /** Draws the tiles that hold ink. */
    fun draw(canvas: Canvas) {
        for (t in tiles) {
            val b = t.bitmap ?: continue
            canvas.drawBitmap(b, t.left.toFloat(), t.top.toFloat(), null)
        }
    }

    private companion object {
        const val TILE = 512
    }
}
