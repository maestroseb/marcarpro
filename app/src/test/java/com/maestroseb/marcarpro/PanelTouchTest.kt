package com.maestroseb.marcarpro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PanelTouchTest {

    @Test
    fun readsTheAreaNumberThePanelAssigns() {
        // Reply captured on the IFP7533 (2026-10-05).
        val reply = """{"paramsJson":"{\"result\":5}","responseName":"RespIntegerResult","status":"SUCCESS"}"""
        assertEquals(5, PanelTouch.resultOf(reply))
    }

    @Test
    fun voidRepliesHaveNoNumber() {
        assertNull(PanelTouch.resultOf("""{"paramsJson":"{}","responseName":"RespVoidResult","status":"SUCCESS"}"""))
    }
}
