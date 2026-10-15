package com.maestroseb.marcarpro

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils

/**
 * A round button with an icon (the whiteboard): white with a teal ring, solid teal when [on].
 * An optional small badge sits on its rim at two o'clock, like the main orb's cursor: on the
 * whiteboard button, the scissors that cut a piece of the screen out onto it.
 */
@SuppressLint("ViewConstructor")
internal class MiniOrbView(context: Context, iconRes: Int, badgeRes: Int = 0) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { setShadowLayer(dp(6f), 0f, dp(2f), OrbStyle.SHADOW) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2f) }
    private val icon = ContextCompat.getDrawable(context, iconRes)!!.mutate().apply { setTint(OrbStyle.TEAL_DARK) }
    private val badge = if (badgeRes == 0) null else ContextCompat.getDrawable(context, badgeRes)!!.mutate().apply { setTint(Color.WHITE) }
    private val badgeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = OrbStyle.TEAL; setShadowLayer(dp(4f), 0f, dp(1.5f), OrbStyle.SHADOW) }
    private val badgeRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2f); color = Color.WHITE }
    private val spring = PressSpring(this)

    var on = false
        set(value) {
            if (field == value) return
            field = value
            icon.setTint(if (value) Color.WHITE else OrbStyle.TEAL_DARK)
            invalidate()
        }

    /** Whether the press now going on started on the badge. */
    var downOnBadge = false
        private set

    init {
        isClickable = true
        isFocusable = true
    }

    private fun badgeX() = width / 2f + dp(BADGE_DISTANCE_DP) * 0.866f
    private fun badgeY() = height / 2f - dp(BADGE_DISTANCE_DP) * 0.5f

    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            downOnBadge = badge != null && kotlin.math.hypot(event.x - badgeX(), event.y - badgeY()) <= dp(17f)
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = dp(24f)
        fill.color = if (on) OrbStyle.TEAL else Color.WHITE
        ring.color = if (on) Color.WHITE else OrbStyle.TEAL
        canvas.drawCircle(cx, cy, r, fill)
        canvas.drawCircle(cx, cy, r - dp(1f), ring)
        val s = dp(24f)
        icon.setBounds((cx - s / 2).toInt(), (cy - s / 2).toInt(), (cx + s / 2).toInt(), (cy + s / 2).toInt())
        icon.draw(canvas)
        badge?.let { b ->
            val bx = badgeX()
            val by = badgeY()
            canvas.drawCircle(bx, by, dp(13f), badgeFill)
            canvas.drawCircle(bx, by, dp(13f) - dp(1f), badgeRing)
            val bs = dp(15f)
            b.setBounds((bx - bs / 2).toInt(), (by - bs / 2).toInt(), (bx + bs / 2).toInt(), (by + bs / 2).toInt())
            b.draw(canvas)
        }
    }

    override fun setPressed(pressed: Boolean) {
        if (pressed != isPressed) spring.press(pressed)
        super.setPressed(pressed)
    }

    override fun performClick(): Boolean = super.performClick()

    private companion object {
        /** Badge centre from the button's centre (dp): it overlaps the rim. */
        const val BADGE_DISTANCE_DP = 26f
    }
}

/** The clear-all button: hold it and a red ring fills up; when it closes, the board clears. */
@SuppressLint("ViewConstructor")
internal class TrashView(context: Context) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; setShadowLayer(dp(6f), 0f, dp(2f), OrbStyle.SHADOW) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2f); color = OrbStyle.DANGER }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3.5f)
        strokeCap = Paint.Cap.ROUND
        color = OrbStyle.DANGER
    }
    private val icon = ContextCompat.getDrawable(context, R.drawable.ic_trash)!!.mutate().apply { setTint(OrbStyle.DANGER) }
    private var progress = 0f
    private val arc = RectF()
    private var animator: ValueAnimator? = null

    init {
        isClickable = true
        isFocusable = true
        contentDescription = context.getString(R.string.clear_hold)
    }

    /** Starts (or cancels) the fill-up that mirrors a press-and-hold of [ms]. */
    fun hold(holding: Boolean, ms: Long) {
        animator?.cancel()
        if (!holding) {
            progress = 0f
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms
            interpolator = LinearInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = dp(16f)
        canvas.drawCircle(cx, cy, r, fill)
        canvas.drawCircle(cx, cy, r - dp(1f), ring)
        val s = dp(18f)
        icon.setBounds((cx - s / 2).toInt(), (cy - s / 2).toInt(), (cx + s / 2).toInt(), (cy + s / 2).toInt())
        icon.draw(canvas)
        if (progress > 0f) {
            val pr = r + dp(4f)
            arc.set(cx - pr, cy - pr, cx + pr, cy + pr)
            canvas.drawArc(arc, -90f, 360f * progress, false, progressPaint)
        }
    }

    override fun performClick(): Boolean = super.performClick()
}

