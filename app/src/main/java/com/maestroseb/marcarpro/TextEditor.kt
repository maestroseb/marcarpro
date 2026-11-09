package com.maestroseb.marcarpro

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextWatcher
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withTranslation
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** A typed text laid out at letter size [size]: one line per line typed, never wrapped. */
internal class TextBody(context: Context, text: String, size: Float) {
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = Fonts.regular(context)
        textSize = size
    }
    val layout: StaticLayout = StaticLayout.Builder
        .obtain(text, 0, text.length, paint, ceil(StaticLayout.getDesiredWidth(text, paint)).toInt() + 2)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setIncludePad(false)
        .build()
    val width get() = layout.width.toFloat()
    val height get() = layout.height.toFloat()
}

/**
 * True with a real keyboard plugged in. A panel's touch frame or remote can present itself as a
 * keyboard too, so built-in devices and anything that also touches don't count; should something
 * else slip through, the bar's keyboard key changes the way of writing by hand.
 */
internal fun hasPhysicalKeyboard(): Boolean = InputDevice.getDeviceIds().any { id ->
    val d = InputDevice.getDevice(id) ?: return@any false
    val external = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || d.isExternal
    !d.isVirtual && external && d.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC &&
        !d.supportsSource(InputDevice.SOURCE_TOUCHSCREEN)
}

/**
 * Typing a text on the board. The words appear at once in their place, at their real size and
 * colour, in a window of their own: typing never repaints the full-screen ink. Two ways of writing:
 *  - in place, with a physical keyboard: only a small bar over the text;
 *  - with the on-screen keyboard: a card just above it, at the height of the teacher's hands
 *    (on a 75" board the spot may be out of reach), joined to the spot by a dashed line.
 *
 * Both type into the same field, in a focusable window as tall as the screen but only as wide as
 * the card: its height is what tells how much the keyboard covers. A tap outside ends the typing.
 */
internal class TextEditor(context: Context, private val wm: WindowManager) {
    private val prefs = Prefs(context)

    /** What was typed: [text] goes with its top left corner at ([x], [y]) on screen. */
    class Draft(val text: String, val color: Int, val size: Float, val sizeIndex: Int, val x: Float, val y: Float)

    /** Null when cancelled. */
    var onDone: ((Draft?) -> Unit)? = null

    /** The screen areas in use now (for the panel's touch), empty when closed. */
    var onAreas: ((card: Rect, spot: Rect, keyboard: Rect) -> Unit)? = null
    var onLeader: ((FloatArray?) -> Unit)? = null

    /** How a colour shows on the board under the text. */
    var shownColor: (Int) -> Int = { it }

    /** Where the on-screen keyboard is (empty: nowhere), or null when that can't be known. */
    var keyboardBounds: () -> Rect? = { null }

