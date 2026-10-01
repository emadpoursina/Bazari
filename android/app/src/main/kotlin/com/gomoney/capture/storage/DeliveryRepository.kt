package com.gomoney.capture.storage

import com.gomoney.capture.model.DeliveryState
import com.gomoney.capture.model.ErrorCategory
import java.time.OffsetDateTime

/** DeliveryState accessor outside Room's field processing. */
fun DeliveryRecord.deliveryStateOf(): DeliveryState =
    DeliveryState.entries.firstOrNull { it.name.equals(state, ignoreCase = true) } ?: DeliveryState.CAPTURED

/**
 * Delivery state machine helpers (data-model.md §3):
 * CAPTURED → PARSED → QUEUED → SENDING → SENT (terminal) and
 * SENDING → FAILED → QUEUED on retry. 005-account-currency-sources adds
 * `HELD`: a capture with no currency (unbound/stale/blank-currency source)
 * parks here; the only legal transition out is HELD → QUEUED (stamped at
 * bind time — never directly to SENDING). All transitions are transactional.
 */
class DeliveryRepository(
    private val deliveryRecordDao: DeliveryRecordDao,
    private val clock: () -> OffsetDateTime = { OffsetDateTime.now() },
) {

    /** Legal forward transitions (state → state). */
    private val legalTransitions: Map<DeliveryState, Set<DeliveryState>> = mapOf(
        DeliveryState.CAPTURED to setOf(DeliveryState.PARSED),
        DeliveryState.PARSED to setOf(DeliveryState.QUEUED),
        DeliveryState.QUEUED to setOf(DeliveryState.SENDING),
        DeliveryState.SENDING to setOf(DeliveryState.SENT, DeliveryState.FAILED),
        DeliveryState.FAILED to setOf(DeliveryState.QUEUED),
        // 005: held rows unlock only via the bind-time stamp; never straight
        // to SENDING (data-model.md §6 outbox table).
        DeliveryState.HELD to setOf(DeliveryState.QUEUED),
        DeliveryState.SENT to emptySet(), // terminal & immutable
    )

    /** Create the initial outbox row for a parsed transaction. */
    suspend fun createQueued(id: String, sourceEventId: String): DeliveryRecord {
        val record = DeliveryRecord(
            id = id,
            state = DeliveryState.QUEUED.name.lowercase(),
            attempts = 0,
            lastAttemptAt = null,
            nextRetryAt = null,
            errorCategory = null,
            errorDetail = null,
            sourceEventId = sourceEventId,
        )
        deliveryRecordDao.insert(record)
        return record
    }

    /**
     * Create the outbox row for a captured transaction whose source is
     * unbound/stale/blank-currency (005 FR-009/010): currency-less rows are
     * held locally and never delivered until the source is bound.
     */
    suspend fun createHeld(id: String, sourceEventId: String): DeliveryRecord {
        val record = DeliveryRecord(
            id = id,
            state = DeliveryState.HELD.name.lowercase(),
            attempts = 0,
            lastAttemptAt = null,
            nextRetryAt = null,
            errorCategory = null,
            errorDetail = null,
            sourceEventId = sourceEventId,
        )
        deliveryRecordDao.insert(record)
        return record
    }

    /** Create a retained parse-error row (US6 scenario 2 — never silently dropped). */
    suspend fun createParseError(id: String, sourceEventId: String, reason: String): DeliveryRecord {
        val record = DeliveryRecord(
            id = id,
            state = DeliveryState.PARSED.name.lowercase(),
            attempts = 0,
            lastAttemptAt = null,
            nextRetryAt = null,
            errorCategory = ErrorCategory.PARSE_ERROR.name.lowercase(),
            errorDetail = reason,
            sourceEventId = sourceEventId,
        )
        deliveryRecordDao.insert(record)
        return record
    }

    /**
     * Apply a state transition, validating legality. Illegal transitions are
     * rejected (return null) rather than corrupting the outbox.
     */
    suspend fun transition(
        id: String,
        to: DeliveryState,
        errorCategory: ErrorCategory? = null,
        errorDetail: String? = null,
    ): DeliveryRecord? {
        val current = deliveryRecordDao.byId(id) ?: return null
        val from = current.deliveryStateOf()

        if (to !in legalTransitions[from].orEmpty()) {
            return null // illegal — SENT is terminal and immutable (§3)
        }
        if (to == DeliveryState.FAILED && errorCategory == null) {
            return null // FAILED requires a non-null category (§3 rules)
        }
        if (from == DeliveryState.SENT) {
            return null
        }

        val now = clock()
        val updated = current.copy(
            state = to.name.lowercase(),
            attempts = if (to == DeliveryState.SENDING) current.attempts + 1 else current.attempts,
            lastAttemptAt = if (to == DeliveryState.SENDING || to == DeliveryState.SENT || to == DeliveryState.FAILED) {
                now.toString()
            } else {
                current.lastAttemptAt
            },
            errorCategory = errorCategory?.name?.lowercase() ?: current.errorCategory,
            errorDetail = errorDetail ?: current.errorDetail,
        )
        deliveryRecordDao.upsert(updated)
        return updated
    }

    /**
     * Duplicate acknowledgement is terminal: state=sent with
     * errorCategory=duplicate (already recorded in Go Money — §3 rules).
     */
    suspend fun markDuplicateAcked(id: String, detail: String? = null): DeliveryRecord? {
        val current = deliveryRecordDao.byId(id) ?: return null
        if (current.deliveryStateOf() == DeliveryState.SENT) return current
        val updated = current.copy(
            state = DeliveryState.SENT.name.lowercase(),
            lastAttemptAt = clock().toString(),
            errorCategory = ErrorCategory.DUPLICATE.name.lowercase(),
            errorDetail = detail,
        )
        deliveryRecordDao.upsert(updated)
        return updated
    }

    /** Manual/auto retry: FAILED → QUEUED (§3 diagram). */
    suspend fun requeue(id: String, nextRetryAt: OffsetDateTime? = null): DeliveryRecord? {
        val current = deliveryRecordDao.byId(id) ?: return null
        if (current.deliveryStateOf() != DeliveryState.FAILED) return null
        val updated = current.copy(
            state = DeliveryState.QUEUED.name.lowercase(),
            nextRetryAt = nextRetryAt?.toString(),
        )
        deliveryRecordDao.upsert(updated)
        return updated
    }
}
