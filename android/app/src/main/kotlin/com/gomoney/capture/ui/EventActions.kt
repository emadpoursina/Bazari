package com.gomoney.capture.ui

import com.gomoney.capture.capture.SyncEngine
import com.gomoney.capture.storage.AppDatabase
import com.gomoney.capture.storage.DeliveryRepository
import com.gomoney.capture.storage.MaintenanceRepository
import com.gomoney.capture.storage.SettingsRepository

/**
 * Event actions (US4, T040, FR-022): manual retry requeues FAILED → QUEUED
 * and triggers an expedited sync (US2.4); clear removes ONLY sent/terminal
 * rows, preserving pending/failed (spec edge case).
 */
class EventActions(
    private val context: android.content.Context,
    private val db: AppDatabase,
) {

    private val deliveryRepository = DeliveryRepository(db.deliveryRecordDao())
    private val maintenance = MaintenanceRepository(
        rawEventDao = db.rawEventDao(),
        normalizedTransactionDao = db.normalizedTransactionDao(),
        deliveryRecordDao = db.deliveryRecordDao(),
    )

    /** Manual retry: FAILED → QUEUED, then expedited sync. */
    suspend fun retry(deliveryId: String): Boolean {
        val requeued = deliveryRepository.requeue(deliveryId) ?: return false
        SyncEngine.enqueueExpedited(context)
        return requeued.state == "queued"
    }

    /** Clear processed (sent/terminal) rows; pending/failed preserved. */
    suspend fun clearProcessed(): Int = maintenance.clearProcessed()
}
