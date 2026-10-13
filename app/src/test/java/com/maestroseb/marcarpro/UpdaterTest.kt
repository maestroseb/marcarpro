package com.maestroseb.marcarpro

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test
    fun `versions compare number by number`() {
        assertTrue(Updater.isNewer("1.1.0", "1.0"))
        assertTrue(Updater.isNewer("1.10.0", "1.9.2"))
        assertTrue(Updater.isNewer("2", "1.99.99"))
        assertFalse(Updater.isNewer("1.1.0", "1.1.0"))
        assertFalse(Updater.isNewer("1.1", "1.1.0"))
        assertFalse(Updater.isNewer("1.0.9", "1.1.0"))
    }
}
