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
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * «A mi Drive», with Google's sign-in for TVs and other devices without a keyboard: the panel shows
 * a QR code (Google's own page, with the code in it) and the teacher allows Marcar Pro, on their
 * phone, to create files in their Drive. Only files it creates: it never sees the rest. The panel
 * then puts the PDF in the folder «Marcar Pro» of that Drive, straight from the panel, and gives
 * the permission back at once. Nothing is kept on the panel and nothing goes through anyone else.
 * IO thread, except [configured].
 */
internal object DriveLink {
    private const val SCOPE = "https://www.googleapis.com/auth/drive.file"
    private const val FOLDER = "Marcar Pro"
    private const val FOLDER_MIME = "application/vnd.google-apps.folder"
    private const val TIMEOUT_MS = 20_000

    /** Whether this build carries the app's Google client (see build.gradle.kts). */
    val configured get() = BuildConfig.DRIVE_CLIENT_ID.isNotEmpty()

    /** What the teacher needs: the page (with the code in it, for the QR) and the code itself. */
    class Code(val device: String, val user: String, val page: String, val expiresAt: Long, val interval: Long) {
        val qr get() = "$page?user_code=${URLEncoder.encode(user, "UTF-8")}"
    }

    sealed interface Poll {
        class Allowed(val token: String) : Poll
        data object Waiting : Poll
        data object Slower : Poll
        data object Refused : Poll
        data object Expired : Poll
    }

    fun start(): Code {
        val o = post("https://oauth2.googleapis.com/device/code", "client_id" to BuildConfig.DRIVE_CLIENT_ID, "scope" to SCOPE)
        return Code(
            o.getString("device_code"), o.getString("user_code"), o.optString("verification_url", "https://www.google.com/device"),
            SystemClock.uptimeMillis() + o.optLong("expires_in", 1800) * 1000, o.optLong("interval", 5).coerceAtLeast(1),
        )
    }

    fun poll(code: Code): Poll {
        val o = post(
            "https://oauth2.googleapis.com/token", "client_id" to BuildConfig.DRIVE_CLIENT_ID,
            "client_secret" to BuildConfig.DRIVE_CLIENT_SECRET, "device_code" to code.device,
            "grant_type" to "urn:ietf:params:oauth:grant-type:device_code", errors = true,
        )
        o.optString("access_token").takeIf { it.isNotEmpty() }?.let { return Poll.Allowed(it) }
        return when (o.optString("error")) {
            "authorization_pending" -> Poll.Waiting
            "slow_down" -> Poll.Slower
            "access_denied" -> Poll.Refused
            "expired_token" -> Poll.Expired
            else -> error("token: ${o.optString("error")}")
        }
    }

    /** Puts [source] in the folder «Marcar Pro» (made the first time) of the teacher's Drive. */
    fun upload(context: Context, token: String, source: Uri, name: String) {
        val folder = folder(token)
        val size = context.contentResolver.openAssetFileDescriptor(source, "r")!!.use { it.length }
        val meta = JSONObject().put("name", name).put("mimeType", "application/pdf").put("parents", JSONArray().put(folder))
        val start = open("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable", "POST", token).apply {
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("X-Upload-Content-Type", "application/pdf")
            if (size > 0) setRequestProperty("X-Upload-Content-Length", size.toString())
            doOutput = true
            outputStream.use { it.write(meta.toString().toByteArray()) }
        }
        val session = start.check().getHeaderField("Location") ?: error("no upload session")
        start.disconnect()
        open(session, "PUT", token).apply {
            setRequestProperty("Content-Type", "application/pdf")
            doOutput = true
            if (size > 0) setFixedLengthStreamingMode(size) else setChunkedStreamingMode(64 * 1024)
            context.contentResolver.openInputStream(source)!!.use { input -> outputStream.use { input.copyTo(it) } }
            check()
            disconnect()
        }
    }

    /** Gives the permission back: the panel can do nothing more in that Drive. */
    fun revoke(token: String) {
        runCatching { post("https://oauth2.googleapis.com/revoke", "token" to token, errors = true) }
    }

    private fun folder(token: String): String {
        val q = URLEncoder.encode("name = '$FOLDER' and mimeType = '$FOLDER_MIME' and trashed = false", "UTF-8")
        val found = read(open("https://www.googleapis.com/drive/v3/files?q=$q&spaces=drive&fields=files(id)&pageSize=1", "GET", token).check())
            .getJSONArray("files")
        if (found.length() > 0) return found.getJSONObject(0).getString("id")
        val made = open("https://www.googleapis.com/drive/v3/files?fields=id", "POST", token).apply {
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            doOutput = true
            outputStream.use { it.write(JSONObject().put("name", FOLDER).put("mimeType", FOLDER_MIME).toString().toByteArray()) }
        }
        return read(made.check()).getString("id")
    }

    private fun post(url: String, vararg fields: Pair<String, String>, errors: Boolean = false): JSONObject {
        val body = fields.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val c = open(url, "POST", null).apply {
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            doOutput = true
            outputStream.use { it.write(body.toByteArray()) }
        }
        if (!errors) c.check()
        return read(c)
    }

    private fun open(url: String, method: String, token: String?) = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = TIMEOUT_MS
        readTimeout = TIMEOUT_MS * 3
        useCaches = false
        if (token != null) setRequestProperty("Authorization", "Bearer $token")
    }

    private fun HttpURLConnection.check(): HttpURLConnection {
        if (responseCode !in 200..299) error("HTTP $responseCode")
        return this
    }

    private fun read(c: HttpURLConnection): JSONObject {
        val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        c.disconnect()
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }
}

