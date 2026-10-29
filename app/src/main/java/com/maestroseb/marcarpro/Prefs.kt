package com.maestroseb.marcarpro

import android.content.Context
import android.graphics.Point
import androidx.core.content.edit

/** Remembers the floating controls and ink setup between sessions. */
internal class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("marcar_pro", Context.MODE_PRIVATE)

    var tool: Tool
        get() = Tool.entries.firstOrNull { it.name == sp.getString("tool", null) } ?: Tool.PEN
        set(value) = sp.edit { putString("tool", value.name) }

    var fingerMode: FingerMode
        get() = FingerMode.entries.firstOrNull { it.name == sp.getString("finger_mode", null) } ?: FingerMode.APPS
        set(value) = sp.edit { putString("finger_mode", value.name) }

    /** The pen's colour (a preset or a custom one); see [colorFor]. */
    private var inkColor: Int
        get() {
            if (sp.contains("ink_color")) return sp.getInt("ink_color", DEFAULT_COLOR)
            // Saved by an older version as a position in its palette: carry it over once.
            val old = sp.getInt("color", -1)
            val color = if (old == OLD_AMBER) Presets.COLORS[NEW_YELLOW] else Presets.OLD_COLORS.getOrNull(old) ?: DEFAULT_COLOR
            sp.edit {
                putInt("ink_color", color)
                remove("color")
            }
            return color
        }
        set(value) = sp.edit { putInt("ink_color", value) }

    /** Each ink tool keeps its own colour: the highlighter its own, the rest the pen's. */
    fun colorFor(tool: Tool): Int =
        if (tool == Tool.HIGHLIGHTER) sp.getInt("highlighter_color", Presets.HIGHLIGHTER_COLOR) else inkColor

    fun setColorFor(tool: Tool, color: Int) {
        if (tool == Tool.HIGHLIGHTER) sp.edit { putInt("highlighter_color", color) } else inkColor = color
    }

    /** The three fixed colours next to the orb (blue, red and black until the teacher changes one). */
    val quickColors: List<Int>
        get() {
            val saved = sp.getString("quick_colors", null)?.split(',')?.mapNotNull { it.toIntOrNull() }.orEmpty()
            return if (saved.size == QUICK_COUNT) saved else DEFAULT_QUICK
        }

    fun setQuickColor(slot: Int, color: Int) {
        val list = quickColors.toMutableList()
        if (slot !in list.indices) return
        list[slot] = color
        sp.edit { putString("quick_colors", list.joinToString(",")) }
    }

    /** The ring's tools in the teacher's order ([RingItem.key]s); empty until reordered. */
    var ringOrder: List<String>
        get() = sp.getString("ring_order", null).orEmpty().split(',').filter { it.isNotEmpty() }
        set(value) = sp.edit { putString("ring_order", value.joinToString(",")) }

    /** Ring tools the teacher took out of the ring. */
    var ringHidden: Set<String>
        get() = sp.getString("ring_hidden", null).orEmpty().split(',').filter { it.isNotEmpty() }.toSet()
        set(value) = sp.edit { putString("ring_hidden", value.joinToString(",")) }

    /** Colours made with the picker, newest first. */
    val customColors: List<Int>
        get() = sp.getString("custom_colors", null).orEmpty().split(',').mapNotNull { it.toIntOrNull() }

    fun addCustomColor(color: Int) {
        val list = (listOf(color) + customColors.filter { it != color }).take(Presets.MAX_CUSTOM_COLORS)
        sp.edit { putString("custom_colors", list.joinToString(",")) }
    }

    /** The whiteboard's background (dots at first). */
    var paper: Paper
        get() = Paper.entries.firstOrNull { it.name == sp.getString("paper", null) } ?: Paper.DOTS
        set(value) = sp.edit { putString("paper", value.name) }

    /** The whiteboard's paper colour (white at first). */
    var paperColor: PaperColor
        get() = PaperColor.entries.firstOrNull { it.name == sp.getString("paper_color", null) } ?: PaperColor.WHITE
        set(value) = sp.edit { putString("paper_color", value.name) }

    /** The quick board's pages, and the one shown (see [Boards]). */
    var quickPages: Int
        get() = sp.getInt("quick_pages", 1).coerceIn(1, Boards.MAX_PAGES)
        set(value) = sp.edit { putInt("quick_pages", value) }
    var quickPage: Int
        get() = sp.getInt("quick_page", 0)
        set(value) = sp.edit { putInt("quick_page", value) }

    /** The day the quick board was last written on (0: not known yet). */
    var quickDay: Int
        get() = sp.getInt("quick_day", 0)
        set(value) = sp.edit { putInt("quick_day", value) }

    /** The saved board on the whiteboard, or null for the quick board. */
    var currentBoard: String?
        get() = sp.getString("current_board", null)
        set(value) = sp.edit { putString("current_board", value) }

    var sizeIndex: Int
        get() = sp.getInt("size", 1).coerceIn(0, Presets.SIZES_DP.lastIndex)
        set(value) = sp.edit { putInt("size", value) }

    var eraserIndex: Int
        get() = sp.getInt("eraser", 1).coerceIn(0, Presets.ERASER_DP.lastIndex)
        set(value) = sp.edit { putInt("eraser", value) }

    /** Remembered once a stylus has been used, for boards that don't declare one. */
    var stylusSeen: Boolean
        get() = sp.getBoolean("stylus_seen", false)
        set(value) = sp.edit { putBoolean("stylus_seen", value) }

    var askedNotifications: Boolean
        get() = sp.getBoolean("asked_notifications", false)
        set(value) = sp.edit { putBoolean("asked_notifications", value) }

    /** Centre of a floating control, in screen pixels; [fallback] until it has been moved. */
    fun position(key: String, fallback: Point): Point =
        if (sp.contains("${key}_x")) Point(sp.getInt("${key}_x", 0), sp.getInt("${key}_y", 0)) else fallback

    fun savePosition(key: String, x: Int, y: Int) = sp.edit {
        putInt("${key}_x", x)
        putInt("${key}_y", y)
    }

    companion object {
        const val QUICK_COUNT = 3
        private val DEFAULT_QUICK = listOf(Presets.COLORS[6], Presets.COLORS[2], Presets.COLORS[0]) // blue, red, black

        const val DEFAULT_COLOR = 0xFFC0392B.toInt() // red: stands out on most lesson content
        const val OLD_AMBER = 4 // the old amber became the new yellow
        const val NEW_YELLOW = 4
    }
}