    private val context = ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault_Light_NoActionBar)
    private val imm = context.getSystemService(InputMethodManager::class.java)
    private val screen = wm.screenSize()

    private var root: FrameLayout? = null
    private lateinit var card: LinearLayout
    private lateinit var field: Field
    private lateinit var bar: TextBar
    private lateinit var spot: TextSpot
    private val rootParams = WindowManager.LayoutParams(
        0, 0, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
    }
    private val spotParams = WindowManager.LayoutParams(
        0, 0, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private var x = 0f
    private var y = 0f
    private var size = 0f
    private var sizeIndex = -1
    private var color = Color.BLACK
    private var inPlace = false
    private var keyboard = 0

    // A floating keyboard leaves no insets: its place is read from time to time while typing on the
    // card, as the teacher can drag it.
    private val floating = Rect()
    private var floatingKnown = true
    private var waited = false
    private val watch = object : Runnable {
        override fun run() {
            if (!isOpen) return
            if (!inPlace) {
                val read = keyboardBounds()
                // The whole screen isn't a keyboard: it's being dragged, or its window says nothing.
                val blurred = read != null && read.height() > screen.y * MAX_KEYBOARD_HEIGHT
                val now = if (blurred) floating.takeUnless { it.isEmpty } else read
                val first = !waited
                waited = true
                if (first || (now != null) != floatingKnown || (now ?: Rect()) != floating) {
                    floatingKnown = now != null
                    floating.set(now ?: Rect())
                    arrange()
                }
            }
            root?.postDelayed(this, WATCH_MS)
        }
    }
    private var cardGap = 0
    private val areaColumn = Rect()
    private val areaSpot = Rect()
    private val areaKeyboard = Rect()

    val isOpen get() = root != null

    private fun dp(v: Float) = context.dp(v)
    private fun dpi(v: Float) = context.dpi(v)

    /** Opens with [text] at ([x], [y]); [sizeIndex] is -1 when [size] isn't one of the bar's. */
    @SuppressLint("ClickableViewAccessibility")
    fun open(x: Float, y: Float, text: String, color: Int, size: Float, sizeIndex: Int) {
        if (isOpen) return
        this.x = x
        this.y = y
        this.color = color
        this.size = size
        this.sizeIndex = sizeIndex
        inPlace = hasPhysicalKeyboard() || System.currentTimeMillis() - prefs.realKeyAt < REAL_KEY_MEMORY_MS
        keyboard = 0
        floating.setEmpty()
        floatingKnown = true
        waited = false

        field = Field(context).apply {
            setText(text)
            setSelection(text.length)
            hint = context.getString(R.string.text_type_here)
            setTextColor(Chrome.text)
            setHintTextColor(Chrome.hint)
            typeface = Fonts.regular(context)
            textSize = FIELD_SP
            background = null
            maxLines = FIELD_LINES
            isVerticalScrollBarEnabled = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            onCaret = { start, end -> if (::spot.isInitialized) spot.select(start, end) }
            onRealKey = {
                prefs.realKeyAt = System.currentTimeMillis()
                if (!inPlace) switchWriting() else hideOnScreenKeyboard()
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) = refreshSpot()
            })
            setOnKeyListener { _, code, e ->
                if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when {
                    code == KeyEvent.KEYCODE_ESCAPE -> finish(commit = false).let { true }
                    code == KeyEvent.KEYCODE_ENTER && e.isCtrlPressed -> finish(commit = true).let { true }
                    else -> false
                }
            }
        }
        bar = TextBar(context, this.color, sizeIndex, inPlace).apply {
            onColor = {
                this@TextEditor.color = it
                refreshSpot()
            }
            onSize = {
                this@TextEditor.sizeIndex = it
                this@TextEditor.size = dp(SIZES_DP[it])
                refreshSpot()
            }
            onKeyboard = {
                switchWriting()
                // Chosen by hand: the on-screen keyboard until a real key is typed again.
                if (!inPlace) prefs.realKeyAt = 0L
            }
        }
        val width = min(dpi(CARD_DP), screen.x - 2 * dpi(EDGE_DP))
        card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            background = GradientDrawable().apply {
                setColor(Chrome.surface)
                cornerRadius = dp(18f)
                setStroke(dpi(1f), Chrome.hairline)
            }
            elevation = dp(8f)
            addView(field, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpi(TextBar.HEIGHT_DP)))
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placed() }
        }
        val window = object : FrameLayout(context) {
            override fun dispatchTouchEvent(e: MotionEvent): Boolean {
                if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    // Touches on the on-screen keyboard (another app's window) come with no position.
                    if ((e.rawX != 0f || e.rawY != 0f) && !onSpot(e.rawX, e.rawY)) finish(commit = true)
                    return false
                }
                return super.dispatchTouchEvent(e)
            }

            override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                super.onWindowFocusChanged(hasWindowFocus)
                if (!hasWindowFocus) return
                field.requestFocus()
                if (!inPlace) imm.showSoftInput(field, 0)
            }
        }
        window.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) finish(commit = true)
            true
        }
        window.setOnApplyWindowInsetsListener { _, insets ->
            val h = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(WindowInsets.Type.ime()).bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetBottom
            }
            if (h != keyboard) {
                keyboard = h
                arrange()
            }
            insets
        }
        window.addView(card, FrameLayout.LayoutParams(width, FrameLayout.LayoutParams.WRAP_CONTENT))
        root = window
        rootParams.width = width + 2 * dpi(EDGE_DP)
        rootParams.height = screen.y

        spot = TextSpot(context).apply {
            onTap = { field.setSelection(it.coerceIn(0, field.length())) }
            onDrag = { dx, dy ->
                this@TextEditor.x = spotParams.x + pad + dx
                this@TextEditor.y = spotParams.y + pad + dy
                refreshSpot()
                arrange()
            }
        }
        layOutField()
        refreshSpot(attach = false)
        arrange(attach = false)
        wm.addView(window, rootParams)
        wm.addView(spot, spotParams)
        window.postDelayed(watch, FIRST_WATCH_MS)

        card.alpha = 0f
        card.translationY = dp(RISE_DP)
        card.animate().alpha(1f).translationY(0f).setDuration(RISE_MS).setInterpolator(EASE).withLayer().start()
    }

    /** Ends the typing, keeping what was typed or not. */
    fun finish(commit: Boolean) {
        val window = root ?: return
        val draft = if (commit) Draft(field.text.toString().trimEnd(), color, size, sizeIndex, x, y) else null
        root = null
        imm.hideSoftInputFromWindow(field.windowToken, 0)
        card.animate().cancel()
        spot.stop()
        runCatching { wm.removeView(spot) }
        runCatching { wm.removeView(window) }
        onLeader?.invoke(null)
        areaColumn.setEmpty()
        areaSpot.setEmpty()
        areaKeyboard.setEmpty()
        onAreas?.invoke(areaColumn, areaSpot, areaKeyboard)
        onDone?.invoke(draft)
    }

    private fun onSpot(rawX: Float, rawY: Float) =
        rawX >= spotParams.x && rawX < spotParams.x + spotParams.width &&
            rawY >= spotParams.y && rawY < spotParams.y + spotParams.height

    /** The bar's keyboard key: from the card to in place, or back (the keyboard goes or comes). */
    private fun switchWriting() {
        inPlace = !inPlace
        bar.inPlace = inPlace
        layOutField()
        if (inPlace) {
            hideOnScreenKeyboard()
        } else {
            imm.showSoftInput(field, 0)
        }
        spot.caret = inPlace
        refreshSpot()
        arrange()
    }

    /** Hides the on-screen keyboard, now and after the key being handled (it may bring it back). */
    private fun hideOnScreenKeyboard() {
        val hide = {
            imm.hideSoftInputFromWindow(field.windowToken, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) field.windowInsetsController?.hide(android.view.WindowInsets.Type.ime())
        }
        hide()
        field.post(hide)
    }

    // In place the field stays (it takes the keys) but doesn't show: the words are in the spot.
    private fun layOutField() {
        // With a real keyboard the on-screen one mustn't come back on its own (a panel that passes the
        // keys through a virtual device doesn't tell Android a keyboard is there, so it would).
        field.showSoftInputOnFocus = !inPlace
        field.alpha = if (inPlace) 0f else 1f
        field.layoutParams = field.layoutParams.apply { height = if (inPlace) 1 else LinearLayout.LayoutParams.WRAP_CONTENT }
        val pad = dpi(16f)
        if (inPlace) field.setPadding(0, 0, 0, 0) else field.setPadding(pad + dpi(4f), pad, pad, dpi(4f))
        spot.caret = inPlace
    }

    /** Shows the words as they are now; the window fits them and stays clear of the card. */
    private fun refreshSpot(attach: Boolean = true) {
        if (!::spot.isInitialized || (attach && !isOpen)) return
        spot.show(field.text.toString(), size, shownColor(color))
        spot.select(field.selectionStart, field.selectionEnd)
        val pad = spot.pad.toInt()
        spotParams.width = spot.boxWidth
        spotParams.height = spot.boxHeight
        val margin = dpi(8f)
        val top = coverTop()
        spotParams.x = (x.toInt() - pad).coerceIn(margin, max(margin, screen.x - spotParams.width - margin))
        spotParams.y = (y.toInt() - pad).coerceIn(margin, max(margin, top - spotParams.height - margin))
        if (attach) wm.updateViewLayout(spot, spotParams)
        placed()
    }

    // Where what covers the board from below begins: the card and the keyboard, when writing on the card.
    private fun coverTop(): Int {
        if (inPlace) return screen.y
        val keyboardTop = keyboardRect()?.top ?: screen.y
        return min(keyboardTop, screen.y - cardGap - max(card.height, dpi(CARD_MIN_DP)))
    }

    // What the on-screen keyboard covers: read when that's possible (a floating one leaves only a
    // thin strip in its insets), else told by its insets.
    private fun keyboardRect(): Rect? = when {
        !floating.isEmpty -> floating
        keyboard > dpi(MIN_INSET_DP) -> Rect(0, screen.y - keyboard, screen.x, screen.y)
        else -> null
    }

    /** Puts the card over the keyboard, or by the words when typing in place. */
    private fun arrange(attach: Boolean = true) {
        val lp = card.layoutParams as FrameLayout.LayoutParams
        val edge = dpi(EDGE_DP)
        if (inPlace) {
            val h = dpi(TextBar.HEIGHT_DP)
            val above = spotParams.y - edge - h
            lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            lp.topMargin = if (above >= edge) above else spotParams.y + spotParams.height + edge
            lp.bottomMargin = 0
            rootParams.x = (spotParams.x - edge).coerceIn(0, max(0, screen.x - rootParams.width))
        } else {
            val keys = keyboardRect()
            val height = max(card.height, dpi(CARD_MIN_DP))
            val beside = keys != null && (keys.left >= rootParams.width || screen.x - keys.right >= rootParams.width)
            cardGap = when {
                keys == null -> if (waited && !floatingKnown) (screen.y * UNKNOWN_KEYBOARD_TOP).toInt() else edge
                beside -> max(edge, screen.y - keys!!.bottom) // at hand height, next to it
                keys.top - edge - height >= edge -> screen.y - keys.top + edge // over it
                else -> max(edge, screen.y - keys.bottom - edge - height) // dragged to the top: under it
            }
            lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            lp.topMargin = 0
            lp.bottomMargin = cardGap
            val x = when {
                keys == null -> (screen.x - rootParams.width) / 2
                beside && keys.left >= rootParams.width -> keys.left - rootParams.width
                beside -> keys.right
                else -> keys.centerX() - rootParams.width / 2
            }
            rootParams.x = x.coerceIn(0, max(0, screen.x - rootParams.width))
        }
        card.layoutParams = lp
        if (attach && isOpen) {
            wm.updateViewLayout(root, rootParams)
            refreshSpot()
        }
    }

    // The card has its place: the dashed line and the areas follow. Neither changes letter by letter:
    // the line would repaint the whole ink, and each area is a message to the panel.
    private fun placed() {
        if (!isOpen || !card.isLaidOut) return
        val left = rootParams.x + card.left.toFloat()
        val corner = dp(24f)
        val spotX = spotParams.x + spot.pad
        val spotY = spotParams.y + spotParams.height.toFloat()
        val cardX = spotX.coerceIn(left + corner, left + card.width - corner)
        onLeader?.invoke(if (inPlace) null else floatArrayOf(cardX, card.top.toFloat(), spotX, spotY))

        val column = Rect(rootParams.x, 0, rootParams.x + rootParams.width, screen.y)
        val spotNow = Rect(spotParams.x, spotParams.y, spotParams.x + spotParams.width, spotParams.y + spotParams.height)
        val typed = keyboardRect()?.let(::Rect) ?: when {
            !inPlace && waited && !floatingKnown -> Rect(0, (screen.y * (1f - UNKNOWN_KEYBOARD_TOP)).toInt(), screen.x, screen.y)
            else -> Rect()
        }
        if (column == areaColumn && typed == areaKeyboard && areaSpot.contains(spotNow)) return
        areaColumn.set(column)
        areaKeyboard.set(typed)
        areaSpot.set(spotNow.left, spotNow.top, min(screen.x, spotNow.right + dpi(AREA_ROOM_DP)), min(screen.y, spotNow.bottom + dpi(AREA_ROOM_DP)))
        onAreas?.invoke(areaColumn, areaSpot, areaKeyboard)
    }

    /** The field, telling where the caret goes (the spot shows it when typing in place). */
    @SuppressLint("AppCompatCustomView")
    private class Field(context: Context) : EditText(context) {
        var onCaret: ((Int, Int) -> Unit)? = null
        var onRealKey: (() -> Unit)? = null

        // A key of a real keyboard, seen before the on-screen one takes it: there is one after all.
        override fun dispatchKeyEventPreIme(e: KeyEvent): Boolean {
            // A printing key not sent by an on-screen keyboard is a real one, even when the panel
            // passes it on through a virtual device (as CVTE panels do with a USB keyboard).
            if (e.action == KeyEvent.ACTION_DOWN && e.isPrintingKey && (e.flags and KeyEvent.FLAG_SOFT_KEYBOARD) == 0) onRealKey?.invoke()
            return super.dispatchKeyEventPreIme(e)
        }

        override fun onSelectionChanged(selStart: Int, selEnd: Int) {
            super.onSelectionChanged(selStart, selEnd)
            onCaret?.invoke(selStart, selEnd)
        }
    }

    companion object {
        /** The bar's letter sizes (dp on screen): for labels, for headings, for titles. */
        val SIZES_DP = floatArrayOf(28f, 44f, 68f)

        /** After a real key is typed, texts open for that keyboard for this long (a school day). */
        private const val REAL_KEY_MEMORY_MS = 10L * 60 * 60 * 1000

        private const val CARD_DP = 660f
        private const val CARD_MIN_DP = 150f
        private const val AREA_ROOM_DP = 160f
        private const val MIN_INSET_DP = 100f
        private const val MAX_KEYBOARD_HEIGHT = 0.75f
        private const val FIRST_WATCH_MS = 250L
        private const val WATCH_MS = 400L

        // Without the accessibility service a floating keyboard can't be located: the card stays
        // over the part of the screen it opens on (the lower 45 %).
        private const val UNKNOWN_KEYBOARD_TOP = 0.45f
        private const val EDGE_DP = 16f
        private const val FIELD_SP = 22f
        private const val FIELD_LINES = 3
        private const val RISE_DP = 20f
        private const val RISE_MS = 180L
        private val EASE = PathInterpolator(0.2f, 0f, 0f, 1f)
    }
}

