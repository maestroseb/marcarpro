package com.maestroseb.marcarpro

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import java.io.OutputStream

/** Where the share card sends the board's PDF. */
internal enum class ShareKind { DRIVE, USB }

/**
 * The share card, under the share button: the whole board as one PDF, to the teacher's own Drive
 * (a QR for their phone; the first and suggested choice) or to a USB stick. The PDF is also kept
 * in Downloads › Marcar Pro. A tap outside closes it. Drawn in code, in the controls' material.
 */
@SuppressLint("ViewConstructor")
internal class ShareCardView(context: Context, private val onPick: (ShareKind) -> Unit, private val onOutside: () -> Unit) : View(context) {
    private val margin = dp(10f)
    private val card = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1f) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1.6f); strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val title = text(16f, bold = true)
    private val big = text(15f, bold = true)
    private val small = text(13f)
    private val r = RectF()
    private val path = Path()
    private var pressed: ShareKind? = null

    /** The board's name, under the title. */
    var boardName = ""
        set(value) { field = value; invalidate() }

    /** Pages in the board, for the subtitle. */
    var pages = 1
        set(value) { field = value; invalidate() }

    /** The choice being prepared (its note says so), or null. */
    var busy: ShareKind? = null
        set(value) { field = value; invalidate() }

    private fun text(size: Float, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = context.sp(size)
        typeface = if (bold) Fonts.bold(context) else Fonts.regular(context)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(dpi(WIDTH_DP), (margin * 2 + dp(HEIGHT_DP)).toInt())
    }

    private fun rowTop(kind: ShareKind) = margin + dp(PAD_DP + HEAD_DP) + kind.ordinal * dp(ROW_DP + ROW_GAP_DP)

    override fun onDraw(canvas: Canvas) {
        r.set(margin, margin, width - margin, height - margin)
        val corner = dp(16f)
        card.color = Chrome.surface
        Chrome.shade(card, dp(10f), dp(3f))
        canvas.drawRoundRect(r, corner, corner, card)
        edge.color = Chrome.hairline
        r.inset(dp(0.5f), dp(0.5f))
        canvas.drawRoundRect(r, corner, corner, edge)

        val x = margin + dp(PAD_DP + 10f)
        title.color = Chrome.text
        canvas.drawText(context.getString(R.string.share_title), x, margin + dp(PAD_DP + 20f), title)
        small.color = Chrome.hint
        val sub = "$boardName · ${resources.getQuantityString(R.plurals.share_pdf, pages, pages)}"
        canvas.drawText(TextUtils.ellipsize(sub, TextPaint(small), width - x - margin - dp(PAD_DP), TextUtils.TruncateAt.MIDDLE).toString(), x, margin + dp(PAD_DP + 40f), small)

        drawRow(canvas, ShareKind.DRIVE, context.getString(R.string.share_drive), context.getString(R.string.share_drive_note))
        drawRow(canvas, ShareKind.USB, context.getString(R.string.share_usb), context.getString(R.string.share_usb_note))

        val line = height - margin - dp(FOOT_DP)
        edge.color = Chrome.hairline
        canvas.drawLine(margin + dp(PAD_DP), line, width - margin - dp(PAD_DP), line, edge)
        small.color = Chrome.hint
        canvas.drawText(context.getString(R.string.share_saved_note), x, line + dp(FOOT_DP) / 2 + small.textSize * 0.35f, small)
    }

    private fun drawRow(canvas: Canvas, kind: ShareKind, label: String, note: String) {
        val top = rowTop(kind)
        r.set(margin + dp(PAD_DP), top, width - margin - dp(PAD_DP), top + dp(ROW_DP))
        val suggested = kind == ShareKind.DRIVE
        val corner = dp(10f)
        if (suggested) {
            fill.color = ColorUtils.setAlphaComponent(OrbStyle.TEAL, if (pressed == kind) 0x3D else 0x21)
            canvas.drawRoundRect(r, corner, corner, fill)
        } else if (pressed == kind) {
            fill.color = Chrome.pressed
            canvas.drawRoundRect(r, corner, corner, fill)
        }
        icon.color = if (suggested) OrbStyle.TEAL_DARK else Chrome.icon
        val cx = r.left + dp(27f)
        val cy = r.centerY()
        if (suggested) drawCloud(canvas, cx, cy) else drawStick(canvas, cx, cy)

        val tx = r.left + dp(56f)
        big.color = if (suggested) OrbStyle.TEAL_DARK else Chrome.text
        big.typeface = if (suggested) Fonts.bold(context) else Fonts.regular(context)
        canvas.drawText(label, tx, cy - dp(2f), big)
        small.color = if (suggested) OrbStyle.TEAL_DARK else Chrome.hint
        canvas.drawText(if (busy == kind) context.getString(R.string.share_busy) else note, tx, cy + dp(17f), small)
    }

    /** A cloud with an arrow going up into it. */
    private fun drawCloud(canvas: Canvas, cx: Float, cy: Float) {
        val u = dp(1f)
        path.reset()
        path.moveTo(cx - 8 * u, cy + 7 * u)
        path.cubicTo(cx - 15 * u, cy + 7 * u, cx - 15 * u, cy - 2 * u, cx - 9 * u, cy - 2 * u)
        path.cubicTo(cx - 9 * u, cy - 11 * u, cx + 4 * u, cy - 12 * u, cx + 6 * u, cy - 4 * u)
        path.cubicTo(cx + 14 * u, cy - 5 * u, cx + 15 * u, cy + 7 * u, cx + 7 * u, cy + 7 * u)
        path.lineTo(cx + 3 * u, cy + 7 * u)
        canvas.drawPath(path, icon)
        canvas.drawLine(cx - 3 * u, cy + 7 * u, cx - 8 * u, cy + 7 * u, icon)
        canvas.drawLine(cx, cy + 11 * u, cx, cy - 1 * u, icon)
        canvas.drawLine(cx - 4 * u, cy + 3 * u, cx, cy - 1 * u, icon)
        canvas.drawLine(cx + 4 * u, cy + 3 * u, cx, cy - 1 * u, icon)
    }

    /** A USB stick: the body and its metal plug. */
    private fun drawStick(canvas: Canvas, cx: Float, cy: Float) {
        val u = dp(1f)
        canvas.drawRoundRect(cx - 6 * u, cy - 4 * u, cx + 6 * u, cy + 12 * u, 3 * u, 3 * u, icon)
        canvas.drawRect(cx - 4 * u, cy - 11 * u, cx + 4 * u, cy - 4 * u, icon)
        canvas.drawLine(cx - 1.5f * u, cy - 8 * u, cx - 1.5f * u, cy - 7 * u, icon)
        canvas.drawLine(cx + 1.5f * u, cy - 8 * u, cx + 1.5f * u, cy - 7 * u, icon)
    }

    private fun kindAt(y: Float): ShareKind? = ShareKind.entries.firstOrNull { y >= rowTop(it) && y < rowTop(it) + dp(ROW_DP) }

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
            MotionEvent.ACTION_DOWN -> { pressed = if (busy == null) kindAt(e.y) else null; invalidate() }
            MotionEvent.ACTION_CANCEL -> { pressed = null; invalidate() }
            MotionEvent.ACTION_UP -> {
                val k = pressed?.takeIf { kindAt(e.y) == it }
                pressed = null
                invalidate()
                if (k != null) onPick(k)
            }
        }
        return true
    }

    companion object {
        const val WIDTH_DP = 300f
        private const val PAD_DP = 12f
        private const val HEAD_DP = 54f
        private const val ROW_DP = 60f
        private const val ROW_GAP_DP = 4f
        private const val FOOT_DP = 38f
        private const val HEIGHT_DP = PAD_DP + HEAD_DP + ROW_DP * 2 + ROW_GAP_DP + 8f + FOOT_DP
    }
}

