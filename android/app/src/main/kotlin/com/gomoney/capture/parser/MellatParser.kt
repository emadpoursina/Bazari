package com.gomoney.capture.parser

import com.gomoney.capture.model.Confidence
import com.gomoney.capture.model.TxType
import com.gomoney.capture.storage.RawEvent

/**
 * Bank Mellat parser (bank slug `mellat`).
 * Coverage (fixtures): purchase, withdrawal, deposit.
 */
class MellatParser : BankParser {
    override val name: String = "MellatParser"
    override val bank: String = "mellat"
    override val supportedSources: Set<String> = setOf(
        "ir.mellat.mellatab", // notification package
        "MELLAT", // SMS sender id
    )

    override fun canParse(event: RawEvent): Boolean = sourceMatches(event)

    override fun parse(event: RawEvent): ParseResult {
        val text = AmountNormalizer.normalizeDigits(event.text)

        val amount = AmountNormalizer.parseAmountMinor(text)
        val accountHint = AmountNormalizer.extractAccountHint(text)

        if (amount == null || accountHint == null) {
            return ParseResult.Failure("no amount or card hint recognized", Confidence.MEDIUM)
        }

        val type: TxType = when {
            text.contains("خريد") || text.contains("خرید") || text.contains("برداشت") -> TxType.EXPENSE
            text.contains("واریز") || text.contains("واريز") -> TxType.INCOME
            else -> return ParseResult.Failure("unrecognized mellat message type", Confidence.MEDIUM)
        }

        val tx = draft(event).copy(
            accountHint = accountHint,
            type = type.name.lowercase(),
            amountMinor = amount,
            currency = "IRR",
            txAt = event.postedAt,
            description = BankParser.cleanDescription(event),
            confidence = Confidence.HIGH.name,
        )
        return ParseResult.Success(tx)
    }
}