/**
 * The words in their place while they are typed. Touchable on purpose: an untouchable overlay is
 * dimmed to 80 % (see [Floater.closeAfter]). A tap puts the caret there, a drag moves the words.
 */
@SuppressLint("ViewConstructor")
private class TextSpot(context: Context) : View(context) {
    val pad = dp(14f)
    var boxWidth = 0
        private set
    var boxHeight = 0
        private set
    var onTap: ((Int) -> Unit)? = null
    var onDrag: ((dx: Float, dy: Float) -> Unit)? = null

    /** Typing in place: the caret is here. */
    var caret = false
        set(value) {
            if (field == value) return
            field = value
            removeCallbacks(blink)
            if (value) postDelayed(blink, BLINK_MS)
            invalidate()
        }

    private var body: TextBody? = null
    private var start = 0
    private var end = 0
    private var caretOn = true
    private val selectionPath = Path()
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x553E8784 }
    private val caretPaint = Paint().apply { strokeWidth = dp(2.5f) }
    private val outerLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        color = 0x55000000
    }
    private val innerLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = Color.WHITE
        pathEffect = DashPathEffect(floatArrayOf(dp(7f), dp(5f)), 0f)
    }
    private val box = RectF()
    private val blink = object : Runnable {
        override fun run() {
            caretOn = !caretOn
            invalidate()
            postDelayed(this, BLINK_MS)
        }
    }

    // Past the touch slop a press is a drag.
    private val slop = 2 * ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false

    fun show(text: String, size: Float, color: Int) {
        val b = TextBody(context, text, size).also { it.paint.color = color }
        body = b
        boxWidth = (b.width + 2 * pad).toInt() + b.paint.textSize.toInt() / 2 // room for the next letter
        boxHeight = (max(b.height, size) + 2 * pad).toInt()
        caretPaint.color = color
        caretOn = true
        invalidate()
    }

    fun select(start: Int, end: Int) {
        this.start = start
        this.end = end
        invalidate()
    }

    fun stop() = removeCallbacks(blink)

    override fun onDraw(canvas: Canvas) {
        val b = body ?: return
        box.set(dp(2f), dp(2f), width - dp(2f), height - dp(2f))
        val r = dp(8f)
        canvas.drawRoundRect(box, r, r, outerLine)
        canvas.drawRoundRect(box, r, r, innerLine)
        canvas.withTranslation(pad, pad) {
            val layout = b.layout
            if (caret && start != end) {
                layout.getSelectionPath(min(start, layout.text.length), min(end, layout.text.length), selectionPath)
                drawPath(selectionPath, selectionPaint)
            }
            layout.draw(this)
            if (caret && caretOn && start == end) {
                val at = min(start, layout.text.length)
                val line = layout.getLineForOffset(at)
                val cx = layout.getPrimaryHorizontal(at)
                drawLine(cx, layout.getLineTop(line).toFloat(), cx, layout.getLineBottom(line).toFloat(), caretPaint)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX
                downY = e.rawY
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (!dragging && hypot(dx, dy) > slop) dragging = true
                if (dragging) {
                    downX = e.rawX
                    downY = e.rawY
                    onDrag?.invoke(dx, dy)
                }
            }
            MotionEvent.ACTION_UP -> if (!dragging) {
                val layout = body?.layout ?: return true
                val line = layout.getLineForVertical((e.y - pad).toInt())
                onTap?.invoke(layout.getOffsetForHorizontal(line, e.x - pad))
            }
        }
        return true
    }

    private companion object {
        const val BLINK_MS = 530L
    }
}

