package com.maestroseb.marcarpro

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * «A mi Drive», through a small Apps Script web (appsscript/ in this repository). The QR code opens
 * it on the teacher's phone; when they tap «Guardar», their own Drive opens an upload of one file
 * in its folder «Marcar Pro» and the web hands the panel only that address. The panel then sends
 * the PDF straight into the teacher's Drive: the file never goes through the web, so the account
 * that published it is never a bottleneck. IO thread, except [configured] and [Session].
 */
internal object DriveLink {
    private const val TIMEOUT_MS = 20_000
    private val random = SecureRandom()

    /** Whether this build carries the addresses of the web (see build.gradle.kts). */
    val configured get() = BuildConfig.DRIVE_PANEL_URL.isNotEmpty() && BuildConfig.DRIVE_PHONE_URL.isNotEmpty()

    /** One PDF waiting for a teacher: the panel keeps the secret, the QR only carries its hash. */
    class Session(name: String, val expiresAt: Long) {
        private val secret = web64(ByteArray(32).also(random::nextBytes))
        /** Only the web's address and a short id: the fewer letters, the plainer the code. */
        val qr = "${BuildConfig.DRIVE_PHONE_URL}?s=${idOf(secret)}"
        /** The board's name goes to the web with the first question instead of in the QR. */
        internal var name: String? = name.removeSuffix(".pdf")
        internal fun url(action: String) = "${BuildConfig.DRIVE_PANEL_URL}?a=$action&k=$secret"
    }

    /** What the web says: still waiting, or the address to send the PDF to (only given once). */
    class Answer(val ready: Boolean, val upload: String?)

    fun ask(session: Session): Answer {
        val first = session.name?.let { "&n=${URLEncoder.encode(it, "UTF-8")}" }.orEmpty()
        val o = answer(open(session.url("estado") + first, "GET"))
        session.name = null
        return Answer(o.optString("estado") == "listo", o.optString("subida").takeIf { it.startsWith("https://") })
    }

    /**
     * Sends [source] to the upload the teacher's Drive opened, then tells the web it's there.
     * In pieces of [CHUNK] bytes, never the whole file in memory: a piece that fails (a school
     * network that stalls for a moment) is asked about and sent again from where Drive has it, a
     * few times; a piece that hangs is cut after [CHUNK_DEADLINE_MS]. Until 1.9.11 it was one PUT
     * of the whole file with no time limit, and a stalled network left «Saving…» forever.
     */
    fun send(context: Context, session: Session, upload: String, source: Uri, cancelled: () -> Boolean, progress: (Float) -> Unit) {
        context.contentResolver.openFileDescriptor(source, "r")!!.use { pfd ->
            java.io.FileInputStream(pfd.fileDescriptor).channel.use { file -> upload(file, upload, cancelled, progress) }
        }
        runCatching { answer(open(session.url("hecho"), "GET")) }
    }

    /** The upload itself, piece by piece (see [send]). */
    internal fun upload(file: java.nio.channels.FileChannel, upload: String, cancelled: () -> Boolean, progress: (Float) -> Unit) {
                val total = file.size()
                require(total > 0) { "empty PDF" }
                val buf = java.nio.ByteBuffer.allocate(CHUNK)
                var at = 0L
                var failures = 0
                progress(0f)
                while (true) {
                    if (cancelled()) throw InterruptedException()
                    val n = minOf(CHUNK.toLong(), total - at).toInt()
                    buf.clear()
                    buf.limit(n)
                    file.position(at)
                    while (buf.hasRemaining()) if (file.read(buf) < 0) break
                    val next = try {
                        putPiece(upload, buf.array(), n, at, total)
                    } catch (e: java.io.IOException) {
                        if (++failures > MAX_FAILURES || cancelled()) throw e
                        Thread.sleep(RETRY_MS * failures)
                        // Where Drive has it now (the piece may have arrived after all).
                        runCatching { whereIs(upload, total) }.getOrNull() ?: at
                    }
                    if (next >= total) break
                    if (next > at) failures = 0
                    at = next
                    progress(at.toFloat() / total)
                }
                progress(1f)
    }

    private val watchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()

