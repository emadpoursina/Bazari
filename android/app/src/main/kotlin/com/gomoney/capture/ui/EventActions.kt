package com.gomoney.capture.ui

import com.gomoney.capture.sync.SyncEngine
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
    )

    /** Manual retry: FAILED → QUEUED, then expedited sync. */
    suspend fun retry(deliveryId: String): Boolean {
        val requeued = deliveryRepository.requeue(deliveryId) ?: return false
        SyncEngine.enqueueExpedited(context)
        return requeued.state == "queued"
    }

    /** Save a private note locally first, then sync it to the bridge/Go Money. */
    suspend fun saveMemo(transactionId: String, memo: String): Boolean {
        val normalizedMemo = memo.trim().take(MAX_MEMO_LENGTH).ifBlank { null }
        val current = db.normalizedTransactionDao().byId(transactionId) ?: return false
        if (current.userMemo == normalizedMemo && current.memoSyncState == "synced") return true
        if (db.normalizedTransactionDao().updateMemo(transactionId, normalizedMemo) == 0) return false
        SyncEngine.enqueueExpedited(context)
        return true
    }

    /** Clear processed (sent/terminal) rows; pending/failed preserved. */
    suspend fun clearProcessed(): Int = maintenance.clearProcessed()

    /** Clear all rows, including queued and failed items (requires user confirmation in the UI). */
    suspend fun clearAll(): Int = maintenance.clearAll()

    private companion object {
        const val MAX_MEMO_LENGTH = 200
    }
}
