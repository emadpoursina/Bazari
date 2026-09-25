package com.gomoney.capture.capture

import com.gomoney.capture.logging.Redactor
import com.gomoney.capture.model.DeliveryState
import com.gomoney.capture.model.EventSource
import com.gomoney.capture.parser.Fingerprint
import com.gomoney.capture.parser.ParserRegistry
import com.gomoney.capture.parser.ParseResult
import com.gomoney.capture.storage.AppDatabase
import com.gomoney.capture.storage.DedupRepository
import com.gomoney.capture.storage.DeliveryRepository
import com.gomoney.capture.storage.NormalizedTransaction
import com.gomoney.capture.storage.RawEvent
import com.gomoney.capture.storage.ServerConfiguration
import com.gomoney.capture.storage.SettingsRepository
import com.gomoney.capture.storage.eventSource
import com.gomoney.capture.storage.txType
import java.util.UUID

/**
 * Capture pipeline (US1, T020):
 * RawEvent → allow-list gate → ParserRegistry selection → parse →
 * fingerprint → persist NormalizedTransaction + DeliveryRecord(queued)
 * transactionally. No parser match → RawEvent retained flagged parse_error
 * (US6 scenario 2 — never silently dropped).
 */
class CapturePipeline(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val registry: ParserRegistry = ParserRegistry(),
    private val deliveryRepository: DeliveryRepository,
    private val dedupRepository: DedupRepository,
    private val onCaptured: suspend (NormalizedTransaction) -> Unit = {},
) {

    /**
     * Process one captured event. Raw text is persisted BEFORE any processing
     * (FR-002) and never logged (FR-028).
     */
    suspend fun process(event: RawEvent): Outcome {
        val config = settings.current()

        // Capture source toggle (spec scenario 1.3: disabled → capture nothing).
        if (event.eventSource() == EventSource.NOTIFICATION && !config.notificationCaptureEnabled) {
            return Outcome.IGNORED_DISABLED
        }
        if (event.eventSource() == EventSource.SMS && !config.smsCaptureEnabled) {
            return Outcome.IGNORED_DISABLED
        }

        // Allow-list gate (FR-001): ignore every other app silently.
        if (event.sourcePackage !in config.enabledBankPackages) {
            return Outcome.IGNORED_NOT_ALLOW_LISTED
        }

        // FR-002: persist the raw event before ANY processing.
        db.rawEventDao().insert(event)

        val parser = registry.select(event)
        if (parser == null) {
            // Retained, flagged parse_error (US6 scenario 2).
            deliveryRepository.createParseError(
                id = event.id,
                sourceEventId = event.id,
                reason = "no parser claims this source",
            )
            return Outcome.RETAINED_PARSE_ERROR
        }

        val result = parser.parse(event)
        val parsed = when (result) {
            is ParseResult.Success -> result.transaction
            is ParseResult.Failure -> {
                deliveryRepository.createParseError(
                    id = event.id,
                    sourceEventId = event.id,
                    reason = Redactor.safeDetail(result.reason), // sanitized only
                )
                return Outcome.RETAINED_PARSE_ERROR
            }
        }

        // Fingerprint computed by the pipeline (parser-interface.md rule 4).
        val fingerprint = Fingerprint.compute(
            bank = parsed.bank,
            accountHint = parsed.accountHint,
            type = parsed.txType(),
            amountMinor = parsed.amountMinor,
            txAt = parsed.txAt,
            description = parsed.description,
        )

        // Local short-circuit: a re-captured identical event (or known
        // duplicate after restart) is queued as already-terminal (US3).
        val known = dedupRepository.lookup(fingerprint)

        val finalTx = parsed.copy(
            id = UUID.randomUUID().toString(),
            fingerprint = fingerprint,
        )

        return persist(finalTx, event, known)
    }

    private suspend fun persist(
        tx: NormalizedTransaction,
        event: RawEvent,
        knownOutcome: com.gomoney.capture.storage.DedupCache?,
    ): Outcome {
        // The unique fingerprint index guards against cross-source duplicates
        // that share an exact fingerprint (same source duplicate → identical
        // fingerprint). If the row already exists locally this is a duplicate
        // capture: short-circuit instead of corrupting state.
        val inserted = db.normalizedTransactionDao().insert(tx)
        if (inserted == -1L) {
            // Fingerprint already stored — mark the raw event's delivery row
            // as duplicate-acked (terminal) so it is not re-delivered.
            deliveryRepository.markDuplicateAcked(event.id, "exact duplicate of existing capture")
            return Outcome.DUPLICATE_SHORT_CIRCUITED
        }

        if (knownOutcome != null) {
            // Locally known terminal duplicate — queue is unnecessary.
            db.deliveryRecordDao().insert(
                com.gomoney.capture.storage.DeliveryRecord(
                    id = tx.id,
                    state = DeliveryState.SENT.name.lowercase(),
                    attempts = 0,
                    lastAttemptAt = null,
                    nextRetryAt = null,
                    errorCategory = com.gomoney.capture.model.ErrorCategory.DUPLICATE.name.lowercase(),
                    errorDetail = "locally known ${knownOutcome.outcome}",
                    sourceEventId = event.id,
                ),
            )
            return Outcome.QUEUED_AS_KNOWN_DUPLICATE
        }

        deliveryRepository.createQueued(id = tx.id, sourceEventId = event.id)
        onCaptured(tx) // triggers the expedited sync enqueue (T031)
        return Outcome.QUEUED
    }
}

enum class Outcome {
    QUEUED,
    QUEUED_AS_KNOWN_DUPLICATE,
    DUPLICATE_SHORT_CIRCUITED,
    RETAINED_PARSE_ERROR,
    IGNORED_DISABLED,
    IGNORED_NOT_ALLOW_LISTED,
}