/** Saving what is shared in Downloads › Marcar Pro, and handing it to Android's share menu. */
internal object ShareFiles {
    private const val FOLDER = "Marcar Pro"

    /** Whether this Android can keep files in Downloads without a storage permission (10+). */
    val supported get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /**
     * Writes a new file called [name] into Downloads › Marcar Pro through [write]; its address, or
     * null if it failed (a half-written file is removed). IO thread.
     */
    fun save(context: Context, name: String, mime: String, write: (OutputStream) -> Unit): Uri? {
        if (!supported) return null
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) }.getOrNull() ?: return null
        val ok = runCatching {
            resolver.openOutputStream(uri)?.use(write) ?: error("no stream")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }.isSuccess
        if (!ok) runCatching { resolver.delete(uri, null, null) }
        return uri.takeIf { ok }
    }

    /** Android's share menu for [uri] (Classroom, Drive, Gmail, a USB stick…), from a service. */
    fun chooser(context: Context, uri: Uri, mime: String, title: String): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, title)
            clipData = android.content.ClipData.newRawUri(title, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, context.getString(R.string.share_title))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** A file name from the board's name: letters, digits, spaces and a few signs only. */
    fun fileName(board: String, extension: String): String {
        val clean = board.replace('·', '-').replace(':', '.').replace(Regex("""[^\p{L}\p{N} ._-]"""), "").replace(Regex(" +"), " ").trim()
        return "${clean.ifEmpty { "Marcar Pro" }}.$extension"
    }
}
