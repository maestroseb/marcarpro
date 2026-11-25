package com.maestroseb.marcarpro

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import java.util.Date
import java.util.Locale

/**
 * The clock on the whiteboard: a `Chrome` capsule with the time, in its own window that each
 * teacher drags where it suits them. A tap opens [TimerCardView] (the day, a stopwatch and a
 * countdown); while one runs, the capsule grows to the right with it and the time stays in view.
 * It redraws once a minute (once a second with a timer), only while shown, never the ink.
 */
internal class ClockView(context: Context, private val onResize: (Int) -> Unit, private val onEnd: () -> Unit) : View(context) {
    enum class Mode { NONE, WATCH, COUNT }

    private val margin = dp(8f)
    private val card = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1f) }
    private val pressFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2.2f); strokeCap = Paint.Cap.ROUND }
    private val timeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = context.sp(18f)
        typeface = Fonts.bold(context)
        textAlign = Paint.Align.CENTER
        fontFeatureSettings = "tnum"
    }
    private val r = RectF()
    private val timeFormat = android.text.format.DateFormat.getTimeFormat(context)

    private fun time(ms: Long = System.currentTimeMillis()): String = timeFormat.format(Date(ms))

    private val pad get() = dp(PAD_DP)

    /** The time's part: wide enough for any time of day, so it never shifts. */
    private val timeWidth: Float = run {
        val c = java.util.Calendar.getInstance()
        c.set(2026, 0, 28, 23, 58)
        var w = timeText.measureText(time(c.timeInMillis))
        c.set(java.util.Calendar.HOUR_OF_DAY, 12)
        w = maxOf(w, timeText.measureText(time(c.timeInMillis)))
        w + 2 * pad
    }

    /**
     * The timer's part fits its text's shape, not each number: with tabular digits "0:07" and
     * "9:59" are as wide, so it only changes at "10:00", past an hour or at "Time!" (growing to
     * the right, the time never moves).
     */
    private var timerWidth = 0f

    private fun widthFor(label: String) =
        dp(ICON_DP) + dp(6f) + timeText.measureText(label.replace(DIGIT, "8")) + 2 * pad

    /** What the timer part shows now. */
    private fun timerLabel(): String = when {
        ended -> context.getString(R.string.timer_done)
        mode == Mode.WATCH -> clockText(elapsed() - 999)
        else -> clockText(remaining())
    }

    /** Resizes the capsule if the timer's text changed shape. */
    private fun fit() {
        val w = if (mode == Mode.NONE) 0f else widthFor(timerLabel())
        if (w != timerWidth) {
            timerWidth = w
            requestLayout()
            onResize(windowWidth)
        }
    }

    private val capsuleWidth get() = timeWidth + if (mode == Mode.NONE) 0f else timerWidth

    /** The window's size: the capsule and its shadow margin. */
    val windowWidth get() = (capsuleWidth + 2 * margin).toInt()
    val windowHeight get() = (dp(HEIGHT_DP) + 2 * margin).toInt()

    var pressedLook = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    // region Timer

    var mode = Mode.NONE
        private set
    /** Counting down: how long, in ms. */
    var duration = 0L
        private set
    private var startedAt = 0L
    private var pausedAt = 0L
    val paused get() = pausedAt != 0L
    /** The countdown reached zero (it stays on "Time!" until removed or repeated). */
    var ended = false
        private set

    private fun now() = SystemClock.elapsedRealtime()
    private fun elapsed() = (if (paused) pausedAt else now()) - startedAt
    private fun remaining() = duration - elapsed()

    private val end = Runnable {
        if (mode != Mode.COUNT || paused) return@Runnable
        ended = true
        fit()
        invalidate()
        celebrate()
        onEnd()
    }

    /** The last seconds of a countdown: amber, and a soft beat each second. */
    private val warning get() = mode == Mode.COUNT && !ended && remaining() <= WARN_MS

    /** 0..1: how red the capsule is, fading after the end. */
    private var flash = 0f

    private fun beat(scale: Float, ms: Long) {
        animate().cancel()
        scaleX = 1f
        scaleY = 1f
        animate().scaleX(scale).scaleY(scale).setDuration(ms / 2)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .withEndAction { animate().scaleX(1f).scaleY(1f).setDuration(ms / 2).setInterpolator(android.view.animation.AccelerateDecelerateInterpolator()).start() }
            .start()
    }

    /** The end: three beats and a red glow that fades, so the back row sees it too. */
    private fun celebrate() {
        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = CELEBRATE_MS
            addUpdateListener {
                val t = it.animatedValue as Float
                // Three bumps, each a little smaller, then still.
                val bump = kotlin.math.abs(kotlin.math.sin(t * Math.PI * 3)).toFloat() * (1f - t * 0.6f)
                scaleX = 1f + BEAT_END * bump
                scaleY = scaleX
                flash = 1f - t
                invalidate()
            }
            start()
        }
    }

    fun startWatch() = start(Mode.WATCH, 0L)

    fun startCount(minutes: Int) = start(Mode.COUNT, minutes * 60_000L)

    /** The same countdown again, from the top. */
    fun repeat() = start(Mode.COUNT, duration)

    private fun start(m: Mode, ms: Long) {
        flash = 0f
        mode = m
        duration = ms
        startedAt = now()
        pausedAt = 0L
        ended = false
        armEnd()
        resized()
        restartTick()
    }

    fun togglePause() {
        if (mode == Mode.NONE || ended) return
        if (paused) {
            startedAt += now() - pausedAt
            pausedAt = 0L
        } else {
            pausedAt = now()
        }
        armEnd()
        restartTick()
    }

    fun stop() {
        if (mode == Mode.NONE) return
        mode = Mode.NONE
        pausedAt = 0L
        ended = false
        removeCallbacks(end)
        resized()
        restartTick()
    }

    private fun armEnd() {
        removeCallbacks(end)
        if (mode == Mode.COUNT && !paused) postDelayed(end, remaining().coerceAtLeast(0L))
    }

    private fun resized() {
        timerWidth = -1f
        fit()
        invalidate()
    }

    /** "4:05", or "1:02:05" past an hour. */
    private fun clockText(ms: Long): String {
        val s = (ms.coerceAtLeast(0L) + 999) / 1000
        val h = s / 3600
        val m = s % 3600 / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec) else String.format(Locale.ROOT, "%d:%02d", m, sec)
    }

    // endregion

    private val tick = object : Runnable {
        override fun run() {
            fit()
            invalidate()
            if (warning && !paused && isShown) beat(BEAT_WARN, 420L)
            if (mode == Mode.NONE || paused || ended) {
                postDelayed(this, MINUTE_MS - System.currentTimeMillis() % MINUTE_MS + 50)
            } else {
                // On the timer's own seconds, so it never skips or stalls a number.
                val e = elapsed()
                val phase = if (mode == Mode.COUNT) (duration - e) % 1000 else 1000 - e % 1000
                postDelayed(this, (if (phase <= 0) 1000 else phase) + 5)
            }
        }
    }

    private fun restartTick() {
        removeCallbacks(tick)
        if (isShown) tick.run()
    }

    init {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        removeCallbacks(tick)
        contentDescription = time()
        if (isVisible) tick.run()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(windowWidth, windowHeight)
    }

    override fun onDraw(canvas: Canvas) {
        val h = dp(HEIGHT_DP)
        val left = margin
        val top = (height - h) / 2
        val w = capsuleWidth
        r.set(left, top, left + w, top + h)
        card.color = if (flash > 0f) ColorUtils.blendARGB(Chrome.surface, ColorUtils.blendARGB(Chrome.surface, END, 0.22f), flash) else Chrome.surface
        Chrome.shade(card, dp(8f), dp(2.5f))
        canvas.drawRoundRect(r, h / 2, h / 2, card)
        edge.color = if (flash > 0f) ColorUtils.blendARGB(Chrome.hairline, END, flash) else Chrome.hairline
        r.inset(dp(0.5f), dp(0.5f))
        canvas.drawRoundRect(r, h / 2, h / 2, edge)
        if (pressedLook) {
            pressFill.color = Chrome.pressed
            r.inset(dp(4.5f), dp(4.5f))
            canvas.drawRoundRect(r, r.height() / 2, r.height() / 2, pressFill)
        }
        val cy = top + h / 2
        val base = cy - (timeText.descent() + timeText.ascent()) / 2
        timeText.color = Chrome.text
        timeText.textAlign = Paint.Align.CENTER
        canvas.drawText(time(), left + timeWidth / 2, base, timeText)
        if (mode == Mode.NONE) return

        edge.color = Chrome.hairline
        val sx = left + timeWidth
        canvas.drawLine(sx, cy - dp(11f), sx, cy + dp(11f), edge)
        val color = if (ended) END else if (paused) Chrome.hint else if (warning) WARN else ACCENT
        val ix = sx + pad + dp(ICON_DP) / 2
        glyph.color = color
        if (mode == Mode.WATCH) drawWatch(canvas, ix, cy) else drawRing(canvas, ix, cy, (remaining().toFloat() / duration).coerceIn(0f, 1f))
        timeText.color = color
        timeText.textAlign = Paint.Align.LEFT
        val label = timerLabel()
        canvas.drawText(label, ix + dp(ICON_DP) / 2 + dp(6f), base, timeText)
        timeText.textAlign = Paint.Align.CENTER
    }

    /** A stopwatch: the dial, its crown and a hand. */
    private fun drawWatch(canvas: Canvas, cx: Float, cy: Float) {
        val rr = dp(7.5f)
        val y = cy + dp(1.5f)
        canvas.drawCircle(cx, y, rr, glyph)
        canvas.drawLine(cx, y - rr - dp(1.5f), cx, y - rr - dp(3.5f), glyph)
        canvas.drawLine(cx - dp(2.5f), y - rr - dp(3.5f), cx + dp(2.5f), y - rr - dp(3.5f), glyph)
        canvas.drawLine(cx, y, cx + dp(3f), y - dp(3f), glyph)
    }

    /** The time left as a ring that empties clockwise. */
    private fun drawRing(canvas: Canvas, cx: Float, cy: Float, left: Float) {
        val rr = dp(8.5f)
        val old = glyph.color
        glyph.color = ColorUtils.setAlphaComponent(old, 0x40)
        canvas.drawCircle(cx, cy, rr, glyph)
        glyph.color = old
        r.set(cx - rr, cy - rr, cx + rr, cy + rr)
        canvas.drawArc(r, -90f, 360f * left, false, glyph)
    }

    companion object {
        private const val HEIGHT_DP = 44f
        private const val PAD_DP = 16f
        private const val ICON_DP = 20f
        private const val MINUTE_MS = 60_000L
        private val DIGIT = Regex("[0-9]")
        /** The pen's blue: the one accent on the whiteboard's controls. */
        const val ACCENT = 0xFF3B5BDB.toInt()
        const val END = 0xFFD9363E.toInt()
        const val WARN = 0xFFE07B00.toInt()
        private const val WARN_MS = 10_000L
        private const val BEAT_WARN = 1.05f
        private const val BEAT_END = 0.07f
        private const val CELEBRATE_MS = 1600L
    }
}

