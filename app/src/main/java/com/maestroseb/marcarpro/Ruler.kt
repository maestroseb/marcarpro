package com.maestroseb.marcarpro

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The ruler: a clear strip in the controls' material with a centimetre scale along its top edge.
 * Like the protractor ([MathTool]), a finger anywhere on it moves it, a pen writes on it, two
 * fingers make it longer or shorter, ↻ (at its right end) turns it round its zero and ✕ takes it
 * away. A stroke started against either long edge comes out straight along it, its length shown
 * live in a pill.
 */
@SuppressLint("ViewConstructor")
internal class RulerView(context: Context, private val onClose: () -> Unit) : View(context), MathTool {
    override val view: View get() = this
    private enum class Zone { NONE, MOVE, TURN, CLOSE }

    /**
     * Pixels in a centimetre: real ones when the panel says its size believably (a 50–100″ board),
     * else a fixed scale that reads well from the back of the room.
     */
    private val pxPerCm: Float = run {
        val m = resources.displayMetrics
        val inches = if (m.xdpi > 0 && m.ydpi > 0) sqrt((m.widthPixels / m.xdpi).let { it * it } + (m.heightPixels / m.ydpi).let { it * it }) else 0f
        if (inches in 40f..110f) m.xdpi / 2.54f else dp(FALLBACK_CM_DP)
    }

    private val pad = dp(PAD_DP)
    private val tall = dp(HEIGHT_DP)
    private val inset = dp(INSET_DP)
    /** Longest it can be: the width of the screen. The view is always that big (see the protractor). */
    private val maxLen = resources.displayMetrics.widthPixels.toFloat()
    private var lengthCm = DEFAULT_CM.coerceAtMost(maxCm())
    private val len get() = lengthCm * pxPerCm + 2 * inset

    private fun maxCm() = ((maxLen * 0.95f - 2 * inset) / pxPerCm).coerceAtLeast(MIN_CM)

