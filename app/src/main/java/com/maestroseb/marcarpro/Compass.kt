package com.maestroseb.marcarpro

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Build
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The compass (the user's approved mockup): two legs in the controls' material joined at a dark
 * hinge, a steel needle and a pencil whose lead is the ink's colour. The needle is the centre and
 * stays put while it draws.
 *  - The hinge, the needle's leg or (a finger) anywhere on it: moves it.
 *  - The pencil's leg: opens or closes it by as much as the hand moves, from where it was taken
 *    (it never jumps to the finger); the radius shows in centimetres.
 *  - The pencil's point (the ring): turning it round draws, the swept angle showing. A quick move
 *    is filled in along the circle, never cut across. On letting go the arc becomes ink (the pen's
 *    colour and thickness, one undo step); while drawing it is only painted here.
 *  - The two small arrowheads beside the hinge (⇕, quiet, teal while used): up makes the whole
 *    compass bigger, legs and all, for large circles; down smaller. The opening scales with it.
 *  - Two fingers on it open or close it; ✕ (beyond the hinge) takes it away.
 * The view is a square as big as the widest circle around the needle, which sits at its centre: it
 * moves by its translation only, so it is never repainted for that (see the protractor).
 */
@SuppressLint("ViewConstructor")
internal class CompassView(
    context: Context,
    private val onClose: () -> Unit,
    /** The arc drawn, as x, y pairs in the board's view: it becomes ink. */
    private val onArc: (FloatArray) -> Unit,
    /** The pen's colour as the board shows it, and its width on screen. */
    private val inkColor: () -> Int,
    private val inkWidth: () -> Float,
) : View(context), MathTool {
    override val view: View get() = this
    private enum class Zone { NONE, MOVE, OPEN, DRAW, CLOSE, SIZE }

    private val pxPerCm = boardPxPerCm(context)
    /** How big it is (its legs), changed with the arrowheads beside the hinge. */
    private var scale = 1f
    private val leg get() = dp(LEG_DP) * scale
    private val maxR get() = 2 * leg * 0.97f
    private val minR = max(pxPerCm, dp(24f))
    private val pad = dp(PAD_DP)
    /** The needle: the view's centre; the view is as big as the widest circle of the biggest compass. */
    private val c0 = pad + 2 * dp(LEG_DP) * MAX_SCALE * 0.97f
    private var radius = (pxPerCm * START_CM).coerceIn(minR, maxR)
    /** Which way the pencil is from the needle (radians, screen: y down). */
    private var angle = START_ANGLE

    // The pencil's point and the hinge, worked out from the radius and the angle.
    private var px = 0f
    private var py = 0f
    private var hx = 0f
    private var hy = 0f
    private var nx = 0f
    private var ny = 0f

    private fun geometry() {
        px = c0 + radius * cos(angle)
        py = c0 + radius * sin(angle)
        // The hinge stands off the chord's middle, square to it, as far as the legs' length allows.
        val h = sqrt(max(leg * leg - radius * radius / 4f, 0f))
        nx = sin(angle)
        ny = -cos(angle)
        hx = (c0 + px) / 2f + nx * h
        hy = (c0 + py) / 2f + ny * h
    }

    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.2f) }
    private val steel = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = dp(2.4f); strokeCap = Paint.Cap.ROUND; color = 0xFF8A929C.toInt() }
    private val wood = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE8C9A0.toInt() }
    private val lead = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = dp(3f); strokeCap = Paint.Cap.ROUND }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2f) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1.5f); color = OrbStyle.TEAL
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(5f), dp(5f)), 0f)
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2f); strokeCap = Paint.Cap.ROUND }
    private val pillText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = context.sp(20f); typeface = Fonts.bold(context); textAlign = Paint.Align.CENTER; fontFeatureSettings = "tnum"
    }
    private val path = Path()
    private val arc = Path()
    private val rect = android.graphics.RectF()

    init {
        contentDescription = context.getString(R.string.tool_compass)
        geometry()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val side = (2 * c0).toInt()
        setMeasuredDimension(side, side)
    }

    override fun placeIn(w: Int, h: Int) {
        translationX = w * 0.42f - c0
        translationY = h * 0.58f - c0
    }

    // region Where it is held

    // The arrowheads that size it: beside the hinge, on the side away from the pencil, upright.
    private val sizeX get() = hx + (if (cos(angle) > 0) -1 else 1) * dp(SIZE_OFF_DP)
    private val sizeY get() = hy

    private val closeX get() = hx + nx * dp(CLOSE_OFF_DP)
    private val closeY get() = hy + ny * dp(CLOSE_OFF_DP)

    private fun zoneAt(x: Float, y: Float): Zone = when {
        hypot(x - closeX, y - closeY) <= dp(22f) -> Zone.CLOSE
        abs(x - sizeX) <= dp(16f) && abs(y - sizeY) <= dp(30f) -> Zone.SIZE
        hypot(x - px, y - py) <= dp(TIP_GRAB_DP) -> Zone.DRAW
        hypot(x - hx, y - hy) <= dp(26f) -> Zone.MOVE
        segment(x, y, hx, hy, c0, c0) <= dp(18f) -> Zone.MOVE
        segment(x, y, hx, hy, px, py) <= dp(20f) -> Zone.OPEN
        else -> Zone.NONE
    }

    private fun segment(x: Float, y: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val t = (((x - ax) * dx + (y - ay) * dy) / (dx * dx + dy * dy).coerceAtLeast(1f)).coerceIn(0f, 1f)
        return hypot(x - ax - t * dx, y - ay - t * dy)
    }

    private fun local(x: Float, y: Float) = (x - this.x) to (y - this.y)

    override fun hitsParent(x: Float, y: Float): Boolean {
        val (lx, ly) = local(x, y)
        return zoneAt(lx, ly) != Zone.NONE
    }

    override fun onSheet(x: Float, y: Float): Boolean {
        val (lx, ly) = local(x, y)
        return zoneAt(lx, ly) != Zone.NONE || segment(lx, ly, hx, hy, px, py) <= dp(26f)
    }

    /** No edge to follow: it draws with its own pencil. */
    override fun guideFor(x: Float, y: Float): ((Float, Float, FloatArray) -> Unit)? = null

    // endregion

    // region Drawing

    /** The arc being drawn (local x, y pairs), the angle swept so far, and the value shown in a pill. */
    private var points = FloatArray(256)
    private var count = 0
    private var swept = 0f
    private var label: String? = null
    private val hideLabel = Runnable { label = null; invalidate() }

    private fun addPoint(x: Float, y: Float) {
        if (count + 2 > points.size) points = points.copyOf(points.size * 2)
        points[count++] = x
        points[count++] = y
    }

    private fun radiusLabel() = String.format(Locale.getDefault(), "r = %.1f cm", radius / pxPerCm)

    override fun onDraw(canvas: Canvas) {
        // The arc being drawn, under the compass, as it will be in ink.
        if (count >= 4) {
            arc.rewind()
            arc.moveTo(points[0], points[1])
            var i = 2
            while (i < count) { arc.lineTo(points[i], points[i + 1]); i += 2 }
            arcPaint.color = inkColor()
            arcPaint.strokeWidth = inkWidth()
            canvas.drawPath(arc, arcPaint)
        }
        if (zone == Zone.OPEN) canvas.drawLine(c0, c0, px, py, guide)

        // The legs: the needle's to 86 % of its length, the pencil's to 80 %, then steel and wood.
        val nEndX = hx + (c0 - hx) * 0.86f
        val nEndY = hy + (c0 - hy) * 0.86f
        val pEndX = hx + (px - hx) * 0.80f
        val pEndY = hy + (py - hy) * 0.80f
        body.color = ColorUtils.setAlphaComponent(Chrome.surface, 0xD8)
        Chrome.shade(body, dp(8f), dp(3f))
        edge.color = Chrome.hairline
        legPath(hx, hy, nEndX, nEndY, dp(9f), dp(5f))
        canvas.drawPath(path, body)
        canvas.drawPath(path, edge)
        legPath(hx, hy, pEndX, pEndY, dp(9f), dp(6f))
        canvas.drawPath(path, body)
        canvas.drawPath(path, edge)
        body.clearShadowLayer()
        canvas.drawLine(nEndX, nEndY, c0, c0, steel)
        val woodX = pEndX + (px - pEndX) * 0.75f
        val woodY = pEndY + (py - pEndY) * 0.75f
        legPath(pEndX, pEndY, woodX, woodY, dp(6f), dp(2.4f))
        canvas.drawPath(path, wood)
        lead.color = inkColor()
        canvas.drawLine(woodX, woodY, px, py, lead)
        // The ring to draw from: stronger while drawing.
        ring.color = ColorUtils.setAlphaComponent(inkColor(), if (zone == Zone.DRAW) 0xFF else 0x73)
        canvas.drawCircle(px, py, dp(16f), ring)

        // The hinge and its handle; the needle's point.
        fill.color = Chrome.icon
        Chrome.shade(fill, dp(6f), dp(2f))
        canvas.drawCircle(hx, hy, dp(12f), fill)
        fill.clearShadowLayer()
        fill.color = Chrome.surface
        canvas.drawCircle(hx, hy, dp(3.8f), fill)
        fill.color = OrbStyle.TEAL
        canvas.drawCircle(c0, c0, dp(3f), fill)
        // ⇕: two small arrowheads, no plate; teal while in use.
        fill.color = if (zone == Zone.SIZE) OrbStyle.TEAL else ColorUtils.setAlphaComponent(Chrome.icon, 0x8C)
        for (dir in intArrayOf(-1, 1)) {
            path.rewind()
            path.moveTo(sizeX, sizeY + dir * dp(13f))
            path.lineTo(sizeX + dp(5f), sizeY + dir * dp(6f))
            path.lineTo(sizeX - dp(5f), sizeY + dir * dp(6f))
            path.close()
            canvas.drawPath(path, fill)
        }

        // ✕, beyond the hinge.
        fill.color = Chrome.surface
        Chrome.shade(fill, dp(6f), dp(2f))
        canvas.drawCircle(closeX, closeY, dp(CHIP_DP), fill)
        fill.clearShadowLayer()
        canvas.drawCircle(closeX, closeY, dp(CHIP_DP) - dp(0.5f), edge)
        glyph.color = Chrome.icon
        val g = dp(5.5f)
        canvas.drawLine(closeX - g, closeY - g, closeX + g, closeY + g, glyph)
        canvas.drawLine(closeX + g, closeY - g, closeX - g, closeY + g, glyph)

        // The value, beyond ✕ on the same line out from the hinge, so it never covers it.
        label?.let {
            val lx = closeX + nx * dp(LABEL_OFF_DP)
            val ly = closeY + ny * dp(LABEL_OFF_DP)
            pill(canvas, it, lx.coerceIn(dp(80f), width - dp(80f)), ly.coerceIn(dp(24f), height - dp(24f)))
        }
    }

    // A leg: a quad from ([ax], [ay]) [w0] wide to ([bx], [by]) [w1] wide.
    private fun legPath(ax: Float, ay: Float, bx: Float, by: Float, w0: Float, w1: Float) {
        val dx = bx - ax
        val dy = by - ay
        val l = hypot(dx, dy).coerceAtLeast(1f)
        val ux = -dy / l
        val uy = dx / l
        path.rewind()
        path.moveTo(ax + ux * w0, ay + uy * w0)
        path.lineTo(bx + ux * w1, by + uy * w1)
        path.lineTo(bx - ux * w1, by - uy * w1)
        path.lineTo(ax - ux * w0, ay - uy * w0)
        path.close()
    }

    private fun pill(canvas: Canvas, text: String, cx: Float, cy: Float) {
        val tw = pillText.measureText(text)
        rect.set(cx - tw / 2 - dp(12f), cy - dp(17f), cx + tw / 2 + dp(12f), cy + dp(17f))
        fill.color = Chrome.surface
        Chrome.shade(fill, dp(6f), dp(2f))
        canvas.drawRoundRect(rect, dp(17f), dp(17f), fill)
        fill.clearShadowLayer()
        canvas.drawRoundRect(rect, dp(17f), dp(17f), edge)
        pillText.color = Chrome.text
        canvas.drawText(text, cx, cy - (pillText.descent() + pillText.ascent()) / 2, pillText)
    }

    // endregion

    // region Touch

    override var grabNext = false
    private var zone = Zone.NONE
    private var lastX = 0f
    private var lastY = 0f
    // Opening: where the hand took the leg (distance and angle from the needle) and how it was.
    private var fromDist = 0f
    private var fromAngle = 0f
    private var fromRadius = 0f
    private var fromTurn = 0f
    private var fromScale = 1f
    private var fromRawY = 0f
    private var pinching = false
    private var pinchDist = 0f

    private fun show(text: String) {
        removeCallbacks(hideLabel)
        label = text
    }

    private fun settleLabel() {
        removeCallbacks(hideLabel)
        postDelayed(hideLabel, LABEL_SHOWN_MS)
    }

    override fun pinchStart(ax: Float, ay: Float, bx: Float, by: Float) {
        pinching = true
        pinchDist = hypot(bx - ax, by - ay).coerceAtLeast(1f)
        fromRadius = radius
    }

    override fun pinchMove(ax: Float, ay: Float, bx: Float, by: Float) {
        if (!pinching) return
        radius = (fromRadius * hypot(bx - ax, by - ay) / pinchDist).coerceIn(minR, maxR)
        geometry()
        show(radiusLabel())
        invalidate()
    }

    override fun pinchEnd() {
        if (!pinching) return
        pinching = false
        settleLabel()
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
                when (zone) {
                    Zone.OPEN -> {
                        fromDist = hypot(e.x - c0, e.y - c0)
                        fromAngle = atan2(e.y - c0, e.x - c0)
                        fromRadius = radius
                        fromTurn = angle
                        show(radiusLabel())
                    }
                    Zone.SIZE -> {
                        fromScale = scale
                        fromRadius = radius
                        fromRawY = e.rawY
                        show(radiusLabel())
                    }
                    Zone.DRAW -> {
                        count = 0
                        swept = 0f
                        addPoint(px, py)
                        show("0°")
                    }
                    else -> Unit
                }
                invalidate()
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
                zone == Zone.SIZE -> {
                    // Up grows, down shrinks: the whole compass, the opening with it.
                    scale = (fromScale * exp((fromRawY - e.rawY) / dp(SIZE_DRAG_DP))).coerceIn(MIN_SCALE, MAX_SCALE)
                    radius = (fromRadius * scale / fromScale).coerceIn(minR, maxR)
                    geometry()
                    show(radiusLabel())
                    invalidate()
                }
                zone == Zone.OPEN -> {
                    radius = (fromRadius + hypot(e.x - c0, e.y - c0) - fromDist).coerceIn(minR, maxR)
                    angle = fromTurn + (atan2(e.y - c0, e.x - c0) - fromAngle)
                    geometry()
                    show(radiusLabel())
                    invalidate()
                }
                zone == Zone.DRAW -> {
                    // Round the circle from where the pencil is to where the hand is, in small steps.
                    var d = atan2(e.y - c0, e.x - c0) - angle
                    d = ((d + 3 * PI) % (2 * PI) - PI).toFloat()
                    // Once round, no further: a second turn would only thicken the line.
                    val room = (2 * PI).toFloat() - abs(swept)
                    if (abs(d) > room) d = if (d > 0) room else -room
                    if (abs(d) > 0.0005f) {
                        val n = max(1, ceil(abs(d) / STEP_RAD).toInt())
                        for (k in 1..n) {
                            val a = angle + d * k / n
                            addPoint(c0 + radius * cos(a), c0 + radius * sin(a))
                        }
                        angle += d
                        swept += d
                        geometry()
                        show("${min(360, (abs(swept) * 180 / PI).roundToInt())}°")
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
                if (zone == Zone.DRAW) commit()
                if (zone == Zone.OPEN || zone == Zone.DRAW || zone == Zone.SIZE) settleLabel()
                zone = Zone.NONE
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                pinchEnd()
                if (zone == Zone.DRAW) commit()
                settleLabel()
                zone = Zone.NONE
                invalidate()
            }
        }
        return true
    }

    /** The arc drawn becomes ink on the board (in the board view's coordinates). */
    private fun commit() {
        if (count >= 4) {
            val out = FloatArray(count)
            var i = 0
            while (i < count) {
                out[i] = points[i] + x
                out[i + 1] = points[i + 1] + y
                i += 2
            }
            onArc(out)
        }
        count = 0
    }

    // endregion

    private companion object {
        const val LEG_DP = 130f
        const val MIN_SCALE = 0.6f
        const val MAX_SCALE = 2.6f
        const val SIZE_OFF_DP = 24f
        const val LABEL_OFF_DP = 46f
        /** Dragging this far up makes it e (2.7) times bigger. */
        const val SIZE_DRAG_DP = 140f
        const val PAD_DP = 40f
        const val START_CM = 5f
        const val START_ANGLE = -0.35f
        const val TIP_GRAB_DP = 30f
        const val CHIP_DP = 16f
        const val CLOSE_OFF_DP = 40f
        /** The arc is filled in every 0.03 rad (under 2° ): smooth at any radius on a panel. */
        const val STEP_RAD = 0.03f
        const val LABEL_SHOWN_MS = 1200L
    }
}
