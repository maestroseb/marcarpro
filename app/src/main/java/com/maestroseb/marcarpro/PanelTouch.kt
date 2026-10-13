package com.maestroseb.marcarpro

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.util.Log

/**
 * Keeps the board's touch on Android when another source (the OPS PC, HDMI) is on screen.
 *
 * With an external source the panel sends every touch to the PC as well, so a pen stroke
 * would also scroll the PDF underneath. The panel's own tools avoid that by declaring
 * "no-touch areas" through the vendor's open SDK (Seewo/IFPDOS, exported by
 * `com.seewo.mcuservice`); this does the same, talking to the service's binder directly:
 * `ISDKServiceManager.getService("opensdk")` → `ISDKService.getToken(pkg, "pkg:0")` →
 * `ISDKService.sendMessage(token, {"requestName", "paramsJson"})`.
 *
 * On any other device the service isn't there and everything here quietly does nothing.
 * All calls run on a background thread; the binder calls are synchronous.
 */
internal class PanelTouch(context: Context) {

    // The application context: this outlives no one, but [current] is static.
    private val context: Context = context.applicationContext


    private val thread = HandlerThread("MarcarPanelTouch").also { it.start() }
    private val worker = Handler(thread.looper)
    private var sdk: IBinder? = null
    private var token: String? = null
    private var bound = false
    private val areas = HashMap<Int, Rect>()
    private val cableFlags = HashMap<Int, Boolean>()

    // The panel numbers each area itself (the "result" of adding it); removing needs that number.
    private val areaIds = HashMap<Int, Int>()