/**
 * Drop target that appears at the bottom while the main orb is dragged: drop the orb on it
 * to close Marcar Pro. It turns red and grows when the orb is close enough to let go.
 */
@SuppressLint("ViewConstructor")
internal class CloseTargetView(context: Context) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { setShadowLayer(dp(10f), 0f, dp(3f), OrbStyle.SHADOW) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2f); color = Color.WHITE }
    private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }

    var armed = false
        set(value) {
            if (field == value) return
            field = value
            animate().scaleX(if (value) ARMED_SCALE else 1f).scaleY(if (value) ARMED_SCALE else 1f).setDuration(140).start()
            invalidate()
        }

    init {
        alpha = 0f
        // Hidden = GONE, not just transparent: Android 12+ blocks touches that pass through another
        // app's visible window, even an empty one (the on-screen keyboard under the × stopped working).
        visibility = GONE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private var shown = false

    fun show(visible: Boolean) {
        shown = visible
        if (!visible) armed = false
        if (visible) visibility = VISIBLE
        animate().alpha(if (visible) 1f else 0f)
            .translationY(if (visible) 0f else dp(24f))
            .setDuration(if (visible) 180 else 140)
            .withEndAction { if (!shown) visibility = GONE }
            .start()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = dp(30f)
        fill.color = if (armed) OrbStyle.DANGER else 0xD91F2329.toInt()
        canvas.drawCircle(cx, cy, r, fill)
        canvas.drawCircle(cx, cy, r - dp(1f), ring)
        val a = dp(9f)
        canvas.drawLine(cx - a, cy - a, cx + a, cy + a, cross)
        canvas.drawLine(cx - a, cy + a, cx + a, cy - a, cross)
    }

    private companion object {
        /** Armed, the red disc grows past the orb sitting on it, so it still shows around it. */
        const val ARMED_SCALE = 1.6f
    }
}

/**
 * Light card next to a ring tool or the orb's colour dot. For the inks it offers the preset
 * colours, the pupil-made custom ones (with a picker to make more) and the thickness; for the
 * eraser, its size.
 */
@SuppressLint("ViewConstructor")
internal class PaletteView(context: Context, private val onPick: (Pick) -> Unit) : LinearLayout(context) {

    private companion object {
        const val LABEL_WIDTH_DP = 88f
        const val SWATCH_DP = 40f
    }

    /** Called when the user touches anywhere outside the card. */
    var onOutside: (() -> Unit)? = null

    /** Called after the card changed size (the picker opened or closed), to place it again. */
    var onResized: (() -> Unit)? = null

    private var picking = false

