package com.gomoney.capture.parser

import com.gomoney.capture.model.Confidence
import com.gomoney.capture.model.TxType
import com.gomoney.capture.storage.RawEvent

/**
 * Generic fallback parser (bank slug `generic`). Conservative: only parses
 * when BOTH an explicit currency-word amount and a masked card hint are
 * present and a known transaction verb appears; anything else → Failure →
 * the event is retained as parse_error (parser-interface.md MVP table).
 */
class GenericParser : BankParser {
    override val name: String = "GenericParser"
    override val bank: String = "generic"
    override val supportedSources: Set<String> = setOf(
        "com.other.bank",
        "GENERIC",
    )

    override fun canParse(event: RawEvent): Boolean = sourceMatches(event)

    override fun parse(event: RawEvent): ParseResult {
        val text = AmountNormalizer.normalizeDigits(event.text)

        val amount = AmountNormalizer.parseAmountMinor(text)
        val accountHint = AmountNormalizer.extractAccountHint(text)

        if (amount == null || accountHint == null) {
            return ParseResult.Failure("generic parser requires explicit amount + card hint", Confidence.LOW)
        }

        val type: TxType = when {
            text.contains("خرید") || text.contains("خريد") || text.contains("برداشت") -> TxType.EXPENSE
            text.contains("واریز") || text.contains("واریز") -> TxType.INCOME
            else -> return ParseResult.Failure("generic parser: conservative match failed", Confidence.LOW)
        }

        val tx = draft(event).copy(
            accountHint = accountHint,
            type = type.name.lowercase(),
            amountMinor = amount,
            currency = "IRR",
            txAt = event.postedAt,
            description = BankParser.cleanDescription(event),
            confidence = Confidence.MEDIUM.name, // generic matcher is never HIGH
        )
        return ParseResult.Success(tx)
    }
}
