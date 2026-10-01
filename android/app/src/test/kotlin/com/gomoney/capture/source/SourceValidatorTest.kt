package com.gomoney.capture.source

import com.gomoney.capture.storage.ServerAccount
import com.gomoney.capture.storage.TransactionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SourceValidator tests (T016, T059; FR-005/012, SC-009): required placeholders,
 * keyword lists, and duplicate `(identifier, channel)` naming the conflict.
 */
class SourceValidatorTest {

    private fun source(
        id: String = "src-1",
        name: String = "Example Bank",
        identifier: String = "com.example.bank",
        channel: String = "notification",
        template: String = "خرید مبلغ {amount} ریال {direction}",
        income: String = "واریز",
        expense: String = "خرید",
    ) = TransactionSource(
        id = id,
        name = name,
        identifier = identifier,
        channel = channel,
        enabled = true,
        template = template,
        incomeKeywords = income,
        expenseKeywords = expense,
        createdAt = "2026-09-24T00:00:00+03:30",
        updatedAt = "2026-09-24T00:00:00+03:30",
    )

    @Test
    fun `a complete source validates`() {
        assertTrue(SourceValidator.validate(source(), emptyList()).isEmpty())
    }

    @Test
    fun `blank name and identifier are rejected`() {
        val errors = SourceValidator.validate(source(name = "  ", identifier = ""), emptyList())
        assertTrue(errors.any { it.field == "name" })
        assertTrue(errors.any { it.field == "identifier" })
    }

    @Test
    fun `a missing placeholder is named`() {
        val errors = SourceValidator.validate(source(template = "خرید مبلغ {amount}"), emptyList())
        assertTrue(errors.any { it.field == "template" && it.message.contains("{direction}") })
    }

    @Test
    fun `a duplicated placeholder is rejected`() {
        val errors = SourceValidator.validate(
            source(template = "خرید {amount} ریال {amount} {direction}"),
            emptyList(),
        )
        assertTrue(errors.any { it.field == "template" && it.message.contains("{amount}") })
    }

    @Test
    fun `empty keyword lists are rejected`() {
        val errors = SourceValidator.validate(source(income = " , ", expense = ""), emptyList())
        assertTrue(errors.any { it.field == "incomeKeywords" })
        assertTrue(errors.any { it.field == "expenseKeywords" })
    }

    @Test
    fun `duplicate identifier and channel is blocked and names the conflict`() {
        val existing = source(id = "existing", name = "Old Bank")
        val errors = SourceValidator.validate(source(id = "new"), listOf(existing))
        val duplicate = errors.first { it.field == "identifier" }
        assertTrue(duplicate.message.contains("Old Bank"))
    }

    @Test
    fun `editing the same source does not conflict with itself`() {
        val existing = source(id = "src-1")
        assertTrue(SourceValidator.validate(source(id = "src-1"), listOf(existing)).isEmpty())
    }

    @Test
    fun `same identifier on a different channel is allowed`() {
        val existing = source(id = "existing", channel = "notification")
        assertTrue(
            SourceValidator.validate(source(id = "new", channel = "sms"), listOf(existing)).isEmpty(),
        )
    }

    @Test
    fun `an unbound source is valid`() {
        assertTrue(SourceValidator.validate(source(), emptyList()).isEmpty())
        assertEquals(null, source().boundAccountId)
    }

    @Test
    fun `an over-long name is rejected`() {
        val errors = SourceValidator.validate(source(name = "x".repeat(101)), emptyList())
        assertTrue(errors.any { it.field == "name" })
    }

    // --- 005 T016 / FR-006: blank-currency accounts cannot be bound ---

    private fun account(id: Int, label: String, currency: String) = ServerAccount(
        id = id,
        label = label,
        currency = currency,
        type = "asset",
        isDefault = false,
        refreshedAt = "2026-09-24T00:00:00+03:30",
    )

    @Test
    fun `a blank-currency account is rejected for binding with the reason named`() {
        val accounts = listOf(account(9, "USD Wallet", "USD"), account(3, "Currencyless", ""))

        val rejected = SourceValidator.validateBinding(3, accounts)

        assertEquals("boundAccountId", rejected?.field)
        assertTrue(rejected!!.message.contains("Currencyless"))
        assertTrue(rejected.message.contains("no currency"))
    }

    @Test
    fun `accounts with a currency and null bindings validate`() {
        val accounts = listOf(account(9, "USD Wallet", "USD"))

        assertNull(SourceValidator.validateBinding(9, accounts))
        assertNull(SourceValidator.validateBinding(null, accounts))
    }

    @Test
    fun `a stale binding id is not a currency error`() {
        val accounts = listOf(account(9, "USD Wallet", "USD"))

        assertNull(SourceValidator.validateBinding(123, accounts))
    }
}
