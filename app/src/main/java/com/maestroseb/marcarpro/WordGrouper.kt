package com.maestroseb.marcarpro

import kotlin.math.max
import kotlin.math.min

/**
 * Decides which handwritten strokes form one object (roughly, a word).
 *
 * Everything is measured against the writer's own scale — the median height of their recent
 * strokes — so it works for any handwriting size, cursive or print:
 *  - a stroke continues the previous word when it starts soon after it (≤ [gapMs]), sits on
 *    the same line and leaves a horizontal gap of at most [wordGap] × that scale. Inside a
 *    word strokes overlap (cursive) or leave small gaps (print); between words the gap is wide.
 *  - small marks (i-dots, accents, t-crosses) join the word they sit on, even when they are
 *    added a few seconds later, which is how most people finish a sentence.
 *
 * Pure Kotlin, no Android types, so it can be unit-tested with real handwriting.
 */
internal class WordGrouper(
    private val minUnit: Float,
    private val gapMs: Long = 1000L,
    private val wordGap: Float = 0.4f,
    private val markWindowMs: Long = 10_000L,
) {
    class Box(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val start: Long,
        val end: Long,
    ) {
        val width get() = right - left
        val height get() = bottom - top
        val centerX get() = (left + right) / 2f
    }

    /** A stroke already on the board. */
    class Placed(val box: Box, val group: Int)

    private class Span(var left: Float, var top: Float, var right: Float, var bottom: Float)

    /**
     * Returns the group [box] should join, or null when it starts a new object.
     * [placed] are the strokes already on the board, in the order they were written.
     */
    fun groupFor(box: Box, placed: List<Placed>): Int? {
        if (placed.isEmpty()) return null
        val unit = scale(box, placed)

        if (box.height <= MARK_HEIGHT * unit && box.width <= unit) {
            markOwner(box, placed, unit)?.let { return it }
        }

        val prev = placed.last()
        if (box.start - prev.box.end > gapMs) return null
        val word = span(placed.filter { it.group == prev.group })

        // Same line: the stroke must overlap the word's band (with room for ascenders/descenders).
        val slack = LINE_SLACK * unit
        val overlapY = min(box.bottom, word.bottom + slack) - max(box.top, word.top - slack)
        if (overlapY <= 0f) return null

        val gapX = max(0f, max(box.left - word.right, word.left - box.right))
        return if (gapX <= wordGap * unit) prev.group else null
    }

    /** The word a small mark belongs to: the most recent one it sits on or above. */
    private fun markOwner(box: Box, placed: List<Placed>, unit: Float): Int? {
        if (box.start - placed.last().box.end > markWindowMs) return null
        val recentGroups = placed.asReversed().map { it.group }.distinct().take(RECENT_WORDS)
        for (group in recentGroups) {
            val word = span(placed.filter { it.group == group })
            val reach = MARK_REACH * unit
            val horizontally = box.centerX >= word.left - reach && box.centerX <= word.right + reach
            val vertically = box.bottom >= word.top - unit && box.top <= word.bottom
            if (horizontally && vertically) return group
        }
        return null
    }

    /** Median height of recent strokes, ignoring tiny marks so dots can't shrink the scale. */
    private fun scale(box: Box, placed: List<Placed>): Float {
        val heights = placed.takeLast(SCALE_WINDOW).map { it.box.height } + box.height
        val rough = median(heights)
        val bodies = heights.filter { it >= MARK_HEIGHT * rough }
        return max(minUnit, median(bodies.ifEmpty { heights }))
    }

    private fun span(items: List<Placed>): Span {
        val s = Span(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (p in items) {
            s.left = min(s.left, p.box.left)
            s.top = min(s.top, p.box.top)
            s.right = max(s.right, p.box.right)
            s.bottom = max(s.bottom, p.box.bottom)
        }
        return s
    }

    private fun median(values: List<Float>): Float {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
    }

    private companion object {
        const val MARK_HEIGHT = 0.35f // marks are at most this tall, relative to the scale
        const val MARK_REACH = 0.2f
        const val LINE_SLACK = 0.3f
        const val SCALE_WINDOW = 24
        const val RECENT_WORDS = 6
    }
}
