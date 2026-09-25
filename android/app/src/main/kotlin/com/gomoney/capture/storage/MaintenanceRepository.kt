package com.gomoney.capture.storage

import com.gomoney.capture.storage.DeliveryRecordDao
import com.gomoney.capture.storage.RawEventDao
import com.gomoney.capture.storage.NormalizedTransactionDao

/**
 * Maintenance actions (US4, FR-022, spec edge case):
 *  - manual retry requeues FAILED → QUEUED;
 *  - clear processed removes ONLY sent/terminal rows, preserving
 *    pending/failed items.
 */
class MaintenanceRepository(
    private val rawEventDao: RawEventDao,
) {

    /** Clear sent (terminal) sources only; pending/failed and unsynced memos are preserved. */
    suspend fun clearProcessed(): Int = rawEventDao.deleteProcessedSources()

    /** Clear everything, including queued and failed items. Associated
     *  normalized transactions and delivery records cascade from raw_events;
     *  settings and dedup_cache are untouched. */
    suspend fun clearAll(): Int = rawEventDao.deleteAll()
}
