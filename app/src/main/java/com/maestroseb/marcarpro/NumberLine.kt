package com.maestroseb.marcarpro

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin


/**
 * The number line (the user's approved mockup): a strip in the controls' material with an arrowed
 * line, its marks and numbers.
 *  - One finger (or the pen) from one number to another: a jump. Dashed while choosing, with its
 *    value; on letting go a ball hops along the arc and "+3" (teal, forwards) or "−6" (orange,
 *    backwards) comes on landing, and the number landed on gives a little hop. Jumps that cross
 *    others climb until their labels are clear; labels are drawn over every line.
 *  - A tap on a number hides it ("?", for "which number is missing?"); another tap shows it.
 *  - Two fingers: spread to come closer (units, halves, tenths), pinch to go farther (tens,
 *    hundreds), move together to slide, and let go with a flick to glide on. Marks are at 1, 2 or
 *    5 × 10^k, numbers at least [LABEL_GAP_DP] apart. Jumps and hidden numbers keep their number.
 *  - Its chips: Options (from 0 / with negatives, whole numbers / decimals), 0 (back to zero,
 *    same zoom), ▶ (the jumps again one by one, to count them aloud), ✕.
 *  - A finger on the strip's lower band moves it.
 */
@SuppressLint("ViewConstructor")
internal class NumberLineView(context: Context, private val onClose: () -> Unit) : View(context), MathTool {
    override val view: View get() = this

    private val stripW = (resources.displayMetrics.widthPixels * WIDTH_SHARE).coerceAtMost(dp(MAX_WIDTH_DP))
    private val pad = dp(16f)
    private val stripTop = pad + dp(ABOVE_DP)
    private val stripBottom = stripTop + dp(STRIP_DP)
    private val left0 = pad
    private val right0 = pad + stripW
    private val lineY = stripTop + dp(40f)
    private val arcY = lineY - dp(3f)
    private val chipY = stripBottom - dp(30f)
    private val x0 = left0 + dp(30f)
    private val x1 = right0 - dp(40f)

    // The view's values: [lo] at x0, [hi] at x1.
    private var lo = 0.0
    private var hi = 10.0
    private var negatives = false
    private var decimals = false
    private var major = 1.0
    private var minor = 1.0

    private class Jump(val a: Double, val b: Double, var t0: Long, var landed: Boolean) {
        var up = 0f
    }

