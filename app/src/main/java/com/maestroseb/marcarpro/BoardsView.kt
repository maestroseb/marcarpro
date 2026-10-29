package com.maestroseb.marcarpro

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils

/**
 * A little sheet of [p] paper in [color] inside [r]: the picker's samples and the boards' thumbnails.
 * [mark] gets its colour here; its stroke width is the caller's.
 */
internal fun drawPaperSheet(canvas: Canvas, r: RectF, p: Paper, color: PaperColor, sheet: Paint, mark: Paint, corner: Float) {
    sheet.color = color.background
    canvas.drawRoundRect(r, corner, corner, sheet)
    mark.color = if (color.dark) 0xB3FFFFFF.toInt() else 0x991F2329.toInt()
    val w = r.width()
    val h = r.height()
    val inset = w * 0.12f
    when (p) {
        Paper.BLANK -> Unit
        Paper.DOTS -> {
            val step = w / 4
            val rows = (h / step).toInt()
            for (i in 1..3) for (j in 1..rows) {
                val y = r.top + j * step - (rows * step - h) / 2 - step / 2
                if (y > r.top + step / 3 && y < r.bottom - step / 3) canvas.drawCircle(r.left + i * step, y, mark.strokeWidth, mark)
            }
        }
        Paper.GRID -> {
            val step = w / 4
            var x = r.left + step
            while (x < r.right - 1) { canvas.drawLine(x, r.top + 2, x, r.bottom - 2, mark); x += step }
            var y = r.top + (h % step) / 2 + step / 2
            while (y < r.bottom - 1) { canvas.drawLine(r.left + 2, y, r.right - 2, y, mark); y += step }
        }
        Paper.LINES -> {
            val step = h / 4
            for (i in 1..3) canvas.drawLine(r.left + inset, r.top + i * step, r.right - inset, r.top + i * step, mark)
        }
        Paper.DOUBLE -> for (y in floatArrayOf(0.22f, 0.4f, 0.64f, 0.82f)) {
            canvas.drawLine(r.left + inset, r.top + y * h, r.right - inset, r.top + y * h, mark)
        }
        Paper.STAFF -> for (i in 0 until 5) {
            val y = r.top + h * (0.3f + i * 0.1f) // five lines close together: a staff
            canvas.drawLine(r.left + inset, y, r.right - inset, y, mark)
        }
    }
}

