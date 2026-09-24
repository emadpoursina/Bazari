package com.gomoney.capture.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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

    @Query("DELETE FROM raw_events WHERE id IN (SELECT sourceEventId FROM delivery_records WHERE state = 'sent')")
    suspend fun deleteProcessedSources()
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

    @Query("SELECT * FROM normalized_transactions ORDER BY txAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<NormalizedTransaction>>

    @Query("SELECT COUNT(*) FROM normalized_transactions")
    suspend fun count(): Int
}

@Dao
interface DeliveryRecordDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(record: DeliveryRecord)

    @Upsert
    suspend fun upsert(record: DeliveryRecord)

    @Query("SELECT * FROM delivery_records WHERE id = :id")
    suspend fun byId(id: String): DeliveryRecord?

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

    @Query("DELETE FROM delivery_records WHERE state = 'sent'")
    suspend fun clearSent(): Int
}

@Dao
interface DedupCacheDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: DedupCache)

    @Query("SELECT * FROM dedup_cache WHERE fingerprint = :fingerprint LIMIT 1")
    suspend fun byFingerprint(fingerprint: String): DedupCache?
}
