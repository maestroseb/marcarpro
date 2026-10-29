package com.maestroseb.marcarpro

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** The rules that keep the list of boards tidy: a month to the bin, a week there, pins stay. */
class BoardsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs = Prefs(context)
    private lateinit var dir: File
    private var savedCurrent: String? = null
    private var savedDay = 0

    @Before
    fun setUp() {
        dir = File(context.cacheDir, "boards-test").apply { deleteRecursively(); mkdirs() }
        savedCurrent = prefs.currentBoard
        savedDay = prefs.quickDay
        prefs.currentBoard = null
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
        prefs.currentBoard = savedCurrent
        prefs.quickDay = savedDay
    }

    @Test
    fun unopenedBoardsGoToTheBinAndThenAway() {
        val day = 24L * 60 * 60 * 1000
        val now = System.currentTimeMillis()
        val b = Boards(dir, prefs)
        val old = b.create("Old", Paper.DOTS, PaperColor.WHITE, pages = 2).apply { opened = now - 31 * day }
        val pinned = b.create("Pinned", Paper.DOTS, PaperColor.WHITE).apply { opened = now - 90 * day }
        b.setPinned(pinned, true)
        val fresh = b.create("Fresh", Paper.DOTS, PaperColor.WHITE)

        assertTrue(b.tidy(now).isEmpty())
        assertTrue(old.trashed)
        assertFalse(pinned.trashed)
        assertFalse(fresh.trashed)
        assertEquals(listOf(pinned, fresh), b.active)

        // A week in the bin: gone, and its two pages' ink with it.
        val gone = b.tidy(now + 8 * day)
        assertEquals(listOf(Boards.pageKey(old.id, 0), Boards.pageKey(old.id, 1)), gone)
        assertNull(b.find(old.id))

        // Kept on disk.
        val again = Boards(dir, prefs)
        assertEquals(listOf("Pinned", "Fresh"), again.active.map { it.name })
    }

    @Test
    fun restoredBoardsStartTheirMonthAgain() {
        val b = Boards(dir, prefs)
        val board = b.create("A", Paper.GRID, PaperColor.CREAM)
        b.throwAway(board)
        assertEquals(listOf(board), b.bin)
        b.restore(board)
        assertTrue(b.bin.isEmpty())
        assertTrue(System.currentTimeMillis() - board.opened < 5_000)
    }

    @Test
    fun theQuickBoardIsNotThrownAwayTheDayBoardsArrive() {
        prefs.quickDay = 0
        val b = Boards(dir, prefs)
        assertFalse(b.quickExpired)
        prefs.quickDay = 2020001
        assertTrue(b.quickExpired)
    }

    @Test
    fun theOpenBoardIsRemembered() {
        val b = Boards(dir, prefs)
        val board = b.create("A", Paper.LINES, PaperColor.WHITE)
        b.open(board)
        b.addPage()
        b.goTo(1)
        val again = Boards(dir, prefs)
        assertEquals(board.id, again.current?.id)
        assertEquals(Boards.pageKey(board.id, 1), again.key)
    }
}
