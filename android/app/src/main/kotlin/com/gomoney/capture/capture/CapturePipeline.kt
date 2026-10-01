package com.gomoney.capture.capture

import com.gomoney.capture.logging.Redactor
import com.gomoney.capture.model.DeliveryState
import com.gomoney.capture.model.EventSource
import com.gomoney.capture.parser.Fingerprint
import com.gomoney.capture.parser.ParseResult
import com.gomoney.capture.source.TransactionSourceRepository
import com.gomoney.capture.source.UserSourceParser
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
 * Capture pipeline (005 contracts/source-admission.md, T020/T027):
 * RawEvent → enabled TransactionSource(identifier, channel) → UserSourceParser
 * → fingerprint → persist NormalizedTransaction + DeliveryRecord.
 *
 * Sources are the ONLY admission gate: the Settings bank allow-list is gone
 * (FR-012–FR-015) and shipped `ParserRegistry` parsers never run on this path
 * (FR-021). A message from an identifier that is not an enabled source is
 * ignored without persisting anything.
 *
 * Currency/delivery (005 FR-002/010, data-model §6):
 *  - bound source with a non-blank catalog currency → tx carries that currency,
 *    outbox `queued`, expedited sync enqueued;
 *  - unbound/stale/blank-currency source → tx currency `""`, outbox `held`,
 *    NO expedited sync (T027) — the row is stamped `held → queued` when the
 *    source is later bound (T028).
 *
 * Raw text is persisted BEFORE any processing (FR-002) and never logged (FR-028).
 */
class CapturePipeline(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val deliveryRepository: DeliveryRepository,
    private val dedupRepository: DedupRepository,
    private val onCaptured: suspend (NormalizedTransaction) -> Unit = {},
    private val sourceRepository: TransactionSourceRepository,
    private val userSourceParser: UserSourceParser,
) {

    /**
     * Process one captured event. Raw text is persisted BEFORE any processing
     * (FR-002) and never logged (FR-028) — but only when an enabled source
     * claims the identifier/channel; otherwise nothing is persisted (FR-014).
     */
    suspend fun process(event: RawEvent): Outcome {
        val config = settings.current()

        // Master capture toggles (FR-016): a channel whose toggle is off
        // captures nothing, sources or not.
        if (event.eventSource() == EventSource.NOTIFICATION && !config.notificationCaptureEnabled) {
            return Outcome.IGNORED_DISABLED
        }
        if (event.eventSource() == EventSource.SMS && !config.smsCaptureEnabled) {
            return Outcome.IGNORED_DISABLED
        }

        // Source-only admission (005 FR-013/014/017): the identifier + channel
        // must match an enabled source with a user template. No allow-list, no
        // ParserRegistry fallback; disabling or removing the source is
        // sufficient to stop capture.
        val source = sourceRepository.resolve(
            event.sourcePackage,
            com.gomoney.capture.model.Channel.fromEventSource(event.eventSource()),
        )
        if (source == null || !source.enabled) {
            return Outcome.IGNORED_NO_SOURCE
        }
        return processSource(event, source)
    }

    private suspend fun processSource(
        event: RawEvent,
        source: com.gomoney.capture.storage.TransactionSource,
    ): Outcome {
        // FR-002: persist the raw event before matching so a failure can be
        // retained and reviewed (FR-010).
        db.rawEventDao().insert(event)

        val result = userSourceParser.parse(event, source)
        val parsed = when (result) {
            is ParseResult.Success -> result.transaction
            is ParseResult.Failure -> {
                // The ParseErrorMessage is retained by UserSourceParser; the
                // outbox parse error keeps the existing events-screen behaviour.
                deliveryRepository.createParseError(
                    id = event.id,
                    sourceEventId = event.id,
                    reason = Redactor.safeDetail(result.reason),
                )
                return Outcome.RETAINED_PARSE_ERROR
            }
        }

        val fingerprint = Fingerprint.compute(
            bank = parsed.bank,
            accountHint = parsed.accountHint,
            type = parsed.txType(),
            amountMinor = parsed.amountMinor,
            txAt = parsed.txAt,
            description = parsed.description,
        )
        val known = dedupRepository.lookup(fingerprint)
        val finalTx = parsed.copy(id = UUID.randomUUID().toString(), fingerprint = fingerprint)
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

        // 005 FR-010 / data-model §6: a transaction without a currency (source
        // unbound, stale, or blank-currency) is HELD, not queued — and does not
        // trigger the expedited sync (T027). It is stamped `held → queued`
        // when the source is later bound (T028).
        if (tx.currency.isBlank()) {
            deliveryRepository.createHeld(id = tx.id, sourceEventId = event.id)
            return Outcome.HELD
        }

        deliveryRepository.createQueued(id = tx.id, sourceEventId = event.id)
        onCaptured(tx) // triggers the expedited sync enqueue (T031)
        return Outcome.QUEUED
    }
}

enum class Outcome {
    QUEUED,
    /** 005: currency-less capture parked locally until the source is bound. */
    HELD,
    QUEUED_AS_KNOWN_DUPLICATE,
    DUPLICATE_SHORT_CIRCUITED,
    RETAINED_PARSE_ERROR,
    IGNORED_DISABLED,
    /** 005 FR-014: identifier/channel is not an enabled source — nothing captured. */
    IGNORED_NO_SOURCE,
}
