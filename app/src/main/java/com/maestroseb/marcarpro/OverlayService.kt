package com.maestroseb.marcarpro

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams as WLP
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.edit
import androidx.core.graphics.get
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Hosts the ink layer and its floating controls, each in its own overlay window:
 *  - the main orb, which opens the ring of tools (long press = undo); on its rim, the cursor
 *    badge (writing ↔ using the screen underneath) and the ink-colour dot, which opens the ring
 *    of colours (tap, or press and slide onto one);
 *  - the ring of tools, "several pupils" among them, in the teacher's order;
 *  - the whiteboard button (on its own, draggable): an endless white page to write on; its
 *    scissors badge cuts a box of the screen out onto it (an exercise of the digital book…);
 *  - the clear-all button (press and hold);
 *  - a palette card next to the pen, highlighter or eraser, or next to the orb.
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_STOP = "com.maestroseb.marcarpro.action.STOP"

        /** Opens the whiteboard (e.g. from a shortcut). */
        const val ACTION_WHITEBOARD = "com.maestroseb.marcarpro.action.WHITEBOARD"
        const val ACTION_PANEL_CROP = "com.maestroseb.marcarpro.action.PANEL_CROP"
        const val ACTION_SETUP = "com.maestroseb.marcarpro.action.SETUP"

        /** Readable without binding (the boot receiver checks it). */
        @Volatile
        var running = false
            private set

        internal const val CHANNEL = "overlay"

        /** Started by the boot receiver: a good moment to install a waiting update. */
        const val EXTRA_BOOT = "boot"
        private const val NOTIFICATION_ID = 7
        private const val PASS_THROUGH_ALPHA = 0.8f
        private const val RELAY_SETTLE_MS = 40L
        private const val RELAY_TIMEOUT_MS = 1000L
        private const val IDLE_CLOSE_MS = 10_000L
        private const val UNDO_HOLD_MS = 550L
        private const val REOPEN_GUARD_MS = 600L
        private const val CLEAR_HOLD_MS = 900L
        private const val SAVE_DELAY_MS = 1500L
        private const val SAVE_ON_EXIT_MS = 2000L
        private const val MAX_BOARDS = 24
        private const val PAGE_RETRY_MS = 300L
        private const val PAGE_FILE_DAYS = 30L
        private const val UPDATE_FIRST_CHECK_MS = 60_000L
        private const val UPDATE_TICK_MS = 10L * 60 * 1000
        private const val UPDATE_IDLE_MS = 30L * 60 * 1000

        // Before the screenshot: the floating controls (and the whiteboard's ink) must be gone.
        private const val CROP_SETTLE_MS = 120L
        private const val CROP_AFTER_WHITEBOARD_MS = 400L
        private const val CROP_LOADING_RETRY_MS = 100L
        private const val BLANK_GRID = 15
        private const val BLANK_TOLERANCE = 20
        private const val BLANK_SHARE = 0.9f
        private const val PASTE_FOCUS_MS = 400L
        private const val SETUP_POLL_MS = 1000L
        private const val SETUP_WAIT_MS = 3L * 60 * 1000
        private const val PANEL_CROP_TIMEOUT_MS = 10L * 60 * 1000
        private const val PANEL_CROP_RETRIES = 12
        private const val PANEL_CROP_RETRY_MS = 150L
        private const val PANEL_CROP_WATCH_MS = 150L
        private const val PASTE_MAX_PX = 2400
        private const val CANVAS_FLAGS = WLP.FLAG_LAYOUT_IN_SCREEN or
            WLP.FLAG_LAYOUT_NO_LIMITS or WLP.FLAG_NOT_FOCUSABLE
    }

    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())

    private var canvas: AnnotationView? = null
    private var orbit: OrbitLayout? = null
    private var ringFloater: Floater? = null
    private var orbButton: OrbButton? = null
    private var orbFloater: Floater? = null
    private var colorRing: ColorRingView? = null
    private var colorFloater: Floater? = null
    private var trashFloater: Floater? = null
    private var whiteView: MiniOrbView? = null
    private var whiteFloater: Floater? = null
    private var paperPicker: PaperPickerView? = null
    private var paperShown = false
    private val paperParams = WLP(
        WLP.WRAP_CONTENT, WLP.WRAP_CONTENT, WLP.TYPE_APPLICATION_OVERLAY,
        WLP.FLAG_NOT_FOCUSABLE or WLP.FLAG_LAYOUT_IN_SCREEN or WLP.FLAG_WATCH_OUTSIDE_TOUCH, PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.START
        title = "Marcar Pro paper"
    }
    private var cropView: CropView? = null
    private var cropping = false
    private var cropFromWhiteboard = false
    private var cropWasPaused = false
    private var prompt: PromptView? = null
    private var hintView: HintView? = null

    // The whiteboard covers whatever is on screen; [underPage] is the page waiting underneath.
    private var whiteboard = false
    private var underPage = PageKeys.GENERAL
    private var closeTarget: CloseTargetView? = null
    private var closeFloater: Floater? = null
    private var palette: PaletteView? = null
    private var paletteShown = false
    private var paletteHiddenAt = 0L
    private var paletteTool = Tool.PEN
    private val paletteAnchor = Point()

    // Starts "using the screen" (the cursor): the panel is switched on for many things besides writing.
    private var paused = true

    // On the ViewBoard showing the PC, keeps our touches from also reaching the PC.
    private var panel: PanelTouch? = null

    // True while the panel shows another source (the OPS PC, HDMI…) instead of Android. Touch
    // there goes to the PC unless the board is reserved before it lands, and a contact can't be
    // re-routed once down: so it is either "writing" (whole board reserved) or "using the PC"
    // (paused, nothing reserved). The orb's cursor badge switches between the two.
    private var external = false

    private var screenRect = Rect()
    private var relaying = false
    private var relayHintShown = false

    private val canvasParams = WLP(
        WLP.MATCH_PARENT, WLP.MATCH_PARENT, WLP.TYPE_APPLICATION_OVERLAY, CANVAS_FLAGS, PixelFormat.TRANSLUCENT,
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = WLP.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        title = "Marcar Pro ink"
    }

    private val paletteParams = WLP(
        WLP.WRAP_CONTENT, WLP.WRAP_CONTENT, WLP.TYPE_APPLICATION_OVERLAY,
        WLP.FLAG_NOT_FOCUSABLE or WLP.FLAG_WATCH_OUTSIDE_TOUCH or WLP.FLAG_LAYOUT_IN_SCREEN or WLP.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        title = "Marcar Pro palette"
    }

    private val idleClose = Runnable { closeRing() }

    // The board is saved shortly after every change, off the main thread, and on exit.
    private val io = Executors.newSingleThreadExecutor()

    // Updates: checked and downloaded on their own thread (a download must never delay a save),
    // installed only when nobody is writing.
    private val updates = Executors.newSingleThreadExecutor()
    private lateinit var updater: Updater
    private var lastActivity = SystemClock.uptimeMillis()
    private val updateTick = object : Runnable {
        override fun run() {
            updates.execute { updater.checkAndDownload() }
            if (SystemClock.uptimeMillis() - lastActivity > UPDATE_IDLE_MS) installUpdate()
            handler.postDelayed(this, UPDATE_TICK_MS)
        }
    }
    private val saveInk = Runnable { saveNow() }

    // Each browser page has its own board (and undo history); the most recent stay in memory.
    private var pageKey = PageKeys.GENERAL
    private var pendingPage: String? = null
    private val retryPage = Runnable { pendingPage?.let { showPage(it) } }
    private val boards = object : LinkedHashMap<String, AnnotationView.Board>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AnnotationView.Board>) = size > MAX_BOARDS
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        startInForeground()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        wm = getSystemService(WindowManager::class.java)
        prefs = Prefs(this)
        val screen = wm.screenSize()
        val touch = PanelTouch(this).also { it.start() }
        panel = touch
        screenRect = Rect(0, 0, screen.x, screen.y)
        touch.onSourceChanged = { ext ->
            external = ext
            applyCanvasWindow()
            applyReservation()
            refresh()
            if (ext) toast(if (paused) R.string.mode_use_pc else R.string.mode_write_pc)
        }

        // 1. The ink layer. The saved ink is read off the main thread, so the orb is up at once.
        val ink = AnnotationView(windowContext()).apply {
            contentDescription = getString(R.string.canvas_description)
            fingerDraws = !(prefs.stylusSeen || hasStylusHardware())
            onStylusDetected = { prefs.stylusSeen = true }
            onPassThroughTap = { x, y -> relayTap(x, y) }
            onPassThroughDrag = { xs, ys, ms -> relayDrag(xs, ys, ms) }
            onInkShown = { refresh() }
            onMessage = { toast(it) }
            onPasteRequest = { x, y, copiedAt -> pasteFromClipboard(x, y, copiedAt) }
        }
        canvas = ink
        wm.addView(ink, canvasParams)
        pageKey = TouchRelayService.instance?.page ?: PageKeys.GENERAL
        ink.showBoard(loadBoard(ink, pageKey))
        TouchRelayService.onPageChanged = ::switchPage
        io.execute(::forgetOldPages)
        ink.listener = AnnotationView.Listener {
            lastActivity = SystemClock.uptimeMillis()
            changed.add(pageKey)
            handler.removeCallbacks(saveInk)
            handler.postDelayed(saveInk, SAVE_DELAY_MS)
        }

        // 2. Clear-all: press and hold.
        val trash = TrashView(this)
        val trashF = Floater(wm, trash, dpi(52f), core = dpi(20f))
        trashFloater = trashF
        trashF.attach(prefs.position("trash", Point(screen.x - dpi(40f), dpi(56f))))
        reserve(PanelTouch.AREA_TRASH, trashF)
        trash.setOnTouchListener(FloatGestures(trashF, CLEAR_HOLD_MS,
            onTap = { toast(R.string.clear_hold) },
            onLongPress = ::clearAll,
            onPressChanged = { pressing -> trash.hold(pressing, CLEAR_HOLD_MS) },
            onDragEnd = { x, y, _ ->
                prefs.savePosition("trash", x, y)
                reserve(PanelTouch.AREA_TRASH, trashF)
            },
        ))

        // 3. Close target, shown while the main orb is dragged (under the orb, never touchable).
        val target = CloseTargetView(this)
        closeTarget = target
        val targetF = Floater(wm, target, dpi(124f), core = dpi(62f), flags = WLP.FLAG_NOT_TOUCHABLE)
        closeFloater = targetF
        targetF.attach(screen.x / 2, screen.y - dpi(96f))

        // 4. Main orb and its ring, each in its own window (added last, so they float above the others).
        val ring = listOf(
            RingItem(Tool.PEN, R.drawable.ic_pen, getString(R.string.tool_pen), tint = 0xFF3B5BDB.toInt()),
            RingItem(Tool.HIGHLIGHTER, R.drawable.ic_highlighter, getString(R.string.tool_highlighter), tint = 0xFFF08C00.toInt()),
            RingItem(Tool.ERASER, R.drawable.ic_eraser, getString(R.string.tool_eraser), tint = 0xFFE03E5E.toInt()),
            RingItem(null, R.drawable.ic_hide, getString(R.string.tool_hide), RingToggle.HIDE, tint = 0xFF5C677D.toInt()),
            RingItem(Tool.MAGIC, R.drawable.ic_magic, getString(R.string.tool_magic), tint = 0xFFC2255C.toInt()),
            RingItem(Tool.SHAPE, R.drawable.ic_shape, getString(R.string.tool_shape), tint = 0xFF0C8F6F.toInt()),
            RingItem(null, R.drawable.ic_group, getString(R.string.tool_pupils), RingToggle.PUPILS, tint = 0xFF7048E8.toInt()),
        )
        // First time: against the right edge, a little above the middle (clear of the panel's own side tab).
        val orbAt = prefs.position("orb", Point(screen.x - dpi(64f), (screen.y * 0.4f).toInt()))
        val orbitView = OrbitLayout(this, ring, prefs.ringOrder, prefs.ringHidden, ::onRingItem)
        orbit = orbitView
        orbitView.onOrderChanged = { order, hidden ->
            prefs.ringOrder = order
            prefs.ringHidden = hidden
        }
        orbitView.onEditChanged = { editing ->
            if (editing) {
                handler.removeCallbacks(idleClose)
                hidePalette()
                toast(R.string.ring_edit_hint)
            } else {
                armIdle()
            }
        }
        val ringF = Floater(wm, orbitView, orbitView.openSize, core = 0,
            flags = WLP.FLAG_NOT_TOUCHABLE or WLP.FLAG_WATCH_OUTSIDE_TOUCH)
        ringFloater = ringF
        ringF.attach(orbAt)
        orbitView.onEmptyTap = ::closeRing
        orbitView.onOutside = ::onOutsideRing
        val colors = ColorRingView(this, ::onColorItem)
        colorRing = colors
        val colorF = Floater(wm, colors, colors.openSize, core = 0,
            flags = WLP.FLAG_NOT_TOUCHABLE or WLP.FLAG_WATCH_OUTSIDE_TOUCH)
        colorFloater = colorF
        colorF.attach(orbAt)
        colors.onEmptyTap = ::closeColors
        colors.onOutside = { x, y -> if (!nearOrb(x, y)) closeColors() }
        val orb = OrbButton(this)
        orbButton = orb
        // The cursor badge sticks out at two o'clock, so the orb keeps a little further from the right edge.
        val orbF = Floater(wm, orb, dpi(110f), core = dpi(44f), coreRight = dpi(50f))
        orbFloater = orbF
        orbF.attach(orbAt)
        reserve(PanelTouch.AREA_ORB, orbF)
        orb.setOnTouchListener(FloatGestures(orbF, UNDO_HOLD_MS,
            // The cursor badge switches writing ↔ using the screen; while in use, the orb brings the pen back.
            // The colour dot opens the colours right there (or press it and slide onto one).
            onTap = {
                when {
                    orbitView.editing -> orbitView.stopEditing()
                    orb.downOnBadge || paused -> togglePause()
                    colors.isOpen -> closeColors()
                    orb.downOnDot -> openColors()
                    else -> toggleRing()
                }
            },
            onLongPress = ::undo,
            // A slow press on the badge or the dot is still a tap on it, never an undo.
            longPressEnabled = { !orb.downOnBadge && !orb.downOnDot },
            onDragStart = {
                closeRing()
                target.show(true)
            },
            onDragMove = ::onOrbDragged,
            slideEnabled = { orb.downOnDot && !paused },
            onSlide = ::slideColors,
            onDragEnd = { x, y, cancelled ->
                // A gesture the system cancels over the × must not close the app.
                if (target.armed && !cancelled) {
                    exit()
                } else {
                    target.armed = false
                    target.show(false)
                    orb.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                    prefs.savePosition("orb", x, y)
                    reserve(PanelTouch.AREA_ORB, orbF)
                }
            },
        ))

        // 5. Whiteboard button, on its own (first time: bottom right).
        val white = MiniOrbView(this, R.drawable.ic_whiteboard, R.drawable.ic_scissors).apply {
            contentDescription = getString(R.string.whiteboard)
        }
        whiteView = white
        // The scissors stick out at two o'clock, so the button keeps a little further from the right edge.
        val whiteF = Floater(wm, white, dpi(84f), core = dpi(28f), coreRight = dpi(44f))
        whiteFloater = whiteF
        whiteF.attach(prefs.position("whiteboard", Point(screen.x - dpi(56f), screen.y - dpi(150f))))
        reserve(PanelTouch.AREA_WHITEBOARD, whiteF)
        white.setOnTouchListener(FloatGestures(whiteF,
            onTap = { if (white.downOnBadge) startCrop() else toggleWhiteboard() },
            onDragEnd = { x, y, _ ->
                prefs.savePosition("whiteboard", x, y)
                reserve(PanelTouch.AREA_WHITEBOARD, whiteF)
            },
        ))

        // 6. Palette card, shown on demand.
        palette = PaletteView(this, ::onPick).apply {
            onOutside = ::hidePalette
            onResized = { if (paletteShown) placePalette(paletteTool, paletteAnchor.x, paletteAnchor.y) }
        }

        updater = Updater(this)
        handler.postDelayed(updateTick, UPDATE_FIRST_CHECK_MS)
        announceUpdate()

        // 7. The whiteboard's background picker, shown with the whiteboard.
        paperPicker = PaperPickerView(this) { p ->
            prefs.paper = p
            canvas?.paperStyle = p
        }.apply {
            paper = prefs.paper
            // Opening the list makes the window taller (upwards): the PC must not get those touches either.
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (paperShown) reservePaper() }
        }
        ink.paperStyle = prefs.paper

        // It opens with the cursor ("using the screen"); the orb brings the pen, on an ink tool.
        val start = prefs.tool.takeIf { it != Tool.ERASER } ?: Tool.PEN
        ink.tool = start
        prefs.tool = start
        applyInk()
        applyCanvasWindow()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Without the overlay permission onCreate gave up: there is nothing to act on.
        if (canvas == null) return START_NOT_STICKY
        // Opening the app never switches to writing (it starts on the cursor): the orb does that.
        when {
            intent?.getBooleanExtra(EXTRA_BOOT, false) == true -> installUpdate()
            intent?.action == ACTION_STOP -> stopSelf()
            intent?.action == ACTION_WHITEBOARD -> if (!whiteboard) toggleWhiteboard()
            intent?.action == ACTION_PANEL_CROP -> if (panelCropAsked) {
                // The images permission was just granted from the scissors: carry on cutting.
                panelCropAsked = false
                startPanelCrop()
            } else {
                refreshSetup()
            }
            intent?.action == ACTION_SETUP -> showSetup()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        TouchRelayService.onPageChanged = null
        TouchRelayService.onConnected = null
        handler.removeCallbacksAndMessages(null)
        panel?.onSourceChanged = null
        panel?.stop()
        panelCrop.stop()
        canvas?.settle()
        saveNow()
        io.shutdown()
        // Let the last save reach the disk: the process may be killed soon after (panel switched off).
        runCatching { io.awaitTermination(SAVE_ON_EXIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS) }
        running = false // only now: the board is on disk
        updates.shutdown()
        closeFloater?.detach()
        cropView?.let { runCatching { wm.removeView(it) } }
        closePrompt()
        closeSetup()
        hintView?.let { runCatching { wm.removeView(it) } }
        showPaperPicker(false)
        hidePalette()
        whiteFloater?.detach()
        orbFloater?.detach()
        colorFloater?.detach()
        ringFloater?.detach()
        trashFloater?.detach()
        canvas?.let { runCatching { wm.removeView(it) } }
        canvas = null
        super.onDestroy()
    }

    // region Closing and saving

    /** Near the × the orb snaps onto it, so letting go there closes the app. */
    private fun onOrbDragged(x: Int, y: Int) {
        val target = closeTarget ?: return
        val t = closeFloater ?: return
        val near = hypot((x - t.cx).toFloat(), (y - t.cy).toFloat()) < dp(80f)
        if (near != target.armed) target.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        target.armed = near
        orbButton?.let { it.animate().scaleX(if (near) 0.8f else 1f).scaleY(if (near) 0.8f else 1f).setDuration(140).start() }
        if (near) orbFloater?.moveTo(t.cx, t.cy)
    }

    /** Closes Marcar Pro; the ink is saved and comes back next time. A waiting update goes in now. */
    private fun exit() {
        toast(R.string.closed_saved)
        installUpdate()
        stopSelf()
    }

    private fun installUpdate() {
        if (!::updater.isInitialized) return
        updates.execute {
            if (updater.installIfReady() == Updater.Install.NEEDS_PERMISSION) handler.post(::askToAllowUpdates)
        }
    }

    /** After an update, says so once. */
    private fun announceUpdate() {
        val sp = getSharedPreferences("marcar_updates", MODE_PRIVATE)
        val last = sp.getString("announced", null)
        if (last != null && last != BuildConfig.VERSION_NAME) {
            toast(getString(R.string.updated, BuildConfig.VERSION_NAME), long = true)
        }
        if (last != BuildConfig.VERSION_NAME) sp.edit { putString("announced", BuildConfig.VERSION_NAME) }
    }

    // Pages whose ink changed since it was last written: only those are saved.
    private val changed = HashSet<String>()

    private fun saveNow() {
        val key = pageKey
        if (key !in changed) return
        // Until its saved ink is back, writing the board would overwrite that ink.
        if (key in loading) {
            handler.removeCallbacks(saveInk)
            handler.postDelayed(saveInk, SAVE_DELAY_MS)
            return
        }
        val ink = canvas ?: return
        changed.remove(key)
        val file = pageFile(key)
        if (ink.isEmpty) {
            // No file for a page without ink (visiting pages mustn't leave files behind).
            io.execute { InkStore.delete(file) }
            return
        }
        val data = ink.encodeInk()
        io.execute { InkStore.write(file, data) }
    }

    private fun pageFile(key: String) = File(filesDir, PageKeys.fileName(key))

    // Pages whose saved ink is still being read.
    private val loading = HashSet<String>()

    /**
     * The board for [key]: from memory, or a new one that fills in with the saved ink as soon as
     * it has been read and decoded on the IO thread (anything written meanwhile is kept).
     */
    private fun loadBoard(ink: AnnotationView, key: String): AnnotationView.Board {
        boards[key]?.let { return it }
        val board = ink.newBoard(paper = key == PageKeys.WHITEBOARD)
        boards[key] = board
        val file = pageFile(key)
        loading.add(key)
        io.execute {
            val saved = InkStore.read(file)
            handler.post {
                loading.remove(key)
                if (saved.isNotEmpty()) canvas?.addSaved(board, saved)
            }
        }
        return board
    }

    /** Another tab or app came to the front: show its ink, keeping this page's for later. */
    private fun switchPage(key: String) {
        // Under the whiteboard the page can change; it shows again when the whiteboard closes.
        if (whiteboard) {
            underPage = key
            pendingPage = null
            return
        }
        showPage(key)
    }

    /**
     * Shows [key]'s board. A page change waits for the pen to lift; an explicit one ([now], the
     * whiteboard button) happens at once (whatever is half done is landed or dropped).
     */
    private fun showPage(key: String, now: Boolean = false) {
        val ink = canvas ?: return
        handler.removeCallbacks(retryPage)
        if (key == pageKey) {
            pendingPage = null
            return
        }
        if (!now && !ink.isIdle) {
            // Never cut a stroke in half: switch as soon as the pen lifts.
            pendingPage = key
            handler.postDelayed(retryPage, PAGE_RETRY_MS)
            return
        }
        pendingPage = null
        handler.removeCallbacks(saveInk)
        saveNow()
        hidePalette()
        pageKey = key
        ink.showBoard(loadBoard(ink, key))
    }

    /** Ink of pages not visited for a month goes away, so the board doesn't fill up. */
    private fun forgetOldPages() {
        val cutoff = System.currentTimeMillis() - PAGE_FILE_DAYS * 24 * 60 * 60 * 1000
        filesDir.listFiles { f -> f.name.startsWith("page_") && f.lastModified() < cutoff }?.forEach { it.delete() }
    }

    // endregion

    // region Ring and palette

    private fun toggleRing() {
        val o = orbit ?: return
        if (o.isOpen) closeRing() else openRing()
    }

    private fun openRing() {
        val o = orbit ?: return
        val f = orbFloater ?: return
        val ringF = ringFloater ?: return
        hidePalette()
        closeColors()
        // The ring window is empty while closed, so moving it under the orb is invisible.
        ringF.moveTo(f.cx, f.cy)
        ringF.setTouchable(true)
        reserve(PanelTouch.AREA_RING, ringF)
        val screen = wm.screenSize()
        o.open(f.cx, f.cy, screen.x, screen.y)
        armIdle()
    }

    private fun closeRing() {
        val o = orbit ?: return
        handler.removeCallbacks(idleClose)
        hidePalette()
        if (!o.isOpen) return
        ringFloater?.setTouchable(false)
        panel?.clearArea(PanelTouch.AREA_RING)
        o.close()
    }

    /** Whether a screen point is on the main orb (which toggles what is open itself). */
    private fun nearOrb(x: Float, y: Float): Boolean {
        val f = orbFloater ?: return false
        return hypot(x - f.cx, y - f.cy) <= dp(55f)
    }

    /** A touch outside the open ring closes it, unless it is on the orb (which toggles it) or the palette. */
    private fun onOutsideRing(x: Float, y: Float) {
        if (nearOrb(x, y)) return
        if (paletteShown) {
            val p = palette ?: return
            val px = paletteParams.x
            val py = paletteParams.y
            if (x >= px && x <= px + p.width && y >= py && y <= py + p.height) return
        }
        closeRing()
    }

    /** Like Smart Ink, the ring closes itself after a while without use. */
    private fun armIdle() {
        handler.removeCallbacks(idleClose)
        if (orbit?.editing == true) return
        handler.postDelayed(idleClose, IDLE_CLOSE_MS)
    }

    private fun onRingItem(item: RingItem, anchor: SatelliteView) {
        val ink = canvas ?: return
        armIdle()
        when (item.toggle) {
            RingToggle.HIDE -> {
                hidePalette()
                ink.inkHidden = !ink.inkHidden
                refresh()
                return
            }
            RingToggle.PUPILS -> {
                hidePalette()
                togglePupils()
                return
            }
            null -> Unit
        }
        val tool = item.tool ?: return
        val hasPalette = tool == Tool.PEN || tool == Tool.HIGHLIGHTER || tool == Tool.ERASER
        if (tool == ink.tool && hasPalette) {
            // Tapping the active tool again opens (or closes) its colour/size card.
            if (paletteShown || paletteJustClosed()) hidePalette() else showPalette(tool, anchor)
            return
        }
        hidePalette()
        ink.tool = tool
        prefs.tool = tool
        applyInk() // each tool has its own colour
    }

    private fun showPalette(tool: Tool, anchor: View) {
        val loc = IntArray(2).also { anchor.getLocationOnScreen(it) }
        placePalette(tool, loc[0] + anchor.width / 2, loc[1] + dpi(32f))
    }

    /** The tool a colour is for: the ink tool in use, or the pen. */
    private fun inkTarget(): Tool = when (val t = canvas?.tool) {
        Tool.PEN, Tool.HIGHLIGHTER, Tool.SHAPE -> t
        else -> Tool.PEN
    }

    /** Picking a colour while erasing (or with magic ink) means "write". */
    private fun toInkTool() {
        val ink = canvas ?: return
        val tool = inkTarget()
        if (ink.tool == tool) return
        ink.tool = tool
        prefs.tool = tool
    }

    /** The orb's colour dot: the colours around the orb, whatever the tool. */
    private fun openColors() {
        val f = orbFloater ?: return
        val view = colorRing ?: return
        val win = colorFloater ?: return
        if (view.isOpen) return
        closeRing()
        // The window is empty while closed, so moving it under the orb is invisible.
        win.moveTo(f.cx, f.cy)
        win.setTouchable(true)
        reserve(PanelTouch.AREA_RING, win) // never open together with the ring of tools
        val screen = wm.screenSize()
        val colors = Presets.COLORS.toList() + prefs.customColors
        view.open(f.cx, f.cy, screen.x, screen.y, colors, prefs.colorFor(inkTarget()))
    }

    private fun closeColors() {
        val view = colorRing ?: return
        if (!view.isOpen) return
        colorFloater?.setTouchable(false)
        panel?.clearArea(PanelTouch.AREA_RING)
        view.close()
    }

    /** Pressing the colour dot and sliding: the colours open, follow the finger, and letting go picks. */
    private fun slideColors(x: Float, y: Float, end: Boolean) {
        val view = colorRing ?: return
        if (!view.isOpen) openColors()
        if (!end) {
            view.hover(x, y)
            return
        }
        val item = if (x.isNaN()) null else view.itemAt(x, y)
        if (item == null) closeColors() else onColorItem(item)
    }

    private fun onColorItem(item: ColorRingView.Item) {
        lastActivity = SystemClock.uptimeMillis()
        when (item) {
            is ColorRingView.Item.Ink -> {
                toInkTool()
                prefs.setColorFor(inkTarget(), item.color)
                applyInk()
                closeColors()
            }
            ColorRingView.Item.Add -> {
                val f = orbFloater ?: return
                closeColors()
                toInkTool()
                val tool = inkTarget()
                palette?.startPicking(prefs.colorFor(tool))
                placePalette(tool, f.cx, f.cy)
            }
        }
    }

    /** The tap that closed the card (as a touch outside it, on its way down) shouldn't open it again. */
    private fun paletteJustClosed() = SystemClock.uptimeMillis() - paletteHiddenAt < REOPEN_GUARD_MS

    /** Shows the card for [tool] next to the point ([ax], [ay]), opening away from the orb. */
    private fun placePalette(tool: Tool, ax: Int, ay: Int) {
        val p = palette ?: return
        val orbF = orbFloater ?: return
        paletteTool = tool
        paletteAnchor.set(ax, ay)
        p.showFor(tool, prefs.colorFor(tool), prefs.sizeIndex, prefs.eraserIndex, prefs.customColors)
        p.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val pw = p.measuredWidth
        val ph = p.measuredHeight
        val gap = dpi(36f)
        val screen = wm.screenSize()
        // Open outwards from the ring: above a tool at the top, beside one on the side, and so on.
        // From the orb itself, towards the middle of the screen.
        val fromOrb = ax == orbF.cx && ay == orbF.cy
        val dx = if (fromOrb) screen.x / 2 - ax else ax - orbF.cx
        val dy = if (fromOrb) 0 else ay - orbF.cy
        val away = if (fromOrb) dpi(30f) else 0
        var x: Int
        var y: Int
        if (kotlin.math.abs(dy) >= kotlin.math.abs(dx)) {
            x = ax - pw / 2
            y = if (dy < 0) ay - gap - ph else ay + gap + dpi(16f)
        } else {
            x = if (dx < 0) ax - gap - away - pw else ax + gap + away
            y = ay - ph / 2
        }
        // Keep it on screen; if it would hit the top or bottom, flip to the other side.
        if (y < dpi(8f)) y = ay + gap + dpi(16f)
        if (y + ph > screen.y - dpi(8f)) y = ay - gap - ph
        x = x.coerceIn(dpi(8f), screen.x - pw - dpi(8f))
        paletteParams.x = x
        paletteParams.y = y.coerceIn(dpi(8f), screen.y - ph - dpi(8f))
        if (paletteShown) wm.updateViewLayout(p, paletteParams) else wm.addView(p, paletteParams)
        paletteShown = true
        panel?.setArea(PanelTouch.AREA_PALETTE, Rect(paletteParams.x, paletteParams.y, paletteParams.x + pw, paletteParams.y + ph))
    }

    private fun hidePalette() {
        if (!paletteShown) return
        palette?.let {
            runCatching { wm.removeView(it) }
            it.reset()
        }
        paletteShown = false
        paletteHiddenAt = SystemClock.uptimeMillis()
        panel?.clearArea(PanelTouch.AREA_PALETTE)
    }

    private fun onPick(pick: PaletteView.Pick) {
        if (orbit?.isOpen == true) armIdle()
        val ink = canvas ?: return
        if (pick is PaletteView.Pick.Color && pick.live) {
            // Dragging in the picker: the ink and the orb's dot follow; nothing is saved yet.
            ink.inkColor = pick.color
            orbButton?.inkColor = pick.color
            return
        }
        when (pick) {
            is PaletteView.Pick.Color -> {
                prefs.setColorFor(if (paletteTool == Tool.ERASER) inkTarget() else paletteTool, pick.color)
                if (pick.save) prefs.addCustomColor(pick.color)
            }
            is PaletteView.Pick.Size -> prefs.sizeIndex = pick.index
            is PaletteView.Pick.Eraser -> prefs.eraserIndex = pick.index
        }
        // A colour or thickness from the orb's dot while erasing (or with magic ink) means "write".
        if (pick !is PaletteView.Pick.Eraser && (ink.tool == Tool.ERASER || ink.tool == Tool.MAGIC)) {
            ink.tool = Tool.PEN
            prefs.tool = Tool.PEN
        }
        applyInk()
        // While the picker is open the card stays as it is (rebuilding it would end the drag).
        if (paletteShown && palette?.isPicking != true) placePalette(paletteTool, paletteAnchor.x, paletteAnchor.y)
    }

    // endregion

    // region Modes

    /**
     * Opens or closes the endless whiteboard over whatever is on screen. A finger drags the paper,
     * two fingers zoom it; the pen writes as usual. Its ink is kept like any page's.
     */
    private fun toggleWhiteboard() {
        if (!whiteboard) {
            if (paused) togglePause()
            closeRing()
            underPage = pageKey
            whiteboard = true
            showPage(PageKeys.WHITEBOARD, now = true)
            toast(R.string.whiteboard_on)
        } else {
            whiteboard = false
            showPage(underPage, now = true)
        }
        whiteView?.on = whiteboard
        showPaperPicker(whiteboard)
    }

    /** The background picker, at the bottom left, only while the whiteboard is open. */
    private fun showPaperPicker(show: Boolean) {
        val p = paperPicker ?: return
        if (show == paperShown) return
        paperShown = show
        if (!show) {
            p.collapse()
            runCatching { wm.removeView(p) }
            panel?.clearArea(PanelTouch.AREA_PAPER)
            return
        }
        paperParams.x = dpi(8f)
        paperParams.y = dpi(8f)
        wm.addView(p, paperParams)
        p.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        reservePaper()
    }

    private fun reservePaper() {
        val p = paperPicker ?: return
        val w = if (p.width > 0) p.width else p.measuredWidth
        val h = if (p.height > 0) p.height else p.measuredHeight
        val screen = wm.screenSize()
        panel?.setArea(PanelTouch.AREA_PAPER, Rect(paperParams.x, screen.y - paperParams.y - h, paperParams.x + w, screen.y - paperParams.y))
    }

    // region Cut out to the whiteboard

    /**
     * The scissors: the screen is captured (without our controls), frozen, and a box drawn on it
     * goes to the whiteboard, under the cutouts already there, with room below to write.
     */
    private fun startCrop() {
        if (cropping) return
        if (external) {
            // The panel gives apps no picture of the PC or HDMI (it refuses VIDEO_AND_OSD shots):
            // its own Crop can, so that one does the cutting.
            if (panelCrop.available) startPanelCrop() else toast(R.string.crop_external, long = true)
            return
        }
        val relay = TouchRelayService.instance
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return // no screenshots before Android 11
        if (relay == null) {
            // Every time it's needed: a button takes the teacher to the switch.
            askForRelay(crop = true)
            return
        }
        cropping = true
        cropWasPaused = paused
        if (paused) togglePause()
        closeRing()
        closeColors()
        // What gets cut out is the page under the whiteboard.
        val wasWhiteboard = whiteboard
        cropFromWhiteboard = wasWhiteboard
        if (whiteboard) toggleWhiteboard()
        showControls(false)
        handler.postDelayed({ shoot(relay, wasWhiteboard) }, if (wasWhiteboard) CROP_AFTER_WHITEBOARD_MS else CROP_SETTLE_MS)
    }

    // On the PC or HDMI: the panel's own Crop cuts out, then its saved pictures come to the whiteboard.
    private val panelCrop by lazy { PanelCrop(this) }
    private var panelCropping = false

    private fun startPanelCrop(fromWhiteboard: Boolean = whiteboard) {
        if (cropping) return
        if (!panelCrop.canRead) {
            showPrompt(getString(R.string.panel_crop_title), getString(R.string.panel_crop_why), getString(R.string.panel_crop_allow)) {
                panelCropAsked = true
                askForImages()
            }
            return
        }
        val since = System.currentTimeMillis()
        cropping = true
        panelCropping = true
        closeRing()
        closeColors()
        // What gets cut out is what is under the whiteboard; our ink stays (it is part of the picture).
        cropFromWhiteboard = fromWhiteboard
        if (whiteboard) toggleWhiteboard()
        showControls(false)
        // The panel's Crop takes the touches meanwhile.
        applyCanvasWindow()
        panelCropSince = since
        panelCropRead = null
        if (!panelCrop.start { endPanelCrop(since) }) {
            finishPanelCrop(emptyList())
            toast(R.string.crop_external, long = true)
            return
        }
        // Should it never say it closed, the controls come back anyway.
        handler.postDelayed(panelCropTimeout, PANEL_CROP_TIMEOUT_MS)
        handler.postDelayed(watchPanelCrop, PANEL_CROP_WATCH_MS)
    }

    private var panelCropSince = 0L
    private var panelCropAsked = false

    private fun askForImages() {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .setAction(MainActivity.ACTION_ASK_IMAGES)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    // The saved cut-out, read as soon as the panel has written it (while its dialogs still close).
    private var panelCropRead: List<Pair<android.net.Uri, android.graphics.Bitmap>>? = null
    private var panelCropReading = false

    /**
     * After saving, the panel's Crop shows a "Saved" dialog and then stays open for more cut-outs
     * until its Exit is pressed. As soon as a cut-out is saved it is read, both are pressed for
     * the teacher, and the cut-out goes to the whiteboard without waiting for the Crop to close.
     */
    private val watchPanelCrop: Runnable = Runnable {
        if (!panelCropping) return@Runnable
        val since = panelCropSince
        val read = panelCropRead
        if (read == null) {
            if (!panelCropReading) {
                panelCropReading = true
                io.execute {
                    val saved = panelCrop.savedSince(since)
                    val images = saved.mapNotNull { uri -> runCatching { decodeImage(uri) }.getOrNull()?.let { uri to it } }
                    handler.post {
                        panelCropReading = false
                        if (since == panelCropSince && images.isNotEmpty()) panelCropRead = images
                    }
                }
            }
            handler.postDelayed(watchPanelCrop, PANEL_CROP_WATCH_MS)
            return@Runnable
        }
        val relay = TouchRelayService.instance
        if (relay != null) {
            // "Finish" closes the dialog, then Exit closes the Crop (right away, or at the next look).
            panelCrop.label(PanelCrop.SAVED_TITLE)?.let { title ->
                if (relay.shows(PanelCrop.PACKAGE, title)) relay.press(PanelCrop.PACKAGE, null, panelCrop.label(PanelCrop.SAVED_CLOSE))
            }
            if (!relay.press(PanelCrop.PACKAGE, PanelCrop.EXIT_BUTTON, panelCrop.label(PanelCrop.EXIT_LABEL))) {
                handler.postDelayed(watchPanelCrop, PANEL_CROP_WATCH_MS)
                return@Runnable
            }
        }
        // Without the accessibility service the teacher closes the Crop; the cut-out comes now anyway.
        panelCrop.stop()
        finishPanelCrop(read)
    }

    private val panelCropTimeout = Runnable {
        panelCrop.stop()
        finishPanelCrop(emptyList())
    }

    /** The panel's Crop closed by itself (the teacher pressed Exit): what it saved goes to the whiteboard. */
    private fun endPanelCrop(since: Long, attempt: Int = 0) {
        if (canvas == null || !panelCropping) return
        panelCropRead?.let {
            finishPanelCrop(it)
            return
        }
        // Still being read, or saved a moment ago: look again shortly.
        if (attempt < PANEL_CROP_RETRIES) {
            handler.removeCallbacks(watchPanelCrop)
            watchPanelCrop.run()
            handler.postDelayed({ endPanelCrop(since, attempt + 1) }, PANEL_CROP_RETRY_MS)
        } else {
            finishPanelCrop(emptyList())
        }
    }

    private fun finishPanelCrop(read: List<Pair<android.net.Uri, android.graphics.Bitmap>>) {
        if (!panelCropping) return
        handler.removeCallbacks(watchPanelCrop)
        handler.removeCallbacks(panelCropTimeout)
        panelCropping = false
        panelCropRead = null
        cropping = false
        showControls(true)
        applyCanvasWindow()
        if (read.isEmpty()) {
            // Cancelled: back to where the teacher was.
            if (cropFromWhiteboard && !whiteboard) toggleWhiteboard()
            return
        }
        if (!whiteboard) toggleWhiteboard()
        read.forEach { (_, image) -> placePicture(image) }
        // The whiteboard keeps its own copy: the panel's file isn't needed any more.
        deletePanelCrops(read.map { it.first })
    }

    // Asked once per run of the layer.
    private var askedForFiles = false

    /**
     * Deletes the panel's cut-out files once they are on the whiteboard. They belong to the
     * panel's Crop: Android lets Marcar Pro delete them only with "All files access", which the
     * teacher turns on once (a card offers it); without it the files stay.
     */
    private fun deletePanelCrops(uris: List<android.net.Uri>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !android.os.Environment.isExternalStorageManager()) {
            if (!askedForFiles) {
                askedForFiles = true
                showPrompt(getString(R.string.files_title), getString(R.string.files_why), getString(R.string.relay_open)) {
                    runCatching {
                        startActivity(
                            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, android.net.Uri.parse("package:$packageName"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
            }
            return
        }
        io.execute { for (uri in uris) runCatching { contentResolver.delete(uri, null, null) } }
    }

    private fun shoot(relay: TouchRelayService, wasWhiteboard: Boolean) {
        relay.screenshot { shot ->
            if (canvas == null) return@screenshot // the layer closed meanwhile
            showControls(true)
            if (shot == null || isBlank(shot)) {
                cropping = false
                if (panelCrop.available) {
                    // A video input Android can't see (the panel didn't say it was on the PC): the
                    // panel's own Crop can, so it does the cutting.
                    if (cropWasPaused && !paused) togglePause()
                    startPanelCrop(fromWhiteboard = wasWhiteboard)
                    return@screenshot
                }
                if (wasWhiteboard) toggleWhiteboard()
                toast(R.string.crop_failed, long = true)
            } else {
                showCrop(shot)
            }
        }
    }

    private fun showControls(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.INVISIBLE
        for (f in listOf(orbFloater, ringFloater, colorFloater, trashFloater, whiteFloater)) f?.view?.visibility = v
    }

    /**
     * A screen the capture can't see (a video input) comes back as one flat colour: black on
     * some panels, grey on others, maybe with our own ink on top. Blank = almost every sample of
     * a grid is the same colour.
     */
    private fun isBlank(shot: android.graphics.Bitmap): Boolean {
        val n = BLANK_GRID
        val samples = IntArray(n * n) { k -> shot[shot.width * (k % n + 1) / (n + 1), shot.height * (k / n + 1) / (n + 1)] }
        val first = samples.asList().groupingBy { it and 0xF0F0F0 }.eachCount().maxByOrNull { it.value }?.key ?: return true
        val same = samples.count { c ->
            val d = c and 0xFFFFFF
            abs(android.graphics.Color.red(d) - android.graphics.Color.red(first)) <= BLANK_TOLERANCE &&
                abs(android.graphics.Color.green(d) - android.graphics.Color.green(first)) <= BLANK_TOLERANCE &&
                abs(android.graphics.Color.blue(d) - android.graphics.Color.blue(first)) <= BLANK_TOLERANCE
        }
        return same >= samples.size * BLANK_SHARE
    }

    private fun showCrop(shot: android.graphics.Bitmap) {
        val view = CropView(this, shot) { r -> endCrop(shot, r) }
        cropView = view
        val params = WLP(
            WLP.MATCH_PARENT, WLP.MATCH_PARENT, WLP.TYPE_APPLICATION_OVERLAY, CANVAS_FLAGS, PixelFormat.OPAQUE,
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WLP.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            title = "Marcar Pro cut out"
        }
        wm.addView(view, params)
        // On the PC the box is drawn on our window, not on the PC.
        if (external) panel?.setArea(PanelTouch.AREA_INK, screenRect)
    }

    private fun endCrop(shot: android.graphics.Bitmap, box: Rect?) {
        cropView?.let { runCatching { wm.removeView(it) } }
        cropView = null
        cropping = false
        applyReservation()
        if (box == null || box.isEmpty) {
            // Cancelled: back to where the teacher was.
            if (cropFromWhiteboard && !whiteboard) toggleWhiteboard()
            if (cropWasPaused && !paused) togglePause()
            return
        }
        val piece = android.graphics.Bitmap.createBitmap(shot, box.left, box.top, box.width(), box.height())
        if (!whiteboard) toggleWhiteboard()
        placePicture(piece)
    }

    /** Onto the whiteboard, once its saved ink is back (the cutouts already there count). */
    /** Puts [piece] on the whiteboard: under what is there, or centred on [at] (board coordinates). */
    private fun placePicture(piece: android.graphics.Bitmap, at: android.graphics.PointF? = null) {
        if (PageKeys.WHITEBOARD in loading) {
            handler.postDelayed({ placePicture(piece, at) }, CROP_LOADING_RETRY_MS)
            return
        }
        val ink = canvas ?: return
        val stroke = (if (at == null) ink.addPicture(piece, null) else ink.addPictureAt(piece, null, at.x, at.y)) ?: return
        // The PNG for saving is made off the main thread (a save before it's ready makes it then).
        io.execute {
            val png = InkStore.png(piece)
            handler.post { if (stroke.imageData == null) stroke.imageData = png }
        }
    }

    // endregion

    // region Paste from Android's clipboard

    private var pasteWait: Runnable? = null

    /**
     * A long press on an empty spot of the whiteboard. Android only lets the focused window read
     * the clipboard, and the ink window never takes the focus (it would steal the keyboard from
     * the app below): it takes it for a moment, reads, and gives it back.
     */
    private fun pasteFromClipboard(x: Float, y: Float, copiedAt: Long) {
        val ink = canvas ?: return
        pasteWait?.let(handler::removeCallbacks)
        val read = object : Runnable {
            override fun run() {
                if (pasteWait !== this) return
                handler.removeCallbacks(this)
                pasteWait = null
                ink.onFocus = null
                readClipboard(x, y, copiedAt)
                applyCanvasWindow()
            }
        }
        pasteWait = read
        if (ink.hasWindowFocus()) {
            read.run()
            return
        }
        ink.onFocus = { has -> if (has) read.run() }
        // Should the focus never come (another window keeps it), read anyway: the clip just comes back empty.
        handler.postDelayed(read, PASTE_FOCUS_MS)
        canvasParams.flags = canvasParams.flags and WLP.FLAG_NOT_FOCUSABLE.inv()
        wm.updateViewLayout(ink, canvasParams)
    }

    /** An image copied in Android (newer than our own Copy) goes on the board; else our own Copy. */
    private fun readClipboard(x: Float, y: Float, copiedAt: Long) {
        val ink = canvas ?: return
        val clip = runCatching { getSystemService(android.content.ClipboardManager::class.java)?.primaryClip }.getOrNull()
        val uri = clip?.takeIf { it.description.timestamp > copiedAt }?.let { c ->
            (0 until c.itemCount).firstNotNullOfOrNull { i ->
                c.getItemAt(i).uri?.takeIf { u ->
                    c.description.hasMimeType("image/*") ||
                        runCatching { contentResolver.getType(u) }.getOrNull()?.startsWith("image/") == true
                }
            }
        }
        if (uri == null) {
            if (copiedAt > 0) ink.pasteCopied(x, y) else toast(R.string.paste_nothing, long = true)
            return
        }
        io.execute {
            val image = runCatching { decodeImage(uri) }.getOrNull()
            handler.post {
                if (canvas == null) return@post
                if (image == null) toast(R.string.paste_failed) else placePicture(image, android.graphics.PointF(x, y))
            }
        }
    }

    /** Reads an image, scaled down by halves until its longer side is at most [PASTE_MAX_PX]. */
    private fun decodeImage(uri: android.net.Uri): android.graphics.Bitmap? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > PASTE_MAX_PX) sample *= 2
        val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        return contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, options) }
    }

    // endregion

    /** Finger moves the ink ↔ finger writes too (several pupils at once). */
    private fun togglePupils() {
        val next = if (prefs.fingerMode == FingerMode.APPS) FingerMode.WRITE else FingerMode.APPS
        prefs.fingerMode = next
        canvas?.fingersWrite = next == FingerMode.WRITE
        refresh()
        toast(if (next == FingerMode.WRITE) R.string.mode_write else R.string.mode_select)
        if (next == FingerMode.APPS && !external) remindRelay()
    }

    /**
     * Writing ↔ using the screen (the apps or the PC), the same on every source: while in use the
     * ink window takes no touch at all, so the finger scrolls natively and nothing lags.
     */
    private fun togglePause() {
        // Using the screen means seeing it: the whiteboard closes first.
        if (!paused && whiteboard) toggleWhiteboard()
        paused = !paused
        if (paused) {
            closeRing()
            closeColors()
        }
        applyCanvasWindow()
        applyReservation()
        refresh()
        toast(modeLabel())
    }

    private fun modeLabel() = when {
        paused && external -> R.string.mode_use_pc
        paused -> R.string.mode_use_apps
        prefs.fingerMode == FingerMode.WRITE -> R.string.mode_write
        external -> R.string.mode_write_pc
        else -> R.string.mode_write_apps
    }

    private fun applyCanvasWindow() {
        val ink = canvas ?: return
        ink.annotating = !paused
        ink.fingersWrite = prefs.fingerMode == FingerMode.WRITE
        ink.passThrough = !external
        val touchable = !paused && !relaying && !panelCropping
        val flags = if (touchable) CANVAS_FLAGS else CANVAS_FLAGS or WLP.FLAG_NOT_TOUCHABLE
        // Android 12+ only lets touches pass through another app's overlay if it is ≤ 80% opaque.
        val alpha = if (paused) PASS_THROUGH_ALPHA else 1f
        if (flags == canvasParams.flags && alpha == canvasParams.alpha) return
        canvasParams.flags = flags
        canvasParams.alpha = alpha
        wm.updateViewLayout(ink, canvasParams)
    }

    /**
     * On the PC the board must already be reserved when the pen lands, so it is reserved for as
     * long as we are writing; pausing ("using the PC") gives every touch back to the PC.
     */
    private fun applyReservation() {
        if (external && !paused) panel?.setArea(PanelTouch.AREA_INK, screenRect) else panel?.clearArea(PanelTouch.AREA_INK)
    }

    /**
     * Replays a finger tap on the app underneath: the ink window stops catching touches for a
     * moment, the relay service taps, and the window catches touches again.
     */
    private fun relayTap(x: Float, y: Float) {
        // On the PC there is no app under the ink to tap (that is what "using the PC" is for).
        if (external) return
        val relay = TouchRelayService.instance
        if (relay == null) {
            remindRelay()
            return
        }
        val ink = canvas ?: return
        val origin = IntArray(2).also { ink.getLocationOnScreen(it) }
        relaying = true
        applyCanvasWindow()
        // Never stay untouchable: if the gesture's callback is lost (the service died), give up.
        handler.postDelayed(endRelay, RELAY_TIMEOUT_MS)
        handler.postDelayed({
            relay.tap(x + origin[0], y + origin[1]) {
                handler.removeCallbacks(endRelay)
                handler.postDelayed(endRelay, RELAY_SETTLE_MS)
            }
        }, RELAY_SETTLE_MS)
    }

    /** Replays a finger drag (a scroll) on the app underneath, the same way as [relayTap]. */
    private fun relayDrag(xs: FloatArray, ys: FloatArray, durationMs: Long) {
        if (external) return
        val relay = TouchRelayService.instance
        if (relay == null) {
            remindRelay()
            return
        }
        val ink = canvas ?: return
        val origin = IntArray(2).also { ink.getLocationOnScreen(it) }
        for (k in xs.indices) {
            xs[k] += origin[0]
            ys[k] += origin[1]
        }
        relaying = true
        applyCanvasWindow()
        handler.postDelayed(endRelay, RELAY_TIMEOUT_MS + durationMs)
        handler.postDelayed({
            relay.drag(xs, ys, durationMs) {
                handler.removeCallbacks(endRelay)
                handler.postDelayed(endRelay, RELAY_SETTLE_MS)
            }
        }, RELAY_SETTLE_MS)
    }

    private val endRelay = Runnable {
        relaying = false
        applyCanvasWindow()
    }

    /**
     * Explains that a feature needs Marcar Pro's accessibility service, with a button to the
     * switch. Settings must then be usable with the finger, so the layer goes to "using the screen".
     */
    private fun askForRelay(crop: Boolean) {
        val blocked = RelaySetup.blocked(this)
        val message = getString(if (crop) R.string.relay_why_crop else R.string.relay_why_apps) + "\n\n" +
            getString(if (blocked) R.string.relay_how_restricted else R.string.relay_how)
        showPrompt(getString(R.string.relay_title), message, getString(if (blocked) R.string.relay_open_app_info else R.string.relay_open)) {
            // Once it's on, writing comes back by itself.
            TouchRelayService.onConnected = {
                TouchRelayService.onConnected = null
                if (paused) togglePause()
                toast(R.string.relay_ready, long = true)
            }
            RelaySetup.open(this, blocked)
        }
    }

    // Asked once per run of the layer: at the next start (the panel switched on) it asks again.
    private var askedForUpdates = false

    /** An update is waiting but the panel doesn't let Marcar Pro install it: a card asks, once. */
    private fun askToAllowUpdates() {
        if (askedForUpdates || canvas == null) return
        askedForUpdates = true
        showPrompt(getString(R.string.update_ready), getString(R.string.update_allow), getString(R.string.relay_open)) {
            runCatching { startActivity(updater.permissionScreen()) }
        }
    }

    /**
     * A card with a message and two buttons, in the layer's own window. "Yes" first puts the layer
     * on "using the screen", as what it opens (Settings) must be usable with the finger.
     */
    private fun showPrompt(title: String, message: String, yes: String, onYes: () -> Unit) {
        if (prompt != null) return
        val view = PromptView(this, title, message, yes = yes, no = getString(R.string.relay_later)) { accepted ->
            closePrompt()
            if (accepted) {
                if (!paused) togglePause()
                onYes()
            }
        }
        prompt = view
        val params = WLP(WLP.MATCH_PARENT, WLP.MATCH_PARENT, WLP.TYPE_APPLICATION_OVERLAY, CANVAS_FLAGS, PixelFormat.TRANSLUCENT)
        params.title = "Marcar Pro prompt"
        wm.addView(view, params)
        if (external) panel?.setArea(PanelTouch.AREA_INK, screenRect)
    }

    private fun closePrompt() {
        prompt?.let { runCatching { wm.removeView(it) } }
        prompt = null
        applyReservation()
    }

    private fun remindRelay() {
        if (TouchRelayService.instance != null || relayHintShown) return
        relayHintShown = true
        askForRelay(crop = false)
    }

    // endregion

    // region Setup

    private var setupView: SetupView? = null

    // While the teacher is in Settings for one item, the card waits and comes back once it's done.
    private var setupWaitFor: (() -> Boolean)? = null
    private var setupWaitUntil = 0L
    private val setupWait = object : Runnable {
        override fun run() {
            val check = setupWaitFor ?: return
            when {
                check() -> {
                    setupWaitFor = null
                    showSetup(evenIfReady = true)
                }
                SystemClock.uptimeMillis() < setupWaitUntil -> handler.postDelayed(this, SETUP_POLL_MS)
                else -> setupWaitFor = null
            }
        }
    }

    /** Everything the layer can use, with its state and the screen that turns it on. */
    private fun setupItems(): List<Setup.Item> {
        val list = ArrayList<Setup.Item>()
        val blocked = RelaySetup.blocked(this)
        list += Setup.Item(
            getString(R.string.setup_relay),
            getString(if (blocked) R.string.setup_relay_why_restricted else R.string.setup_relay_why),
            done = TouchRelayService.instance != null,
            optional = false,
        ) { openForSetup({ TouchRelayService.instance != null }) { RelaySetup.open(this, blocked) } }
        if (panelCrop.available) {
            list += Setup.Item(
                getString(R.string.setup_images),
                getString(R.string.setup_images_why),
                done = panelCrop.canRead,
                optional = false,
            ) { openForSetup({ panelCrop.canRead }) { askForImages() } }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                list += Setup.Item(
                    getString(R.string.setup_files),
                    getString(R.string.setup_files_why),
                    done = android.os.Environment.isExternalStorageManager(),
                    optional = true,
                ) {
                    openForSetup({ android.os.Environment.isExternalStorageManager() }) {
                        startActivity(
                            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, android.net.Uri.parse("package:$packageName"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
            }
        }
        list += Setup.Item(
            getString(R.string.setup_updates),
            getString(R.string.setup_updates_why),
            done = packageManager.canRequestPackageInstalls(),
            optional = false,
        ) { openForSetup({ packageManager.canRequestPackageInstalls() }) { startActivity(updater.permissionScreen()) } }
        return list
    }

    /**
     * Shows the setup card: when the app is opened from its icon with something still missing
     * (or, with [evenIfReady], to show the last tick after coming back from Settings).
     */
    private fun showSetup(evenIfReady: Boolean = false) {
        if (canvas == null || cropping) return
        if (!evenIfReady && setupItems().all { it.done || it.optional }) return
        setupView?.let {
            it.refresh()
            return
        }
        closePrompt()
        // Settings will open over the ink: the ink must let touches through meanwhile.
        if (!paused) togglePause()
        val view = SetupView(this, ::setupItems) { closeSetup() }
        setupView = view
        val params = WLP(WLP.MATCH_PARENT, WLP.MATCH_PARENT, WLP.TYPE_APPLICATION_OVERLAY, CANVAS_FLAGS, PixelFormat.TRANSLUCENT)
        params.title = "Marcar Pro setup"
        wm.addView(view, params)
        if (external) panel?.setArea(PanelTouch.AREA_INK, screenRect)
    }

    private fun refreshSetup() = setupView?.refresh()

    private fun closeSetup() {
        setupView?.let { runCatching { wm.removeView(it) } }
        setupView = null
        applyReservation()
    }

    /** The card steps aside for Settings ([open]) and comes back once [done] is true. */
    private fun openForSetup(done: () -> Boolean, open: () -> Unit) {
        closeSetup()
        runCatching(open)
        setupWaitFor = done
        setupWaitUntil = SystemClock.uptimeMillis() + SETUP_WAIT_MS
        handler.removeCallbacks(setupWait)
        handler.postDelayed(setupWait, SETUP_POLL_MS)
    }

    // endregion

    // region Actions

    private fun undo() {
        val ink = canvas ?: return
        toast(if (ink.undo()) R.string.undo_done else R.string.nothing_to_undo)
    }

    private fun clearAll() {
        val ink = canvas ?: return
        if (ink.clearAll()) toast(R.string.board_cleared) else toast(R.string.nothing_to_clear)
    }

    private fun applyInk() {
        val ink = canvas ?: return
        ink.inkColor = prefs.colorFor(ink.tool)
        ink.inkSize = dp(Presets.SIZES_DP[prefs.sizeIndex])
        ink.eraserSize = dp(Presets.ERASER_DP[prefs.eraserIndex])
        refresh()
    }

    /** Brings every floating control in line with the current state. */
    private fun refresh() {
        val ink = canvas ?: return
        orbit?.let { o ->
            val (icon, label) = when (ink.tool) {
                Tool.PEN -> R.drawable.ic_pen to R.string.tool_pen
                Tool.HIGHLIGHTER -> R.drawable.ic_highlighter to R.string.tool_highlighter
                Tool.ERASER -> R.drawable.ic_eraser to R.string.tool_eraser
                Tool.MAGIC -> R.drawable.ic_magic to R.string.tool_magic
                Tool.SHAPE -> R.drawable.ic_shape to R.string.tool_shape
            }
            orbButton?.apply {
                setIcon(icon, "${getString(label)} · ${getString(modeLabel())}")
                inkColor = if (ink.tool == Tool.MAGIC) Presets.MAGIC_COLOR else prefs.colorFor(ink.tool)
                sizeLevel = prefs.sizeIndex
                using = paused
            }
            o.setActive(ink.tool, ink.inkHidden, prefs.fingerMode == FingerMode.WRITE)
        }
    }

    private fun toast(res: Int, long: Boolean = false) = toast(getString(res), long)

    /** A short message in our own pill (Android's toasts are hidden when notifications are off). */
    private fun toast(message: String, long: Boolean = false) {
        val view = hintView ?: HintView(this).also { v ->
            hintView = v
            wm.addView(v, WLP(
                WLP.WRAP_CONTENT, WLP.WRAP_CONTENT, WLP.TYPE_APPLICATION_OVERLAY,
                WLP.FLAG_NOT_TOUCHABLE or WLP.FLAG_NOT_FOCUSABLE or WLP.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = dpi(72f)
                title = "Marcar Pro hint"
            })
        }
        view.show(message, long)
    }

    // endregion

    /**
     * A context tied to the screen, for the ink view: a service's own context has no display, and
     * what reads it (motion prediction) would fail on recent Android.
     */
    private fun windowContext(): android.content.Context =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val display = getSystemService(android.hardware.display.DisplayManager::class.java)
                .getDisplay(android.view.Display.DEFAULT_DISPLAY)
            createDisplayContext(display).createWindowContext(WLP.TYPE_APPLICATION_OVERLAY, null)
        } else {
            this
        }

    private fun Floater.attach(p: Point) = attach(p.x, p.y)

    /** Keeps touches on a floating control from also reaching the PC underneath. */
    private fun reserve(id: Int, f: Floater) {
        panel?.setArea(id, f.bounds())
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) },
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_marcar)
            .setColor(OrbStyle.TEAL)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .addAction(R.drawable.ic_close, getString(R.string.action_stop), stop)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }
}
