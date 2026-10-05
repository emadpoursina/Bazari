package com.gomoney.capture.storage

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.gomoney.capture.model.DeliveryState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@Entity(
    tableName = "normalized_transactions",
    foreignKeys = [
        ForeignKey(
            entity = RawEvent::class,
            parentColumns = ["id"],
            childColumns = ["sourceEventId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sourceEventId"), Index(value = ["fingerprint"], unique = true)],
)
data class LegacyNormalizedTransaction(
    @PrimaryKey val id: String,
    val sourceEventId: String,
    val source: String,
    val bank: String,
    val accountHint: String,
    val type: String,
    val amountMinor: Long,
    val currency: String,
    val txAt: String,
    val description: String,
    val rawTextRef: String,
    val fingerprint: String,
    val parserName: String,
    val confidence: String,
)

@Dao
interface LegacyNormalizedTransactionDao {
    @Insert
    suspend fun insert(tx: LegacyNormalizedTransaction)
}

@Dao
interface LegacyRawEventDao {
    @Insert
    suspend fun insert(event: RawEvent)
}

@Database(
    entities = [RawEvent::class, LegacyNormalizedTransaction::class, DeliveryRecord::class, DedupCache::class],
    version = 1,
    exportSchema = false,
)
abstract class LegacyAppDatabase : RoomDatabase() {
    abstract fun rawEventDao(): LegacyRawEventDao
    abstract fun normalizedTransactionDao(): LegacyNormalizedTransactionDao
}

/**
 * v2 shape of `normalized_transactions` (with the memo columns added by
 * MIGRATION_1_2), used to exercise the notification-engine v2 → v3 migration
 * (T012).
 */
@Entity(
    tableName = "normalized_transactions",
    foreignKeys = [
        ForeignKey(
            entity = RawEvent::class,
            parentColumns = ["id"],
            childColumns = ["sourceEventId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sourceEventId"), Index(value = ["fingerprint"], unique = true)],
)
data class LegacyV2NormalizedTransaction(
    @PrimaryKey val id: String,
    val sourceEventId: String,
    val source: String,
    val bank: String,
    val accountHint: String,
    val type: String,
    val amountMinor: Long,
    val currency: String,
    val txAt: String,
    val description: String,
    val rawTextRef: String,
    val fingerprint: String,
    val parserName: String,
    val confidence: String,
    val userMemo: String? = null,
    @ColumnInfo(defaultValue = "'synced'")
    val memoSyncState: String = "synced",
    val gomoneyTxnId: String? = null,
)

@Dao
interface LegacyV2NormalizedTransactionDao {
    @Insert
    suspend fun insert(tx: LegacyV2NormalizedTransaction)
}

@Database(
    entities = [RawEvent::class, LegacyV2NormalizedTransaction::class, DeliveryRecord::class, DedupCache::class],
    version = 2,
    exportSchema = false,
)
abstract class LegacyV2AppDatabase : RoomDatabase() {
    abstract fun rawEventDao(): LegacyRawEventDao
    abstract fun normalizedTransactionDao(): LegacyV2NormalizedTransactionDao
}

@RunWith(RobolectricTestRunner::class)
class AppDatabaseMigrationTest {

    @Test
    fun `version one transactions survive memo migration with empty memo state`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "memo-migration-${UUID.randomUUID()}.db"
        val eventId = "migration-event"
        val transactionId = "migration-transaction"
        val timestamp = "2026-09-24T20:31:22+03:30"

        val v1 = Room.databaseBuilder(context, LegacyAppDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
        v1.rawEventDao().insert(
            RawEvent(eventId, "notification", "pkg", null, "Bank", "message", timestamp, timestamp),
        )
        v1.normalizedTransactionDao().insert(
            LegacyNormalizedTransaction(
                id = transactionId,
                sourceEventId = eventId,
                source = "notification",
                bank = "mellat",
                accountHint = "****1234",
                type = "expense",
                amountMinor = 500_000,
                currency = "IRR",
                txAt = timestamp,
                description = "Card purchase",
                rawTextRef = eventId,
                fingerprint = "a".repeat(64),
                parserName = "MellatParser",
                confidence = "HIGH",
            ),
        )
        v1.close()

        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .allowMainThreadQueries()
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
            )
            .build()
        try {
            val transaction = migrated.normalizedTransactionDao().byId(transactionId)
            assertNotNull(transaction)
            assertEquals(500_000L, transaction?.amountMinor)
            assertEquals(null, transaction?.userMemo)
            assertEquals("synced", transaction?.memoSyncState)
            assertEquals(null, transaction?.gomoneyTxnId)
            // notification-engine columns exist with their defaults (§7)
            assertEquals(null, transaction?.sourceId)
            assertEquals(null, transaction?.destinationAccountId)
            assertEquals("synced", transaction?.assignmentSyncState)
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    /** T012: a v2 database migrates to v3 adding the notification-engine tables/columns. */
    @Test
    fun `version two migrates to v3 adding source and catalog tables`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "notification-engine-migration-${UUID.randomUUID()}.db"
        val eventId = "v2-event"
        val transactionId = "v2-transaction"
        val timestamp = "2026-09-24T20:31:22+03:30"

        val v2 = Room.databaseBuilder(context, LegacyV2AppDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
        v2.rawEventDao().insert(
            RawEvent(eventId, "notification", "pkg", null, "Bank", "message", timestamp, timestamp),
        )
        v2.normalizedTransactionDao().insert(
            LegacyV2NormalizedTransaction(
                id = transactionId,
                sourceEventId = eventId,
                source = "notification",
                bank = "mellat",
                accountHint = "****1234",
                type = "expense",
                amountMinor = 500_000,
                currency = "IRR",
                txAt = timestamp,
                description = "Card purchase",
                rawTextRef = eventId,
                fingerprint = "b".repeat(64),
                parserName = "MellatParser",
                confidence = "HIGH",
                userMemo = "coffee",
                memoSyncState = "pending",
            ),
        )
        v2.close()

        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .allowMainThreadQueries()
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
            )
            .build()
        try {
            val transaction = migrated.normalizedTransactionDao().byId(transactionId)
            assertNotNull(transaction)
            // v2 columns preserved...
            assertEquals(500_000L, transaction?.amountMinor)
            assertEquals("coffee", transaction?.userMemo)
            assertEquals("pending", transaction?.memoSyncState)
            // ...and v3 defaults applied.
            assertEquals(null, transaction?.sourceId)
            assertEquals(null, transaction?.accountId)
            assertEquals(null, transaction?.destinationAccountId)
            assertEquals(null, transaction?.categoryId)
            assertEquals("synced", transaction?.assignmentSyncState)

            // New tables exist and are writable (migration created them).
            assertEquals(0, migrated.transactionSourceDao().all().size)
            assertEquals(0, migrated.parseErrorDao().all().size)
            assertEquals(0, migrated.serverAccountDao().all().size)
            assertEquals(0, migrated.serverCategoryDao().all().size)
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    /**
     * T007 (005): a v3 database migrates to v4 with a no-op migration and keeps
     * its rows; the new `held` delivery state (stored as TEXT in the existing
     * column) round-trips through the 005 state machine.
     */
    @Test
    fun `version three migrates to v4 preserving rows and the held state`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "acs-migration-${UUID.randomUUID()}.db"
        val eventId = "v3-event"
        val transactionId = "v3-transaction"
        val timestamp = "2026-09-24T20:31:22+03:30"

        val v3 = Room.databaseBuilder(context, LegacyV3AppDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
        v3.rawEventDao().insert(
            RawEvent(eventId, "notification", "pkg", null, "Bank", "message", timestamp, timestamp),
        )
        v3.normalizedTransactionDao().insert(
            NormalizedTransaction(
                id = transactionId,
                sourceEventId = eventId,
                source = "notification",
                bank = "user",
                accountHint = "com.example.bank",
                type = "expense",
                amountMinor = 500_000,
                currency = "", // 005: empty currency is valid TEXT
                txAt = timestamp,
                description = "Card purchase",
                rawTextRef = eventId,
                fingerprint = "c".repeat(64),
                parserName = "UserSourceParser",
                confidence = "HIGH",
            ),
        )
        v3.deliveryRecordDao().insert(
            DeliveryRecord(
                id = transactionId,
                state = "held", // 005 wire value, valid in v3 TEXT
                attempts = 0,
                lastAttemptAt = null,
                nextRetryAt = null,
                errorCategory = null,
                errorDetail = null,
                sourceEventId = eventId,
            ),
        )
        v3.close()

        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .allowMainThreadQueries()
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
            )
            .build()
        try {
            val transaction = migrated.normalizedTransactionDao().byId(transactionId)
            assertNotNull(transaction)
            assertEquals("", transaction?.currency)
            assertEquals(500_000L, transaction?.amountMinor)

            val record = migrated.deliveryRecordDao().byId(transactionId)
            assertNotNull(record)
            assertEquals("held", record?.state)
            // The v4 state machine reads it back as HELD.
            assertEquals(DeliveryState.HELD, record?.deliveryStateOf())
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    /**
     * 006 T012: a v4 database migrates to v5 creating the
     * `notification_capture_records` identity table while existing rows and
     * the v4 state machine survive untouched.
     */
    @Test
    fun `version four migrates to v5 creating notification capture records`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "scan-identity-migration-${UUID.randomUUID()}.db"
        val eventId = "v4-event"
        val timestamp = "2026-10-05T10:00:00+03:30"

        val v4 = Room.databaseBuilder(context, LegacyV4AppDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
        v4.rawEventDao().insert(
            RawEvent(eventId, "notification", "pkg", null, null, "message", timestamp, timestamp),
        )
        v4.close()

        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .allowMainThreadQueries()
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
            )
            .build()
        try {
            // The migration created the identity table: it exists, is empty
            // and is writable with the exact v5 column shape.
            val dao = migrated.notificationCaptureRecordDao()
            assertEquals(0, dao.count())
            val inserted = dao.insertIfAbsent(
                NotificationCaptureRecord(
                    notificationKey = "pkg|1|tag|0",
                    eventId = null,
                    state = NotificationCaptureRecord.STATE_PENDING,
                    updatedAt = timestamp,
                ),
            )
            assertTrue(inserted != -1L)
            val row = dao.byKey("pkg|1|tag|0")
            assertNotNull(row)
            assertEquals(NotificationCaptureRecord.STATE_PENDING, row?.state)
            assertEquals("pkg|1|tag|0", row?.notificationKey)

            // Pre-existing v4 data survived the migration.
            assertNotNull(migrated.rawEventDao().byId(eventId))
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }
}

/**
 * v3 shape of the schema (same entities as v4 — 005 changed no tables), used
 * to exercise the 005 v3 → v4 semantic migration (T007).
 */
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
    ],
    version = 3,
    exportSchema = false,
)
abstract class LegacyV3AppDatabase : RoomDatabase() {
    abstract fun rawEventDao(): LegacyRawEventDao
    abstract fun normalizedTransactionDao(): NormalizedTransactionDao
    abstract fun deliveryRecordDao(): LegacyV3DeliveryRecordDao
}

@Dao
interface LegacyV3DeliveryRecordDao {
    @Insert
    suspend fun insert(record: DeliveryRecord)
}

/**
 * v4 shape of the schema (same entities as v5 minus the 006 identity guard),
 * used to exercise the 006 v4 → v5 migration (T012).
 */
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
    ],
    version = 4,
    exportSchema = false,
)
abstract class LegacyV4AppDatabase : RoomDatabase() {
    abstract fun rawEventDao(): RawEventDao
    abstract fun normalizedTransactionDao(): NormalizedTransactionDao
    abstract fun deliveryRecordDao(): DeliveryRecordDao
}
