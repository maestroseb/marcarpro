package com.maestroseb.marcarpro

import androidx.core.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Calendar

/**
 * The whiteboards a teacher keeps. The quick board is the one the whiteboard opens on, as ever:
 * it empties itself the next day, so whoever never saves anything leaves nothing behind. A board
 * saved with a name stays, with its own pages, paper and colour, until nobody opens it for
 * [EXPIRE_DAYS] days: then it goes to the bin, where it can be brought back for [BIN_DAYS] more
 * days before it is deleted. A pinned board never expires.
 *
 * Only the list lives here (a small JSON file); each page's ink is its own file, see [pageKey].
 */
internal class Boards(private val dir: File, private val prefs: Prefs) {

    class Saved(
        val id: String,
        var name: String,
        var pinned: Boolean = false,
        var opened: Long = System.currentTimeMillis(),
        var pages: Int = 1,
        var page: Int = 0,
        var trashedAt: Long = 0,
        var paper: Paper = Paper.DOTS,
        var color: PaperColor = PaperColor.WHITE,
    ) {
        val trashed get() = trashedAt > 0
    }

    private val file = AtomicFile(File(dir, "boards.json"))
    private val saved = ArrayList<Saved>()

    /** The board on the whiteboard now: null is the quick board. */
    var current: Saved? = null
        private set

    init {
        load()
    }

    /** Boards in use: pinned first, then the most recently opened. */
    val active: List<Saved> get() = saved.filter { !it.trashed }.sortedWith(compareBy<Saved>({ !it.pinned }, { -it.opened }))

    /** Boards in the bin, the most recently thrown away first. */
    val bin: List<Saved> get() = saved.filter { it.trashed }.sortedByDescending { it.trashedAt }

    fun find(id: String) = saved.firstOrNull { it.id == id }

    /** Every board's id, in the bin too (a copy: safe to read on another thread). */
    val ids: Set<String> get() = saved.mapTo(HashSet()) { it.id }

    // The quick board's pages (it has no entry in the list).
    var quickPages: Int
        get() = prefs.quickPages
        private set(value) { prefs.quickPages = value }
    var quickPage: Int
        get() = prefs.quickPage.coerceIn(0, quickPages - 1)
        private set(value) { prefs.quickPage = value }

    val pages get() = current?.pages ?: quickPages
    val page get() = current?.page?.coerceIn(0, pages - 1) ?: quickPage

    /** The page shown now, as a page key. */
    val key get() = pageKey(current?.id, page)

    /** Changes the page shown on the current board (no checks: see the callers). */
    fun goTo(page: Int) {
        val c = current
        if (c == null) quickPage = page else c.page = page
        save()
    }

    /** Adds a page at the end of the current board. */
    fun addPage() {
        val c = current
        if (c == null) quickPages += 1 else c.pages += 1
        save()
    }

    /** Takes the last page off the current board (it was left empty). */
    fun dropLastPage() {
        val c = current
        if (pages <= 1) return
        if (c == null) {
            quickPages -= 1
            quickPage = quickPage.coerceAtMost(quickPages - 1)
        } else {
            c.pages -= 1
            c.page = c.page.coerceAtMost(c.pages - 1)
        }
        save()
    }

    /** Opens a board (null: the quick one); opening it again restarts its time before the bin. */
    fun open(board: Saved?) {
        current = board
        board?.opened = System.currentTimeMillis()
        prefs.currentBoard = board?.id
        save()
    }

    /** A new board, named after now, with the paper given. Not opened yet. */
    fun create(name: String, paper: Paper, color: PaperColor, pages: Int = 1, page: Int = 0, trashed: Boolean = false): Saved {
        val now = System.currentTimeMillis()
        val b = Saved(newId(), name, pages = pages, page = page, paper = paper, color = color, trashedAt = if (trashed) now else 0)
        saved += b
        save()
        return b
    }

    /** The quick board starts again empty, with one page. */
    fun resetQuick() {
        quickPages = 1
        quickPage = 0
        prefs.quickDay = today()
    }

    fun setPinned(b: Saved, pinned: Boolean) {
        b.pinned = pinned
        save()
    }

    fun throwAway(b: Saved) {
        b.trashedAt = System.currentTimeMillis()
        b.pinned = false
        if (current === b) current = null.also { prefs.currentBoard = null }
        save()
    }

    fun restore(b: Saved) {
        b.trashedAt = 0
        b.opened = System.currentTimeMillis()
        save()
    }

