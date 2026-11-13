package com.maestroseb.marcarpro

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WholeImageTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun wholeJpeg() = assertTrue(wholeImage(bytes(0xFF, 0xD8, 0xFF, 0xE0, 1, 2, 3, 0xFF, 0xD9)))

    @Test fun wholeJpegWithPadding() = assertTrue(wholeImage(bytes(0xFF, 0xD8, 1, 2, 0xFF, 0xD9, 0, 0, 0)))

    @Test fun halfWrittenJpeg() = assertFalse(wholeImage(bytes(0xFF, 0xD8, 0xFF, 0xE0, 1, 2, 3, 4)))

    @Test fun emptyFile() = assertFalse(wholeImage(ByteArray(0)))

    @Test fun halfWrittenJpegOfZeros() = assertFalse(wholeImage(bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 0, 0, 0)))

    @Test fun wholePng() {
        val png = bytes(0x89, 'P'.code, 'N'.code, 'G'.code, 1, 2, 3, 4) + "IEND".toByteArray() + bytes(0xAE, 0x42, 0x60, 0x82)
        assertTrue(wholeImage(png))
    }

    @Test fun halfWrittenPng() = assertFalse(wholeImage(bytes(0x89, 'P'.code, 'N'.code, 'G'.code, 1, 2, 3, 4, 5, 6, 7, 8, 9)))
}
