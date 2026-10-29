package com.maestroseb.marcarpro

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Look of the floating controls (light, "classroom" style). */
internal object OrbStyle {
    const val TEAL = 0xFF3E8784.toInt()
    const val TEAL_DARK = 0xFF2D6E6B.toInt()
    const val INK = 0xFF1F2329.toInt()
    const val LABEL = 0xFF2C3138.toInt()
    const val DANGER = 0xFFC0392B.toInt()
    const val SHADOW = 0x29141E28

    /**
     * Colour has one job on the board: the orb (and its badge) wear the tool's colour, the dots
     * the ink's. Every other floating control is graphite on white, so nothing clashes with
     * whichever tool is in hand.
     */
    const val CHROME = 0xFF2C3138.toInt()
    const val CHROME_HAIRLINE = 0x332C3138
}

/**
 * The one material of the controls that aren't the orb (1.6, "paper and graphite"): white with a
 * fine graphite edge and a soft shadow, the icon in graphite. On dark paper (chalkboard, black) it
 * turns over: graphite with a light icon, so white buttons don't glare on a dark board. [dark] goes
 * from 0 to 1 in a short fade; views read the colours below as they draw.
 */
internal object Chrome {
    var dark = 0f

    private const val SURFACE_DARK = 0xFF2C3138.toInt()
    private const val ICON_DARK = 0xFFF2F2EC.toInt()

    val surface get() = mix(Color.WHITE, SURFACE_DARK)
    val icon get() = mix(OrbStyle.CHROME, ICON_DARK)
    val text get() = mix(OrbStyle.INK, ICON_DARK)
    val hint get() = mix(0xFF6B7280.toInt(), 0xFFAEB4BD.toInt())
    val hairline get() = mix(OrbStyle.CHROME_HAIRLINE, 0x2EFFFFFF)
    val shadow get() = mix(OrbStyle.SHADOW, 0x66000000)
    val pressed get() = ColorUtils.setAlphaComponent(icon, 0x1A)

    /** The same soft shadow on every control. */
    fun shade(paint: Paint, radius: Float, dy: Float) = paint.setShadowLayer(radius, 0f, dy, shadow)

    private fun mix(light: Int, darkColor: Int) = if (dark <= 0f) light else ColorUtils.blendARGB(light, darkColor, dark)
}

/** Ring items that switch something on and off instead of picking a tool. */
internal enum class RingToggle { HIDE, PUPILS, LASSO }

/** One tool in the ring. */
internal class RingItem(val tool: Tool?, val icon: Int, val label: String, val toggle: RingToggle? = null, val tint: Int) {
    /** Stable name, for remembering the teacher's order. */
    val key: String get() = tool?.name ?: toggle!!.name
}

/**
 * The ring of tools around the main orb. It lives in its own window, under the orb's, and
 * only moves while it is empty: resizing a window that holds the orb made it jump for a frame.
 * The ring fans out towards the middle of the screen when it would not fit near an edge.
 *
 * Holding a tool puts the ring in edit mode, like the icons of a phone: the tools wiggle, a drag
 * puts one somewhere else, "–" takes one out of the ring (it stays there, faded, with a "+" to
 * bring it back) and "Done" (or a tap around) ends it. The pen can't be taken out.
 */