/** The writing bar: how to write, the letter size and the colour. A touch outside the text ends it, as in similar apps. */
@SuppressLint("ViewConstructor")
private class TextBar(context: Context, private var color: Int, private var sizeIndex: Int, inPlace: Boolean) : View(context) {
    var onColor: ((Int) -> Unit)? = null
    var onSize: ((Int) -> Unit)? = null
    var onKeyboard: (() -> Unit)? = null

    /** Typing in place, not on the card. */
    var inPlace = inPlace
        set(value) {
            field = value
            invalidate()
        }

    private val keyIcon = ContextCompat.getDrawable(context, R.drawable.ic_keyboard)!!.mutate()
    private val letter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Fonts.bold(context)
        textAlign = Paint.Align.CENTER
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2.5f) }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1f); color = 0x33000000 }
    private val step = dp(STEP_DP)
    private val dotStep = dp(DOT_STEP_DP)

    // Centres, from the left: the keyboard key, the sizes, then the colours; the whole row centred.
    private var ox = 0f
    private val keyX get() = ox + dp(14f) + step / 2
    private fun sizeX(i: Int) = keyX + step * (i + 1) + dp(6f)
    private fun dotX(i: Int) = sizeX(TextEditor.SIZES_DP.lastIndex) + step / 2 + dp(14f) + dotStep * (i + 0.5f)

    private var downOn = NOTHING

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        ox = 0f
        val content = dotX(Presets.COLORS.lastIndex) + dotStep / 2 + dp(14f)
        ox = max(0f, (w - content) / 2f)
    }

    init {
        contentDescription = context.getString(R.string.tool_text)
    }

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        fill.color = if (inPlace) Chrome.pressed else OrbStyle.TEAL
        canvas.drawCircle(keyX, cy, dp(19f), fill)
        keyIcon.setTint(if (inPlace) Chrome.icon else Color.WHITE)
        val k = dpi(13f)
        keyIcon.setBounds((keyX - k).toInt(), (cy - k).toInt(), (keyX + k).toInt(), (cy + k).toInt())
        keyIcon.draw(canvas)

        for (i in TextEditor.SIZES_DP.indices) {
            val x = sizeX(i)
            if (i == sizeIndex) {
                fill.color = Chrome.pressed
                canvas.drawCircle(x, cy, dp(19f), fill)
            }
            letter.color = Chrome.icon
            letter.textSize = dp(LETTERS_DP[i])
            canvas.drawText("A", x, cy - (letter.descent() + letter.ascent()) / 2f, letter)
        }
        Presets.COLORS.forEachIndexed { i, c ->
            val x = dotX(i)
            fill.color = c
            canvas.drawCircle(x, cy, dp(DOT_DP), fill)
            if (ColorUtils.calculateLuminance(c) > 0.7) canvas.drawCircle(x, cy, dp(DOT_DP), edge)
            if (c == color) {
                ring.color = Chrome.icon
                canvas.drawCircle(x, cy, dp(DOT_DP + 4.5f), ring)
            }
        }
    }

    private fun itemAt(x: Float): Int {
        if (abs(x - keyX) <= step / 2) return KEY
        for (i in TextEditor.SIZES_DP.indices) if (abs(x - sizeX(i)) <= step / 2) return SIZE + i
        for (i in Presets.COLORS.indices) if (abs(x - dotX(i)) <= dotStep / 2) return COLOUR + i
        return NOTHING
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> downOn = itemAt(e.x)
            MotionEvent.ACTION_UP -> if (downOn != NOTHING && itemAt(e.x) == downOn) pick(downOn)
        }
        return true
    }

    private fun pick(item: Int) {
        when {
            item == KEY -> onKeyboard?.invoke()
            item >= COLOUR -> {
                color = Presets.COLORS[item - COLOUR]
                onColor?.invoke(color)
            }
            else -> {
                sizeIndex = item - SIZE
                onSize?.invoke(sizeIndex)
            }
        }
        invalidate()
    }

    companion object {
        const val HEIGHT_DP = 64f
        private const val STEP_DP = 44f
        private const val DOT_STEP_DP = 37f
        private const val DOT_DP = 12f
        private val LETTERS_DP = floatArrayOf(15f, 20f, 26f)
        private const val NOTHING = -1
        private const val KEY = 0
        private const val SIZE = 10
        private const val COLOUR = 20
    }
}
