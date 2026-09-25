package com.gomoney.capture.parser

import com.gomoney.capture.model.Confidence
import com.gomoney.capture.model.TxType
import com.gomoney.capture.storage.RawEvent

/**
 * Blue Bank parser (bank slug `blu`, app package `com.samanpr.blu`).
 *
 * Blue notifications carry no masked card/account suffix, so the parser emits
 * the deterministic, non-PII synthetic account hint `blue-default`. The bridge
 * must map `blu|blue-default` to the user's actual Go Money source account
 * (specs/001-android-txn-capture/contracts/gomoney-integration.md §account
 * resolution). LIMITATION: every Blue capture shares that one hint — the
 * bridge cannot distinguish between multiple Blue accounts.
 *
 * Actual message shape (real customer name redacted in fixtures) — note there
 * is NO `مبلغ` label:
 *   برداشت يول
 *   <customer name>، 10,000,000 ريال از حساب شما
 *   يريد.
 *   موجودى: 10,862,252 ريال
 *
 * Rules enforced here:
 *  - recognition anchors on `برداشت` (withdrawal); the `مبلغ` label is NOT
 *    required (it never appears in real Blue bodies);
 *  - the text is truncated before the balance marker FIRST, so the trailing
 *    `موجودی`/`موجودي`/`موجودى` (balance) figure can NEVER become the
 *    transaction amount;
 *  - the first amount in the truncated text is the transaction amount
 *    (10,000,000 IRR above), never the balance (10,862,252 above);
 *  - description is a fixed, safe type-appropriate constant ("Blue
 *    withdrawal" / "Blue purchase" / "Blue deposit") — the customer name and
 *    balance are structurally excluded, not string-matched, so an income or
 *    purchase message can never carry a raw-name/balance description or a
 *    withdrawal label;
 *  - everything goes through AmountNormalizer (Persian/Arabic digits and
 *    ٬ separators are normalized, FR-010);
 *  - unrecognized messages → sanitized Failure (FR-023/028), never raw text.
 */
class BlueParser : BankParser {
    override val name: String = "BlueParser"
    override val bank: String = "blu"
    override val supportedSources: Set<String> = setOf(
        "com.samanpr.blu", // notification package
    )

    override fun canParse(event: RawEvent): Boolean = sourceMatches(event)

    override fun parse(event: RawEvent): ParseResult {
        val text = AmountNormalizer.normalizeDigits(event.text)

        val type: TxType = when {
            text.contains("برداشت") -> TxType.EXPENSE
            text.contains("خريد") || text.contains("خرید") -> TxType.EXPENSE
            text.contains("واریز") || text.contains("واريز") -> TxType.INCOME
            else -> return ParseResult.Failure("unrecognized blue notification type", Confidence.MEDIUM)
        }
        val description = when (type) {
            TxType.EXPENSE ->
                if (text.contains("برداشت")) SAFE_WITHDRAWAL_DESCRIPTION else SAFE_PURCHASE_DESCRIPTION
            TxType.INCOME -> SAFE_DEPOSIT_DESCRIPTION
        }

        // Truncate BEFORE the balance marker so the trailing balance figure is
        // structurally unreachable — the balance can never win as the amount.
        val body = BALANCE_MARKERS.fold(text) { acc, marker -> acc.substringBefore(marker) }

        // First amount in the truncated body (e.g. "<name>، 10,000,000 ريال")
        // is the transaction amount. AmountNormalizer anchors on the currency
        // word so card digits or the balance are never picked.
        val amount = AmountNormalizer.parseAmountMinor(body)
            ?: return ParseResult.Failure("no withdrawal amount found in blue notification", Confidence.MEDIUM)

        val tx = draft(event).copy(
            accountHint = SYNTHETIC_ACCOUNT_HINT,
            type = type.name.lowercase(),
            amountMinor = amount,
            currency = "IRR",
            txAt = event.postedAt,
            description = description,
            confidence = Confidence.HIGH.name,
        )
        return ParseResult.Success(tx)
    }

    companion object {
        /** Synthetic, non-PII account hint for the bridge mapping key `blu|blue-default`. */
        const val SYNTHETIC_ACCOUNT_HINT = "blue-default"

        /** Safe fixed descriptions — never contain the customer name or balance. */
        const val SAFE_WITHDRAWAL_DESCRIPTION = "Blue withdrawal"
        const val SAFE_PURCHASE_DESCRIPTION = "Blue purchase"
        const val SAFE_DEPOSIT_DESCRIPTION = "Blue deposit"

        /** Balance markers with Arabic ye / Persian ye / alef maksura endings. */
        private val BALANCE_MARKERS = listOf("موجودی", "موجودي", "موجودى")
    }
}
