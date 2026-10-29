package com.maestroseb.marcarpro

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.withSave
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * "Cut out to the whiteboard": the screen, frozen and dimmed, on which a box is drawn around
 * what to take (an exercise of the digital book, say). The box can then be moved or resized by
 * its corners; "To the whiteboard" takes it, "Cancel" leaves.
 *
 * [onDone] gets the chosen part of [shot] (in its pixels), or null when cancelled.
 */
@SuppressLint("ViewConstructor")
internal class CropView(context: Context, private val shot: Bitmap, private val onDone: (Rect?) -> Unit) : View(context) {

    private val box = RectF()
    private val dim = Paint().apply { color = 0x8C101418.toInt() }
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        color = OrbStyle.TEAL
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        color = OrbStyle.TEAL
    }
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { setShadowLayer(dp(6f), 0f, dp(2f), OrbStyle.SHADOW) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = context.sp(16f)
        typeface = Fonts.bold(context)
    }
    private val dst = RectF()
    private val origin = IntArray(2)

    private val takeLabel = context.getString(R.string.crop_take)
    private val cancelLabel = context.getString(R.string.crop_cancel)
    private val hintLabel = context.getString(R.string.crop_hint)
    private val takeRect = RectF()
    private val cancelRect = RectF()
    private val hintRect = RectF()

    private enum class Drag { NONE, NEW, MOVE, CORNER, TAKE, CANCEL }

    private var drag = Drag.NONE
    private var corner = 0
    private var lastX = 0f
    private var lastY = 0f
    private var startX = 0f
    private var startY = 0f

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        // The capture is the whole screen; this window may not cover the system bars: draw it 1:1 in place.
        getLocationOnScreen(origin)
        canvas.drawBitmap(shot, -origin[0].toFloat(), -origin[1].toFloat(), null)
        dst.set(0f, 0f, width.toFloat(), height.toFloat())
        // Everything but the box is dimmed.
        if (box.isEmpty) {
            canvas.drawRect(dst, dim)
        } else {
            canvas.withSave {
                clipOutRect(box)
                drawRect(dst, dim)
            }
            canvas.drawRect(box, frame)
            for (k in 0 until 4) {
                val x = if (k == 0 || k == 3) box.left else box.right
                val y = if (k < 2) box.top else box.bottom
                canvas.drawCircle(x, y, dp(11f), handleFill)
                canvas.drawCircle(x, y, dp(11f), handleRing)
            }
        }
        layoutButtons()
        if (box.isEmpty) drawPill(canvas, hintRect, hintLabel, Color.WHITE, OrbStyle.LABEL)
        if (!box.isEmpty) drawPill(canvas, takeRect, takeLabel, OrbStyle.TEAL, Color.WHITE)
        drawPill(canvas, cancelRect, cancelLabel, Color.WHITE, OrbStyle.LABEL)
    }

    private fun drawPill(canvas: Canvas, r: RectF, label: String, fill: Int, ink: Int) {
        pill.color = fill
        canvas.drawRoundRect(r, r.height() / 2, r.height() / 2, pill)
        text.color = ink
        val tw = text.measureText(label)
        canvas.drawText(label, r.centerX() - tw / 2, r.centerY() - (text.descent() + text.ascent()) / 2, text)
    }

    /** The buttons go under the box (above it near the bottom); before a box, a hint at the top. */
    private fun layoutButtons() {
        val h = dp(48f)
        val gap = dp(12f)
        val takeW = text.measureText(takeLabel) + dp(44f)
        val cancelW = text.measureText(cancelLabel) + dp(44f)
        if (box.isEmpty) {
            val hintW = text.measureText(hintLabel) + dp(44f)
            val left = (width - hintW - gap - cancelW) / 2
            hintRect.set(left, dp(28f), left + hintW, dp(28f) + h)
            cancelRect.set(hintRect.right + gap, hintRect.top, hintRect.right + gap + cancelW, hintRect.bottom)
            takeRect.setEmpty()
            return
        }
        hintRect.setEmpty()
        var top = box.bottom + dp(18f)
        if (top + h > height - dp(8f)) top = box.top - dp(18f) - h
        if (top < dp(8f)) top = box.bottom - dp(18f) - h
        val right = min(max(box.right, takeW + gap + cancelW + dp(8f)), width - dp(8f))
        takeRect.set(right - takeW, top, right, top + h)
        cancelRect.set(takeRect.left - gap - cancelW, top, takeRect.left - gap, top + h)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x
        val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = x
                lastY = y
                startX = x
                startY = y
                drag = when {
                    takeRect.contains(x, y) -> Drag.TAKE
                    cancelRect.contains(x, y) -> Drag.CANCEL
                    cornerAt(x, y) >= 0 -> {
                        corner = cornerAt(x, y)
                        Drag.CORNER
                    }
                    box.contains(x, y) -> Drag.MOVE
                    else -> Drag.NEW
                }
            }
            MotionEvent.ACTION_MOVE -> {
                when (drag) {
                    Drag.NEW -> box.set(min(startX, x), min(startY, y), max(startX, x), max(startY, y))
                    Drag.MOVE -> {
                        val dx = (x - lastX).coerceIn(-box.left, width - box.right)
                        val dy = (y - lastY).coerceIn(-box.top, height - box.bottom)
                        box.offset(dx, dy)
                    }
                    Drag.CORNER -> {
                        val cx = x.coerceIn(0f, width.toFloat())
                        val cy = y.coerceIn(0f, height.toFloat())
                        if (corner == 0 || corner == 3) box.left = cx else box.right = cx
                        if (corner < 2) box.top = cy else box.bottom = cy
                    }
                    else -> Unit
                }
                lastX = x
                lastY = y
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                when (drag) {
                    Drag.TAKE -> if (takeRect.contains(x, y)) finish(take = true)
                    Drag.CANCEL -> if (cancelRect.contains(x, y)) finish(take = false)
                    else -> {
                        box.sort()
                        // A tap (or a sliver) is not a box.
                        if (box.width() < dp(MIN_BOX_DP) || box.height() < dp(MIN_BOX_DP)) box.setEmpty()
                        if (drag == Drag.NEW && abs(x - startX) + abs(y - startY) < dp(4f)) box.setEmpty()
                    }
                }
                drag = Drag.NONE
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> drag = Drag.NONE
        }
        return true
    }

    private fun cornerAt(x: Float, y: Float): Int {
        if (box.isEmpty) return -1
        for (k in 0 until 4) {
            val cx = if (k == 0 || k == 3) box.left else box.right
            val cy = if (k < 2) box.top else box.bottom
            if (hypot(x - cx, y - cy) < dp(CORNER_REACH_DP)) return k
        }
        return -1
    }

    private fun finish(take: Boolean) {
        if (!take || box.isEmpty) {
            onDone(null)
            return
        }
        // From this window to the screenshot (the whole screen).
        getLocationOnScreen(origin)
        val r = Rect(box.left.toInt(), box.top.toInt(), box.right.toInt(), box.bottom.toInt())
        r.offset(origin[0], origin[1])
        onDone(if (r.intersect(0, 0, shot.width, shot.height)) r else null)
    }

    private companion object {
        const val MIN_BOX_DP = 24f
        const val CORNER_REACH_DP = 36f
    }
}