    /** True while the colour picker is open. */
    val isPicking get() = picking
    private var pickerColor = Color.RED

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_OUTSIDE) {
            onOutside?.invoke()
            return false
        }
        return super.dispatchTouchEvent(ev)
    }

    sealed interface Pick {
        /**
         * A colour was chosen. [live]: the picker is being dragged (try it, don't keep it yet);
         * [save]: add it to the custom colours.
         */
        class Color(val color: Int, val save: Boolean = false, val live: Boolean = false) : Pick
        class Size(val index: Int) : Pick
        class Eraser(val index: Int) : Pick
    }

    init {
        orientation = VERTICAL
        val pad = dpi(14f)
        setPadding(pad, dpi(10f), pad, dpi(10f))
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(18f)
            setStroke(dpi(1f), 0x0F000000)
        }
        elevation = dp(10f)
    }

    /** Opens the card on the picker, starting from [color] (the colours' "+"). */
    fun startPicking(color: Int) {
        picking = true
        pickerColor = color
    }

    /** Closes the picker, so the card opens on the swatches next time. */
    fun reset() {
        picking = false
    }

    fun showFor(tool: Tool, color: Int, sizeIndex: Int, eraserIndex: Int, customs: List<Int>) {
        removeAllViews()
        if (tool == Tool.ERASER) {
            addRow(context.getString(R.string.palette_size), Presets.ERASER_DP.indices.map { i ->
                Swatch(context, OrbStyle.INK, dotRadius = 5f + i * 3.5f, ring = true, label = context.resources.getStringArray(R.array.eraser_size_names)[i]).also {
                    it.chosen = i == eraserIndex
                    it.setOnClickListener { onPick(Pick.Eraser(i)) }
                }
            })
            return
        }
        val names = context.resources.getStringArray(R.array.ink_color_names)
        addRow(context.getString(R.string.palette_color), Presets.COLORS.indices.map { i ->
            colorSwatch(Presets.COLORS[i], color, names[i])
        })
        val mine = customs.map { colorSwatch(it, color, context.getString(R.string.palette_custom)) }
        val add = Swatch(context, OrbStyle.INK, dotRadius = 11f, ring = false, label = context.getString(R.string.custom_add), plus = true).also {
            it.chosen = picking
            it.setOnClickListener {
                picking = !picking
                if (picking) pickerColor = color
                onResized?.invoke() // places the card again, rebuilt for the new state
            }
        }
        addRow(context.getString(R.string.palette_custom), mine + add)
        if (picking) addPicker()
        val sizeNames = context.resources.getStringArray(R.array.size_names)
        addRow(context.getString(R.string.palette_thickness), Presets.SIZES_DP.indices.map { i ->
            Swatch(context, color, dotRadius = 3.5f + i * 3f, ring = false, label = sizeNames[i]).also {
                it.chosen = i == sizeIndex
                it.setOnClickListener { onPick(Pick.Size(i)) }
            }
        })
    }

    private fun colorSwatch(c: Int, current: Int, label: String) =
        Swatch(context, c, dotRadius = 11f, ring = false, label = label).also {
            it.chosen = c == current
            it.setOnClickListener { _ ->
                picking = false
                onPick(Pick.Color(c))
            }
        }

    /** Saturation/brightness square and hue bar; the ink follows live, "Save" keeps the colour. */
    private fun addPicker() {
        val picker = ColorPickerView(context, pickerColor) { c, done ->
            pickerColor = c
            onPick(Pick.Color(c, live = !done))
        }
        val row = LinearLayout(context).apply { orientation = VERTICAL }
        row.addView(picker, LayoutParams(dpi(SWATCH_DP * Presets.COLORS.size), dpi(176f)))
        row.addView(TextView(context).apply {
            text = context.getString(R.string.custom_save)
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(OrbStyle.TEAL)
                cornerRadius = dp(20f)
            }
            isClickable = true
            setOnClickListener {
                picking = false
                onPick(Pick.Color(pickerColor, save = true)) // the card is rebuilt by the pick
            }
        }, LayoutParams(dpi(SWATCH_DP * Presets.COLORS.size), dpi(40f)).apply { topMargin = dpi(10f) })
        val wrap = LinearLayout(context).apply { setPadding(dpi(LABEL_WIDTH_DP), dpi(6f), 0, dpi(10f)) }
        wrap.addView(row)
        addView(wrap)
    }

    private fun addRow(title: String, swatches: List<Swatch>) {
        // One label column for every row, wide enough for "Thickness"/"Grosor".
        val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(TextView(context).apply {
            text = title
            textSize = 14f
            setTextColor(0xFF6B7280.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, LayoutParams(dpi(LABEL_WIDTH_DP), LayoutParams.WRAP_CONTENT))
        for (s in swatches) row.addView(s, LayoutParams(dpi(SWATCH_DP), dpi(SWATCH_DP)))
        addView(row)
    }
}

/**
 * Colour picker: a saturation (→) × brightness (↓) square over a hue bar. [onChange] runs while
 * dragging, with `done` true when the finger or pen lifts.
 */
@SuppressLint("ViewConstructor")
internal class ColorPickerView(context: Context, start: Int, private val onChange: (Int, done: Boolean) -> Unit) : View(context) {
    private val hsv = FloatArray(3).also { Color.colorToHSV(start, it) }
    private val square = RectF()
    private val bar = RectF()
    private val svPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val huePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(3f); color = Color.WHITE }
    private val markerEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1f); color = 0x66000000 }
    private val corner = dp(10f)
    private var dragging = 0 // 1 square, 2 hue bar

    init {
        contentDescription = context.getString(R.string.custom_add)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val barH = dp(26f)
        square.set(0f, 0f, w.toFloat(), h - barH - dp(12f))
        bar.set(0f, h - barH, w.toFloat(), h.toFloat())
        val hues = IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) }
        huePaint.shader = android.graphics.LinearGradient(bar.left, 0f, bar.right, 0f, hues, null, android.graphics.Shader.TileMode.CLAMP)
        shadePaint.shader = android.graphics.LinearGradient(0f, square.top, 0f, square.bottom, 0x00000000, 0xFF000000.toInt(), android.graphics.Shader.TileMode.CLAMP)
        updateSquare()
    }

    private fun updateSquare() {
        val pure = Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f))
        svPaint.shader = android.graphics.LinearGradient(square.left, 0f, square.right, 0f, Color.WHITE, pure, android.graphics.Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRoundRect(square, corner, corner, svPaint)
        canvas.drawRoundRect(square, corner, corner, shadePaint)
        canvas.drawRoundRect(bar, bar.height() / 2, bar.height() / 2, huePaint)
        val sx = square.left + hsv[1] * square.width()
        val sy = square.top + (1 - hsv[2]) * square.height()
        canvas.drawCircle(sx, sy, dp(10f), markerEdge)
        canvas.drawCircle(sx, sy, dp(9f), marker)
        val hx = bar.left + hsv[0] / 360f * bar.width()
        canvas.drawCircle(hx, bar.centerY(), bar.height() / 2 + dp(1f), markerEdge)
        canvas.drawCircle(hx, bar.centerY(), bar.height() / 2, marker)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                dragging = if (e.y >= bar.top - dp(8f)) 2 else 1
                parent?.requestDisallowInterceptTouchEvent(true)
                apply(e.x, e.y, done = false)
            }
            android.view.MotionEvent.ACTION_MOVE -> apply(e.x, e.y, done = false)
            android.view.MotionEvent.ACTION_UP -> apply(e.x, e.y, done = true)
            android.view.MotionEvent.ACTION_CANCEL -> dragging = 0
        }
        return true
    }

    private fun apply(x: Float, y: Float, done: Boolean) {
        if (dragging == 2) {
            hsv[0] = ((x - bar.left) / bar.width()).coerceIn(0f, 1f) * 359.9f
            // Picking a hue on a grey would show nothing: make it vivid.
            if (hsv[1] < 0.15f) hsv[1] = 1f
            if (hsv[2] < 0.15f) hsv[2] = 1f
            updateSquare()
        } else if (dragging == 1) {
            hsv[1] = ((x - square.left) / square.width()).coerceIn(0f, 1f)
            hsv[2] = 1 - ((y - square.top) / square.height()).coerceIn(0f, 1f)
        }
        if (done) dragging = 0
        invalidate()
        onChange(Color.HSVToColor(hsv), done)
    }
}

