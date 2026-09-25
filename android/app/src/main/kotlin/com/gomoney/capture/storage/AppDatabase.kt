package com.gomoney.capture.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@androidx.room.Database(
    entities = [RawEvent::class, NormalizedTransaction::class, DeliveryRecord::class, DedupCache::class],
    version = 2,
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
                ).addMigrations(MIGRATION_1_2)
                    .build().also { instance = it }
            }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE normalized_transactions ADD COLUMN userMemo TEXT")
                db.execSQL(
                    "ALTER TABLE normalized_transactions ADD COLUMN memoSyncState TEXT NOT NULL DEFAULT 'synced'",
                )
                db.execSQL("ALTER TABLE normalized_transactions ADD COLUMN gomoneyTxnId TEXT")
            }
        }
    }
}