@SuppressLint("ViewConstructor")
internal class OrbitLayout(
    context: Context,
    items: List<RingItem>,
    order: List<String>,
    hidden: Set<String>,
    private val onItem: (RingItem, SatelliteView) -> Unit,
) : FrameLayout(context) {

    private val byKey = items.associate { item ->
        item.key to SatelliteView(context, item).also { s ->
            s.setOnClickListener { onItem(item, s) }
            s.setOnLongClickListener {
                if (isOpen && !editing) startEditing()
                true
            }
        }
    }
    private val satellites = byKey.values.toList()

    // The teacher's order (tools added by a later version go at the end) and the tools left out.
    private val order = (order.filter { it in byKey } + byKey.keys.filter { it !in order }).toMutableList()
    private val hidden = hidden.filter { it in byKey && it != Tool.PEN.name }.toMutableSet()
    private val satSize = dpi(56f)
    private val orbitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = 0x733E8784
        pathEffect = DashPathEffect(floatArrayOf(dp(5f), dp(5f)), 0f)
    }
    private var orbitAlpha = 0f
    private var orbitVisible = false

    /** Distance from the orb centre to each satellite centre. */
    val radius = dp(118f)

    /** Near an edge the tools fan out on a wider arc of this radius instead of a full ring. */
    private val fanRadius = max(radius, (items.size - 1) * satSize * 1.3f / FAN_SPAN.toFloat())

    val openSize = (2 * (fanRadius + satSize / 2f + dp(34f))).toInt()

    var isOpen = false
        private set

    /** Called when a tap lands on empty space inside the open ring. */
    var onEmptyTap: (() -> Unit)? = null

    /** Called with the screen position of a touch outside this window while the ring is open. */
    var onOutside: ((x: Float, y: Float) -> Unit)? = null

    /** Called after the teacher changed the order or took a tool out (or put it back). */
    var onOrderChanged: ((order: List<String>, hidden: Set<String>) -> Unit)? = null

    /** Called when edit mode starts or ends. */
    var onEditChanged: ((editing: Boolean) -> Unit)? = null

    var editing = false
        private set

    // Where the ring was last opened, to place the tools again while editing.
    private var openCx = 0
    private var openCy = 0
    private var screenW = 0
    private var screenH = 0
    private var slots = emptyList<PointF>()
    private var doneAngle = PI / 2
    private var doneDistance = DONE_RING_DP
    private val doneRect = RectF()
    private val donePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = OrbStyle.CHROME }
    private val doneText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = context.sp(14f)
        typeface = Fonts.bold(context)
    }
    private val doneLabel = context.getString(R.string.ring_done)
    private var wiggle: android.animation.ValueAnimator? = null
    private val badgeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; setShadowLayer(dp(3f), 0f, dp(1f), OrbStyle.SHADOW) }
    private val badgeMark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        strokeCap = Paint.Cap.ROUND
        color = OrbStyle.LABEL
    }

    // A drag in edit mode.
    private var touching = false
    private var dragged: SatelliteView? = null
    private var pressedBadge: SatelliteView? = null
    private var pressedDone = false
    private var downX = 0f
    private var downY = 0f
    private var grabX = 0f
    private var grabY = 0f
    private var moved = false

    init {
        clipChildren = false
        clipToPadding = false
        setWillNotDraw(false)
        for (s in satellites) {
            s.alpha = 0f
            s.visibility = INVISIBLE
            addView(s, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
    }

    fun setActive(tool: Tool, hidden: Boolean, pupils: Boolean) {
        for (s in satellites) s.active = when (s.item.toggle) {
            RingToggle.HIDE -> hidden
            RingToggle.PUPILS -> pupils
            RingToggle.LASSO -> false // a one-off action, never "on"
            null -> s.item.tool == tool
        }
    }

    /** The tools in the ring now: all of them while editing, else the ones not taken out. */
    private fun shown(): List<SatelliteView> = order.filter { editing || it !in hidden }.map { byKey.getValue(it) }

    /**
     * Opens the ring around the orb, whose centre is at ([cx], [cy]) on a [sw]×[sh] screen.
     * The window must already be centred there.
     */
    fun open(cx: Int, cy: Int, sw: Int, sh: Int) {
        isOpen = true
        openCx = cx
        openCy = cy
        screenW = sw
        screenH = sh
        val shown = shown()
        slots = placeSlots(shown.size)
        for (s in satellites) if (s !in shown) {
            s.visibility = INVISIBLE
            s.alpha = 0f
        }
        shown.forEachIndexed { i, s ->
            s.visibility = VISIBLE
            s.translationX = 0f
            s.translationY = -s.circleOffset()
            s.scaleX = 0.3f
            s.scaleY = 0.3f
            s.animate().translationX(slots[i].x).translationY(slots[i].y - s.circleOffset()).scaleX(1f).scaleY(1f).alpha(1f)
                .setStartDelay(i * 28L).setDuration(380).setInterpolator(OvershootInterpolator(1.3f))
                .withEndAction(null).start()
        }
        animateOrbit(if (orbitVisible) 1f else 0f)
    }

    /** Where each of [n] tools goes (circle centres, from the orb's centre); sets the orbit and "Done". */
    private fun placeSlots(n: Int): List<PointF> {
        val cx = openCx
        val cy = openCy
        val sw = screenW
        val sh = screenH
        val margin = satSize / 2f + dp(8f)
        val labelRoom = dp(26f)
        val fits = cx - radius - margin > 0 && cx + radius + margin < sw &&
            cy - radius - margin > 0 && cy + radius + margin + labelRoom < sh
        val angles = DoubleArray(n)
        val r = if (fits) radius else fanRadius
        if (fits) {
            for (i in 0 until n) angles[i] = -PI / 2 + i * 2 * PI / n
            doneAngle = PI / 2
            doneDistance = DONE_RING_DP
        } else {
            // Fan out towards the middle of the screen; turn the fan until every tool is on screen.
            val toCentre = atan2((sh / 2f - cy).toDouble(), (sw / 2f - cx).toDouble())
            val onScreen = { base: Double ->
                (0 until n).all { i ->
                    val a = base + (if (n > 1) (i / (n - 1.0) - 0.5) * FAN_SPAN else 0.0)
                    val x = cx + cos(a) * r
                    val y = cy + sin(a) * r
                    x in margin..(sw - margin) && y in margin..(sh - margin - labelRoom)
                }
            }
            val base = FAN_TURNS.map { toCentre + it }.firstOrNull(onScreen) ?: toCentre
            for (i in 0 until n) angles[i] = base + (if (n > 1) (i / (n - 1.0) - 0.5) * FAN_SPAN else 0.0)
            doneAngle = base
            doneDistance = DONE_FAN_DP
        }
        orbitVisible = fits
        return angles.map { a -> PointF((cos(a) * r).toFloat(), (sin(a) * r).toFloat()) }
    }

    fun close() {
        if (!isOpen) return
        if (editing) stopEditing()
        isOpen = false
        animateOrbit(0f)
        for (s in satellites) {
            if (s.visibility != VISIBLE) continue
            s.animate().translationX(0f).translationY(-s.circleOffset()).scaleX(0.3f).scaleY(0.3f).alpha(0f)
                .setStartDelay(0).setDuration(160).setInterpolator(EASE)
                .withEndAction { s.visibility = INVISIBLE }.start()
        }
    }

    // region Edit mode

    private fun startEditing() {
        editing = true
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        relayout()
        wiggle = android.animation.ValueAnimator.ofFloat(0f, (2 * PI).toFloat()).apply {
            duration = WIGGLE_MS
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { a ->
                val v = a.animatedValue as Float
                satellites.forEachIndexed { i, s -> if (s !== dragged) s.rotation = sin(v + i * 1.7f) * WIGGLE_DEGREES }
                invalidate() // the badges follow the tools as they move
            }
            start()
        }
        onEditChanged?.invoke(true)
    }

    /** Ends edit mode (the ring stays open, with the tools left in it). */
    fun stopEditing() {
        if (!editing) return
        editing = false
        wiggle?.cancel()
        wiggle = null
        dragged = null
        for (s in satellites) {
            s.rotation = 0f
            s.badge = SatelliteView.Badge.NONE
        }
        relayout()
        onEditChanged?.invoke(false)
    }

    /** Moves the tools now in the ring to their places (after a change of mode, order or set). */
    private fun relayout() {
        val shown = shown()
        slots = placeSlots(shown.size)
        for (s in satellites) {
            if (s in shown) continue
            s.animate().scaleX(0.3f).scaleY(0.3f).alpha(0f).setStartDelay(0).setDuration(160)
                .withEndAction { s.visibility = INVISIBLE }.start()
        }
        shown.forEachIndexed { i, s ->
            s.visibility = VISIBLE
            s.badge = when {
                !editing -> SatelliteView.Badge.NONE
                s.item.tool == Tool.PEN -> SatelliteView.Badge.NONE
                s.item.key in hidden -> SatelliteView.Badge.PLUS
                else -> SatelliteView.Badge.MINUS
            }
            if (s === dragged) return@forEachIndexed
            val faded = editing && s.item.key in hidden
            s.animate().translationX(slots[i].x).translationY(slots[i].y - s.circleOffset())
                .scaleX(1f).scaleY(1f).alpha(if (faded) HIDDEN_ALPHA else 1f)
                .setStartDelay(0).setDuration(220).setInterpolator(EASE).withEndAction(null).start()
        }
        animateOrbit(if (orbitVisible) 1f else 0f)
        invalidate()
    }

    private fun circleX(s: SatelliteView) = width / 2f + s.translationX
    private fun circleY(s: SatelliteView) = height / 2f + s.translationY + s.circleOffset()

    private fun satelliteAt(x: Float, y: Float): SatelliteView? =
        shown().minByOrNull { hypot(x - circleX(it), y - circleY(it)) }
            ?.takeIf { hypot(x - circleX(it), y - circleY(it)) <= satSize / 2f + dp(6f) }

    private fun badgeAt(x: Float, y: Float): SatelliteView? = shown().firstOrNull { s ->
        s.badge != SatelliteView.Badge.NONE &&
            hypot(x - circleX(s) - s.badgeDx(), y - circleY(s) - s.badgeDy()) <= dp(18f)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = editing

    private fun editTouch(e: MotionEvent) {
        val ending = e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL
        // The lift of the long press that started editing: nothing to do.
        if (!touching && ending) return
        val slop = 2 * android.view.ViewConfiguration.get(context).scaledTouchSlop
        if (e.actionMasked == MotionEvent.ACTION_DOWN || !touching) {
            // A new press (or the rest of the long press that started editing).
            touching = true
            moved = false
            downX = e.x
            downY = e.y
            pressedDone = doneRect.contains(e.x, e.y)
            pressedBadge = if (pressedDone) null else badgeAt(e.x, e.y)
            dragged = if (pressedDone || pressedBadge != null) null else satelliteAt(e.x, e.y)
            dragged?.let { s ->
                grabX = e.x - circleX(s)
                grabY = e.y - circleY(s)
                s.animate().cancel()
                s.rotation = 0f
                s.animate().scaleX(DRAG_SCALE).scaleY(DRAG_SCALE).setDuration(120).start()
                s.bringToFront()
            }
            return
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val s = dragged ?: return
                if (!moved && hypot(e.x - downX, e.y - downY) <= slop) return
                moved = true
                s.translationX = e.x - grabX - width / 2f
                s.translationY = e.y - grabY - height / 2f - s.circleOffset()
                // Take the place nearest to where the tool is now; the others make room.
                val key = s.item.key
                val shown = shown()
                val from = shown.indexOf(s)
                val to = slots.indices.minByOrNull { hypot(circleX(s) - width / 2f - slots[it].x, circleY(s) - height / 2f - slots[it].y) } ?: from
                if (to != from && to >= 0) {
                    val target = shown[to].item.key
                    order.remove(key)
                    order.add(order.indexOf(target) + if (to > from) 1 else 0, key)
                    relayout()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touching = false
                val up = e.actionMasked == MotionEvent.ACTION_UP
                val badge = pressedBadge
                val s = dragged
                dragged = null
                when {
                    up && pressedDone && doneRect.contains(e.x, e.y) -> stopEditing()
                    up && badge != null -> {
                        val key = badge.item.key
                        if (!hidden.remove(key)) hidden.add(key)
                        relayout()
                        save()
                    }
                    s != null -> {
                        s.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                        relayout()
                        if (moved) save()
                    }
                    // A tap around the tools ends editing.
                    up && !moved -> stopEditing()
                }
            }
        }
    }

    private fun save() = onOrderChanged?.invoke(order.toList(), hidden.toSet())

    // endregion

    private fun animateOrbit(target: Float) {
        android.animation.ValueAnimator.ofFloat(orbitAlpha, target).apply {
            duration = 260
            addUpdateListener {
                orbitAlpha = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (orbitAlpha > 0f && orbitVisible) {
            orbitPaint.alpha = (115 * orbitAlpha).toInt()
            canvas.drawCircle(width / 2f, height / 2f, radius * (0.6f + 0.4f * orbitAlpha), orbitPaint)
        }
        if (editing) {
            // "Done", between the orb and the tools.
            val tw = doneText.measureText(doneLabel)
            val x = width / 2f + (cos(doneAngle) * doneDistance * resources.displayMetrics.density).toFloat()
            val y = height / 2f + (sin(doneAngle) * doneDistance * resources.displayMetrics.density).toFloat()
            doneRect.set(x - tw / 2 - dp(14f), y - dp(16f), x + tw / 2 + dp(14f), y + dp(16f))
            canvas.drawRoundRect(doneRect, dp(16f), dp(16f), donePaint)
            canvas.drawText(doneLabel, x - tw / 2, y - (doneText.descent() + doneText.ascent()) / 2, doneText)
        } else {
            doneRect.setEmpty()
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (!editing) return
        // Only tools in the ring carry a badge while editing (no list built per wiggle frame).
        for (s in satellites) {
            if (s.badge == SatelliteView.Badge.NONE || s.visibility != VISIBLE) continue
            val bx = circleX(s) + s.badgeDx() * s.scaleX
            val by = circleY(s) + s.badgeDy() * s.scaleY
            badgeFill.alpha = if (s.alpha < 0.1f) 0 else 255 // "+" stays clear on a faded tool
            badgeMark.alpha = badgeFill.alpha
            canvas.drawCircle(bx, by, dp(11f), badgeFill)
            val a = dp(5f)
            canvas.drawLine(bx - a, by, bx + a, by, badgeMark)
            if (s.badge == SatelliteView.Badge.PLUS) canvas.drawLine(bx, by - a, bx, by + a, badgeMark)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (editing) {
            editTouch(event)
            return true
        }
        // Taps on the transparent area around an open ring close it.
        if (event.actionMasked == MotionEvent.ACTION_DOWN && isOpen) onEmptyTap?.invoke()
        return isOpen
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
        const val FAN_SPAN = PI * 0.85
        val FAN_TURNS = listOf(0.0, 0.2, -0.2, 0.4, -0.4, 0.6, -0.6, 0.8, -0.8, 1.0, -1.0)
        const val WIGGLE_MS = 700L
        const val WIGGLE_DEGREES = 2.5f
        const val HIDDEN_ALPHA = 0.45f
        const val DRAG_SCALE = 1.15f

        /** "Done" from the orb's centre (dp), between the orb and the tools: below it on a full ring, towards the fan. */
        const val DONE_RING_DP = 64.0
        const val DONE_FAN_DP = 90.0
    }
}

/**
 * The orb: in the active tool's colour (as in the ring), with the tool's icon, and a dot with the
 * ink colour and thickness. A small
 * cursor badge sits on its rim at two o'clock: tapping it switches between writing and using
 * the screen underneath.
 */
@SuppressLint("ViewConstructor")
internal class OrbButton(context: Context) : View(context) {
    private var spring: PressSpring? = null
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = OrbStyle.TEAL }
    // A white rim and the controls' own shadow (1.6), instead of a coloured halo.
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2.5f) }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val badgeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { setShadowLayer(dp(5f), 0f, dp(2f), OrbStyle.SHADOW) }
    private val badgeRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2f) }
    private val badgeHalo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cursor = ContextCompat.getDrawable(context, R.drawable.ic_cursor)!!.mutate()
    private val pupilsIcon = ContextCompat.getDrawable(context, R.drawable.ic_pupils)!!.mutate()
    private var icon: Drawable? = null
    private var iconRes = 0
    var inkColor = Color.BLACK
        set(value) { if (field != value) { field = value; invalidate() } }

    /** The active tool's colour (its ring item's tint): the disc takes it. */
    var tint = OrbStyle.TEAL
        set(value) {
            if (field == value) return
            field = value
            fill.color = value
            invalidate()
        }
    var sizeLevel = 1
        set(value) { if (field != value) { field = value; invalidate() } }

    /** Using the screen underneath: the orb fades and the cursor badge lights up. */
    var using = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /**
     * On the whiteboard the badge is "Pupils" (the screen button already goes back to the
     * screen): violet while several can write with their fingers.
     */
    var pupilsBadge = false
        set(value) { if (field != value) { field = value; invalidate() } }
    var pupilsOn = false
        set(value) { if (field != value) { field = value; invalidate() } }

    /** Whether the press now going on started on the cursor badge. */
    var downOnBadge = false
        private set

    /** Whether the press now going on started on the ink colour dot. */
    var downOnDot = false
        private set

    init {
        isClickable = true
        isFocusable = true
        spring = PressSpring(this)
    }

    fun setIcon(res: Int, description: String) {
        contentDescription = description
        if (res == iconRes) return
        iconRes = res
        icon = ContextCompat.getDrawable(context, res)?.mutate()?.apply { setTint(Color.WHITE) }
        invalidate()
    }

    private fun badgeX() = width / 2f + BADGE_DISTANCE * 0.866f * resources.displayMetrics.density
    private fun badgeY() = height / 2f - BADGE_DISTANCE * 0.5f * resources.displayMetrics.density

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            downOnBadge = kotlin.math.hypot(event.x - badgeX(), event.y - badgeY()) <= dp(21f)
            val dot = dp(34f) * 0.62f
            downOnDot = !downOnBadge && kotlin.math.hypot(event.x - width / 2f - dot, event.y - height / 2f - dot) <= dp(17f)
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = dp(34f)
        // Fingers on the screen (cursor) or pupils writing with them: the pen steps back.
        val faded = using || (pupilsBadge && pupilsOn)
        val layer = if (faded) canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), 140) else -1
        Chrome.shade(fill, dp(10f), dp(4f))
        canvas.drawCircle(cx, cy, r, fill)
        rim.color = ColorUtils.blendARGB(Color.WHITE, 0xD9FFFFFF.toInt(), Chrome.dark)
        canvas.drawCircle(cx, cy, r - rim.strokeWidth / 2, rim)
        icon?.let {
            val s = dp(ORB_ICON_DP)
            it.setBounds((cx - s / 2).toInt(), (cy - s / 2).toInt(), (cx + s / 2).toInt(), (cy + s / 2).toInt())
            it.draw(canvas)
        }
        val dr = dp(DOT_RADII[sizeLevel.coerceIn(0, DOT_RADII.lastIndex)])
        val dx = cx + r * 0.62f
        val dy = cy + r * 0.62f
        canvas.drawCircle(dx, dy, dr + dp(2f), dotRing)
        dot.color = inkColor
        canvas.drawCircle(dx, dy, dr, dot)
        if (layer >= 0) canvas.restoreToCount(layer)

        val bx = badgeX()
        val by = badgeY()
        val br = dp(15f)
        // The badge belongs to the orb: it takes the orb's own colour (the tool's), never a
        // colour of its own that would clash with some tools. White with the icon in that colour;
        // lit, filled with it and a white rim that parts it from the orb.
        val lit = if (pupilsBadge) pupilsOn else using
        val deep = ColorUtils.blendARGB(tint, Color.BLACK, 0.18f)
        // Lit, like a chosen tool in the ring: full colour, white rim and a halo, so "on" reads
        // at a glance even over the orb's own colour.
        if (lit) {
            badgeHalo.color = ColorUtils.setAlphaComponent(tint, 0x66)
            canvas.drawCircle(bx, by, br + dp(5f), badgeHalo)
        }
        // At rest it is made of the controls' material (graphite on a dark board), the icon in the tool's colour.
        badgeFill.color = if (lit) deep else Chrome.surface
        Chrome.shade(badgeFill, dp(5f), dp(2f))
        canvas.drawCircle(bx, by, br, badgeFill)
        badgeRing.strokeWidth = dp(if (lit) 2.5f else 1f)
        badgeRing.color = if (lit) Color.WHITE else ColorUtils.blendARGB(ColorUtils.setAlphaComponent(deep, 0x59), Chrome.hairline, Chrome.dark)
        canvas.drawCircle(bx, by, br - badgeRing.strokeWidth / 2, badgeRing)
        val mark = if (pupilsBadge) pupilsIcon else cursor
        mark.setTint(if (lit) Color.WHITE else ColorUtils.blendARGB(deep, ColorUtils.blendARGB(tint, Color.WHITE, 0.45f), Chrome.dark))
        val s = dp(if (pupilsBadge) 19f else 17f)
        mark.setBounds((bx - s / 2).toInt(), (by - s / 2).toInt(), (bx + s / 2).toInt(), (by + s / 2).toInt())
        mark.draw(canvas)
    }

    override fun setPressed(pressed: Boolean) {
        if (pressed != isPressed) spring?.press(pressed)
        super.setPressed(pressed)
    }

    override fun performClick(): Boolean = super.performClick()

    private companion object {
        /** Badge centre from the orb centre, in dp: it overlaps the orb's rim. */
        const val BADGE_DISTANCE = 37f

        /** Colour dot radius (dp) for each ink thickness. */
        val DOT_RADII = floatArrayOf(4.5f, 6f, 7.5f)

        /** The tool's icon on the 68 dp disc: large enough to read from the back of the class. */
        const val ORB_ICON_DP = 38f
    }
}