/** A choosable dot (colour or thickness) or ring (eraser size); the chosen one gets a dark ring. */
@SuppressLint("ViewConstructor")
internal class Swatch(
    context: Context,
    private val color: Int,
    dotRadius: Float,
    private val ring: Boolean,
    label: String,
    private val plus: Boolean = false,
) : View(context) {
    private val r = dp(dotRadius)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = this@Swatch.color }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.75f); this.color = OrbStyle.INK }
    private val chosenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2.25f); this.color = OrbStyle.INK }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1f); this.color = 0x24000000 }
    private val rainbow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(3f) }

    var chosen = false
        set(value) { field = value; isSelected = value; invalidate() }

    init {
        contentDescription = label
        isClickable = true
        isFocusable = true
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        if (plus) {
            // "Make a colour": a rainbow ring with a plus.
            canvas.drawCircle(cx, cy, r - dp(1.5f), rainbow)
            val a = dp(5f)
            canvas.drawLine(cx - a, cy, cx + a, cy, outline)
            canvas.drawLine(cx, cy - a, cx, cy + a, outline)
        } else if (ring) {
            canvas.drawCircle(cx, cy, r, outline)
        } else {
            canvas.drawCircle(cx, cy, r, fill)
            if (ColorUtils.calculateLuminance(color) > 0.7) canvas.drawCircle(cx, cy, r, edge)
        }
        if (chosen) canvas.drawCircle(cx, cy, maxOf(r + dp(4f), dp(9f)), chosenPaint)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (plus) {
            val hues = IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 0.85f, 0.95f)) }
            rainbow.shader = android.graphics.SweepGradient(w / 2f, h / 2f, hues, null)
        }
    }

    override fun performClick(): Boolean = super.performClick()
}

