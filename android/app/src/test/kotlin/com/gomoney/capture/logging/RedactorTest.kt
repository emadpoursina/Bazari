package com.gomoney.capture.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Redactor tests (T047, FR-028, SC-007). */
class RedactorTest {

    /** Production logs never contain raw message text or amounts. */
    @Test
    fun `raw text is redacted to shape metadata only`() {
        val redacted = Redactor.redactRawText("خريد شماره کارت 6104.****1234 به مبلغ ۵۰۰٬۰۰۰ ریال")
        assertTrue(!redacted.contains("خريد"))
        assertTrue(!redacted.contains("6104"))
        assertTrue(redacted.startsWith("<redacted:"))
    }

    @Test
    fun `amounts never reach logs`() {
        assertEquals("<amount>", Redactor.redactAmount(500_000L))
    }

    @Test
    fun `safe detail strips long digit runs and caps length`() {
        val detail = Redactor.safeDetail("card 6104-****1234 failed for account ۵۰۰۰۰۰")
        assertTrue(!detail.contains("6104"))
        assertTrue(!detail.contains("500000"))
        assertTrue(detail.length <= 120)
    }

    @Test
    fun `debug gating hides raw text when debug mode off`() {
        val raw = "خريد ۵۰۰٬۰۰۰ ریال"
        assertNull(Redactor.debugOnlyRawText(raw, debugModeEnabled = false))
        assertEquals(raw, Redactor.debugOnlyRawText(raw, debugModeEnabled = true))
    }
}