/**
 * A tool button: a disc of the controls' material ([Chrome]) with a graphite icon, its name in a
 * pill underneath. The chosen one turns to the tool's full colour with a white icon, a white rim
 * and a pill of its colour: colour on the ring means "in hand".
 */
@SuppressLint("ViewConstructor")
internal class SatelliteView(context: Context, val item: RingItem) : View(context) {
    private val size = dp(56f)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = context.sp(12f)
        typeface = Fonts.bold(context)
    }
    private val labelWidth = labelPaint.measureText(item.label)
    private val pillH = dp(20f)
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG)

    // At rest the controls' material (1.6: white, a hairline, a graphite icon); chosen, the tool's
    // full colour with a white rim, as the orb then wears it.
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1f) }
    private val chosenRim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(3f); color = Color.WHITE }
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icon = ContextCompat.getDrawable(context, item.icon)!!.mutate()
    private val pillRect = RectF()
    private var spring: PressSpring? = null

    var active = false
        set(value) {
            if (field == value) return
            field = value
            isSelected = value
            invalidate()
        }

    /** In edit mode: "–" takes the tool out of the ring, "+" puts it back. */
    enum class Badge { NONE, MINUS, PLUS }

    /** Drawn by the ring, above every tool (a neighbour's label would cover it). */
    var badge = Badge.NONE

    /** The badge's centre from the circle's centre. */
    fun badgeDx() = size * 0.36f
    fun badgeDy() = -size * 0.36f

    init {
        contentDescription = item.label
        isClickable = true
        isFocusable = true
        spring = PressSpring(this)
    }

    /** Offset from the view's centre to the circle's centre (the label hangs below the circle). */
    fun circleOffset(): Float = -(measuredOrEstimatedHeight() - size - dp(8f)) / 2f

    private fun measuredOrEstimatedHeight() = size + dp(4f) + pillH + dp(8f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = max(size + dp(16f), labelWidth + dp(22f))
        setMeasuredDimension(w.toInt(), measuredOrEstimatedHeight().toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = size / 2f + dp(4f)
        val r = size / 2f
        disc.color = if (active) item.tint else Chrome.surface
        Chrome.shade(disc, dp(8f), dp(3f))
        canvas.drawCircle(cx, cy, r, disc)
        if (active) {
            canvas.drawCircle(cx, cy, r - dp(1.5f), chosenRim)
        } else {
            edge.color = Chrome.hairline
            canvas.drawCircle(cx, cy, r - dp(0.5f), edge)
        }
        icon.setTint(if (active) Color.WHITE else Chrome.icon)
        val s = dp(ICON_DP)
        icon.setBounds((cx - s / 2).toInt(), (cy - s / 2).toInt(), (cx + s / 2).toInt(), (cy + s / 2).toInt())
        icon.draw(canvas)
        val top = cy + r - dp(4f)
        val half = labelWidth / 2f + dp(8f)
        pillRect.set(cx - half, top, cx + half, top + pillH)
        pill.color = if (active) ColorUtils.blendARGB(item.tint, Color.BLACK, 0.12f) else OrbStyle.LABEL
        canvas.drawRoundRect(pillRect, dp(6f), dp(6f), pill)
        val baseline = top + pillH / 2f - (labelPaint.descent() + labelPaint.ascent()) / 2f
        canvas.drawText(item.label, cx - labelWidth / 2f, baseline, labelPaint)
    }

    override fun setPressed(pressed: Boolean) {
        if (pressed != isPressed) spring?.press(pressed)
        super.setPressed(pressed)
    }

    override fun performClick(): Boolean = super.performClick()

    private companion object {
        /** The icon on the 56 dp disc. */
        const val ICON_DP = 31f
    }
}
