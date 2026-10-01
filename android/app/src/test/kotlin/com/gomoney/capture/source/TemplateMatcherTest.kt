package com.gomoney.capture.source

import com.gomoney.capture.model.Direction
import com.gomoney.capture.storage.TransactionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TemplateMatcher tests (T015, T059; FR-007/009/010/011/026): compile/match,
 * anchor tolerance, digit normalization, amount/direction failures, and
 * sanitized reasons.
 */
class TemplateMatcherTest {

    private fun source(
        template: String,
        income: String = "واریز",
        expense: String = "خرید,خريد",
    ) = TransactionSource(
        id = "src",
        name = "Example",
        identifier = "com.example.bank",
        channel = "notification",
        enabled = true,
        template = template,
        incomeKeywords = income,
        expenseKeywords = expense,
        createdAt = "2026-09-24T00:00:00+03:30",
        updatedAt = "2026-09-24T00:00:00+03:30",
    )

    private fun match(template: String, message: String, income: String = "واریز", expense: String = "خرید,خريد"): MatchResult {
        val src = source(template, income, expense)
        return TemplateMatcher.match(TemplateMatcher.compile(template), message, src)
    }

    @Test
    fun `compiles literal anchors and ordered slots`() {
        val compiled = TemplateMatcher.compile("خرید {amount} ریال {direction}")
        assertEquals(listOf("خرید", "ریال", ""), compiled.anchors)
        assertEquals(
            listOf(CompiledTemplate.SlotKind.AMOUNT, CompiledTemplate.SlotKind.DIRECTION),
            compiled.slots,
        )
    }

    @Test
    fun `compile rejects a template missing a placeholder`() {
        assertTrue(!TemplateMatcher.isValidTemplate("خرید {amount}"))
        assertTrue(TemplateMatcher.templateErrors("خرید {amount}").any { it.contains("{direction}") })
        assertTrue(TemplateMatcher.templateErrors("خرید").any { it.contains("{amount}") })
    }

    @Test
    fun `compile rejects a duplicated placeholder`() {
        assertTrue(!TemplateMatcher.isValidTemplate("a {amount} b {amount} c {direction}"))
    }

    @Test
    fun `matches a purchase and reads the amount`() {
        val result = match("خرید به مبلغ {amount} ریال {direction}", "خرید به مبلغ ۵۰۰٬۰۰۰ ریال فروشگاه")
        assertTrue(result is MatchResult.Success)
        result as MatchResult.Success
        assertEquals(Direction.EXPENSE, result.direction)
        assertEquals(500_000L, result.amountMinor)
    }

    @Test
    fun `anchor tolerance allows dates and reference numbers to differ`() {
        val template = "خرید به مبلغ {amount} ریال {direction}"
        val a = match(template, "خرید به مبلغ ۵۰۰٬۰۰۰ ریال فروشگاه، مرجع ۱۲۳۴۵")
        val b = match(template, "خرید به مبلغ ۵۰۰٬۰۰۰ ریال فروشگاه، مرجع ۹۹۹۹۹")
        assertTrue(a is MatchResult.Success && b is MatchResult.Success)
    }

    @Test
    fun `persian digits and separators normalize to a canonical amount`() {
        val result = match("خرید مبلغ {amount} ریال {direction}", "خرید مبلغ ۲۵۰٬۰۰۰ ریال نانوایی")
        assertEquals(250_000L, (result as MatchResult.Success).amountMinor)
    }

    @Test
    fun `direction comes from the keyword lists not the placeholder text`() {
        val result = match(
            "مبلغ {amount} {direction}",
            "واریز مبلغ ۱۲۰٬۰۰۰ ریال",
            income = "واریز",
            expense = "خرید",
        )
        assertEquals(Direction.INCOME, (result as MatchResult.Success).direction)
    }

    @Test
    fun `missing amount fails with a sanitized reason`() {
        val result = match("خرید مبلغ {amount} ریال {direction}", "خرید مبلغ ریال فروشگاه")
        assertEquals("amount not found", (result as MatchResult.Failure).reason)
    }

