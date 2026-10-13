package com.maestroseb.marcarpro

import org.junit.Assert.assertEquals
import org.junit.Test

class WordGrouperTest {

    private val grouper = WordGrouper(minUnit = 8f)

    private fun stroke(left: Int, top: Int, right: Int, bottom: Int, start: Long, end: Long) =
        WordGrouper.Box(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), start, end)

    /** Feeds strokes in writing order and returns the object index each one ended up in. */
    private fun segment(vararg boxes: WordGrouper.Box): List<Int> {
        val placed = ArrayList<WordGrouper.Placed>()
        var next = 0
        for (box in boxes) {
            val group = grouper.groupFor(box, placed) ?: next++
            placed.add(WordGrouper.Placed(box, group))
        }
        return placed.map { it.group }
    }

    @Test
    fun `natural cursive sentence from the board photo splits into its four words`() {
        // "Hola me llama Sebastián", measured on the photo of the real panel (pixels, ms).
        val groups = segment(
            stroke(190, 545, 218, 860, 0, 400),        // H stem
            stroke(200, 640, 432, 820, 550, 1500),     // rest of H + "ola"
            stroke(555, 700, 682, 775, 1900, 2500),    // "me"
            stroke(790, 570, 1125, 775, 2900, 4200),   // "llama"
            stroke(1230, 550, 1365, 770, 4600, 5100),  // "S"
            stroke(1290, 650, 1560, 770, 5250, 6000),  // "ebas"
            stroke(1540, 590, 1740, 770, 6150, 6900),  // "tián"
            stroke(1480, 660, 1600, 690, 7050, 7250),  // t-cross
            stroke(1660, 640, 1700, 690, 7400, 7500),  // accent
        )
        assertEquals(listOf(0, 0, 1, 2, 3, 3, 3, 3, 3), groups)
    }

    @Test
    fun `print handwriting keeps letters of a word together and splits words`() {
        val groups = segment(
            stroke(0, 40, 45, 90, 0, 250),        // m
            stroke(55, 45, 90, 90, 450, 650),     // e
            stroke(150, 0, 160, 90, 850, 1000),   // l
            stroke(172, 0, 182, 90, 1200, 1350),  // l
            stroke(192, 45, 230, 90, 1550, 1750), // a
            stroke(242, 45, 290, 90, 1950, 2200), // m
            stroke(302, 45, 340, 90, 2400, 2600), // o
        )
        assertEquals(listOf(0, 0, 1, 1, 1, 1, 1), groups)
    }

    @Test
    fun `a long pause starts a new object even when strokes touch`() {
        val groups = segment(
            stroke(0, 0, 100, 80, 0, 500),
            stroke(90, 0, 200, 80, 2500, 3000),
        )
        assertEquals(listOf(0, 1), groups)
    }

    @Test
    fun `writing on the next line never joins the line above`() {
        val groups = segment(
            stroke(0, 0, 300, 80, 0, 900),
            stroke(0, 160, 120, 240, 1100, 1600),
        )
        assertEquals(listOf(0, 1), groups)
    }

    @Test
    fun `an i-dot added at the end of the sentence joins its own word`() {
        val groups = segment(
            stroke(0, 40, 30, 80, 0, 300),         // s
            stroke(40, 40, 45, 80, 450, 550),      // i stem
            stroke(110, 40, 170, 80, 900, 1400),   // "no"
            stroke(40, 20, 46, 26, 4000, 4050),    // the i's dot, seconds later
        )
        assertEquals(listOf(0, 0, 1, 0), groups)
    }
}
