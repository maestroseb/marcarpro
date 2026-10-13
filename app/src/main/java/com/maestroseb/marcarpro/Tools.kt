package com.maestroseb.marcarpro

/** What the pen does. Fingers and fists follow their own rules (see [AnnotationView]). */
enum class Tool { PEN, HIGHLIGHTER, ERASER, MAGIC, SHAPE }

/** The whiteboard's background; DOUBLE is the school "doble pauta" (pairs of lines). */
enum class Paper { BLANK, DOTS, GRID, LINES, DOUBLE }

/** What a finger does on empty space: reach the apps underneath, or write (several pupils). */
enum class FingerMode { APPS, WRITE }

/** Ink presets shared by the overlay and the launcher screen. */
internal object Presets {
    /** In the order of the `ink_color_names` string array. */
    val COLORS = intArrayOf(
        0xFF1F2329.toInt(), // black
        0xFFFFFFFF.toInt(), // white
        0xFFC0392B.toInt(), // red
        0xFFE67E22.toInt(), // orange
        0xFFF4C20D.toInt(), // yellow
        0xFF3A9A52.toInt(), // green
        0xFF2F63D6.toInt(), // blue
        0xFF8E44AD.toInt(), // purple
    )

    /** The highlighter starts yellow (the pen keeps its own colour). */
    val HIGHLIGHTER_COLOR = COLORS[4]

    /** The palette before white and custom colours, when the colour was saved by position. */
    val OLD_COLORS = intArrayOf(
        0xFF1F2329.toInt(), 0xFF2F63D6.toInt(), 0xFFC0392B.toInt(),
        0xFF3A9A52.toInt(), 0xFFE8A317.toInt(), 0xFF8E44AD.toInt(),
    )

    /** How many custom colours are remembered (the newest first). */
    const val MAX_CUSTOM_COLORS = 6
    val SIZES_DP = floatArrayOf(3f, 5f, 9f)

    /** Fist/palm eraser radius; about 6, 11 and 19 cm wide on a 75" board. */
    val ERASER_DP = floatArrayOf(24f, 44f, 72f)

    /** Magic ink stays fully visible this long, then fades out over [MAGIC_FADE_MS]. */
    const val MAGIC_HOLD_MS = 2600L
    const val MAGIC_FADE_MS = 700L

    /** Magic ink is always this neon pink, with a glow, so it never passes for normal ink. */
    const val MAGIC_COLOR = 0xFFFF2BD6.toInt()
    const val MAGIC_GLOW = 0xFFFF7AE6.toInt()
}
