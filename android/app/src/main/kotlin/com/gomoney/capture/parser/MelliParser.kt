package com.gomoney.capture.parser

import com.gomoney.capture.model.Confidence
import com.gomoney.capture.model.TxType
import com.gomoney.capture.storage.RawEvent

/**
 * Bank Melli parser (bank slug `melli`).
 * Coverage (fixtures): purchase, transfer.
 */
class MelliParser : BankParser {
    override val name: String = "MelliParser"
    override val bank: String = "melli"
    override val supportedSources: Set<String> = setOf(
        "ir.bmi.mobilebank", // notification package
        "BMI", // SMS sender id
    )

    override fun canParse(event: RawEvent): Boolean = sourceMatches(event)

    override fun parse(event: RawEvent): ParseResult {
        val text = AmountNormalizer.normalizeDigits(event.text)

        val amount = AmountNormalizer.parseAmountMinor(text)
        val accountHint = AmountNormalizer.extractAccountHint(text)

        if (amount == null || accountHint == null) {
            return ParseResult.Failure("no amount or card hint found", Confidence.MEDIUM)
        }

        val type: TxType = when {
            text.contains("خرید") || text.contains("خريد") -> TxType.EXPENSE
            text.contains("انتقال") -> TxType.EXPENSE // outgoing transfer
            else -> return ParseResult.Failure("unrecognized melli message type", Confidence.MEDIUM)
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