    /** The zero mark (the point it turns round), in this view. */
    private val zx = pad + inset
    private val top = pad
    private val bottom get() = pad + tall
    private val left0 = pad
    private val right get() = pad + len

    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.2f) }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val numbers = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = context.sp(13f); typeface = Fonts.bold(context); textAlign = Paint.Align.CENTER; fontFeatureSettings = "tnum"
    }
    private val unit = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = context.sp(11f); typeface = Fonts.regular(context) }
    private val chip = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(2f); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val pillText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = context.sp(20f); typeface = Fonts.bold(context); textAlign = Paint.Align.CENTER; fontFeatureSettings = "tnum"
    }
    private val r = RectF()

    init {
        pivotX = zx
        pivotY = top + tall / 2
        contentDescription = context.getString(R.string.tool_ruler)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Room above and below for the pills (the angle, a length).
        setMeasuredDimension((2 * pad + maxLen).toInt(), (2 * pad + tall).toInt())
    }

    override fun placeIn(w: Int, h: Int) {
        translationX = w / 2f - (left0 + len / 2)
        translationY = h * 0.35f - (top + tall / 2)
        rotation = 0f
    }

    // Chips inside the strip, at its right end: ↻ last (a lever), ✕ before it.
    private val chipY get() = top + tall * 0.66f
    private val turnX get() = right - dp(CHIP_DP) - dp(8f)
    private val closeX get() = turnX - 2 * dp(CHIP_DP) - dp(10f)

    private fun zoneAt(x: Float, y: Float): Zone {
        val grab = dp(24f)
        return when {
            hypot(x - turnX, y - chipY) <= grab -> Zone.TURN
            hypot(x - closeX, y - chipY) <= grab -> Zone.CLOSE
            // The plate (for when fingers write): the lower half, under the numbers.
            x in left0..right && y in (top + tall * 0.5f)..bottom -> Zone.MOVE
            else -> Zone.NONE
        }
    }

    private val inv = Matrix()
    private val pt = FloatArray(2)

    private fun toLocal(x: Float, y: Float) {
        pt[0] = x - left
        pt[1] = y - top()
        matrix.invert(inv)
        inv.mapPoints(pt)
    }

    /** The view's own top on the board (View.getTop, renamed: `top` here is the strip's edge). */
    private fun top() = super.getTop()

    private fun toBoard(x: Float, y: Float, out: FloatArray) {
        out[0] = x
        out[1] = y
        matrix.mapPoints(out)
        out[0] += left
        out[1] += top()
    }

    override fun onSheet(x: Float, y: Float): Boolean {
        toLocal(x, y)
        return pt[0] in left0..right && pt[1] in top..bottom
    }

    override fun hitsParent(x: Float, y: Float): Boolean {
        toLocal(x, y)
        return zoneAt(pt[0], pt[1]) != Zone.NONE
    }

    // region A stroke along an edge, and its length

    /** While a stroke follows an edge: where it started and where it is (local x), and on which edge. */
    private var measuring = false
    private var fromX = 0f
    private var toX = 0f
    private var onTop = true
    private val hideMeasure = Runnable { measuring = false; invalidate() }

    override fun guideFor(x: Float, y: Float): ((Float, Float, FloatArray) -> Unit)? {
        toLocal(x, y)
        val lx = pt[0]
        val ly = pt[1]
        if (lx !in left0..right) return null
        val reach = dp(EDGE_REACH_DP)
        val inn = dp(EDGE_IN_DP)
        val lineY = when {
            ly in (top - reach)..(top + inn) -> top - dp(PEN_GAP_DP)
            ly in (bottom - inn)..(bottom + reach) -> bottom + dp(PEN_GAP_DP)
            else -> return null
        }
        removeCallbacks(hideMeasure)
        onTop = lineY < top
        fromX = lx
        toX = lx
        measuring = true
        invalidate()
        return { px, py, o ->
            toLocal(px, py)
            toX = pt[0]
            invalidate()
            toBoard(pt[0], lineY, o)
        }
    }

    override fun guideEnd() {
        removeCallbacks(hideMeasure)
        postDelayed(hideMeasure, MEASURE_SHOWN_MS)
    }

    private fun cm(px: Float): String = String.format(Locale.getDefault(), "%.1f cm", abs(px) / pxPerCm)

    // endregion

    override fun onDraw(canvas: Canvas) {
        // The strip.
        r.set(left0, top, right, bottom)
        body.color = ColorUtils.setAlphaComponent(Chrome.surface, 0xD0)
        Chrome.shade(body, dp(10f), dp(3f))
        canvas.drawRoundRect(r, dp(6f), dp(6f), body)
        edge.color = Chrome.hairline
        canvas.drawRoundRect(r, dp(6f), dp(6f), edge)

        // The scale along the top edge: millimetres when they are far enough apart to see.
        tick.color = Chrome.icon
        numbers.color = Chrome.text
        val mm = pxPerCm / 10f
        val showMm = mm >= dp(2.5f)
        val step = if (showMm) 1 else 5
        // Numbers every centimetre, or every 2 / 5 when centimetres are narrow.
        val every = when {
            pxPerCm >= numbers.measureText("88") + dp(6f) -> 1
            2 * pxPerCm >= numbers.measureText("88") + dp(6f) -> 2
            else -> 5
        }
        val total = (lengthCm * 10).toInt()
        var i = 0
        while (i <= total) {
            val x = zx + i * mm
            val len = dp(if (i % 10 == 0) 18f else if (i % 5 == 0) 12f else 7f)
            tick.strokeWidth = dp(if (i % 10 == 0) 1.4f else 1f)
            tick.alpha = if (i % 10 == 0) 230 else 150
            canvas.drawLine(x, top, x, top + len, tick)
            if (i % 10 == 0 && (i / 10) % every == 0) {
                canvas.drawText("${i / 10}", x, top + dp(34f), numbers)
            }
            i += step
        }
        unit.color = Chrome.hint
        canvas.drawText("cm", zx + dp(6f), top + dp(52f), unit)

        // ✕ and ↻.
        glyph.color = Chrome.icon
        drawChip(canvas, closeX, chipY)
        val x = dp(6f)
        canvas.drawLine(closeX - x, chipY - x, closeX + x, chipY + x, glyph)
        canvas.drawLine(closeX + x, chipY - x, closeX - x, chipY + x, glyph)
        drawChip(canvas, turnX, chipY)
        val t = dp(7.5f)
        r.set(turnX - t, chipY - t, turnX + t, chipY + t)
        val start = -50f
        val sweep = 290f
        canvas.drawArc(r, start, sweep, false, glyph)
        val endA = Math.toRadians((start + sweep).toDouble())
        val ex = turnX + (cos(endA) * t).toFloat()
        val ey = chipY + (sin(endA) * t).toFloat()
        val dx = -sin(endA).toFloat()
        val dy = cos(endA).toFloat()
        val hl = dp(4.5f)
        for (side in floatArrayOf(-1f, 1f)) {
            val a2 = atan2(dy.toDouble(), dx.toDouble()) + Math.PI + side * Math.PI / 4
            canvas.drawLine(ex, ey, ex + (cos(a2) * hl).toFloat(), ey + (sin(a2) * hl).toFloat(), glyph)
        }

        // While turning: its angle over the ↻; while drawing along it: the length, by the pen.
        if (zone == Zone.TURN) {
            var a = (-rotation % 360f + 360f) % 360f
            if (a > 180f) a -= 360f
            pill(canvas, "${abs(a).roundToInt()}°", turnX, top - dp(30f))
        }
        if (measuring) {
            pill(canvas, cm(toX - fromX), toX.coerceIn(left0 + dp(40f), right - dp(40f)), if (onTop) top - dp(30f) else bottom + dp(30f))
        }
    }

    /** A value in an upright pill centred at ([cx], [cy]). */
    private fun pill(canvas: Canvas, label: String, cx: Float, cy: Float) {
        val tw = pillText.measureText(label)
        canvas.save()
        canvas.rotate(-rotation, cx, cy)
        r.set(cx - tw / 2 - dp(12f), cy - dp(17f), cx + tw / 2 + dp(12f), cy + dp(17f))
        chip.color = Chrome.surface
        Chrome.shade(chip, dp(6f), dp(2f))
        canvas.drawRoundRect(r, dp(17f), dp(17f), chip)
        chip.clearShadowLayer()
        edge.color = Chrome.hairline
        canvas.drawRoundRect(r, dp(17f), dp(17f), edge)
        pillText.color = Chrome.text
        canvas.drawText(label, cx, cy - (pillText.descent() + pillText.ascent()) / 2, pillText)
        canvas.restore()
    }

    private fun drawChip(canvas: Canvas, cx: Float, cy: Float) {
        val rad = dp(CHIP_DP)
        chip.color = Chrome.surface
        Chrome.shade(chip, dp(6f), dp(2f))
        canvas.drawCircle(cx, cy, rad, chip)
        chip.clearShadowLayer()
        edge.color = Chrome.hairline
        canvas.drawCircle(cx, cy, rad - dp(0.5f), edge)
    }

    // region Touch

    override var grabNext = false
    private var zone = Zone.NONE
    private var lastX = 0f
    private var lastY = 0f
    private var turnFrom = 0f
    private var rotationFrom = 0f
    private var pinching = false
    private var pinchDist = 0f
    private var lengthFrom = 0f

    private fun zeroOnScreen(): Pair<Float, Float> {
        toBoard(zx, top + tall / 2, pt)
        val at = IntArray(2)
        (parent as? View)?.getLocationOnScreen(at)
        return (pt[0] + at[0]) to (pt[1] + at[1])
    }

    override fun pinchStart(ax: Float, ay: Float, bx: Float, by: Float) {
        pinching = true
        pinchDist = hypot(bx - ax, by - ay).coerceAtLeast(1f)
        lengthFrom = lengthCm
    }

    override fun pinchMove(ax: Float, ay: Float, bx: Float, by: Float) {
        if (!pinching) return
        // Whole centimetres, like a real one.
        val next = (lengthFrom * hypot(bx - ax, by - ay) / pinchDist).roundToInt().toFloat().coerceIn(MIN_CM, maxCm())
        if (next != lengthCm) {
            lengthCm = next
            invalidate()
        }
    }

    override fun pinchEnd() {
        pinching = false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                zone = zoneAt(e.x, e.y)
                if (zone == Zone.NONE && grabNext) zone = Zone.MOVE
                grabNext = false
                pinching = false
                if (zone == Zone.NONE) return false
                lastX = e.rawX
                lastY = e.rawY
                if (zone == Zone.TURN) {
                    val (cx, cy) = zeroOnScreen()
                    turnFrom = Math.toDegrees(atan2((e.rawY - cy).toDouble(), (e.rawX - cx).toDouble())).toFloat()
                    rotationFrom = rotation
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (zone == Zone.MOVE && e.pointerCount == 2 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                pinchStart(e.getRawX(0), e.getRawY(0), e.getRawX(1), e.getRawY(1))
            }
            MotionEvent.ACTION_MOVE -> when {
                pinching && e.pointerCount >= 2 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                    pinchMove(e.getRawX(0), e.getRawY(0), e.getRawX(1), e.getRawY(1))
                zone == Zone.MOVE && !pinching -> {
                    translationX += e.rawX - lastX
                    translationY += e.rawY - lastY
                    lastX = e.rawX
                    lastY = e.rawY
                }
                zone == Zone.TURN -> {
                    val (cx, cy) = zeroOnScreen()
                    val now = Math.toDegrees(atan2((e.rawY - cy).toDouble(), (e.rawX - cx).toDouble())).toFloat()
                    var rot = rotationFrom + (now - turnFrom)
                    val near = (rot / 15f).roundToInt() * 15f
                    if (abs(rot - near) < SNAP_DEG) rot = near
                    if (rot != rotation) {
                        rotation = rot
                        invalidate()
                    }
                }
                else -> Unit
            }
            MotionEvent.ACTION_POINTER_UP -> if (pinching) {
                pinchEnd()
                zone = Zone.NONE
            }
            MotionEvent.ACTION_UP -> {
                pinchEnd()
                if (zone == Zone.CLOSE && zoneAt(e.x, e.y) == Zone.CLOSE) onClose()
                zone = Zone.NONE
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                pinchEnd()
                zone = Zone.NONE
                invalidate()
            }
        }
        return true
    }

    // endregion

    private companion object {
        const val PAD_DP = 56f
        const val HEIGHT_DP = 84f
        const val INSET_DP = 14f
        const val CHIP_DP = 18f
        const val DEFAULT_CM = 30f
        const val MIN_CM = 5f
        /** A centimetre when the panel doesn't say its real size. */
        const val FALLBACK_CM_DP = 38f
        const val SNAP_DEG = 3f
        const val EDGE_IN_DP = 6f
        const val EDGE_REACH_DP = 22f
        const val PEN_GAP_DP = 3f
        const val MEASURE_SHOWN_MS = 1800L
    }
}
