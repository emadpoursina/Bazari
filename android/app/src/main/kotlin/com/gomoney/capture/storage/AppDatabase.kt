package com.gomoney.capture.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        RawEvent::class,
        NormalizedTransaction::class,
        DeliveryRecord::class,
        DedupCache::class,
        TransactionSource::class,
        ParseErrorMessage::class,
        ServerAccount::class,
        ServerCategory::class,
        NotificationCaptureRecord::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun rawEventDao(): RawEventDao
    abstract fun normalizedTransactionDao(): NormalizedTransactionDao
    abstract fun deliveryRecordDao(): DeliveryRecordDao
    abstract fun dedupCacheDao(): DedupCacheDao
    abstract fun transactionSourceDao(): TransactionSourceDao
    abstract fun parseErrorDao(): ParseErrorDao
    abstract fun serverAccountDao(): ServerAccountDao
    abstract fun serverCategoryDao(): ServerCategoryDao
    abstract fun notificationCaptureRecordDao(): NotificationCaptureRecordDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "gomoney-capture.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build().also { instance = it }
            }

        /**
         * v4 → v5 (006-notification-scan-button data-model.md §1): adds the
         * per-notification identity guard table. Pure CREATE TABLE — no
         * existing table changes, `exportSchema = false` unchanged.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `notification_capture_records` (
                        `notification_key` TEXT NOT NULL,
                        `event_id` TEXT,
                        `state` TEXT NOT NULL,
                        `updated_at` TEXT NOT NULL,
                        PRIMARY KEY(`notification_key`)
                    )""",
                )
            }
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

        /**
         * v3 → v4 (005-account-currency-sources data-model.md §Schema migration):
         * a semantic-only bump. `held` is a new wire value in the existing
         * TEXT `delivery_records.state` column and empty-string currency is
         * already valid TEXT on `normalized_transactions.currency`, so no
         * table rewrite is required. Former-bank source seeding is idempotent
         * and runs from app startup (TransactionSourceRepository.seedDefaults),
         * not from SQL.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // No-op: held delivery state and empty-string currency live in
                // existing TEXT columns.
            }
        }

        /**
         * v2 → v3 (data-model.md §1/§3/§5/§6/§7): adds the user-source, parse
         * error, and catalog-cache tables and extends `normalized_transactions`
         * with the assignment columns.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `transaction_sources` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `identifier` TEXT NOT NULL,
                        `channel` TEXT NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `template` TEXT NOT NULL,
                        `incomeKeywords` TEXT NOT NULL,
                        `expenseKeywords` TEXT NOT NULL,
                        `boundAccountId` INTEGER,
                        `boundAccountLabel` TEXT,
                        `createdAt` TEXT NOT NULL,
                        `updatedAt` TEXT NOT NULL,
                        PRIMARY KEY(`id`)
                    )""",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_transaction_sources_identifier_channel` " +
                        "ON `transaction_sources` (`identifier`, `channel`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_transaction_sources_enabled` " +
                        "ON `transaction_sources` (`enabled`)",
                )

                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `parse_errors` (
                        `id` TEXT NOT NULL,
                        `sourceId` TEXT,
                        `sourceEventId` TEXT NOT NULL,
                        `failureReason` TEXT NOT NULL,
                        `occurredAt` TEXT NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`sourceEventId`) REFERENCES `raw_events`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )""",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_parse_errors_sourceEventId` " +
                        "ON `parse_errors` (`sourceEventId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_parse_errors_occurredAt` " +
                        "ON `parse_errors` (`occurredAt`)",
                )

                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `server_accounts` (
                        `id` INTEGER NOT NULL,
                        `label` TEXT NOT NULL,
                        `currency` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `isDefault` INTEGER NOT NULL,
                        `refreshedAt` TEXT NOT NULL,
                        PRIMARY KEY(`id`)
                    )""",
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `server_categories` (
                        `id` INTEGER NOT NULL,
                        `label` TEXT NOT NULL,
                        `refreshedAt` TEXT NOT NULL,
                        PRIMARY KEY(`id`)
                    )""",
                )

                db.execSQL("ALTER TABLE normalized_transactions ADD COLUMN sourceId TEXT")
                db.execSQL("ALTER TABLE normalized_transactions ADD COLUMN accountId INTEGER")
                db.execSQL("ALTER TABLE normalized_transactions ADD COLUMN destinationAccountId INTEGER")
                db.execSQL("ALTER TABLE normalized_transactions ADD COLUMN categoryId INTEGER")
                db.execSQL(
                    "ALTER TABLE normalized_transactions ADD COLUMN assignmentSyncState TEXT NOT NULL DEFAULT 'synced'",
                )
            }
        }
    }
}
