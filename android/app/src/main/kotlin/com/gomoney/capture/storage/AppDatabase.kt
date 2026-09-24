package com.gomoney.capture.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@androidx.room.Database(
    entities = [RawEvent::class, NormalizedTransaction::class, DeliveryRecord::class, DedupCache::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun rawEventDao(): RawEventDao
    abstract fun normalizedTransactionDao(): NormalizedTransactionDao
    abstract fun deliveryRecordDao(): DeliveryRecordDao
    abstract fun dedupCacheDao(): DedupCacheDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "gomoney-capture.db",
                ).build().also { instance = it }
            }
    }
}
