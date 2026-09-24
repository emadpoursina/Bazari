package com.gomoney.capture.parser

import com.gomoney.capture.model.Confidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** AmountNormalizer unit tests (T006, FR-010, research.md R10). */
class AmountNormalizerTest {

    @Test
    fun `persian digits map to ascii`() {
        assertEquals("500000", AmountNormalizer.normalizeDigits("۵۰۰۰۰۰"))
        assertEquals("500000", AmountNormalizer.normalizeDigits("٥٠٠٠٠٠")) // Arabic-Indic
        assertEquals("1234", AmountNormalizer.normalizeDigits("۱۲۳۴"))
    }

    @Test
    fun `persian separators map to ascii`() {
        assertEquals("500,000", AmountNormalizer.normalizeDigits("۵۰۰٬۰۰۰"))
        assertEquals("500.25", AmountNormalizer.normalizeDigits("۵۰۰٫۲۵"))
    }

    @Test
    fun `labeled amount parses`() {
        // مبلغ ۵۰۰٬۰۰۰ ریال — 500,000 IRR
        assertEquals(500_000L, AmountNormalizer.parseAmountMinor("مبلغ ۵۰۰٬۰۰۰ ریال"))
        assertEquals(500_000L, AmountNormalizer.parseAmountMinor("مبلغ 500,000 ریال"))
    }

    @Test
    fun `trailing currency amount parses`() {
        assertEquals(1_200_000L, AmountNormalizer.parseAmountMinor("خرید ۱٬۲۰۰٬۰۰۰ ریال فروشگاه"))
    }

    @Test
    fun `card number is not parsed as amount`() {
        // Card prefix 6104-****1234 with no amount → null
        assertNull(AmountNormalizer.parseAmountMinor("خرید شماره کارت 6104-****1234 فروشگاه"))
    }

    @Test
    fun `toman multiplies by ten`() {
        assertEquals(50_000L, AmountNormalizer.parseAmountMinor("مبلغ ۵۰۰۰ تومان"))
    }

    @Test
    fun `no amount returns null`() {
        assertNull(AmountNormalizer.parseAmountMinor("پیام بانکی بدون مبلغ"))
    }

    @Test
    fun `account hint extraction`() {
        assertEquals("****1234", AmountNormalizer.extractAccountHint("کارت 6104-****1234"))
        assertEquals("****987", AmountNormalizer.extractAccountHint("****۹۸۷"))
        assertNull(AmountNormalizer.extractAccountHint("بدون کارت"))
    }

    @Test
    fun `description normalization`() {
        assertEquals("card purchase at shop", AmountNormalizer.normalizeDescription("  card   purchase  at  SHOP "))
    }
}
