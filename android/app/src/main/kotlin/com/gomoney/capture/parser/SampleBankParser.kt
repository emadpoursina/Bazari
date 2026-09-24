package com.gomoney.capture.parser

import com.gomoney.capture.model.Confidence
import com.gomoney.capture.model.TxType
import com.gomoney.capture.storage.RawEvent

/**
 * Sample fourth-bank parser — proves US6 extensibility: adding a bank is ONE
 * new class + fixtures + the single registry line in ParserRegistry().
 * Zero changes to capture/queue/delivery code (SC-005).
 */
class SampleBankParser : BankParser {
    override val name: String = "SampleBankParser"
    override val bank: String = "samplebank"
    override val supportedSources: Set<String> = setOf(
        "com.sample.bank",
        "SAMPLEBANK",
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
            text.contains("خرید") || text.contains("خريد") || text.contains("برداشت") -> TxType.EXPENSE
            text.contains("واریز") -> TxType.INCOME
            else -> return ParseResult.Failure("unrecognized sample-bank message type", Confidence.MEDIUM)
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