    /** True once the panel's SDK answered (so this is a panel that needs it). */
    @Volatile
    var available = false
        private set

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            binder ?: return
            worker.post { connect(binder) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            worker.post {
                sdk = null
                token = null
                available = false
            }
        }
    }

    fun start() {
        current = this
        val intent = Intent(SERVICE_MANAGER).setPackage(SDK_PACKAGE)
        bound = runCatching { context.bindService(intent, connection, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
        if (!bound) Log.i(TAG, "panel SDK not present")
    }

    fun stop() {
        if (current === this) current = null
        worker.removeCallbacks(pollSource)
        worker.post {
            for (id in areas.keys.toList()) remove(id)
            if (bound) runCatching { context.unbindService(connection) }
            bound = false
            thread.quitSafely()
        }
    }

    /** Reserves [rect] (screen pixels) for Android under [id]; an empty rect releases it. */
    fun setArea(id: Int, rect: Rect, cable: Boolean = true) {
        val copy = Rect(rect)
        worker.post {
            cableFlags[id] = cable
            if (copy.isEmpty) remove(id) else put(id, copy)
        }
    }

    fun clearArea(id: Int) = worker.post { remove(id) }


    /** Asks which input the panel is showing; [done] runs on the worker thread with the raw reply. */
    fun querySource(done: (String?) -> Unit) = worker.post {
        done(send("com.seewo.sdk.internal.command.source.CmdGetCurrentSource", "{}"))
    }

    /**
     * Called on the main thread when the panel switches between Android and an external source
     * (the OPS PC, HDMI…). Checked every couple of seconds while the SDK is there.
     */
    var onSourceChanged: ((external: Boolean) -> Unit)? = null
    private var external: Boolean? = null
    private val main = Handler(android.os.Looper.getMainLooper())
    private val pollSource = object : Runnable {
        override fun run() {
            // Polled only while the SDK is there; a reconnection starts it again.
            if (!available) return
            val reply = runCatching {
                send("com.seewo.sdk.internal.command.source.CmdGetCurrentSource", "{}", quiet = true)
            }.getOrNull()
            sourceOf(reply)?.let { item ->
                val now = item != "ANDROID"
                if (now != external) {
                    external = now
                    Log.i(TAG, "source $item")
                    main.post { onSourceChanged?.invoke(now) }
                }
            }
            worker.postDelayed(this, SOURCE_POLL_MS)
        }
    }

    private fun connect(manager: IBinder) {
        val service = runCatching { getService(manager, "opensdk") }.getOrNull()
        if (service == null) {
            Log.w(TAG, "opensdk service unavailable")
            return
        }
        val pkg = context.packageName
        val t = runCatching { getToken(service, pkg, "$pkg:0") }.onFailure { Log.w(TAG, "getToken failed", it) }.getOrNull()
        sdk = service
        token = t
        available = t != null
        Log.i(TAG, "panel SDK connected, token=${t != null}")
        // A previous run that died mid-stroke could have left an area behind. Adding with the same
        // widget id updates it, which tells us its number, and then it can be removed.
        for (id in ALL_AREAS) if (id !in areas) {
            put(id, Rect(0, 0, 1, 1))
            remove(id)
        }
        // Areas requested before the connection (or before a reconnection) are applied now.
        for ((id, r) in areas) put(id, r)
        worker.removeCallbacks(pollSource)
        worker.post(pollSource)
    }

    private fun put(id: Int, r: Rect) {
        areas[id] = r
        val params = """{"area":{"bottom":${r.bottom},"empty":false,"left":${r.left},"right":${r.right},""" +
            """"stability":0,"top":${r.top},"valid":true},"isCableTouchEnable":${cableFlags[id] ?: true},"widgetId":$id}"""
        val reply = send("com.seewo.sdk.internal.command.touch.CmdAddOrUpdateNoTouchArea", params) ?: return
        val areaId = resultOf(reply)
        if (areaId != null) areaIds[id] = areaId else Log.w(TAG, "no area number in reply: $reply")
    }

    private fun remove(id: Int) {
        areas.remove(id)
        val areaId = areaIds.remove(id) ?: return
        send(REMOVE, """{"areaId":$areaId}""")
    }

    private fun send(request: String, params: String, quiet: Boolean = false): String? {
        val service = sdk ?: return null
        val t = token ?: return null
        val message = """{"paramsJson":${quote(params)},"requestName":${quote(request)}}"""
        return runCatching { sendMessage(service, t, message) }
            .onSuccess { if (DEBUG && !quiet) Log.d(TAG, "$request → $it") }
            .onFailure { Log.w(TAG, "$request failed", it) }
            .getOrNull()
    }

    // --- Raw binder calls (the AIDL interfaces live in the vendor's SDK, not in the platform) ---

    private fun getService(manager: IBinder, name: String): IBinder? = call(manager, MANAGER_DESCRIPTOR, 1) {
        writeString(name)
    }?.let { reply -> reply.readStrongBinder().also { reply.recycle() } }

    private fun getToken(service: IBinder, a: String, b: String): String? = call(service, SDK_DESCRIPTOR, 1) {
        writeString(a)
        writeString(b)
    }?.let { reply -> reply.readString().also { reply.recycle() } }

    private fun sendMessage(service: IBinder, token: String, message: String): String? = call(service, SDK_DESCRIPTOR, 3) {
        writeString(token)
        writeString(message)
    }?.let { reply -> reply.readString().also { reply.recycle() } }

    /** Returns the reply (after its exception header) for the caller to read and recycle. */
    private inline fun call(binder: IBinder, descriptor: String, code: Int, write: Parcel.() -> Unit): Parcel? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(descriptor)
            data.write()
            binder.transact(code, data, reply, 0)
            reply.readException()
            return reply
        } catch (e: Exception) {
            reply.recycle()
            throw e
        } finally {
            data.recycle()
        }
    }

    companion object {
        /** The running instance, for the debug-only experiment receiver (holds only the app context). */
        @android.annotation.SuppressLint("StaticFieldLeak")
        @Volatile
        var current: PanelTouch? = null
            private set

        private const val TAG = "MarcarPanel"
        private val DEBUG = BuildConfig.DEBUG
        private const val SDK_PACKAGE = "com.seewo.mcuservice"
        private const val SERVICE_MANAGER = "com.ifpdos.opensdk.SERVICE_MANAGER"
        private const val MANAGER_DESCRIPTOR = "com.seewo.sdk.internal.model.ISDKServiceManager"
        private const val SDK_DESCRIPTOR = "com.seewo.sdk.internal.model.ISDKService"

        // Area ids, far from the ones the panel's own bar uses (1–5).
        const val AREA_INK = 7701
        const val AREA_ORB = 7702
        const val AREA_RING = 7703
        const val AREA_WHITEBOARD = 7704
        const val AREA_TRASH = 7705
        const val AREA_PALETTE = 7706
        const val AREA_PAPER = 7707
        private val ALL_AREAS = AREA_INK..AREA_PAPER
        private const val REMOVE = "com.seewo.sdk.internal.command.touch.CmdRemoveNoTouchArea"

        private val RESULT = Regex("""\\"result\\":(-?\d+)""")

        private const val SOURCE_POLL_MS = 2000L
        private val SOURCE = Regex("""sdkSourceItem\\":\\"(\w+)""")

        /** The source in a reply like {"paramsJson":"{\"sdkSourceItem\":\"ANDROID\"}",...}. */
        internal fun sourceOf(reply: String?): String? = reply?.let { SOURCE.find(it)?.groupValues?.get(1) }

        /** The area number in a reply like {"paramsJson":"{\"result\":5}",...}. */
        internal fun resultOf(reply: String): Int? = RESULT.find(reply)?.groupValues?.get(1)?.toIntOrNull()

        private fun quote(s: String) = buildString {
            append('"')
            for (c in s) when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                else -> append(c)
            }
            append('"')
        }
    }
}
