package com.gomoney.capture.ui

import com.gomoney.capture.ui.theme.AmountFormat
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class AmountFormatTest {

    @Test
    fun formatsWithGroupingSeparators() {
        val formatted = AmountFormat.formatAmountMinor(1000000L, "IRR", Locale.US)
        assertEquals("1,000,000 IRR", formatted)
    }

    @Test
    fun blankCurrencyFormatsNumberOnlyWithoutInventingCode() {
        val formattedBlank = AmountFormat.formatAmountMinor(50000L, "", Locale.US)
        assertEquals("50,000", formattedBlank)

        val formattedNull = AmountFormat.formatAmountMinor(50000L, null, Locale.US)
        assertEquals("50,000", formattedNull)

        val formattedWhitespace = AmountFormat.formatAmountMinor(50000L, "   ", Locale.US)
        assertEquals("50,000", formattedWhitespace)
    }

    @Test
    fun doesNotRescaleMinorUnits() {
        // IRR and integer amounts are kept as whole rials/units — no division by 100
        val formatted = AmountFormat.formatAmountMinor(123456789L, "IRR", Locale.US)
        assertEquals("123,456,789 IRR", formatted)
    }

    @Test
    fun handlesZeroAndNegativeAmounts() {
        assertEquals("0 USD", AmountFormat.formatAmountMinor(0L, "USD", Locale.US))
        assertEquals("-1,500 EUR", AmountFormat.formatAmountMinor(-1500L, "EUR", Locale.US))
    }
}
