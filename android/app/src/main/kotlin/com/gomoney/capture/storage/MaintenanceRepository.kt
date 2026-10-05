package com.gomoney.capture.storage

import com.gomoney.capture.source.ParseErrorRepository
import java.time.OffsetDateTime

/**
 * Maintenance actions (US4, FR-022, spec edge case):
 *  - manual retry requeues FAILED → QUEUED;
 *  - clear processed removes ONLY sent/terminal rows, preserving
 *    pending/failed items;
 *  - periodic pruning of the parse-error review list (FR-028).
 */
class MaintenanceRepository(
    private val rawEventDao: RawEventDao,
    private val parseErrorDao: ParseErrorDao? = null,
    private val clock: () -> OffsetDateTime = { OffsetDateTime.now() },
    private val notificationCaptureRecordDao: NotificationCaptureRecordDao? = null,
) {

    /** Clear sent (terminal) sources only; pending/failed and unsynced memos are preserved. */
    suspend fun clearProcessed(): Int {
        pruneParseErrors()
        return rawEventDao.deleteProcessedSources()
    }

    /** Clear everything, including queued and failed items. Associated
     *  normalized transactions and delivery records cascade from raw_events;
     *  settings and dedup_cache are untouched.
     *
     *  006 research R8: the per-notification identity rows are deleted too,
     *  so a later scan may re-create events for notifications still in the
     *  shade; `clearProcessed()` deliberately leaves them alone so cleared
     *  terminal events are never resurrected. */
    suspend fun clearAll(): Int {
        notificationCaptureRecordDao?.clearAll()
        return rawEventDao.deleteAll()
    }

    /** Enforce the parse-error retention cap (last 200 entries or 30 days). */
    suspend fun pruneParseErrors(): Int =
        parseErrorDao?.let { ParseErrorRepository(it, clock).prune() } ?: 0
}
