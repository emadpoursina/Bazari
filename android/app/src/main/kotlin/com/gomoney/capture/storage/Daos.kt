package com.gomoney.capture.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface RawEventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: RawEvent)

    @Query("SELECT * FROM raw_events WHERE id = :id")
    suspend fun byId(id: String): RawEvent?

    @Query("SELECT * FROM raw_events ORDER BY capturedAt DESC")
    fun observeAll(): Flow<List<RawEvent>>

    @Query(
        """DELETE FROM raw_events WHERE id IN (
            SELECT sourceEventId FROM delivery_records
            WHERE state = 'sent'
                AND id NOT IN (
                    SELECT id FROM normalized_transactions
                    WHERE memoSyncState = 'pending' OR assignmentSyncState = 'pending'
                )
        )""",
    )
    suspend fun deleteProcessedSources(): Int

    /** Delete every captured event; normalized transactions and delivery
     *  records cascade via their raw_events foreign keys (FR-022). */
    @Query("DELETE FROM raw_events")
    suspend fun deleteAll(): Int
}

@Dao
interface NormalizedTransactionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tx: NormalizedTransaction): Long

    @Upsert
    suspend fun upsert(tx: NormalizedTransaction)

    @Query("SELECT * FROM normalized_transactions WHERE id = :id")
    suspend fun byId(id: String): NormalizedTransaction?

    @Query("SELECT * FROM normalized_transactions WHERE fingerprint = :fingerprint LIMIT 1")
    suspend fun byFingerprint(fingerprint: String): NormalizedTransaction?

    /** The transaction produced by one captured event (005 hold/stamp tests). */
    @Query("SELECT * FROM normalized_transactions WHERE sourceEventId = :sourceEventId LIMIT 1")
    suspend fun bySourceEventId(sourceEventId: String): NormalizedTransaction?

    @Query("UPDATE normalized_transactions SET userMemo = :memo, memoSyncState = 'pending' WHERE id = :id")
    suspend fun updateMemo(id: String, memo: String?): Int

    @Query("UPDATE normalized_transactions SET gomoneyTxnId = :gomoneyTxnId WHERE id = :id")
    suspend fun updateGomoneyTxnId(id: String, gomoneyTxnId: String?)

    @Query(
        """UPDATE normalized_transactions SET memoSyncState = 'synced'
            WHERE id = :id AND COALESCE(userMemo, '') = COALESCE(:memo, '')""",
    )
    suspend fun markMemoSynced(id: String, memo: String?)

    @Query(
        """SELECT normalized_transactions.* FROM normalized_transactions
            INNER JOIN delivery_records ON delivery_records.id = normalized_transactions.id
            WHERE normalized_transactions.memoSyncState = 'pending'
                AND delivery_records.state = 'sent'
            ORDER BY delivery_records.lastAttemptAt ASC, normalized_transactions.id ASC""",
    )
    suspend fun pendingMemos(): List<NormalizedTransaction>

    @Query("SELECT * FROM normalized_transactions ORDER BY txAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<NormalizedTransaction>>

    @Query("SELECT COUNT(*) FROM normalized_transactions")
    suspend fun count(): Int

    // --- notification-engine assignment support (data-model.md §7) ---

    /** Local-first assignment write: set destination/category and mark pending. */
    @Query(
        """UPDATE normalized_transactions
            SET destinationAccountId = :destinationAccountId,
                categoryId = :categoryId,
                assignmentSyncState = 'pending'
            WHERE id = :id""",
    )
    suspend fun updateAssignment(id: String, destinationAccountId: Int?, categoryId: Int?): Int

    /** Clear pending only when the values still match what was pushed. */
    @Query(
        """UPDATE normalized_transactions SET assignmentSyncState = 'synced'
            WHERE id = :id
                AND destinationAccountId IS :destinationAccountId
                AND categoryId IS :categoryId""",
    )
    suspend fun markAssignmentSynced(id: String, destinationAccountId: Int?, categoryId: Int?): Int

    /** Already-delivered transactions whose assignment still needs pushing. */
    @Query(
        """SELECT normalized_transactions.* FROM normalized_transactions
            INNER JOIN delivery_records ON delivery_records.id = normalized_transactions.id
            WHERE normalized_transactions.assignmentSyncState = 'pending'
                AND delivery_records.state = 'sent'
            ORDER BY delivery_records.lastAttemptAt ASC, normalized_transactions.id ASC""",
    )
    suspend fun pendingAssignments(): List<NormalizedTransaction>

    // --- 005-account-currency-sources: bind-time currency stamp (T028) ---

    /**
     * Stamp the bound account's currency + account id onto a source's
     * still-held rows that have NO currency yet (FR-011). Rows with a
     * non-empty currency — including delivered `sent` rows — are never
     * rewritten (FR-008).
     */
    @Query(
        """UPDATE normalized_transactions
            SET currency = :currency, accountId = :accountId
            WHERE sourceId = :sourceId
                AND currency = ''
                AND id IN (SELECT id FROM delivery_records WHERE state = 'held')""",
    )
    suspend fun stampHeldEmptyCurrency(sourceId: String, currency: String, accountId: Int): Int

    /**
     * 005 FR-022: pre-delivery user currency edit (delivery state held/queued/
     * failed only — enforced in EventActions). Local update; no state change,
     * no fingerprint recompute.
     */
    @Query("UPDATE normalized_transactions SET currency = :currency WHERE id = :id")
    suspend fun updateCurrency(id: String, currency: String): Int
}

