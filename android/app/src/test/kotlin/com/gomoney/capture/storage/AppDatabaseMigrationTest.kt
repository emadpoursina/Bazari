package com.gomoney.capture.storage

import android.content.Context
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
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .build()
        try {
            val transaction = migrated.normalizedTransactionDao().byId(transactionId)
            assertNotNull(transaction)
            assertEquals(500_000L, transaction?.amountMinor)
            assertEquals(null, transaction?.userMemo)
            assertEquals("synced", transaction?.memoSyncState)
            assertEquals(null, transaction?.gomoneyTxnId)
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }
}
