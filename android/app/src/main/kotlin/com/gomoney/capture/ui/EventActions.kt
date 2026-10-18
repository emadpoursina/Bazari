package com.gomoney.capture.ui

import com.gomoney.capture.sync.SyncEngine
import com.gomoney.capture.storage.AppDatabase
import com.gomoney.capture.storage.DeliveryRepository
import com.gomoney.capture.storage.MaintenanceRepository
import com.gomoney.capture.storage.SettingsRepository
import com.gomoney.capture.storage.deliveryStateOf

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
        // 006 research R8: clearAll() also wipes the notification identity
        // rows; clearProcessed() deliberately does not touch them.
        notificationCaptureRecordDao = db.notificationCaptureRecordDao(),
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

    /**
     * Set/change a captured transaction's destination account and category
     * (US3, FR-017/018/019/020): persist locally first, mark pending, then
     * trigger an expedited sync so it reaches the same Go Money transaction.
     */
    suspend fun saveAssignment(transactionId: String, destinationAccountId: Int?, categoryId: Int?): Boolean {
        val current = db.normalizedTransactionDao().byId(transactionId) ?: return false
        if (current.destinationAccountId == destinationAccountId &&
            current.categoryId == categoryId &&
            current.assignmentSyncState == "synced"
        ) {
            return true
        }
        if (db.normalizedTransactionDao().updateAssignment(transactionId, destinationAccountId, categoryId) == 0) {
            return false
        }
        SyncEngine.enqueueExpedited(context)
        return true
    }

    /**
     * 005 FR-022: edit a captured transaction's currency on the transaction
     * screen ONLY while it is local and not yet delivered (`held`, `queued`,
     * `failed`). `sending`/`sent` rows are read-only and rejected here.
     * A `held` row stays held (no held → queued move); assignment is
     * untouched and currency is not part of the fingerprint.
     */
    suspend fun saveCurrency(transactionId: String, currency: String): Boolean {
        val normalized = currency.trim()
        if (normalized.isEmpty()) return false
        val current = db.normalizedTransactionDao().byId(transactionId) ?: return false
        val state = db.deliveryRecordDao().byId(transactionId)?.deliveryStateOf()
        if (!currencyEditable(state?.name?.lowercase())) {
            return false
        }
        if (current.currency == normalized) return true
        if (db.normalizedTransactionDao().updateCurrency(transactionId, normalized) == 0) return false
        // Queued rows need an expedited sync to deliver the corrected value;
        // held rows stay held until the source is bound.
        if (state == com.gomoney.capture.model.DeliveryState.QUEUED) {
            SyncEngine.enqueueExpedited(context)
        }
        return true
    }

    /** Clear processed (sent/terminal) rows; pending/failed preserved. */
    suspend fun clearProcessed(): Int = maintenance.clearProcessed()

    /** Clear all rows, including queued and failed items (requires user confirmation in the UI). */
    suspend fun clearAll(): Int = maintenance.clearAll()

    /**
     * 007 FR-005: delete only the selected events (raw cascade).
     * Returns the deleted row count; empty selection is a no-op.
     */
    suspend fun deleteSelected(ids: Set<String>): Int {
        if (ids.isEmpty()) return 0
        return maintenance.deleteEvents(ids)
    }

    /**
     * 007 FR-004: sync only the selected events. Selected FAILED rows are
     * requeued to QUEUED; then a scoped drain pushes only [ids] (plus their
     * pending memos/assignments). Unselected rows are never touched.
     * Returns the number of selected failures requeued.
     */
    suspend fun syncSelected(ids: Set<String>): Int {
        if (ids.isEmpty()) return 0
        var requeued = 0
        for (id in ids) {
            if (deliveryRepository.requeue(id) != null) requeued++
        }
        val settings = SettingsRepository(context)
        val configs = runCatching { settings.currentConfigs() }.getOrDefault(emptyList())
        if (configs.any { it.serverUrl.isNotBlank() }) {
            runCatching {
                SyncEngine.drainIds(
                    db,
                    configs,
                    deliveryRepository,
                    com.gomoney.capture.storage.DedupRepository(db.dedupCacheDao()),
                    ids,
                )
            }
        }
        // Follow-up for any memo/assignment still pending on the selection.
        runCatching { SyncEngine.enqueueExpedited(context) }
        return requeued
    }

    // 008 R2: single source of truth for the note limit, shared with the
    // EventDetailScreen note editor (T002).
    internal companion object {
        const val MAX_MEMO_LENGTH = 200
    }
}