@Dao
interface DeliveryRecordDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(record: DeliveryRecord)

    @Upsert
    suspend fun upsert(record: DeliveryRecord)

    @Query("SELECT * FROM delivery_records WHERE id = :id")
    suspend fun byId(id: String): DeliveryRecord?

    /** Outbox rows for one captured event (005 hold/stamp tests). */
    @Query("SELECT * FROM delivery_records WHERE sourceEventId = :sourceEventId")
    suspend fun bySourceEventId(sourceEventId: String): List<DeliveryRecord>

    /** Queued or failed rows, oldest first (FR-011 drain order). */
    @Query("SELECT * FROM delivery_records WHERE state IN ('queued', 'failed') ORDER BY lastAttemptAt ASC, id ASC")
    suspend fun pendingForDelivery(): List<DeliveryRecord>

    @Query("SELECT * FROM delivery_records WHERE state IN ('queued', 'failed') ORDER BY lastAttemptAt ASC, id ASC")
    fun observePending(): Flow<List<DeliveryRecord>>

    @Query("SELECT COUNT(*) FROM delivery_records WHERE state IN ('queued','failed','sending') OR (state = 'parsed' AND errorCategory IS NOT NULL)")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM delivery_records WHERE state = 'sent'")
    suspend fun sentCount(): Int

    @Query("SELECT COUNT(*) FROM delivery_records WHERE state = 'failed'")
    suspend fun failedCount(): Int

    @Query("SELECT * FROM delivery_records WHERE state = 'sent' ORDER BY lastAttemptAt DESC LIMIT 1")
    suspend fun lastSent(): DeliveryRecord?

    @Query("SELECT * FROM delivery_records ORDER BY lastAttemptAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<DeliveryRecord>>

    @Query(
        """DELETE FROM delivery_records
            WHERE state = 'sent'
                AND id NOT IN (
                    SELECT id FROM normalized_transactions
                    WHERE memoSyncState = 'pending' OR assignmentSyncState = 'pending'
                )""",
    )
    suspend fun clearSent(): Int

    // --- 005-account-currency-sources: held → queued on bind (T006/T028) ---

    /**
     * Move a source's held rows to `queued` once their transaction has been
     * stamped with the bound account's currency (FR-011); returns the number
     * of rows moved. Rows whose transaction does not carry [currency] stay
     * held (or are simply not present — held rows always have empty currency).
     */
    @Query(
        """UPDATE delivery_records SET state = 'queued'
            WHERE state = 'held'
                AND id IN (
                    SELECT id FROM normalized_transactions
                    WHERE sourceId = :sourceId AND currency = :currency
                )""",
    )
    suspend fun moveHeldToQueuedForSource(sourceId: String, currency: String): Int
}

@Dao
interface DedupCacheDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: DedupCache)

    @Query("SELECT * FROM dedup_cache WHERE fingerprint = :fingerprint LIMIT 1")
    suspend fun byFingerprint(fingerprint: String): DedupCache?
}

@Dao
interface TransactionSourceDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(source: TransactionSource): Long

    @Update
    suspend fun update(source: TransactionSource)

    @Query("SELECT * FROM transaction_sources ORDER BY name ASC")
    fun observeAll(): Flow<List<TransactionSource>>

    @Query("SELECT * FROM transaction_sources ORDER BY name ASC")
    suspend fun all(): List<TransactionSource>

    @Query("SELECT * FROM transaction_sources WHERE enabled = 1")
    suspend fun enabled(): List<TransactionSource>

    @Query("SELECT * FROM transaction_sources WHERE id = :id")
    suspend fun byId(id: String): TransactionSource?

    @Query("SELECT * FROM transaction_sources WHERE identifier = :identifier AND channel = :channel LIMIT 1")
    suspend fun byIdentifierAndChannel(identifier: String, channel: String): TransactionSource?

    @Query("UPDATE transaction_sources SET enabled = :enabled, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean, updatedAt: String): Int

    @Query("DELETE FROM transaction_sources WHERE id = :id")
    suspend fun deleteById(id: String): Int
}

