package com.maestroseb.marcarpro

/**
 * Undo for the ink layer. Changes are recorded after they have been applied to [strokes];
 * undo replays them backwards.
 */
internal class History(
    private val strokes: MutableList<Stroke>,
    private val onChanged: () -> Unit,
) {
    sealed interface Change
    class Added(val stroke: Stroke) : Change

    /**
     * Index-level removals and insertions, in the order they happened. Covers deleting,
     * clearing and partial erasing (a stroke removed and its surviving pieces inserted).
     */
    class Edited(val ops: List<Op>) : Change {
        val inserted get() = ops.filter { it.insert }.map { it.stroke }
    }

    class Op(val insert: Boolean, val index: Int, val stroke: Stroke)

    /** Selection scaled and/or rotated (and moved) by [matrix]; [scale] scales ink width. */
    class Transformed(val strokes: List<Stroke>, val matrix: android.graphics.Matrix, val scale: Float) : Change {
        val inverse = android.graphics.Matrix().also { matrix.invert(it) }
    }

    /** Selection recoloured; [before] holds each stroke's previous colour. */
    class Recolored(val strokes: List<Stroke>, val before: IntArray) : Change

    private val done = ArrayDeque<Change>()

    fun record(change: Change) {
        done.addLast(change)
        if (done.size > LIMIT) done.removeFirst()
        onChanged()
    }

    /** The change [undo] would revert next. */
    fun peek(): Change? = done.lastOrNull()

    fun undo(): Change? {
        val change = done.removeLastOrNull() ?: return null
        revert(change)
        onChanged()
        return change
    }

    private fun revert(change: Change) {
        when (change) {
            is Added -> strokes.remove(change.stroke)
            is Edited -> change.ops.asReversed().forEach { op ->
                if (op.insert) strokes.remove(op.stroke) else strokes.add(op.index.coerceAtMost(strokes.size), op.stroke)
            }
            is Transformed -> change.strokes.forEach { it.transform(change.inverse, 1f / change.scale) }
            is Recolored -> change.strokes.forEachIndexed { i, s -> s.color = change.before[i] }
        }
    }

    private companion object {
        const val LIMIT = 200
    }
}