/**
 * The clock's card: the whole day on top, then a stopwatch and quick countdowns; with a timer
 * running, pause (or resume) and remove; once a countdown ends, repeat and remove.
 */
internal class TimerCardView(context: Context, private val onOutside: () -> Unit, private val onDone: () -> Unit) : View(context) {
    var clock: ClockView? = null

    private enum class Act { WATCH, C1, C3, C5, C10, C15, MINUS, PLUS, GO, PAUSE, REPEAT, REMOVE }

    /** The countdown on the stepper, in minutes: the last one chosen there (5 at first). */
    private var custom = 5

    private val margin = dp(10f)
    private val card = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1f) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(2f); strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val title = text(16f, bold = true).apply { textAlign = Paint.Align.CENTER }
    private val big = text(15f, bold = true)
    private val small = text(13f)
    private val chipText = text(15f, bold = true).apply { textAlign = Paint.Align.CENTER; fontFeatureSettings = "tnum" }
    private val r = RectF()
    private val path = Path()
    private val parts = mutableListOf<Pair<Act, RectF>>()
    private var pressed: Act? = null

    private fun text(size: Float, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = context.sp(size)
        typeface = if (bold) Fonts.bold(context) else Fonts.regular(context)
    }

    private val idle get() = (clock?.mode ?: ClockView.Mode.NONE) == ClockView.Mode.NONE

    /** Lays the buttons out for the clock's state; call before showing. */
    fun refresh() {
        parts.clear()
        val l = margin + dp(PAD_DP)
        val rr = dp(WIDTH_DP) - margin - dp(PAD_DP)
        var y = margin + dp(PAD_DP + HEAD_DP)
        if (idle) {
            parts += Act.WATCH to RectF(l, y, rr, y + dp(ROW_DP))
            y += dp(ROW_DP + LABEL_DP)
            val acts = listOf(Act.C1, Act.C3, Act.C5, Act.C10, Act.C15)
            val gap = dp(6f)
            val w = (rr - l - gap * (acts.size - 1)) / acts.size
            acts.forEachIndexed { i, a -> parts += a to RectF(l + i * (w + gap), y, l + i * (w + gap) + w, y + dp(CHIP_DP)) }
            y += dp(CHIP_DP + STEP_GAP_DP)
            val sq = dp(STEP_DP)
            parts += Act.MINUS to RectF(l, y, l + sq, y + sq)
            val goW = dp(GO_DP)
            parts += Act.GO to RectF(rr - goW, y, rr, y + sq)
            parts += Act.PLUS to RectF(rr - goW - gap - sq, y, rr - goW - gap, y + sq)
        } else {
            val mid = (l + rr) / 2
            val first = if (clock?.ended == true) Act.REPEAT else Act.PAUSE
            parts += first to RectF(l, y, mid - dp(4f), y + dp(ROW_DP))
            parts += Act.REMOVE to RectF(mid + dp(4f), y, rr, y + dp(ROW_DP))
        }
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val body = if (idle) ROW_DP + LABEL_DP + CHIP_DP + STEP_GAP_DP + STEP_DP else ROW_DP
        setMeasuredDimension(dpi(WIDTH_DP), (2 * margin + dp(2 * PAD_DP + HEAD_DP + body)).toInt())
    }

    private fun today(): String {
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM")
        return java.text.SimpleDateFormat(pattern, Locale.getDefault()).format(Date()).replaceFirstChar { it.titlecase() }
    }

    override fun onDraw(canvas: Canvas) {
        r.set(margin, margin, width - margin, height - margin)
        val corner = dp(16f)
        card.color = Chrome.surface
        Chrome.shade(card, dp(10f), dp(3f))
        canvas.drawRoundRect(r, corner, corner, card)
        edge.color = Chrome.hairline
        r.inset(dp(0.5f), dp(0.5f))
        canvas.drawRoundRect(r, corner, corner, edge)
        title.color = Chrome.text
        canvas.drawText(today(), width / 2f, margin + dp(PAD_DP + 24f), title)
        if (idle) {
            small.color = Chrome.hint
            val labelY = parts.first { it.first == Act.WATCH }.second.bottom + dp(LABEL_DP) - dp(9f)
            canvas.drawText(context.getString(R.string.timer_countdown_min), margin + dp(PAD_DP + 4f), labelY, small)
        }
        for ((a, box) in parts) drawPart(canvas, a, box)
        if (idle) {
            // The stepper's value, between − and +.
            val minus = parts.first { it.first == Act.MINUS }.second
            val plus = parts.first { it.first == Act.PLUS }.second
            chipText.color = Chrome.text
            canvas.drawText("$custom min", (minus.right + plus.left) / 2, minus.centerY() - (chipText.descent() + chipText.ascent()) / 2, chipText)
        }
    }

    private fun drawPart(canvas: Canvas, a: Act, box: RectF) {
        val corner = dp(12f)
        if (a == Act.GO) {
            fill.color = if (pressed == a) ColorUtils.blendARGB(ClockView.ACCENT, android.graphics.Color.BLACK, 0.15f) else ClockView.ACCENT
            canvas.drawRoundRect(box, box.height() / 2, box.height() / 2, fill)
            big.color = android.graphics.Color.WHITE
            val t = context.getString(R.string.timer_start)
            canvas.drawText(t, box.centerX() - big.measureText(t) / 2, box.centerY() - (big.descent() + big.ascent()) / 2, big)
            return
        }
        if (a == Act.MINUS || a == Act.PLUS) {
            fill.color = if (pressed == a) Chrome.pressed else Chrome.surface
            canvas.drawCircle(box.centerX(), box.centerY(), box.height() / 2, fill)
            edge.color = Chrome.hairline
            canvas.drawCircle(box.centerX(), box.centerY(), box.height() / 2 - dp(0.5f), edge)
            icon.color = Chrome.icon
            val k = dp(7f)
            canvas.drawLine(box.centerX() - k, box.centerY(), box.centerX() + k, box.centerY(), icon)
            if (a == Act.PLUS) canvas.drawLine(box.centerX(), box.centerY() - k, box.centerX(), box.centerY() + k, icon)
            return
        }
        val chip = a in CHIPS
        fill.color = if (pressed == a) Chrome.pressed else Chrome.surface
        canvas.drawRoundRect(box, if (chip) box.height() / 2 else corner, if (chip) box.height() / 2 else corner, fill)
        edge.color = Chrome.hairline
        canvas.drawRoundRect(box, if (chip) box.height() / 2 else corner, if (chip) box.height() / 2 else corner, edge)
        if (chip) {
            chipText.color = Chrome.text
            val label = when (a) { Act.C1 -> "1"; Act.C3 -> "3"; Act.C5 -> "5"; Act.C10 -> "10"; else -> "15" }
            canvas.drawText(label, box.centerX(), box.centerY() - (chipText.descent() + chipText.ascent()) / 2, chipText)
            return
        }
        val label = context.getString(
            when (a) {
                Act.WATCH -> R.string.timer_stopwatch
                Act.PAUSE -> if (clock?.paused == true) R.string.timer_resume else R.string.timer_pause
                Act.REPEAT -> R.string.timer_again
                else -> R.string.timer_remove
            },
        )
        val iconW = dp(20f)
        val gap = dp(8f)
        val tw = big.measureText(label)
        val x0 = box.centerX() - (iconW + gap + tw) / 2
        val cx = x0 + iconW / 2
        val cy = box.centerY()
        icon.color = if (a == Act.WATCH) ClockView.ACCENT else Chrome.icon
        icon.style = Paint.Style.STROKE
        val u = dp(1f)
        when (a) {
            Act.WATCH -> {
                canvas.drawCircle(cx, cy + 1.5f * u, 7.5f * u, icon)
                canvas.drawLine(cx, cy - 7.5f * u, cx, cy - 9.5f * u, icon)
                canvas.drawLine(cx, cy + 1.5f * u, cx + 3 * u, cy - 1.5f * u, icon)
            }
            Act.PAUSE -> if (clock?.paused == true) {
                path.reset(); path.moveTo(cx - 4 * u, cy - 7 * u); path.lineTo(cx + 6 * u, cy); path.lineTo(cx - 4 * u, cy + 7 * u); path.close()
                canvas.drawPath(path, icon)
            } else {
                canvas.drawLine(cx - 3.5f * u, cy - 7 * u, cx - 3.5f * u, cy + 7 * u, icon)
                canvas.drawLine(cx + 3.5f * u, cy - 7 * u, cx + 3.5f * u, cy + 7 * u, icon)
            }
            Act.REPEAT -> {
                // An open circle going round clockwise, its arrowhead at the top.
                val rad = 7 * u
                r.set(cx - rad, cy - rad, cx + rad, cy + rad)
                canvas.drawArc(r, -45f, 285f, false, icon)
                val ex = cx
                val ey = cy - rad
                canvas.drawLine(ex, ey, ex - 3.5f * u, ey - 3.5f * u, icon)
                canvas.drawLine(ex, ey, ex - 3.5f * u, ey + 3.5f * u, icon)
            }
            else -> {
                canvas.drawLine(cx - 6 * u, cy - 6 * u, cx + 6 * u, cy + 6 * u, icon)
                canvas.drawLine(cx + 6 * u, cy - 6 * u, cx - 6 * u, cy + 6 * u, icon)
            }
        }
        big.color = if (a == Act.WATCH) ClockView.ACCENT else Chrome.text
        canvas.drawText(label, x0 + iconW + gap, cy - (big.descent() + big.ascent()) / 2, big)
    }

    private fun actAt(x: Float, y: Float): Act? = parts.firstOrNull { it.second.contains(x, y) }?.first

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            onOutside()
            return false
        }
        return super.dispatchTouchEvent(e)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pressed = actAt(e.x, e.y); invalidate() }
            MotionEvent.ACTION_CANCEL -> { pressed = null; invalidate() }
            MotionEvent.ACTION_UP -> {
                val a = pressed?.takeIf { actAt(e.x, e.y) == it }
                pressed = null
                invalidate()
                if (a != null) act(a)
            }
        }
        return true
    }

    private fun act(a: Act) {
        val c = clock ?: return
        when (a) {
            Act.WATCH -> c.startWatch()
            Act.C1 -> c.startCount(1)
            Act.C3 -> c.startCount(3)
            Act.C5 -> c.startCount(5)
            Act.C10 -> c.startCount(10)
            Act.C15 -> c.startCount(15)
            Act.MINUS, Act.PLUS -> {
                custom = step(custom, if (a == Act.PLUS) 1 else -1)
                invalidate()
                return
            }
            Act.GO -> c.startCount(custom)
            Act.PAUSE -> { c.togglePause(); invalidate(); return }
            Act.REPEAT -> c.repeat()
            Act.REMOVE -> c.stop()
        }
        onDone()
    }

    /** Comes in with a small rise and fade, from the clock's side. */
    fun enter(fromAbove: Boolean) {
        alpha = 0f
        translationY = dp(if (fromAbove) -8f else 8f)
        animate().alpha(1f).translationY(0f).setDuration(180)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).start()
    }

    private companion object {
        /** One minute at a time up to 10, then five at a time (12 → 15 → 20…), 1 to 90. */
        fun step(m: Int, dir: Int): Int = if (dir > 0) {
            if (m < 10) m + 1 else (m / 5 + 1) * 5
        } else {
            if (m <= 10) m - 1 else ((m - 1) / 5) * 5
        }.coerceIn(1, 90)
        const val STEP_DP = 48f
        const val STEP_GAP_DP = 10f
        const val GO_DP = 104f
        val CHIPS = setOf(Act.C1, Act.C3, Act.C5, Act.C10, Act.C15)
        const val WIDTH_DP = 300f
        const val PAD_DP = 12f
        const val HEAD_DP = 40f
        const val ROW_DP = 52f
        const val LABEL_DP = 30f
        const val CHIP_DP = 42f
    }
}