@Dao
interface ParseErrorDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(error: ParseErrorMessage)

    @Query("SELECT * FROM parse_errors ORDER BY occurredAt DESC, id DESC")
    fun observeAll(): Flow<List<ParseErrorMessage>>

    @Query("SELECT * FROM parse_errors ORDER BY occurredAt DESC, id DESC")
    suspend fun all(): List<ParseErrorMessage>

    @Query("SELECT * FROM parse_errors WHERE id = :id")
    suspend fun byId(id: String): ParseErrorMessage?

    @Query("SELECT COUNT(*) FROM parse_errors")
    suspend fun count(): Int

    @Query("DELETE FROM parse_errors WHERE id = :id")
    suspend fun dismiss(id: String): Int

    @Query("UPDATE parse_errors SET sourceId = NULL WHERE sourceId = :sourceId")
    suspend fun clearSource(sourceId: String): Int

    /** Retention: drop entries older than the cutoff (FR-028). */
    @Query("DELETE FROM parse_errors WHERE occurredAt < :cutoffIso")
    suspend fun deleteOlderThan(cutoffIso: String): Int

    /** Retention: keep only the newest [keep] rows (FR-028). */
    @Query(
        """DELETE FROM parse_errors WHERE id IN (
            SELECT id FROM parse_errors ORDER BY occurredAt DESC, id DESC LIMIT -1 OFFSET :keep
        )""",
    )
    suspend fun deleteBeyond(keep: Int): Int
}

@Dao
interface ServerAccountDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(accounts: List<ServerAccount>)

    @Query("DELETE FROM server_accounts")
    suspend fun deleteAll()

    @Transaction
    suspend fun replaceAll(accounts: List<ServerAccount>) {
        deleteAll()
        insertAll(accounts)
    }

    @Query("SELECT * FROM server_accounts ORDER BY label ASC")
    fun observeAll(): Flow<List<ServerAccount>>

    @Query("SELECT * FROM server_accounts ORDER BY label ASC")
    suspend fun all(): List<ServerAccount>

    @Query("SELECT * FROM server_accounts WHERE id = :id")
    suspend fun byId(id: Int): ServerAccount?

    @Query("SELECT COUNT(*) FROM server_accounts")
    suspend fun count(): Int
}

/**
 * Identity-guard storage for 006 FR-003 (contracts/notification-identity.md §2):
 * insert-or-take-over a `pending` row, read by key, commit to the terminal
 * `recorded` state, release (delete) when nothing was persisted, clear-all.
 */
@Dao
interface NotificationCaptureRecordDao {
    /** Insert a fresh claim; -1 when the key is already claimed (race backstop). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(record: NotificationCaptureRecord): Long

    /**
     * Stale take-over (60 s): refresh `updated_at` ONLY while the row is
     * still `pending`, so a concurrently committed `recorded` row can never
     * be reopened (contract rule 2). Returns 1 when the claim was taken.
     */
    @Query(
        """UPDATE notification_capture_records
            SET updated_at = :updatedAt
            WHERE notification_key = :key AND state = 'pending'""",
    )
    suspend fun takeOverPending(key: String, updatedAt: String): Int

    @Query("SELECT * FROM notification_capture_records WHERE notification_key = :key LIMIT 1")
    suspend fun byKey(key: String): NotificationCaptureRecord?

    /** Terminal: the notification produced [eventId] and may never produce another. */
    @Query(
        """UPDATE notification_capture_records
            SET state = 'recorded', event_id = :eventId, updated_at = :updatedAt
            WHERE notification_key = :key""",
    )
    suspend fun commit(key: String, eventId: String, updatedAt: String): Int

    /** Delete the claim so the notification is re-examinable later. */
    @Query("DELETE FROM notification_capture_records WHERE notification_key = :key")
    suspend fun release(key: String): Int

    /** MaintenanceRepository.clearAll(): identity history dies with the events. */
    @Query("DELETE FROM notification_capture_records")
    suspend fun clearAll(): Int

    @Query("SELECT COUNT(*) FROM notification_capture_records")
    suspend fun count(): Int
}

@Dao
interface ServerCategoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(categories: List<ServerCategory>)

    @Query("DELETE FROM server_categories")
    suspend fun deleteAll()

    @Transaction
    suspend fun replaceAll(categories: List<ServerCategory>) {
        deleteAll()
        insertAll(categories)
    }

    @Query("SELECT * FROM server_categories ORDER BY label ASC")
    fun observeAll(): Flow<List<ServerCategory>>

    @Query("SELECT * FROM server_categories ORDER BY label ASC")
    suspend fun all(): List<ServerCategory>

    @Query("SELECT * FROM server_categories WHERE id = :id")
    suspend fun byId(id: Int): ServerCategory?

    @Query("SELECT COUNT(*) FROM server_categories")
    suspend fun count(): Int
}
