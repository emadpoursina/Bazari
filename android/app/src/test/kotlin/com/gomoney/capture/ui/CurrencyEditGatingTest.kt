package com.gomoney.capture.ui

import com.gomoney.capture.storage.ServerAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 005 T033 / FR-022, SC-009: the transaction screen's currency control is
 * editable only while the row is local and undelivered (`held`, `queued`,
 * `failed`) and read-only once delivery begins (`sending`, `sent`); the
 * dropdown offers the distinct non-blank server-account currencies.
 */
class CurrencyEditGatingTest {

    @Test
    fun `currency is editable for held queued and failed`() {
        assertTrue(currencyEditable("held"))
        assertTrue(currencyEditable("queued"))
        assertTrue(currencyEditable("failed"))
    }

    @Test
    fun `currency is read-only for sending and sent`() {
        assertFalse(currencyEditable("sending"))
        assertFalse(currencyEditable("sent"))
    }

    @Test
    fun `missing or unknown states are not editable`() {
        assertFalse(currencyEditable(null))
        assertFalse(currencyEditable("queued ".trim() + "x"))
        assertFalse(currencyEditable("captured"))
        assertFalse(currencyEditable("parsed"))
    }

    @Test
    fun `state casing is normalized`() {
        assertTrue(currencyEditable("HELD"))
        assertFalse(currencyEditable("SENT"))
    }

    @Test
    fun `dropdown currencies are distinct and non-blank`() {
        val accounts = listOf(
            account(1, "Rial", "IRR"),
            account(2, "USD", "USD"),
            account(3, "Blank", ""),
            account(4, "Whitespace", "  "),
            account(5, "EUR", "EUR"),
            account(6, "Rial 2", "IRR"),
        )

        val currencies = accounts.distinctCurrencies()

        assertEquals(listOf("IRR", "USD", "EUR"), currencies)
    }

    private fun account(id: Int, label: String, currency: String) = ServerAccount(
        id = id,
        label = label,
        currency = currency,
        type = "asset",
        isDefault = false,
        refreshedAt = "2026-09-24T00:00:00+03:30",
    )
}