/**
 * The whiteboard's background, at the bottom left while it is open: one button showing the paper
 * in use; a tap opens the list upwards (plain, dots, squares, lines, double ruling), each a small
 * sample of the paper with its name, and picking one (or a tap anywhere else) closes it.
 */
@SuppressLint("ViewConstructor")
internal class PaperPickerView(context: Context, private val onPick: (Paper) -> Unit) : View(context) {
    private val names = context.resources.getStringArray(R.array.paper_names)
    private val cellW = dp(CELL_W_DP)
    private val cellH = dp(CELL_H_DP)
    private val pad = dp(PAD_DP)
    private val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; setShadowLayer(dp(8f), 0f, dp(3f), OrbStyle.SHADOW) }
    private val chosenFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ColorUtils.setAlphaComponent(OrbStyle.TEAL, 0x2E) }
    private val sheet = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val sheetEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.5f); color = 0x40000000 }
    private val chosenEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2.5f); color = OrbStyle.TEAL }
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x991F2329.toInt(); strokeWidth = dp(1.3f); strokeCap = Paint.Cap.ROUND }
    private val chevron = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = OrbStyle.TEAL_DARK
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = context.sp(11.5f)
        color = OrbStyle.LABEL
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val r = RectF()

    var paper = Paper.DOTS
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** True while the list is open. */
    var expanded = false
        private set

    init {
        isClickable = true
        contentDescription = context.getString(R.string.paper_label)
    }

    /** Closes the list (a tap elsewhere, the whiteboard closing). */
    fun collapse() {
        if (!expanded) return
        expanded = false
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val rows = if (expanded) Paper.entries.size else 1
        setMeasuredDimension((cellW + 2 * pad).toInt(), (cellH * rows + 2 * pad).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        r.set(pad, pad, width - pad, height - pad)
        canvas.drawRoundRect(r, dp(16f), dp(16f), card)
        if (!expanded) {
            drawCell(canvas, paper, pad, chosen = false)
            // "Opens upwards".
            val cx = width - pad - dp(12f)
            val cy = pad + dp(12f)
            val a = dp(5f)
            canvas.drawLine(cx - a, cy + a / 2, cx, cy - a / 2, chevron)
            canvas.drawLine(cx, cy - a / 2, cx + a, cy + a / 2, chevron)
            return
        }
        for (p in Paper.entries) drawCell(canvas, p, pad + p.ordinal * cellH, chosen = p == paper)
    }

    private fun drawCell(canvas: Canvas, p: Paper, top: Float, chosen: Boolean) {
        if (chosen) {
            r.set(pad + dp(4f), top + dp(4f), pad + cellW - dp(4f), top + cellH - dp(4f))
            canvas.drawRoundRect(r, dp(12f), dp(12f), chosenFill)
        }
        // A little sheet of that paper.
        val s = dp(26f)
        val cx = pad + cellW / 2
        val sheetTop = top + dp(9f)
        r.set(cx - s / 2, sheetTop, cx + s / 2, sheetTop + s)
        canvas.drawRoundRect(r, dp(4f), dp(4f), sheet)
        val step = s / 4
        when (p) {
            Paper.BLANK -> Unit
            Paper.DOTS -> for (i in 1..3) for (j in 1..3) canvas.drawCircle(r.left + i * step, r.top + j * step, dp(1.3f), mark)
            Paper.GRID -> for (i in 1..3) {
                canvas.drawLine(r.left + i * step, r.top + dp(2f), r.left + i * step, r.bottom - dp(2f), mark)
                canvas.drawLine(r.left + dp(2f), r.top + i * step, r.right - dp(2f), r.top + i * step, mark)
            }
            Paper.LINES -> for (i in 1..3) canvas.drawLine(r.left + dp(3f), r.top + i * step, r.right - dp(3f), r.top + i * step, mark)
            Paper.DOUBLE -> for (y in floatArrayOf(0.22f, 0.4f, 0.64f, 0.82f)) {
                canvas.drawLine(r.left + dp(3f), r.top + y * s, r.right - dp(3f), r.top + y * s, mark)
            }
        }
        canvas.drawRoundRect(r, dp(4f), dp(4f), if (chosen) chosenEdge else sheetEdge)
        canvas.drawText(names[p.ordinal], cx, sheetTop + s + dp(17f), text)
    }

    override fun dispatchTouchEvent(e: android.view.MotionEvent): Boolean {
        if (e.actionMasked == android.view.MotionEvent.ACTION_OUTSIDE) {
            collapse()
            return false
        }
        return super.dispatchTouchEvent(e)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        if (e.actionMasked != android.view.MotionEvent.ACTION_UP) return true
        if (!expanded) {
            expanded = true
            requestLayout()
            invalidate()
            return true
        }
        Paper.entries.getOrNull(((e.y - pad) / cellH).toInt())?.let {
            paper = it
            onPick(it)
        }
        collapse()
        return true
    }

    private companion object {
        const val CELL_W_DP = 76f
        const val CELL_H_DP = 64f
        const val PAD_DP = 10f
    }
}

