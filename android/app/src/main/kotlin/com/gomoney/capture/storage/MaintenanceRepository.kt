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
    private val normalizedTransactionDao: NormalizedTransactionDao,
    private val deliveryRecordDao: DeliveryRecordDao,
) {

    /** Clear sent (terminal) rows only; pending/failed are preserved. */
    suspend fun clearProcessed(): Int {
        val removed = deliveryRecordDao.clearSent()
        rawEventDao.deleteProcessedSources()
        return removed
    }
}
