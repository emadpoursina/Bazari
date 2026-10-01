package com.gomoney.capture.source

import com.gomoney.capture.logging.Redactor
import com.gomoney.capture.model.Confidence
import com.gomoney.capture.parser.BankParser
import com.gomoney.capture.parser.ParseResult
import com.gomoney.capture.storage.NormalizedTransaction
import com.gomoney.capture.storage.ParseErrorMessage
import com.gomoney.capture.storage.RawEvent
import com.gomoney.capture.storage.ServerAccountDao
import com.gomoney.capture.storage.TransactionSource
import java.time.OffsetDateTime
import java.util.UUID

/**
 * User-source capture parser (contracts/source-template.md, T021; 005
 * contracts/source-admission.md): matches a message against the source's
 * template and produces a NormalizedTransaction that flows through the
 * existing fingerprint/queue/delivery path unchanged (FR-022). On failure it
 * retains a sanitized [ParseErrorMessage] (FR-010) and returns
 * [ParseResult.Failure] so the pipeline records the outbox parse error.
 *
 * Currency (005 FR-001/002/003, FR-018): never a hardcoded default. When the
 * source is bound to a server account whose cached row has a non-blank
 * currency, that code is stamped on the transaction; unbound, stale, or
 * blank-currency sources produce `""` and the pipeline holds the row.
 *
 * Raw message text never leaves this class except into the local RawEvent
 * reference; only a normalized description is produced.
 */
class UserSourceParser(
    private val parseErrorRepository: ParseErrorRepository,
    private val serverAccountDao: ServerAccountDao? = null,
    private val clock: () -> OffsetDateTime = { OffsetDateTime.now() },
) {

    /**
     * The bound account's currency, or null when the source is unbound, the
     * binding is stale (account no longer in the cached list), or the account's
     * currency is blank — the three hold cases in data-model.md §1.
     */
    suspend fun boundCurrency(source: TransactionSource): String? {
        val boundId = source.boundAccountId ?: return null
        val accounts = serverAccountDao?.all() ?: return null
        val account = accounts.firstOrNull { it.id == boundId } ?: return null // stale → null
        return account.currency.takeIf { it.isNotBlank() } // blank currency → null
    }

    suspend fun parse(event: RawEvent, source: TransactionSource): ParseResult {
        val compiled = runCatching { TemplateMatcher.compile(source.template) }
            .getOrElse { return fail(event, source, "invalid template") }

        return when (val result = TemplateMatcher.match(compiled, event.text, source)) {
            is MatchResult.Success -> ParseResult.Success(
                NormalizedTransaction(
                    id = "",
                    sourceEventId = event.id,
                    source = event.source,
                    bank = USER_BANK,
                    accountHint = source.identifier,
                    type = result.direction.wire,
                    amountMinor = result.amountMinor,
                    // 005 FR-001/002: copy the bound account's currency; never
                    // invent IRR. Empty means unknown → the pipeline holds it.
                    currency = boundCurrency(source) ?: "",
                    txAt = event.postedAt,
                    description = BankParser.cleanDescription(event),
                    rawTextRef = event.id,
                    fingerprint = "",
                    parserName = PARSER_NAME,
                    confidence = Confidence.HIGH.name,
                    sourceId = source.id,
                    accountId = source.boundAccountId, // FR-015
                ),
            )

            is MatchResult.Failure -> fail(event, source, result.reason)
        }
    }

    private suspend fun fail(event: RawEvent, source: TransactionSource, reason: String): ParseResult {
        val sanitized = Redactor.safeDetail(reason) // never raw text (FR-023)
        parseErrorRepository.insert(
            ParseErrorMessage(
                id = UUID.randomUUID().toString(),
                sourceId = source.id,
                sourceEventId = event.id,
                failureReason = sanitized,
                occurredAt = clock().toString(),
            ),
        )
        return ParseResult.Failure(sanitized, Confidence.MEDIUM)
    }

    companion object {
        const val PARSER_NAME = "UserSourceParser"

        /** Stable slug for user-source transactions so fingerprints stay uniform. */
        const val USER_BANK = "user"
    }
}