/**
 * The card in the middle of the screen while the PDF goes to a Drive: the QR code and the steps,
 * then "Saving…", then done (or what went wrong). Drawn in code, in the controls' material.
 */
@SuppressLint("ViewConstructor")
internal class DriveCardView(context: Context, private val onClose: () -> Unit) : View(context) {
    enum class State { CONNECTING, WAITING, SAVING, DONE, REFUSED, EXPIRED, FAILED }

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
    private val codeText = text(26f, bold = true).apply { letterSpacing = 0.08f }
    private val button = text(15f, bold = true)
    private val r = RectF()
    private val src = Rect()
    private var qr: Bitmap? = null
    private var pressed = false

    var state = State.CONNECTING
        set(value) { field = value; invalidate() }

    /** The board's file name, under the title. */
    var fileName = ""
        set(value) { field = value; invalidate() }

    private var code: DriveLink.Code? = null

    fun show(c: DriveLink.Code) {
        code = c
        qr?.recycle()
        qr = qrBitmap(c.qr)
        state = State.WAITING
    }

    fun reset() {
        code = null
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
                y += dp(26f)
                center(canvas, context.getString(R.string.drive_code_label), y, small, Chrome.hint)
                y += dp(34f)
                center(canvas, code?.user.orEmpty(), y, codeText, OrbStyle.TEAL_DARK)
                val left = code?.let { ((it.expiresAt - SystemClock.uptimeMillis()) / 1000).coerceAtLeast(0) } ?: 0
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
                if (busy) {
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
                    State.REFUSED -> R.string.drive_refused to R.string.drive_again_note
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
        val label = context.getString(if (busyOrWaiting && state != State.SAVING) R.string.drive_cancel else R.string.drive_close)
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
        private const val WIDTH_DP = 340f
        private const val HEIGHT_DP = 500f
        private const val PAD_DP = 18f
        private const val QR_DP = 200f

        /** One pixel per module, with the quiet zone; drawn scaled up without smoothing. */
        private fun qrBitmap(text: String): Bitmap {
            val m = QRCodeWriter().encode(
                text, BarcodeFormat.QR_CODE, 0, 0,
                mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2),
            )
            val px = IntArray(m.width * m.height) { if (m[it % m.width, it / m.width]) 0xFF000000.toInt() else -1 }
            return Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888)
        }
    }
}