/** A white card with rounded corners and a soft shadow, [margin] inside its bounds for the shadow. */
internal class CardBackground(context: Context, private val margin: Float, private val corner: Float) : android.graphics.drawable.Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        setShadowLayer(context.dp(10f), 0f, context.dp(3f), OrbStyle.SHADOW)
    }
    private val r = RectF()

    override fun draw(canvas: Canvas) {
        r.set(bounds)
        r.inset(margin, margin)
        canvas.drawRoundRect(r, corner, corner, paint)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/**
 * "My boards": a card over the paper picker with the quick board, the saved ones (pinned first,
 * then the most recently opened) and the bin. Each saved board has a pin and a bin button; a tap
 * on a row opens that board. Graphite on white, like every control that isn't the orb.
 */
@SuppressLint("ViewConstructor")
internal class BoardListView(context: Context, private val listener: Listener) : LinearLayout(context) {

    interface Listener {
        fun onOpenBoard(board: Boards.Saved?)
        fun onPinBoard(board: Boards.Saved, pinned: Boolean)
        fun onThrowBoard(board: Boards.Saved)
        fun onRestoreBoard(board: Boards.Saved)
        fun onSaveQuick()
        fun onNewBoard()
        fun onBoardsOutside()

        /** A picture of the board's ink, or null (its paper is shown instead). */
        fun boardThumb(board: Boards.Saved): android.graphics.Bitmap?
    }

    private val margin = dp(MARGIN_DP)
    private val title = text(17f, OrbStyle.INK, bold = true)
    private val binButton = text(14f, OrbStyle.CHROME, bold = true).apply {
        setPadding(dpi(12f), dpi(7f), dpi(12f), dpi(7f))
        background = GradientDrawable().apply {
            cornerRadius = dp(16f)
            setStroke(dpi(1f), OrbStyle.CHROME_HAIRLINE)
        }
        isClickable = true
        setOnClickListener {
            inBin = !inBin
            boards?.let { show(it, quickHasInk) }
        }
    }
    private val rows = LinearLayout(context).apply { orientation = VERTICAL }
    private val scroll = object : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dpi(MAX_LIST_DP), MeasureSpec.AT_MOST))
        }
    }.apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        addView(rows)
    }
    private val action = text(15f, Color.WHITE, bold = true).apply {
        gravity = Gravity.CENTER
        setPadding(dpi(16f), dpi(12f), dpi(16f), dpi(12f))
        background = GradientDrawable().apply {
            setColor(OrbStyle.CHROME)
            cornerRadius = dp(22f)
        }
        isClickable = true
    }
    private val rule = text(12.5f, MUTED).apply { setLineSpacing(0f, 1.25f) }

    private var boards: Boards? = null
    private var quickHasInk = false
    private var inBin = false

    init {
        orientation = VERTICAL
        val m = margin.toInt()
        background = CardBackground(context, margin, dp(18f))
        setPadding(m + dpi(14f), m + dpi(14f), m + dpi(14f), m + dpi(14f))
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) setLayerType(LAYER_TYPE_SOFTWARE, null)
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(binButton)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dpi(8f) })
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(action, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(10f) })
        addView(rule, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(10f) })
    }

    /** Starts on the list (not the bin), scrolled to the top. */
    fun reset() {
        inBin = false
        scroll.scrollTo(0, 0)
    }

    /** Fills the card in from [b]: called on every change, it is only a few rows. */
    fun show(b: Boards, quickHasInk: Boolean) {
        boards = b
        this.quickHasInk = quickHasInk
        rows.removeAllViews()
        val bin = b.bin
        // The last one brought back: back to the list, where it now is.
        if (inBin && bin.isEmpty() && b.active.isNotEmpty()) inBin = false
        title.text = context.getString(if (inBin) R.string.boards_bin_title else R.string.boards_title)
        binButton.text = when {
            inBin -> context.getString(R.string.boards_back)
            bin.isEmpty() -> context.getString(R.string.boards_bin_title)
            else -> context.getString(R.string.boards_bin, bin.size)
        }
        if (inBin) {
            if (bin.isEmpty()) {
                rows.addView(text(14f, MUTED).apply {
                    text = context.getString(R.string.boards_bin_empty)
                    setPadding(dpi(8f), dpi(14f), dpi(8f), dpi(14f))
                })
            }
            val now = System.currentTimeMillis()
            for (s in bin) {
                val left = Boards.BIN_DAYS - daysSince(s.trashedAt, now)
                val meta = context.resources.getQuantityString(R.plurals.board_deleted_in, left.toInt().coerceAtLeast(1), left.coerceAtLeast(1))
                rows.addView(row(s.name, meta, s.paper, s.color, listener.boardThumb(s), chosen = false, onTap = null,
                    icons = listOf(Icon(R.drawable.ic_restore, R.string.board_restore, on = true) { listener.onRestoreBoard(s) })))
            }
            action.visibility = GONE
            rule.text = context.getString(R.string.boards_bin_rule)
            return
        }
        val current = b.current
        // The quick board first: it is where the whiteboard always starts.
        rows.addView(row(context.getString(R.string.board_quick), context.getString(R.string.board_quick_meta),
            null, null, null, chosen = current == null, onTap = { listener.onOpenBoard(null) }, icons = emptyList()))
        val now = System.currentTimeMillis()
        for (s in b.active) {
            val days = daysSince(s.opened, now)
            val left = Boards.EXPIRE_DAYS - days
            val opened = when (days) {
                0L -> context.getString(R.string.board_opened_today)
                1L -> context.getString(R.string.board_opened_yesterday)
                else -> context.resources.getQuantityString(R.plurals.board_opened_days, days.toInt(), days)
            }
            val pages = if (s.pages > 1) " · " + context.resources.getQuantityString(R.plurals.board_pages, s.pages, s.pages) else ""
            val (meta, warn) = when {
                s.pinned -> context.getString(R.string.board_pinned) + " · " + opened.replaceFirstChar { it.lowercase() } + pages to false
                left <= WARN_DAYS -> context.resources.getQuantityString(R.plurals.board_expires, left.toInt().coerceAtLeast(1), left.coerceAtLeast(1)) to true
                else -> opened + pages to false
            }
            rows.addView(row(s.name, meta, s.paper, s.color, listener.boardThumb(s), chosen = s === current, warn = warn,
                onTap = { listener.onOpenBoard(s) },
                icons = listOf(
                    Icon(R.drawable.ic_pin, if (s.pinned) R.string.board_unpin else R.string.board_pin, on = s.pinned) { listener.onPinBoard(s, !s.pinned) },
                    Icon(R.drawable.ic_trash, R.string.board_throw, on = false) { listener.onThrowBoard(s) },
                )))
        }
        action.visibility = VISIBLE
        if (current == null) {
            action.text = context.getString(R.string.boards_save_new)
            action.setOnClickListener { listener.onSaveQuick() }
        } else {
            action.text = context.getString(R.string.boards_new)
            action.setOnClickListener { listener.onNewBoard() }
        }
        rule.text = context.getString(R.string.boards_rule)
    }

    private class Icon(val res: Int, val label: Int, val on: Boolean, val onTap: () -> Unit)

    private fun row(
        name: String, meta: String, paper: Paper?, color: PaperColor?, thumb: android.graphics.Bitmap?, chosen: Boolean, warn: Boolean = false,
        onTap: (() -> Unit)?, icons: List<Icon>,
    ): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dpi(8f), dpi(8f), dpi(4f), dpi(8f))
        background = GradientDrawable().apply {
            cornerRadius = dp(12f)
            setColor(if (chosen) ColorUtils.setAlphaComponent(OrbStyle.CHROME, 0x14) else Color.TRANSPARENT)
        }
        if (onTap != null) {
            isClickable = true
            setOnClickListener { onTap() }
        }
        addView(Thumb(context, paper, color, thumb), LayoutParams(dpi(THUMB_W_DP), dpi(THUMB_H_DP)))
        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            addView(text(15f, OrbStyle.INK, bold = true).apply {
                text = name
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            addView(text(12.5f, if (warn) WARN else MUTED).apply {
                text = meta
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dpi(12f) })
        for (i in icons) addView(ImageView(context).apply {
            setImageDrawable(ContextCompat.getDrawable(context, i.res)!!.mutate().apply { setTint(if (i.on) OrbStyle.CHROME else IDLE_ICON) })
            contentDescription = context.getString(i.label)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dpi(9f), dpi(9f), dpi(9f), dpi(9f))
            isClickable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (i.on) ColorUtils.setAlphaComponent(OrbStyle.CHROME, 0x14) else Color.TRANSPARENT)
            }
            setOnClickListener { i.onTap() }
        }, LayoutParams(dpi(40f), dpi(40f)))
    }

    /** A board's thumbnail: its ink if there is a picture of it, else its paper; the quick board's is a lightning bolt. */
    private class Thumb(context: Context, private val paper: Paper?, private val color: PaperColor?, private val picture: android.graphics.Bitmap?) : View(context) {
        private val clip = android.graphics.Path()
        private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val sheet = Paint(Paint.ANTI_ALIAS_FLAG)
        private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = dp(1f); strokeCap = Paint.Cap.ROUND }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1f); color = 0x33000000 }
        private val bolt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = OrbStyle.CHROME }
        private val path = android.graphics.Path()
        private val r = RectF()

        override fun onDraw(canvas: Canvas) {
            r.set(0.5f, 0.5f, width - 0.5f, height - 0.5f)
            val corner = dp(6f)
            if (paper == null || color == null) {
                sheet.color = 0xFFF3F4F5.toInt()
                canvas.drawRoundRect(r, corner, corner, sheet)
                // A lightning bolt: the board for right now.
                val cx = width / 2f
                val cy = height / 2f
                val u = height / 9f
                path.reset()
                path.moveTo(cx + 0.6f * u, cy - 3.4f * u)
                path.lineTo(cx - 2f * u, cy + 0.5f * u)
                path.lineTo(cx - 0.1f * u, cy + 0.5f * u)
                path.lineTo(cx - 0.6f * u, cy + 3.4f * u)
                path.lineTo(cx + 2f * u, cy - 0.5f * u)
                path.lineTo(cx + 0.1f * u, cy - 0.5f * u)
                path.close()
                canvas.drawPath(path, bolt)
            } else if (picture != null) {
                clip.reset()
                clip.addRoundRect(r, corner, corner, android.graphics.Path.Direction.CW)
                canvas.save()
                canvas.clipPath(clip)
                canvas.drawBitmap(picture, null, r, bitmapPaint)
                canvas.restore()
            } else {
                drawPaperSheet(canvas, r, paper, color, sheet, mark, corner)
            }
            canvas.drawRoundRect(r, corner, corner, edge)
        }
    }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            listener.onBoardsOutside()
            return false
        }
        return super.dispatchTouchEvent(e)
    }

    private fun text(size: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    companion object {
        const val MARGIN_DP = 10f
        const val THUMB_W_DP = 60f
        const val THUMB_H_DP = 40f
        /** The card's width, shadow margin included. */
        const val WIDTH_DP = 380f
        private const val MAX_LIST_DP = 330f
        private const val WARN_DAYS = 7
        private const val MUTED = 0xFF6B7280.toInt()
        private const val IDLE_ICON = 0xFF9AA0A8.toInt()
        private const val WARN = 0xFFB25E00.toInt() // amber that still reads on white
        private const val DAY_MS = 24L * 60 * 60 * 1000

        private fun daysSince(time: Long, now: Long) = ((now - time) / DAY_MS).coerceAtLeast(0)
    }
}

