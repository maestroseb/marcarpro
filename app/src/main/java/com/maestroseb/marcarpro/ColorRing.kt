package com.maestroseb.marcarpro

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SweepGradient
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withTranslation
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * The colours, around the main orb: opened by tapping the orb's colour dot (then a tap picks),
 * or by pressing the dot and sliding onto a colour (letting go there picks it). They sit on a
 * ring; near an edge they fan out towards the middle of the screen, on as many arcs as they need.
 * The thickness stays in the tools' card (it changes far less often).
 *
 * Its own window, centred on the orb and under it; it takes touches only while open.
 */
@SuppressLint("ViewConstructor")
internal class ColorRingView(context: Context, private val onItem: (Item) -> Unit) : View(context) {

    sealed interface Item {
        class Ink(val color: Int) : Item
        data object Add : Item
    }

    private class Spot(val item: Item, val x: Float, val y: Float, val r: Float, val delay: Float)

    private var spots = emptyList<Spot>()
    private var current = Color.BLACK
    private var hovered: Spot? = null
    private var pressed: Spot? = null
    private var progress = 0f
    private var animator: ValueAnimator? = null

    var isOpen = false
        private set

    /** Called when a tap lands on empty space inside the open ring. */
    var onEmptyTap: (() -> Unit)? = null

    /** Called with the screen position of a touch outside this window while it is open. */
    var onOutside: ((x: Float, y: Float) -> Unit)? = null

