package com.maestroseb.marcarpro

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The protractor: a clear sheet in the controls' material (white, a graphite hairline) lying on the
 * board, with a degree scale both ways, a measuring arm in the pen's blue and its angle in a pill.
 * It is a view of its own over the ink: moving and turning it only changes its transform (the
 * board is never repainted). It answers only where it is held (the centre plate, the arm's knob,
 * the turning and closing chips); a pen anywhere else on it writes, as on a real one.
 */
@SuppressLint("ViewConstructor")
internal class ProtractorView(context: Context, private val onClose: () -> Unit) : View(context) {
    private enum class Zone { NONE, MOVE, ARM, TURN, CLOSE }

    private val radius = dp(RADIUS_DP)
    private val band = dp(BAND_DP)
    private val pad = dp(PAD_DP)
    /** The centre point (the vertex of the angle), in this view. */
    private val ox = pad + radius
    private val oy = pad + radius

    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.2f) }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val outer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = context.sp(13f); typeface = Fonts.bold(context); textAlign = Paint.Align.CENTER; fontFeatureSettings = "tnum"
    }
    private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = context.sp(10.5f); typeface = Fonts.regular(context); textAlign = Paint.Align.CENTER; fontFeatureSettings = "tnum"
    }
    private val arm = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = dp(2.5f); strokeCap = Paint.Cap.ROUND; color = ClockView.ACCENT }
    private val wedge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ColorUtils.setAlphaComponent(ClockView.ACCENT, 0x24) }
    private val chip = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(2f); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val readout = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = context.sp(22f); typeface = Fonts.bold(context); textAlign = Paint.Align.CENTER; fontFeatureSettings = "tnum"
    }
    private val path = Path()
    private val r = RectF()

    /** The arm's angle, whole degrees from the right-hand zero, 0..180. */
    private var angle = 60

    init {
        // Drawn once into its own layer; moving and turning it only re-composes that layer.
        setLayerType(LAYER_TYPE_HARDWARE, null)
        pivotX = ox
        pivotY = oy
        contentDescription = context.getString(R.string.tool_protractor)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((2 * (pad + radius)).toInt(), (oy + band + dp(CHIP_GAP_DP) + 2 * dp(CHIP_DP) + pad).toInt())
    }

    /** First placed in the middle of a [w]×[h] board, a little low. */
    fun placeIn(w: Int, h: Int) {
        translationX = w / 2f - ox
        translationY = h * 0.6f - oy
        rotation = 0f
    }

    // Chips under the band: turn (middle) and close (left).
    private val chipY get() = oy + band + dp(CHIP_GAP_DP) + dp(CHIP_DP)
    private val turnX get() = ox
    private val closeX get() = ox - radius + dp(CHIP_DP) + dp(6f)
    private fun knobAt(): Pair<Float, Float> {
        val a = Math.toRadians(angle.toDouble())
        val k = radius + dp(KNOB_OUT_DP)
        return (ox + cos(a) * k).toFloat() to (oy - sin(a) * k).toFloat()
    }

    private fun zoneAt(x: Float, y: Float): Zone {
        val (kx, ky) = knobAt()
        val grab = dp(26f)
        return when {
            hypot(x - kx, y - ky) <= grab -> Zone.ARM
            hypot(x - turnX, y - chipY) <= grab -> Zone.TURN
            hypot(x - closeX, y - chipY) <= grab -> Zone.CLOSE
            y <= oy + band && hypot(x - ox, y - oy) <= radius * PLATE -> Zone.MOVE
            else -> Zone.NONE
        }
    }

    private val inv = Matrix()
    private val pt = FloatArray(2)

    /** Whether a touch at ([x], [y]) in the board's coordinates lands where this tool is held. */
    fun hitsParent(x: Float, y: Float): Boolean {
        pt[0] = x - left
        pt[1] = y - top
        matrix.invert(inv)
        inv.mapPoints(pt)
        return zoneAt(pt[0], pt[1]) != Zone.NONE
    }

    override fun onDraw(canvas: Canvas) {
        // The sheet: a half disc over a narrow straight band.
        path.reset()
        r.set(ox - radius, oy - radius, ox + radius, oy + radius)
        path.arcTo(r, 180f, 180f)
        path.lineTo(ox + radius, oy + band)
        path.lineTo(ox - radius, oy + band)
        path.close()
        body.color = ColorUtils.setAlphaComponent(Chrome.surface, 0xD0)
        Chrome.shade(body, dp(10f), dp(3f))
        canvas.drawPath(path, body)
        edge.color = Chrome.hairline
        canvas.drawPath(path, edge)

        // Degree ticks, every degree; numbers every ten, both ways.
        tick.color = Chrome.icon
        outer.color = Chrome.text
        inner.color = Chrome.hint
        for (d in 0..180) {
            val a = Math.toRadians(d.toDouble())
            val c = cos(a).toFloat()
            val s = sin(a).toFloat()
            val len = dp(if (d % 10 == 0) 16f else if (d % 5 == 0) 10f else 6f)
            tick.strokeWidth = dp(if (d % 10 == 0) 1.4f else 1f)
            tick.alpha = if (d % 10 == 0) 220 else 140
            canvas.drawLine(ox + c * radius, oy - s * radius, ox + c * (radius - len), oy - s * (radius - len), tick)
            if (d % 10 == 0) {
                val ro = radius - dp(30f)
                canvas.drawText("$d", ox + c * ro, oy - s * ro - (outer.descent() + outer.ascent()) / 2, outer)
                // The inner scale (read from the left) leaves out its ends, which would sit on the baseline.
                if (d in 10..170) {
                    val ri = radius - dp(50f)
                    canvas.drawText("${180 - d}", ox + c * ri, oy - s * ri - (inner.descent() + inner.ascent()) / 2, inner)
                }
            }
        }
        // The baseline and the centre mark.
        tick.alpha = 255
        tick.strokeWidth = dp(1.2f)
        canvas.drawLine(ox - radius, oy, ox + radius, oy, tick)
        canvas.drawLine(ox, oy - dp(8f), ox, oy + dp(8f), tick)

        // The angle: a soft wedge, the zero ray and the arm out to its knob.
        val a = Math.toRadians(angle.toDouble())
        val w = radius * 0.34f
        r.set(ox - w, oy - w, ox + w, oy + w)
        canvas.drawArc(r, 0f, -angle.toFloat(), true, wedge)
        // The zero ray stops short of the scale, so its "0" stays readable.
        canvas.drawLine(ox, oy, ox + radius * 0.62f, oy, arm)
        val (kx, ky) = knobAt()
        canvas.drawLine(ox, oy, kx - (cos(a) * dp(13f)).toFloat(), ky + (sin(a) * dp(13f)).toFloat(), arm)
        drawChip(canvas, kx, ky, dp(13f))
        chip.color = ClockView.ACCENT
        canvas.drawCircle(kx, ky, dp(5f), chip)
        chip.color = Chrome.surface
        canvas.drawCircle(ox, oy, dp(4f), chip)
        edge.color = ClockView.ACCENT
        canvas.drawCircle(ox, oy, dp(4f), edge)

        // The angle in a pill, inside the plate it is moved by.
        val label = "$angle°"
        val tw = readout.measureText(label)
        val py = oy - radius * 0.3f
        // Always upright, however the protractor is turned.
        canvas.save()
        canvas.rotate(-rotation, ox, py)
        r.set(ox - tw / 2 - dp(14f), py - dp(19f), ox + tw / 2 + dp(14f), py + dp(19f))
        chip.color = Chrome.surface
        Chrome.shade(chip, dp(6f), dp(2f))
        canvas.drawRoundRect(r, dp(19f), dp(19f), chip)
        chip.clearShadowLayer()
        edge.color = Chrome.hairline
        canvas.drawRoundRect(r, dp(19f), dp(19f), edge)
        readout.color = Chrome.text
        canvas.drawText(label, ox, py - (readout.descent() + readout.ascent()) / 2, readout)
        canvas.restore()

        // Turn and close.
        drawChip(canvas, turnX, chipY, dp(CHIP_DP))
        glyph.color = Chrome.icon
        val t = dp(8f)
        r.set(turnX - t, chipY - t, turnX + t, chipY + t)
        canvas.drawArc(r, 200f, 250f, false, glyph)
        canvas.drawLine(turnX + t * 0.2f, chipY - t * 1.25f, turnX - t * 0.35f, chipY - t * 1.0f, glyph)
        drawChip(canvas, closeX, chipY, dp(CHIP_DP))
        val x = dp(6f)
        canvas.drawLine(closeX - x, chipY - x, closeX + x, chipY + x, glyph)
        canvas.drawLine(closeX + x, chipY - x, closeX - x, chipY + x, glyph)
    }

    private fun drawChip(canvas: Canvas, cx: Float, cy: Float, rad: Float) {
        chip.color = Chrome.surface
        Chrome.shade(chip, dp(6f), dp(2f))
        canvas.drawCircle(cx, cy, rad, chip)
        chip.clearShadowLayer()
        edge.color = Chrome.hairline
        canvas.drawCircle(cx, cy, rad - dp(0.5f), edge)
    }

    // region Touch

    private var zone = Zone.NONE
    private var lastX = 0f
    private var lastY = 0f
    private var turnFrom = 0f
    private var rotationFrom = 0f

    /** The centre point on screen, for turning around it. */
    private fun centreOnScreen(): Pair<Float, Float> {
        pt[0] = ox
        pt[1] = oy
        matrix.mapPoints(pt)
        val at = IntArray(2)
        (parent as? View)?.getLocationOnScreen(at)
        return (pt[0] + left + at[0]) to (pt[1] + top + at[1])
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                zone = zoneAt(e.x, e.y)
                if (zone == Zone.NONE) return false
                lastX = e.rawX
                lastY = e.rawY
                if (zone == Zone.TURN) {
                    val (cx, cy) = centreOnScreen()
                    turnFrom = Math.toDegrees(atan2((e.rawY - cy).toDouble(), (e.rawX - cx).toDouble())).toFloat()
                    rotationFrom = rotation
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> when (zone) {
                Zone.MOVE -> {
                    translationX += e.rawX - lastX
                    translationY += e.rawY - lastY
                    lastX = e.rawX
                    lastY = e.rawY
                }
                Zone.ARM -> {
                    val d = Math.toDegrees(atan2((oy - e.y).toDouble(), (e.x - ox).toDouble()))
                    // Below the baseline it stays at the nearer end.
                    val next = (if (d < -90) 180.0 else d.coerceIn(0.0, 180.0)).roundToInt()
                    if (next != angle) {
                        angle = next
                        invalidate()
                    }
                }
                Zone.TURN -> {
                    val (cx, cy) = centreOnScreen()
                    val now = Math.toDegrees(atan2((e.rawY - cy).toDouble(), (e.rawX - cx).toDouble())).toFloat()
                    var rot = rotationFrom + (now - turnFrom)
                    // It settles on the usual angles (every 15°) when close to one.
                    val near = (rot / 15f).roundToInt() * 15f
                    if (abs(rot - near) < SNAP_DEG) rot = near
                    if (rot != rotation) {
                        rotation = rot
                        invalidate() // the angle's pill turns back upright
                    }
                }
                else -> Unit
            }
            MotionEvent.ACTION_UP -> {
                if (zone == Zone.CLOSE && zoneAt(e.x, e.y) == Zone.CLOSE) onClose()
                zone = Zone.NONE
            }
            MotionEvent.ACTION_CANCEL -> zone = Zone.NONE
        }
        return true
    }

    // endregion

    private companion object {
        const val RADIUS_DP = 230f
        const val BAND_DP = 26f
        const val PAD_DP = 40f
        const val CHIP_DP = 20f
        const val CHIP_GAP_DP = 8f
        const val KNOB_OUT_DP = 22f
        /** The plate it is moved by: the inner part of the half disc (the scale stays free to write on). */
        const val PLATE = 0.55f
        const val SNAP_DEG = 3f
    }
}