/**
 * Marcar Pro's own short messages ("Writing", "Undone"…), in a dark pill at the bottom centre.
 * Android's toasts can't be relied on: on the panel they are hidden when the app's notifications
 * are off. Never takes a touch.
 */
@SuppressLint("ViewConstructor")
internal class HintView(context: Context) : TextView(context) {
    init {
        textSize = 17f
        setTextColor(Color.WHITE)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        maxWidth = dpi(720f)
        setPadding(dpi(22f), dpi(12f), dpi(22f), dpi(12f))
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xE61F2329.toInt())
            cornerRadius = dp(24f)
        }
        alpha = 0f
        // Between messages the pill is GONE, not just transparent: Android 12+ blocks touches that
        // pass through another app's visible window (the keyboard's middle keys under it stopped working).
        visibility = GONE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
    }

    private var shown = false
    private val hide = Runnable {
        shown = false
        animate().alpha(0f).setDuration(220).withEndAction { if (!shown) visibility = GONE }.start()
    }

    fun show(message: CharSequence, long: Boolean) {
        removeCallbacks(hide)
        shown = true
        text = message
        visibility = VISIBLE
        animate().alpha(1f).setDuration(140).start()
        postDelayed(hide, if (long) LONG_MS else SHORT_MS)
    }

    private companion object {
        const val SHORT_MS = 2200L
        const val LONG_MS = 4500L
    }
}

/**
 * One of the quick colours next to the orb. The one in use is full size with a white rim; the
 * others are smaller, in their true colour, with only a fine see-through rim, so the one in use stands out
 * on any background (the user's choice "D").
 */
internal class QuickColorView(context: Context) : View(context) {
    var color = Color.BLACK
        set(value) {
            if (field == value) return
            field = value
            contentDescription = context.getString(R.string.quick_color)
            invalidate()
        }

    /** This is the colour in use (a second tap changes the colour kept here). */
    var active = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        color = Color.WHITE
    }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = 0x33000000
    }

    // The softer ones: a fine see-through white rim, so a dark colour still shows on a dark screen.
    private val hairline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = 0x66FFFFFF
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // the shadow layer
        visibility = GONE
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val press = if (isPressed) 1.2f else 1f
        val light = androidx.core.graphics.ColorUtils.calculateLuminance(color) > 0.7
        fill.color = color
        if (active) {
            val r = dp(ACTIVE_DP) * press
            fill.setShadowLayer(dp(4f), 0f, dp(1.5f), OrbStyle.SHADOW)
            canvas.drawCircle(cx, cy, r, fill)
            canvas.drawCircle(cx, cy, r - dp(1.25f), rim)
            if (light) canvas.drawCircle(cx, cy, r, edge)
        } else {
            val r = dp(IDLE_DP) * press
            // Smaller, but in their true colour: see-through, black looked grey (another colour).
            fill.clearShadowLayer()
            canvas.drawCircle(cx, cy, r, fill)
            // A white or yellow dot would vanish on the whiteboard without a dark hairline.
            canvas.drawCircle(cx, cy, r - dp(0.5f), if (light) edge else hairline)
        }
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        invalidate()
    }

    private companion object {
        const val ACTIVE_DP = 11f
        const val IDLE_DP = 8f
    }
}