    private val jumps = ArrayList<Jump>()
    private val hidden = HashSet<Long>()
    private val pops = HashMap<Long, Long>()

    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.2f) }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val numbers = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = context.sp(17f); typeface = Fonts.bold(context); textAlign = Paint.Align.CENTER; fontFeatureSettings = "tnum"
    }
    private val chipText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = context.sp(14f); typeface = Fonts.bold(context); textAlign = Paint.Align.CENTER
    }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = context.sp(13f); typeface = Fonts.regular(context); textAlign = Paint.Align.CENTER }
    private val dash = android.graphics.DashPathEffect(floatArrayOf(dp(7f), dp(7f)), 0f)
    private val dots = android.graphics.DashPathEffect(floatArrayOf(dp(3f), dp(3f)), 0f)
    private val path = Path()
    private val r = RectF()

    private val optionsLabel = context.getString(R.string.numberline_options)
    private val hintText = context.getString(R.string.numberline_hint)
    private val choices = listOf(
        listOf(context.getString(R.string.numberline_from_zero) to (Choice.NEGATIVES to false), context.getString(R.string.numberline_negatives) to (Choice.NEGATIVES to true)),
        listOf(context.getString(R.string.numberline_whole) to (Choice.DECIMALS to false), context.getString(R.string.numberline_decimals) to (Choice.DECIMALS to true)),
    )

    private enum class Choice { NEGATIVES, DECIMALS }

    init {
        contentDescription = context.getString(R.string.tool_numberline)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((stripW + 2 * pad).toInt(), (stripBottom + pad).toInt())
    }

    override fun placeIn(w: Int, h: Int) {
        translationX = (w - measuredWidth) / 2f
        translationY = h * 0.55f - stripTop
    }

    // region Scale

    private val per get() = (x1 - x0) / (hi - lo)
    private fun xOf(v: Double) = x0 + ((v - lo) * per).toFloat()
    private fun vOf(x: Float) = lo + (x - x0) / per
    private fun clean(v: Double) = (v * 1e9).roundToLong() / 1e9
    private fun key(v: Double) = (v * 1000).roundToLong()
    private fun isMajor(v: Double) = abs(v / major - (v / major).roundToLong()) < 1e-6

    // Numbered marks at 1, 2 or 5 × 10^k, never closer than LABEL_GAP_DP; small marks only at round
    // values (halves of a 2, fifths of a 5), so a jump never lands on fifths of a unit.
    private fun steps() {
        val raw = dp(LABEL_GAP_DP) / per
        val e = 10.0.pow(floor(log10(raw)))
        major = listOf(1.0, 2.0, 5.0, 10.0).map { it * e }.first { it >= raw * 0.999 }
        val mant = (major / 10.0.pow(floor(log10(major) + 1e-9))).roundToInt()
        val sub = when (mant) { 2 -> major / 2; 5 -> major / 5; else -> major }
        minor = if (sub * per >= dp(9f)) sub else major
    }

    private fun snap(x: Float) = clean((vOf(x) / minor).roundToLong() * minor)

    private fun fmt(v: Double): String {
        val r = abs(v)
        val s = BigDecimal(r).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString().replace('.', ',')
        return if (v < -1e-9) "−$s" else s
    }

    private fun jumpText(a: Double, b: Double) = (if (b > a) "+" else "−") + fmt(abs(b - a))

    // Whole numbers only: no closer than a numbered mark every unit (a unit narrower than 1.9 gaps,
    // or halves would be numbered).
    private fun minSpan() = if (decimals) MIN_SPAN else ((x1 - x0) / (dp(LABEL_GAP_DP) * 1.9f)).toDouble()

    /** The view kept inside its limits: no closer than whole numbers allow, not left of 0 from 0. */
    private fun limit(a: Double, b: Double): Pair<Double, Double> {
        var na = a
        var nb = b
        val span = (nb - na).coerceIn(minSpan(), MAX_SPAN)
        if (span != nb - na) {
            val m = (na + nb) / 2
            na = m - span / 2
            nb = m + span / 2
        }
        if (!negatives && na < 0) {
            nb -= na
            na = 0.0
        }
        return na to nb
    }

    /** Where 0 takes you, at the same zoom: on the left from 0, in the middle with negatives. */
    private fun home(): Pair<Double, Double> {
        val span = hi - lo
        return if (negatives) -span / 2 to span / 2 else 0.0 to span
    }

    // A slide to another view (0, a new choice), and a glide after a flick.
    private var animFrom = 0.0 to 0.0
    private var animTo = 0.0 to 0.0
    private var animStart = -1L
    private var flingV = 0f // px per ms
    private var flingLast = 0L

    private fun goTo(a: Double, b: Double) {
        animFrom = lo to hi
        animTo = limit(a, b)
        animStart = SystemClock.uptimeMillis()
        flingV = 0f
        invalidate()
    }

    // endregion

    // region Drawing

    private fun ease(t: Float) = 1f - (1f - t).pow(3)

    private fun arcPoint(xa: Float, xb: Float, up: Float, t: Float, out: FloatArray) {
        val mx = (xa + xb) / 2
        val cy = arcY - 2 * up
        val u = 1 - t
        out[0] = u * u * xa + 2 * u * t * mx + t * t * xb
        out[1] = u * u * arcY + 2 * u * t * cy + t * t * arcY
    }

    private val pt = FloatArray(2)

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        var busy = false
        if (flingV != 0f) {
            val dt = min(50L, now - flingLast).toFloat()
            flingLast = now
            val dv = -flingV * dt / per
            val (a, b) = limit(lo + dv, hi + dv)
            if (abs(a - (lo + dv)) > 1e-9) flingV = 0f
            lo = a
            hi = b
            flingV *= exp(-dt / FLING_TAU_MS)
            if (abs(flingV) < 0.02f) flingV = 0f else busy = true
        }
        if (animStart >= 0) {
            val p = min(1f, (now - animStart) / SLIDE_MS)
            val q = ease(p).toDouble()
            lo = animFrom.first + (animTo.first - animFrom.first) * q
            hi = animFrom.second + (animTo.second - animFrom.second) * q
            if (p < 1f) busy = true else animStart = -1
        }
        steps()

        // The strip.
        r.set(left0, stripTop, right0, stripBottom)
        body.color = ColorUtils.setAlphaComponent(Chrome.surface, 0xD8)
        Chrome.shade(body, dp(10f), dp(3f))
        canvas.drawRoundRect(r, dp(12f), dp(12f), body)
        body.clearShadowLayer()
        edge.color = Chrome.hairline
        canvas.drawRoundRect(r, dp(12f), dp(12f), edge)

        canvas.save()
        canvas.clipRect(left0 + dp(2f), 0f, right0 - dp(2f), height.toFloat())
        // The line and its arrow.
        line.color = Chrome.icon
        line.strokeWidth = dp(2.5f)
        canvas.drawLine(left0 + dp(8f), lineY, x1 + dp(16f), lineY, line)
        path.rewind()
        path.moveTo(x1 + dp(26f), lineY)
        path.lineTo(x1 + dp(13f), lineY - dp(7f))
        path.lineTo(x1 + dp(13f), lineY + dp(7f))
        path.close()
        fill.color = Chrome.icon
        canvas.drawPath(path, fill)

        // Marks and numbers.
        numbers.color = Chrome.text
        val k0 = ceil(lo / minor - 1e-9).toLong()
        val k1 = floor(hi / minor + 1e-9).toLong()
        val textY = lineY + dp(30f) - (numbers.descent() + numbers.ascent()) / 2
        for (k in k0..k1) {
            val v = clean(k * minor)
            val x = xOf(v)
            val big = isMajor(v)
            line.strokeWidth = dp(if (big) 2f else 1.2f)
            val h = dp(if (big) 11f else 6f)
            canvas.drawLine(x, lineY - h, x, lineY + h, line)
            if (!big) continue
            val kv = key(v)
            if (kv in hidden) {
                r.set(x - dp(17f), lineY + dp(17f), x + dp(17f), lineY + dp(43f))
                stroke.color = OrbStyle.TEAL
                stroke.strokeWidth = dp(1.6f)
                stroke.pathEffect = dots
                canvas.drawRoundRect(r, dp(8f), dp(8f), stroke)
                stroke.pathEffect = null
                numbers.color = OrbStyle.TEAL
                canvas.drawText("?", x, textY, numbers)
                numbers.color = Chrome.text
                continue
            }
            var s = 1f
            pops[kv]?.let { t ->
                val p = (now - t) / POP_MS
                if (p < 1f) { busy = true; s = 1f + 0.45f * sin(PI.toFloat() * p) } else pops.remove(kv)
            }
            numbers.color = if (v == 0.0) OrbStyle.TEAL else Chrome.text
            canvas.save()
            canvas.scale(s, s, x, lineY + dp(30f))
            canvas.drawText(fmt(v), x, textY, numbers)
            canvas.restore()
        }

        // Jumps: heights first (each takes the lowest arc whose label is clear of the others and
        // whose top is clear of any arc it crosses), then the arcs, then the labels over everything.
        val all = ArrayList<Jump>(jumps)
        val preview = dragFrom?.let { a -> dragTo.takeIf { it != a }?.let { Jump(a, it, Long.MIN_VALUE, true) } }
        if (preview != null) all.add(preview)
        heights(all)
        val labels = ArrayList<() -> Unit>()
        for (j in all) {
            val p = if (j === preview) 1f else (now - j.t0) / HOP_MS
            if (p < 0f) { busy = true; continue }
            val xa = xOf(j.a)
            val xb = xOf(j.b)
            val col = if (j.b > j.a) OrbStyle.TEAL else WARM
            val text = jumpText(j.a, j.b)
            if (j === preview) {
                stroke.color = ColorUtils.setAlphaComponent(col, 0x99)
                stroke.strokeWidth = dp(2.5f)
                stroke.pathEffect = dash
                canvas.drawPath(arcPath(xa, xb, j.up, 1f), stroke)
                stroke.pathEffect = null
                labels.add { label(canvas, text, (xa + xb) / 2, arcY - j.up - dp(4f), col, 1f, dashed = true) }
                continue
            }
            val q = ease(min(1f, p))
            val landedNow = p >= 1f
            // Landed, the line stops where the arrowhead begins (its round end never pokes through).
            val tEnd = if (landedNow) max(0.5f, 1f - dp(ARROW_L_DP) * 0.75f / arcLength(xa, xb, j.up)) else q
            stroke.color = col
            stroke.strokeWidth = dp(3f)
            canvas.drawPath(arcPath(xa, xb, j.up, tEnd), stroke)
            if (!landedNow) {
                busy = true
                arcPoint(xa, xb, j.up, q, pt)
                fill.color = col
                canvas.drawCircle(pt[0], pt[1], dp(6.5f), fill)
                fill.color = 0xFFFFFFFF.toInt()
                canvas.drawCircle(pt[0] - dp(2f), pt[1] - dp(2f), dp(2f), fill)
                continue
            }
            if (!j.landed) {
                j.landed = true
                pops[key(j.b)] = now
                busy = true
            }
            arrow(canvas, xa, xb, j.up, col)
            val lp = min(1f, (now - j.t0 - HOP_MS) / POP_MS)
            if (lp < 1f) busy = true
            val ls = 0.6f + 0.4f * ease(max(0f, lp))
            labels.add { label(canvas, text, (xa + xb) / 2, arcY - j.up - dp(4f), col, ls, dashed = false) }
        }
        for (l in labels) l()
        canvas.restore()

        // Chips: Options at the left; 0, ▶ and ✕ at the right; the hint between them if there is room.
        chipRects.clear()
        val optW = chipText.measureText("$optionsLabel  ▾") + dp(30f)
        chip(canvas, left0 + dp(14f), chipY, optW, "$optionsLabel  ▾", menu, Hit.OPTIONS)
        val closeX = right0 - dp(14f) - dp(40f)
        val playX = closeX - dp(8f) - dp(48f)
        val zeroX = playX - dp(8f) - dp(48f)
        chip(canvas, zeroX, chipY, dp(48f), "0", false, Hit.ZERO)
        chip(canvas, playX, chipY, dp(48f), "", false, Hit.PLAY)
        playGlyph(canvas, playX + dp(24f), chipY)
        chip(canvas, closeX, chipY, dp(40f), "", false, Hit.CLOSE)
        closeGlyph(canvas, closeX + dp(20f), chipY)
        val hintRoom = zeroX - (left0 + dp(14f) + optW)
        if (hintRoom > hint.measureText(hintText) + dp(24f)) {
            hint.color = Chrome.hint
            canvas.drawText(hintText, left0 + dp(14f) + optW + hintRoom / 2, chipY - (hint.descent() + hint.ascent()) / 2, hint)
        }
        if (menu) drawMenu(canvas)
        if (busy) postInvalidateOnAnimation()
    }

    private fun heights(all: List<Jump>) {
        class Placed(val l: Float, val r: Float, val m: Float, val lw: Float, val up: Float, val y: Float)
        val placed = ArrayList<Placed>()
        for (j in all) {
            val xa = xOf(j.a)
            val xb = xOf(j.b)
            val l = min(xa, xb)
            val rr = max(xa, xb)
            val m = (xa + xb) / 2
            val lw = numbers.measureText(jumpText(j.a, j.b)) + dp(18f)
            val base = min(dp(64f), (rr - l) * 0.42f + dp(20f))
            var up = base
            for (k in 0..MAX_LANE) {
                up = base + k * dp(LANE_DP)
                val y = arcY - up
                val ok = placed.all { h ->
                    val apart = abs(m - h.m) > (lw + h.lw) / 2 + dp(8f) || abs(y - h.y) > dp(30f)
                    val crosses = min(rr, h.r) - max(l, h.l) > dp(2f)
                    apart && (!crosses || abs(up - h.up) >= dp(20f))
                }
                if (ok) break
            }
            j.up = up
            placed.add(Placed(l, rr, m, lw, up, arcY - up))
        }
    }

    private fun arcPath(xa: Float, xb: Float, up: Float, tEnd: Float): Path {
        path.rewind()
        for (s in 0..ARC_STEPS) {
            arcPoint(xa, xb, up, tEnd * s / ARC_STEPS, pt)
            if (s == 0) path.moveTo(pt[0], pt[1]) else path.lineTo(pt[0], pt[1])
        }
        return path
    }

    private fun arcLength(xa: Float, xb: Float, up: Float): Float {
        var len = 0f
        arcPoint(xa, xb, up, 0f, pt)
        var lx = pt[0]
        var ly = pt[1]
        for (s in 1..ARC_STEPS) {
            arcPoint(xa, xb, up, s.toFloat() / ARC_STEPS, pt)
            len += hypot(pt[0] - lx, pt[1] - ly)
            lx = pt[0]
            ly = pt[1]
        }
        return len.coerceAtLeast(1f)
    }

    /** The arrowhead along the arc's own direction where it lands: a dart with a notch at its back. */
    private fun arrow(canvas: Canvas, xa: Float, xb: Float, up: Float, col: Int) {
        val mx = (xa + xb) / 2
        val cy = arcY - 2 * up
        var ux = xb - mx
        var uy = arcY - cy
        val l = hypot(ux, uy).coerceAtLeast(1f)
        ux /= l
        uy /= l
        val nx = -uy
        val ny = ux
        val bx = xb - ux * dp(ARROW_L_DP)
        val by = arcY - uy * dp(ARROW_L_DP)
        val w = dp(ARROW_W_DP)
        path.rewind()
        path.moveTo(xb, arcY)
        path.lineTo(bx + nx * w, by + ny * w)
        path.lineTo(bx + ux * dp(3.5f), by + uy * dp(3.5f))
        path.lineTo(bx - nx * w, by - ny * w)
        path.close()
        fill.color = col
        canvas.drawPath(path, fill)
    }

    private fun label(canvas: Canvas, text: String, cx: Float, cy: Float, col: Int, scale: Float, dashed: Boolean) {
        val w = numbers.measureText(text) + dp(18f)
        canvas.save()
        canvas.scale(scale, scale, cx, cy)
        r.set(cx - w / 2, cy - dp(15f), cx + w / 2, cy + dp(15f))
        fill.color = Chrome.surface
        canvas.drawRoundRect(r, dp(15f), dp(15f), fill)
        stroke.color = col
        stroke.strokeWidth = dp(1.6f)
        if (dashed) stroke.pathEffect = dots
        canvas.drawRoundRect(r, dp(15f), dp(15f), stroke)
        stroke.pathEffect = null
        numbers.color = col
        canvas.drawText(text, cx, cy - (numbers.descent() + numbers.ascent()) / 2, numbers)
        numbers.color = Chrome.text
        canvas.restore()
    }

    // endregion

    // region Chips and the options

    private enum class Hit { OPTIONS, ZERO, PLAY, CLOSE, CHOICE }

    private class ChipRect(val rect: RectF, val hit: Hit, val choice: Pair<Choice, Boolean>? = null)

    private val chipRects = ArrayList<ChipRect>()
    private var menu = false

    private fun chip(canvas: Canvas, x: Float, cy: Float, w: Float, text: String, on: Boolean, hit: Hit, choice: Pair<Choice, Boolean>? = null) {
        val rr = RectF(x, cy - dp(17f), x + w, cy + dp(17f))
        fill.color = if (on) OrbStyle.TEAL else Chrome.surface
        Chrome.shade(fill, dp(5f), dp(1.5f))
        canvas.drawRoundRect(rr, dp(17f), dp(17f), fill)
        fill.clearShadowLayer()
        if (!on) canvas.drawRoundRect(rr, dp(17f), dp(17f), edge)
        if (text.isNotEmpty()) {
            chipText.color = if (on) 0xFFFFFFFF.toInt() else Chrome.text
            canvas.drawText(text, rr.centerX(), cy - (chipText.descent() + chipText.ascent()) / 2, chipText)
        }
        chipRects.add(ChipRect(rr, hit, choice))
    }

    private fun playGlyph(canvas: Canvas, cx: Float, cy: Float) {
        path.rewind()
        path.moveTo(cx - dp(5f), cy - dp(7f))
        path.lineTo(cx + dp(7f), cy)
        path.lineTo(cx - dp(5f), cy + dp(7f))
        path.close()
        fill.color = Chrome.icon
        canvas.drawPath(path, fill)
    }

    private fun closeGlyph(canvas: Canvas, cx: Float, cy: Float) {
        stroke.color = Chrome.icon
        stroke.strokeWidth = dp(2f)
        val g = dp(5.5f)
        canvas.drawLine(cx - g, cy - g, cx + g, cy + g, stroke)
        canvas.drawLine(cx + g, cy - g, cx - g, cy + g, stroke)
    }

    /** The options, in a card above the strip: two switches of two, the chosen one in teal. */
    private fun drawMenu(canvas: Canvas) {
        val gap = dp(6f)
        val rowsW = choices.map { row -> row.sumOf { (chipText.measureText(it.first) + dp(30f)).toDouble() }.toFloat() + gap }
        val cw = rowsW.max() + dp(24f)
        val top = stripTop - dp(118f)
        r.set(left0 + dp(10f), top, left0 + dp(10f) + cw, top + dp(108f))
        fill.color = Chrome.surface
        Chrome.shade(fill, dp(10f), dp(3f))
        canvas.drawRoundRect(r, dp(16f), dp(16f), fill)
        fill.clearShadowLayer()
        canvas.drawRoundRect(r, dp(16f), dp(16f), edge)
        choices.forEachIndexed { i, row ->
            var x = left0 + dp(22f)
            val y = top + dp(32f) + i * dp(44f)
            for ((text, choice) in row) {
                val w = chipText.measureText(text) + dp(30f)
                val on = (if (choice.first == Choice.NEGATIVES) negatives else decimals) == choice.second
                chip(canvas, x, y, w, text, on, Hit.CHOICE, choice)
                x += w + gap
            }
        }
    }

    // endregion

    // region Touch

    override var grabNext = false
    private enum class Zone { NONE, MOVE, JUMP, CHIP }

    private var zone = Zone.NONE
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var chipDown: ChipRect? = null
    private var dragFrom: Double? = null
    private var dragTo = 0.0
    // Two fingers: the numbers each took, and the middle's speed for a flick.
    private var pinching = false
    private var pinchA = 0.0
    private var pinchB = 0.0
    private var pinchMid = 0f
    private var pinchT = 0L
    private var pinchV = 0f

    private fun local(x: Float, y: Float) = (x - this.x) to (y - this.y)
    private fun chipAt(x: Float, y: Float) = chipRects.lastOrNull { it.rect.contains(x, y) }
    private fun onStrip(x: Float, y: Float) = x in left0..right0 && y in stripTop..stripBottom
    private fun onLine(x: Float, y: Float) = x in (x0 - dp(24f))..(x1 + dp(24f)) && y in (lineY - dp(58f))..(lineY + dp(46f))
    private fun onMenu(x: Float, y: Float) = menu && x in left0..(left0 + dp(400f)) && y in (stripTop - dp(118f))..stripTop

    private fun zoneAt(x: Float, y: Float): Zone = when {
        chipAt(x, y) != null -> Zone.CHIP
        onMenu(x, y) -> Zone.CHIP
        onLine(x, y) -> Zone.JUMP
        onStrip(x, y) -> Zone.MOVE
        else -> Zone.NONE
    }

    override fun hitsParent(x: Float, y: Float): Boolean {
        val (lx, ly) = local(x, y)
        return zoneAt(lx, ly) != Zone.NONE
    }

    override fun onSheet(x: Float, y: Float): Boolean {
        val (lx, ly) = local(x, y)
        return onStrip(lx, ly) || onLine(lx, ly)
    }

    override fun guideFor(x: Float, y: Float): ((Float, Float, FloatArray) -> Unit)? = null

    private fun beginPinch(ax: Float, bx: Float) {
        if (abs(bx - ax) < dp(20f)) return
        pinching = true
        dragFrom = null
        menu = false
        flingV = 0f
        animStart = -1
        pinchA = vOf(ax)
        pinchB = vOf(bx)
        pinchMid = (ax + bx) / 2
        pinchT = SystemClock.uptimeMillis()
        pinchV = 0f
        invalidate()
    }

    private fun movePinch(ax: Float, bx: Float) {
        if (!pinching || abs(bx - ax) < dp(20f)) return
        // Each finger keeps the number it took: spread = closer, together = farther, both = slide.
        val p = (bx - ax) / (pinchB - pinchA)
        if (p <= 0) return
        val nlo = pinchA - (ax - x0) / p
        val nhi = nlo + (x1 - x0) / p
        if (nhi - nlo < minSpan() || nhi - nlo > MAX_SPAN) return
        val (a, b) = limit(nlo, nhi)
        lo = a
        hi = b
        val m = (ax + bx) / 2
        val t = SystemClock.uptimeMillis()
        val dt = (t - pinchT).toFloat()
        if (dt > 0) {
            pinchV = 0.7f * ((m - pinchMid) / dt) + 0.3f * pinchV
            pinchMid = m
            pinchT = t
        }
        invalidate()
    }

    private fun endPinch() {
        if (!pinching) return
        pinching = false
        val now = SystemClock.uptimeMillis()
        if (abs(pinchV) > FLING_MIN && now - pinchT < 80) {
            flingV = pinchV
            flingLast = now
            invalidate()
        }
    }

    override fun pinchStart(ax: Float, ay: Float, bx: Float, by: Float) = beginPinch(ax - x, bx - x)
    override fun pinchMove(ax: Float, ay: Float, bx: Float, by: Float) = movePinch(ax - x, bx - x)
    override fun pinchEnd() = endPinch()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                flingV = 0f
                zone = zoneAt(e.x, e.y)
                if (zone == Zone.NONE && grabNext) zone = Zone.MOVE
                grabNext = false
                pinching = false
                if (zone == Zone.NONE) {
                    if (menu) { menu = false; invalidate() }
                    return false
                }
                lastX = e.rawX
                lastY = e.rawY
                downX = e.x
                when (zone) {
                    Zone.CHIP -> chipDown = chipAt(e.x, e.y)
                    Zone.JUMP -> {
                        if (menu) menu = false
                        val v = snap(e.x)
                        dragFrom = v
                        dragTo = v
                    }
                    else -> if (menu) menu = false
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2 && zone != Zone.CHIP) {
                beginPinch(e.getX(0), e.getX(1))
            }
            MotionEvent.ACTION_MOVE -> when {
                pinching && e.pointerCount >= 2 -> movePinch(e.getX(0), e.getX(1))
                zone == Zone.MOVE && !pinching -> {
                    translationX += e.rawX - lastX
                    translationY += e.rawY - lastY
                    lastX = e.rawX
                    lastY = e.rawY
                }
                zone == Zone.JUMP && dragFrom != null -> {
                    val v = snap(e.x)
                    if (v != dragTo) {
                        dragTo = v
                        invalidate()
                    }
                }
                else -> Unit
            }
            MotionEvent.ACTION_POINTER_UP -> if (pinching) {
                endPinch()
                zone = Zone.NONE
            }
            MotionEvent.ACTION_UP -> {
                endPinch()
                when (zone) {
                    Zone.CHIP -> chipDown?.takeIf { chipAt(e.x, e.y) === it }?.let { tap(it) }
                    Zone.JUMP -> {
                        val a = dragFrom
                        if (a != null) {
                            if (a == dragTo && abs(e.x - downX) < dp(12f)) {
                                if (isMajor(a)) {
                                    val k = key(a)
                                    if (!hidden.remove(k)) hidden.add(k) else pops[k] = SystemClock.uptimeMillis()
                                }
                            } else if (a != dragTo) {
                                jumps.add(Jump(a, dragTo, SystemClock.uptimeMillis(), false))
                            }
                        }
                    }
                    else -> Unit
                }
                dragFrom = null
                chipDown = null
                zone = Zone.NONE
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                endPinch()
                dragFrom = null
                chipDown = null
                zone = Zone.NONE
                invalidate()
            }
        }
        return true
    }

    private fun tap(c: ChipRect) {
        when (c.hit) {
            Hit.OPTIONS -> menu = !menu
            Hit.ZERO -> { menu = false; val (a, b) = home(); goTo(a, b) }
            Hit.PLAY -> {
                menu = false
                val t = SystemClock.uptimeMillis()
                jumps.forEachIndexed { i, j -> j.t0 = t + i * (HOP_MS + COUNT_PAUSE_MS).toLong(); j.landed = false }
            }
            Hit.CLOSE -> { menu = false; onClose() }
            Hit.CHOICE -> {
                val (what, on) = c.choice ?: return
                menu = false
                if (what == Choice.NEGATIVES) {
                    negatives = on
                    val (a, b) = home()
                    goTo(a, b)
                } else {
                    decimals = on
                    goTo(lo, hi)
                }
            }
        }
        invalidate()
    }

    // endregion

    private companion object {
        const val WIDTH_SHARE = 0.72f
        const val MAX_WIDTH_DP = 1400f
        const val ABOVE_DP = 170f
        const val STRIP_DP = 142f
        const val LABEL_GAP_DP = 52f
        const val MIN_SPAN = 0.5
        const val MAX_SPAN = 2000.0
        const val HOP_MS = 420f
        const val POP_MS = 220f
        const val COUNT_PAUSE_MS = 260f
        const val SLIDE_MS = 320f
        const val FLING_TAU_MS = 380f
        const val FLING_MIN = 0.35f
        const val ARROW_L_DP = 14f
        const val ARROW_W_DP = 7f
        const val LANE_DP = 28f
        const val MAX_LANE = 3
        const val ARC_STEPS = 28
        val WARM = 0xFFD9822B.toInt()
    }
}