    fun setPaper(paper: Paper, color: PaperColor) {
        val c = current ?: return
        c.paper = paper
        c.color = color
        save()
    }

    /**
     * The tidying of each start: boards nobody opened for a month go to the bin, the ones in the
     * bin for a week are deleted. Returns the page keys whose ink files must go. The quick board
     * is emptied when its day is over ([quickExpired]).
     */
    fun tidy(now: Long = System.currentTimeMillis()): List<String> {
        val gone = ArrayList<String>()
        var dirty = false
        for (b in saved.toList()) {
            if (!b.trashed && !b.pinned && now - b.opened > EXPIRE_DAYS * DAY_MS && current !== b) {
                b.trashedAt = now
                dirty = true
            } else if (b.trashed && now - b.trashedAt > BIN_DAYS * DAY_MS) {
                saved -= b
                for (p in 0 until b.pages) gone += pageKey(b.id, p)
                dirty = true
            }
        }
        if (dirty) save()
        return gone
    }

    /**
     * Whether the quick board's ink belongs to another day (it empties itself the next day). The
     * first time (just updated to boards) its day is today: nothing written before goes at once.
     */
    val quickExpired: Boolean
        get() {
            if (prefs.quickDay == 0) prefs.quickDay = today()
            return prefs.quickDay != today()
        }

    /** Today, to tell when the quick board's day is over. */
    fun markQuickUsed() {
        prefs.quickDay = today()
    }

    private fun today(): Int = Calendar.getInstance().let { it.get(Calendar.YEAR) * 1000 + it.get(Calendar.DAY_OF_YEAR) }

    private fun newId(): String {
        var id: String
        do {
            id = java.lang.Long.toString(System.currentTimeMillis(), 36) + (('a'..'z').random())
        } while (find(id) != null)
        return id
    }

    private fun load() {
        val data = try {
            String(file.readFully())
        } catch (_: IOException) {
            null
        }
        if (data != null) runCatching {
            val list = JSONArray(data)
            for (i in 0 until list.length()) {
                val o = list.getJSONObject(i)
                val id = o.getString("id")
                if (!SAFE_ID.matches(id)) continue
                saved += Saved(
                    id = id,
                    name = o.optString("name"),
                    pinned = o.optBoolean("pinned"),
                    opened = o.optLong("opened"),
                    pages = o.optInt("pages", 1).coerceIn(1, MAX_PAGES),
                    page = o.optInt("page"),
                    trashedAt = o.optLong("trashed"),
                    paper = Paper.entries.firstOrNull { it.name == o.optString("paper") } ?: Paper.DOTS,
                    color = PaperColor.entries.firstOrNull { it.name == o.optString("color") } ?: PaperColor.WHITE,
                )
            }
        }
        current = prefs.currentBoard?.let { id -> find(id)?.takeIf { !it.trashed } }
    }

    private fun save() {
        val list = JSONArray()
        for (b in saved) list.put(
            JSONObject()
                .put("id", b.id).put("name", b.name).put("pinned", b.pinned).put("opened", b.opened)
                .put("pages", b.pages).put("page", b.page).put("trashed", b.trashedAt)
                .put("paper", b.paper.name).put("color", b.color.name),
        )
        var out: java.io.FileOutputStream? = null
        try {
            out = file.startWrite()
            out.write(list.toString().toByteArray())
            file.finishWrite(out)
        } catch (_: IOException) {
            if (out != null) file.failWrite(out)
        }
    }

    companion object {
        const val EXPIRE_DAYS = 30L
        const val BIN_DAYS = 7L
        const val MAX_PAGES = 99
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private val SAFE_ID = Regex("[a-z0-9]{1,24}")

        /**
         * A whiteboard page's key. The quick board's first page keeps the whiteboard's old key,
         * so the ink written before boards existed is still there.
         */
        fun pageKey(board: String?, page: Int): String =
            if (board == null && page == 0) PageKeys.WHITEBOARD else "${PageKeys.WHITEBOARD}${board ?: QUICK}:$page"

        const val QUICK = "quick"

        /** "Jue 29 oct · 10:15": the name of a board nobody named. */
        fun nameFor(time: Long, locale: java.util.Locale = java.util.Locale.getDefault()): String =
            java.text.SimpleDateFormat("EEE d MMM · HH:mm", locale).format(java.util.Date(time))
                .replace(".", "")
                .replaceFirstChar { it.titlecase(locale) }
    }
}
