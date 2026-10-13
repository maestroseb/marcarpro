package com.maestroseb.marcarpro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageKeysTest {

    @Test
    fun sameAddressWrittenDifferentlyIsTheSamePage() {
        val a = PageKeys.fromAddressBar("https://www.es.wikipedia.org/wiki/Pizarra/")
        val b = PageKeys.fromAddressBar("es.wikipedia.org/wiki/Pizarra#Historia")
        val c = PageKeys.fromAddressBar("  ES.Wikipedia.org/wiki/Pizarra ")
        assertEquals("web:es.wikipedia.org/wiki/Pizarra", a)
        assertEquals(a, b)
        assertEquals(a, c)
    }

    @Test
    fun differentPagesAndVideosAreDifferent() {
        assertNotEquals(
            PageKeys.fromAddressBar("youtube.com/watch?v=abc"),
            PageKeys.fromAddressBar("youtube.com/watch?v=xyz"),
        )
        assertNotEquals(PageKeys.fromAddressBar("example.com/a"), PageKeys.fromAddressBar("example.com/b"))
    }

    @Test
    fun searchesAndEmptyBarsAreNotPages() {
        assertNull(PageKeys.fromAddressBar(""))
        assertNull(PageKeys.fromAddressBar(null))
        assertNull(PageKeys.fromAddressBar("la pizarra digital"))
        assertNull(PageKeys.fromAddressBar("pizarra"))
    }

    @Test
    fun filesNeverContainTheAddress() {
        val name = PageKeys.fileName("web:es.wikipedia.org/wiki/Pizarra")
        assert(name.startsWith("page_") && name.endsWith(".mkp") && "wikipedia" !in name)
        assertEquals("board.mkp", PageKeys.fileName(PageKeys.GENERAL))
        assertEquals(name, PageKeys.fileName("web:es.wikipedia.org/wiki/Pizarra"))
    }
}