    @Test
    fun `zero amount fails as a non-positive number`() {
        val result = match("خرید مبلغ {amount} ریال {direction}", "خرید مبلغ ۰ ریال فروشگاه")
        assertEquals("amount is not a positive number", (result as MatchResult.Failure).reason)
    }

    @Test
    fun `unrecognized direction fails`() {
        val result = match(
            "مبلغ {amount} {direction}",
            "برداشت مبلغ ۱۰۰٬۰۰۰ ریال",
            income = "واریز",
            expense = "خرید",
        )
        assertEquals("direction not recognized", (result as MatchResult.Failure).reason)
    }

    @Test
    fun `ambiguous direction fails`() {
        val result = match(
            "مبلغ {amount} {direction}",
            "خرید و واریز مبلغ ۱۰۰٬۰۰۰ ریال",
            income = "واریز",
            expense = "خرید",
        )
        assertEquals("direction is ambiguous", (result as MatchResult.Failure).reason)
    }

    @Test
    fun `non matching message fails without echoing raw text`() {
        val result = match("خرید مبلغ {amount} ریال {direction}", "پیام کاملا نامربوط")
        val failure = result as MatchResult.Failure
        assertEquals("message does not match template", failure.reason)
        assertTrue(!failure.reason.contains("نامربوط"))
    }

    @Test
    fun `placeholder order may be reversed`() {
        val result = match("{direction} مبلغ {amount} ریال", "خرید مبلغ ۵۰۰٬۰۰۰ ریال")
        assertEquals(500_000L, (result as MatchResult.Success).amountMinor)
    }

    @Test
    fun `match is a pure function of its inputs`() {
        val template = "خرید مبلغ {amount} ریال {direction}"
        val src = source(template)
        val compiled = TemplateMatcher.compile(template)
        val first = TemplateMatcher.match(compiled, "خرید مبلغ ۵۰۰٬۰۰۰ ریال فروشگاه", src)
        val second = TemplateMatcher.match(compiled, "خرید مبلغ ۵۰۰٬۰۰۰ ریال فروشگاه", src)
        assertEquals(first, second)
    }

    @Test
    fun `preview works offline before saving`() {
        val result = TemplateMatcher.preview(
            template = "خرید مبلغ {amount} ریال {direction}",
            message = "خرید مبلغ ۵۰۰٬۰۰۰ ریال فروشگاه",
            incomeKeywords = listOf("واریز"),
            expenseKeywords = listOf("خرید"),
        )
        assertEquals(500_000L, (result as MatchResult.Success).amountMinor)
    }

    @Test
    fun `preview reports an invalid template instead of throwing`() {
        val result = TemplateMatcher.preview("خرید {amount}", "خرید ۵۰۰۰ ریال", emptyList(), listOf("خرید"))
        assertTrue(result is MatchResult.Failure)
    }

    /** T059 regression: a whitespace-only literal between placeholders is zero-width. */
    @Test
    fun `whitespace-only anchor between placeholders still finds the amount`() {
        val result = match(
            "مبلغ {amount} {direction}",
            "واریز مبلغ ۱۲۰٬۰۰۰ ریال",
            income = "واریز",
            expense = "خرید",
        )
        assertEquals(120_000L, (result as MatchResult.Success).amountMinor)
    }

    @Test
    fun `template with no literal anchors still extracts the amount`() {
        val result = match(
            "{amount} {direction}",
            "۵۰۰٬۰۰۰ ریال خرید",
            income = "واریز",
            expense = "خرید",
        )
        assertEquals(500_000L, (result as MatchResult.Success).amountMinor)
    }

    @Test
    fun `a non numeric amount fails as not found`() {
        val result = match("خرید مبلغ {amount} ریال {direction}", "خرید مبلغ نامعلوم ریال فروشگاه")
        assertEquals("amount not found", (result as MatchResult.Failure).reason)
    }
}