/**
 * The whiteboard's pages, in the top left corner: "‹ 2 / 3 › +". The arrows turn the page, the
 * plus adds one at the end. Turning past the first or the last page does nothing (the arrow is
 * faded there). Graphite on white.
 */
@SuppressLint("ViewConstructor")
internal class PagePillView(context: Context, private val onPrev: () -> Unit, private val onNext: () -> Unit, private val onAdd: () -> Unit) : View(context) {
    private val margin = dp(8f)
    private val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; setShadowLayer(dp(8f), 0f, dp(2.5f), OrbStyle.SHADOW) }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.4f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = OrbStyle.CHROME
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = context.sp(14.5f)
        color = OrbStyle.INK
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
        fontFeatureSettings = "tnum"
    }
    private val divider = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = OrbStyle.CHROME_HAIRLINE; strokeWidth = dp(1f) }
    private val pressFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ColorUtils.setAlphaComponent(OrbStyle.CHROME, 0x14) }
    private val r = RectF()
    private var pressed = -1

    var page = 0
        private set
    var pages = 1
        private set

    init {
        isClickable = true
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun set(page: Int, pages: Int) {
        if (page == this.page && pages == this.pages) return
        this.page = page
        this.pages = pages
        contentDescription = context.getString(R.string.page_of, page + 1, pages)
        invalidate()
    }

    private val cell get() = dp(CELL_DP)
    private val middle get() = dp(MIDDLE_DP)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((cell * 3 + middle + 2 * margin).toInt(), (dp(HEIGHT_DP) + 2 * margin).toInt())
    }

    // Parts left to right: previous, the "2 / 3" label, next, add.
    private fun part(x: Float): Int {
        val local = x - margin
        return when {
            local < cell -> 0
            local < cell + middle -> 1
            local < 2 * cell + middle -> 2
            else -> 3
        }
    }

    private fun left(part: Int): Float = margin + when (part) {
        0 -> 0f
        1 -> cell
        2 -> cell + middle
        else -> 2 * cell + middle
    }

    override fun onDraw(canvas: Canvas) {
        val h = dp(HEIGHT_DP)
        r.set(margin, margin, width - margin, margin + h)
        canvas.drawRoundRect(r, h / 2, h / 2, card)
        if (pressed in listOf(0, 2, 3)) {
            val x = left(pressed)
            canvas.drawCircle(x + cell / 2, margin + h / 2, h / 2 - dp(5f), pressFill)
        }
        val cy = margin + h / 2
        val a = dp(4.5f)
        // ‹
        glyph.alpha = if (page > 0) 255 else 70
        var cx = left(0) + cell / 2 + dp(2f)
        canvas.drawLine(cx + a / 2, cy - a, cx - a / 2, cy, glyph)
        canvas.drawLine(cx - a / 2, cy, cx + a / 2, cy + a, glyph)
        // ›
        glyph.alpha = if (page < pages - 1) 255 else 70
        cx = left(2) + cell / 2 - dp(2f)
        canvas.drawLine(cx - a / 2, cy - a, cx + a / 2, cy, glyph)
        canvas.drawLine(cx + a / 2, cy, cx - a / 2, cy + a, glyph)
        // + after a hairline: it changes the board, the arrows only look through it.
        glyph.alpha = 255
        val sep = left(3)
        canvas.drawLine(sep, cy - dp(10f), sep, cy + dp(10f), divider)
        cx = sep + cell / 2
        canvas.drawLine(cx - a, cy, cx + a, cy, glyph)
        canvas.drawLine(cx, cy - a, cx, cy + a, glyph)
        val t = "${page + 1} / $pages"
        canvas.drawText(t, left(1) + middle / 2, cy - (label.descent() + label.ascent()) / 2, label)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pressed = part(e.x).takeIf { it != 1 } ?: -1; invalidate() }
            MotionEvent.ACTION_CANCEL -> { pressed = -1; invalidate() }
            MotionEvent.ACTION_UP -> {
                val p = part(e.x)
                pressed = -1
                invalidate()
                when (p) {
                    0 -> if (page > 0) onPrev()
                    2 -> if (page < pages - 1) onNext()
                    3 -> onAdd()
                }
            }
        }
        return true
    }

    private companion object {
        const val HEIGHT_DP = 44f
        const val CELL_DP = 40f
        const val MIDDLE_DP = 52f
    }
}