    private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { setShadowLayer(dp(6f), 0f, dp(2f), OrbStyle.SHADOW) }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(3f); color = Color.WHITE }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1f); color = 0x33000000 }
    private val chosen = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(3f) }
    private val rainbow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(4f) }
    private val plus = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        strokeCap = Paint.Cap.ROUND
        color = OrbStyle.INK
    }
    private val band = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x1F3E8784 }

    private val colorR = dp(20f)
    private var bandRadius = 0f

    /** Window size: room for the widest fan, whatever the number of colours. */
    val openSize = (2 * (RADII.last() * resources.displayMetrics.density + colorR + dp(14f))).toInt()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /**
     * Opens around the orb, whose centre is at ([cx], [cy]) on a [sw]×[sh] screen (the window
     * must already be centred there), with [colors] (the last one chosen being [color]).
     */
    fun open(cx: Int, cy: Int, sw: Int, sh: Int, colors: List<Int>, color: Int) {
        current = color
        hovered = null
        pressed = null
        spots = layout(cx.toFloat(), cy.toFloat(), sw.toFloat(), sh.toFloat(), colors.map { Item.Ink(it) } + Item.Add)
        spots.firstOrNull { it.item == Item.Add }?.let { a ->
            val hues = IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 0.85f, 0.95f)) }
            rainbow.shader = SweepGradient(a.x, a.y, hues, null)
        }
        isOpen = true
        animateTo(1f, OPEN_MS)
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        hovered = null
        animateTo(0f, CLOSE_MS)
    }

    /** The item at a screen position, or null. */
    fun itemAt(rawX: Float, rawY: Float): Item? {
        getLocationOnScreen(origin)
        return spotAt(rawX - origin[0], rawY - origin[1])?.item
    }

    /** While sliding from the orb: grows the item under the finger. */
    fun hover(rawX: Float, rawY: Float) {
        getLocationOnScreen(origin)
        val s = spotAt(rawX - origin[0], rawY - origin[1])
        if (s === hovered) return
        hovered = s
        if (s != null) performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        invalidate()
    }

    private val origin = IntArray(2)

    private fun spotAt(x: Float, y: Float): Spot? {
        if (!isOpen) return null
        // Generous: anything within the nearest item's reach (a finger on the IR board is wide).
        val best = spots.minByOrNull { hypot(x - it.x, y - it.y) } ?: return null
        return if (hypot(x - best.x, y - best.y) <= best.r + dp(12f)) best else null
    }

    /** Places the colours around the orb at ([cx], [cy]), in window coordinates. */
    private fun layout(cx: Float, cy: Float, sw: Float, sh: Float, colors: List<Item>): List<Spot> {
        val ox = openSize / 2f
        val oy = openSize / 2f
        val d = resources.displayMetrics.density
        val margin = colorR + dp(8f)
        val out = ArrayList<Spot>()
        val n = colors.size
        // A full ring when it fits around the orb.
        val ringR = max(RADII[0] * d, n * dp(SPACING_DP) / (2 * PI).toFloat())
        val fits = cx - ringR - margin > 0 && cx + ringR + margin < sw && cy - ringR - margin > 0 && cy + ringR + margin < sh
        if (fits) {
            for (i in 0 until n) {
                val a = -PI / 2 + i * 2 * PI / n
                out += Spot(colors[i], ox + (cos(a) * ringR).toFloat(), oy + (sin(a) * ringR).toFloat(), colorR, i / n.toFloat())
            }
            bandRadius = ringR
        } else {
            // Fan out towards the middle of the screen, on as many arcs as the colours need.
            val toCentre = atan2((sh / 2 - cy).toDouble(), (sw / 2 - cx).toDouble())
            val rows = ArrayList<Pair<Float, Int>>()
            var left = n
            for (r in RADII) {
                if (left == 0) break
                val cap = floor(FAN_SPAN * r * d / dp(SPACING_DP)).toInt() + 1
                val k = minOf(cap, left)
                rows += r * d to k
                left -= k
            }
            // Neighbours keep the same distance apart on every arc, centred on the fan's direction.
            val angleOf = { base: Double, r: Float, k: Int, i: Int -> base + (i - (k - 1) / 2.0) * dp(SPACING_DP) / r }
            val onScreen = { base: Double ->
                rows.all { (r, k) ->
                    (0 until k).all { i ->
                        val a = angleOf(base, r, k, i)
                        val x = cx + cos(a) * r
                        val y = cy + sin(a) * r
                        x in margin..(sw - margin) && y in margin..(sh - margin)
                    }
                }
            }
            val base = FAN_TURNS.map { toCentre + it }.firstOrNull(onScreen) ?: toCentre
            var idx = 0
            rows.forEachIndexed { row, (r, k) ->
                for (i in 0 until k) {
                    val a = angleOf(base, r, k, i)
                    out += Spot(colors[idx], ox + (cos(a) * r).toFloat(), oy + (sin(a) * r).toFloat(), colorR, (row * 4 + i) / (n + 4f))
                    idx++
                }
            }
            bandRadius = 0f
        }
        return out
    }

    private fun animateTo(target: Float, ms: Long) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(progress, target).apply {
            duration = ms
            interpolator = EASE
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (progress <= 0f) return
        val ox = width / 2f
        val oy = height / 2f
        if (bandRadius > 0f) {
            band.strokeWidth = colorR * 2 + dp(12f)
            band.alpha = (0x1F * progress).toInt()
            canvas.drawCircle(ox, oy, bandRadius * (0.7f + 0.3f * progress), band)
        }
        for (s in spots) {
            // Each item grows out of the orb, a little after the one before.
            val t = ((progress - s.delay * STAGGER) / (1 - STAGGER)).coerceIn(0f, 1f)
            if (t <= 0f) continue
            val x = ox + (s.x - ox) * t
            val y = oy + (s.y - oy) * t
            val grow = if (s === hovered || s === pressed) HOVER_SCALE else 1f
            val r = s.r * (0.3f + 0.7f * t) * grow
            val a = (255 * t).toInt()
            when (val item = s.item) {
                is Item.Ink -> {
                    disc.color = item.color
                    disc.alpha = a
                    canvas.drawCircle(x, y, r, disc)
                    rim.alpha = a
                    canvas.drawCircle(x, y, r - dp(1.5f), rim)
                    if (ColorUtils.calculateLuminance(item.color) > 0.7) canvas.drawCircle(x, y, r, edge)
                    if (item.color == current) {
                        chosen.color = if (ColorUtils.calculateLuminance(item.color) > 0.7) OrbStyle.INK else item.color
                        chosen.alpha = a
                        canvas.drawCircle(x, y, r + dp(6f), chosen)
                    }
                }
                Item.Add -> {
                    disc.color = Color.WHITE
                    disc.alpha = a
                    canvas.drawCircle(x, y, r, disc)
                    rainbow.alpha = a
                    // The rainbow's gradient is centred on the item's final place: draw it there, moved.
                    canvas.withTranslation(x - s.x, y - s.y) { drawCircle(s.x, s.y, r - dp(3f), rainbow) }
                    val p = r * 0.4f
                    plus.alpha = a
                    canvas.drawLine(x - p, y, x + p, y, plus)
                    canvas.drawLine(x, y - p, x, y + p, plus)
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isOpen) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = spotAt(e.x, e.y)
                if (pressed == null) onEmptyTap?.invoke()
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val s = pressed
                pressed = null
                invalidate()
                if (s != null && spotAt(e.x, e.y) === s) onItem(s.item)
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed = null
                invalidate()
            }
        }
        return true
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            if (isOpen) onOutside?.invoke(event.rawX, event.rawY)
            return false
        }
        return super.dispatchTouchEvent(event)
    }

    private companion object {
        val EASE = PathInterpolator(0.2f, 0f, 0f, 1f)
        const val OPEN_MS = 260L
        const val CLOSE_MS = 150L
        const val STAGGER = 0.35f
        const val HOVER_SCALE = 1.3f

        /** Arcs for the colours, from the orb's centre (dp); a full ring uses the first. */
        val RADII = floatArrayOf(120f, 172f, 224f)

        /** Centre-to-centre distance between neighbouring colours (dp). */
        const val SPACING_DP = 46f
        const val FAN_SPAN = PI
        val FAN_TURNS = listOf(0.0, 0.2, -0.2, 0.4, -0.4, 0.6, -0.6, 0.8, -0.8, 1.0, -1.0)
    }
}