    /** A connection that is cut if it is still going after [CHUNK_DEADLINE_MS] (writes have no timeout). */
    private inline fun <T> guarded(c: HttpURLConnection, block: () -> T): T {
        val cut = watchdog.schedule({ c.disconnect() }, CHUNK_DEADLINE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        try {
            return block()
        } finally {
            cut.cancel(false)
            c.disconnect()
        }
    }

    /** Sends [n] bytes of [bytes] as the piece at [at]; how many bytes Drive has now ([total] = all). */
    private fun putPiece(upload: String, bytes: ByteArray, n: Int, at: Long, total: Long): Long {
        val c = open(upload, "PUT")
        return guarded(c) {
            c.setRequestProperty("Content-Type", "application/pdf")
            c.setRequestProperty("Content-Range", "bytes $at-${at + n - 1}/$total")
            c.doOutput = true
            c.setFixedLengthStreamingMode(n)
            c.outputStream.use { it.write(bytes, 0, n) }
            received(c, total)
        }
    }

    /** Asks Drive how much of the file it has. */
    private fun whereIs(upload: String, total: Long): Long {
        val c = open(upload, "PUT")
        return guarded(c) {
            c.setRequestProperty("Content-Range", "bytes */$total")
            c.doOutput = true
            c.setFixedLengthStreamingMode(0)
            c.outputStream.close()
            received(c, total)
        }
    }

    /** From Drive's answer: all of it (200/201), or up to where (308 and its Range "bytes=0-N"). */
    private fun received(c: HttpURLConnection, total: Long): Long = when (val code = c.responseCode) {
        200, 201 -> total
        308 -> c.getHeaderField("Range")?.substringAfterLast('-')?.toLongOrNull()?.plus(1) ?: 0L
        in 500..599, 408, 429 -> throw java.io.IOException("upload: HTTP $code")
        // 404/410: the upload is gone (it lasts a week); nothing to retry.
        else -> error("upload: HTTP $code")
    }

    /** A piece: a multiple of 256 KiB, as Drive asks; small enough to resend quickly. */
    private const val CHUNK = 2 * 1024 * 1024
    private const val CHUNK_DEADLINE_MS = 90_000L
    private const val MAX_FAILURES = 5
    private const val RETRY_MS = 2_000L

    /** The id the web knows a session by: SHA-256 of the secret, its first 22 letters (128 bits). */
    private fun idOf(secret: String) = web64(MessageDigest.getInstance("SHA-256").digest(secret.toByteArray())).take(22)

    private fun web64(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun open(url: String, method: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = TIMEOUT_MS
        readTimeout = TIMEOUT_MS * 3
        useCaches = false
        // Apps Script answers from another address (a redirect), always fetched with GET.
        instanceFollowRedirects = false
    }

    private fun answer(first: HttpURLConnection): JSONObject {
        var c = first
        repeat(3) {
            if (c.responseCode !in 300..399) return@repeat
            val next = c.getHeaderField("Location") ?: error("redirect without address")
            c.disconnect()
            c = open(next, "GET")
        }
        if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
        val text = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        return JSONObject(text)
    }
}

/**
 * The card in the middle of the screen while the PDF goes to a Drive: the QR code and the steps,
 * then "Saving…", then done (or what went wrong). Drawn in code, in the controls' material.
 */
@SuppressLint("ViewConstructor")
internal class DriveCardView(context: Context, private val onClose: () -> Unit) : View(context) {
    enum class State { CONNECTING, WAITING, SAVING, DONE, EXPIRED, FAILED }

    private val margin = dp(12f)
    private val card = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1f) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(4f); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val qrPaint = Paint().apply { isFilterBitmap = false }
    private val title = text(20f, bold = true)
    private val body = text(15f)
    private val small = text(13f)
    private val button = text(15f, bold = true)
    private val r = RectF()
    private val src = Rect()
    private var qr: Bitmap? = null
    private var pressed = false

    var state = State.CONNECTING
        set(value) { field = value; invalidate() }

    /** While saving: how much has gone, 0..1 (below 0: not known yet). */
    var progress = -1f
        set(value) { field = value; invalidate() }

    /** The board's file name, under the title. */
    var fileName = ""
        set(value) { field = value; invalidate() }

    private var session: DriveLink.Session? = null

    fun show(c: DriveLink.Session) {
        session = c
        qr?.recycle()
        qr = qrBitmap(c.qr)
        state = State.WAITING
    }

    fun reset() {
        progress = -1f
        session = null
        qr?.recycle()
        qr = null
        state = State.CONNECTING
    }

    private fun text(size: Float, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = context.sp(size)
        typeface = if (bold) Fonts.bold(context) else Fonts.regular(context)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((margin * 2 + dp(WIDTH_DP)).toInt(), (margin * 2 + dp(HEIGHT_DP)).toInt())
    }

    private fun buttonRect() = r.apply {
        val w = dp(140f)
        set(width / 2f - w / 2, height - margin - dp(PAD_DP + 46f), width / 2f + w / 2, height - margin - dp(PAD_DP))
    }

    override fun onDraw(canvas: Canvas) {
        r.set(margin, margin, width - margin, height - margin)
        val corner = dp(20f)
        card.color = Chrome.surface
        Chrome.shade(card, dp(14f), dp(4f))
        canvas.drawRoundRect(r, corner, corner, card)
        edge.color = Chrome.hairline
        r.inset(dp(0.5f), dp(0.5f))
        canvas.drawRoundRect(r, corner, corner, edge)

        val cx = width / 2f
        var y = margin + dp(PAD_DP + 26f)
        center(canvas, context.getString(R.string.drive_title), y, title, Chrome.text)
        y += dp(24f)
        center(canvas, fileName, y, small, Chrome.hint)
        y += dp(20f)

        val qrSize = dp(QR_DP)
        val box = RectF(cx - qrSize / 2, y, cx + qrSize / 2, y + qrSize)
        when (state) {
            State.WAITING -> {
                qr?.let {
                    src.set(0, 0, it.width, it.height)
                    canvas.drawBitmap(it, src, box, qrPaint)
                }
                y = box.bottom + dp(26f)
                center(canvas, context.getString(R.string.drive_step_scan), y, body, Chrome.text)
                y += dp(22f)
                center(canvas, context.getString(R.string.drive_step_allow), y, body, Chrome.text)
                val left = session?.let { ((it.expiresAt - SystemClock.uptimeMillis()) / 1000).coerceAtLeast(0) } ?: 0
                y += dp(24f)
                center(canvas, context.getString(R.string.drive_expires, left / 60, left % 60), y, small, Chrome.hint)
                postInvalidateDelayed(1000)
            }
            else -> {
                val busy = state == State.CONNECTING || state == State.SAVING
                val good = state == State.DONE
                // A ring: turning while busy, a tick when saved, a cross when not.
                mark.color = if (busy || good) OrbStyle.TEAL else Chrome.icon
                val rc = box.centerY() - dp(20f)
                val rr = dp(34f)
                if (state == State.SAVING && progress >= 0f) {
                    // Saving: the ring fills as the PDF goes, its share in the middle.
                    mark.alpha = 50
                    canvas.drawCircle(cx, rc, rr, mark)
                    mark.alpha = 255
                    canvas.drawArc(cx - rr, rc - rr, cx + rr, rc + rr, -90f, 360f * progress, false, mark)
                    center(canvas, "${(progress * 100).toInt()} %", rc + small.textSize * 0.35f, small, Chrome.text)
                } else if (busy) {
                    val sweep = (SystemClock.uptimeMillis() % 1200) / 1200f * 360f
                    canvas.drawArc(cx - rr, rc - rr, cx + rr, rc + rr, sweep, 270f, false, mark)
                    postInvalidateDelayed(32)
                } else {
                    canvas.drawCircle(cx, rc, rr, mark)
                    if (good) {
                        canvas.drawLine(cx - dp(14f), rc + dp(1f), cx - dp(4f), rc + dp(11f), mark)
                        canvas.drawLine(cx - dp(4f), rc + dp(11f), cx + dp(16f), rc - dp(10f), mark)
                    } else {
                        canvas.drawLine(cx - dp(11f), rc - dp(11f), cx + dp(11f), rc + dp(11f), mark)
                        canvas.drawLine(cx + dp(11f), rc - dp(11f), cx - dp(11f), rc + dp(11f), mark)
                    }
                }
                val (main, note) = when (state) {
                    State.CONNECTING -> R.string.drive_connecting to 0
                    State.SAVING -> R.string.drive_saving to 0
                    State.DONE -> R.string.drive_done to R.string.drive_done_note
                    State.EXPIRED -> R.string.drive_expired to R.string.drive_again_note
                    else -> R.string.drive_failed to R.string.drive_failed_note
                }
                y = rc + rr + dp(40f)
                center(canvas, context.getString(main), y, title, Chrome.text)
                if (note != 0) center(canvas, context.getString(note), y + dp(26f), body, Chrome.hint)
            }
        }

        val b = buttonRect()
        val busyOrWaiting = state == State.CONNECTING || state == State.WAITING || state == State.SAVING
        fill.color = if (pressed) Chrome.pressed else androidx.core.graphics.ColorUtils.blendARGB(Chrome.surface, Chrome.text, 0.06f)
        canvas.drawRoundRect(b, b.height() / 2, b.height() / 2, fill)
        val label = context.getString(if (busyOrWaiting) R.string.drive_cancel else R.string.drive_close)
        center(canvas, label, b.centerY() + button.textSize * 0.35f, button, Chrome.text)
    }

    private fun center(canvas: Canvas, s: String, y: Float, p: Paint, color: Int) {
        p.color = color
        canvas.drawText(s, width / 2f - p.measureText(s) / 2, y, p)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        qr?.recycle()
        qr = null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val inButton = buttonRect().contains(e.x, e.y)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pressed = inButton; invalidate() }
            MotionEvent.ACTION_CANCEL -> { pressed = false; invalidate() }
            MotionEvent.ACTION_UP -> {
                val hit = pressed && inButton
                pressed = false
                invalidate()
                if (hit) onClose()
            }
        }
        return true
    }

    companion object {
        private const val WIDTH_DP = 380f
        private const val HEIGHT_DP = 440f
        private const val PAD_DP = 18f
        private const val QR_DP = 200f

        /** One pixel per module, with the quiet zone; drawn scaled up without smoothing. */
        private fun qrBitmap(text: String): Bitmap {
            val m = QRCodeWriter().encode(
                text, BarcodeFormat.QR_CODE, 0, 0,
                mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.MARGIN to 2),
            )
            val px = IntArray(m.width * m.height) { if (m[it % m.width, it / m.width]) 0xFF000000.toInt() else -1 }
            return Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888)
        }
    }
}
