package com.gomoney.capture.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gomoney.capture.storage.AppDatabase
import com.gomoney.capture.storage.DedupRepository
import com.gomoney.capture.storage.DeliveryRecord
import com.gomoney.capture.storage.DeliveryRepository
import com.gomoney.capture.storage.NormalizedTransaction
import com.gomoney.capture.storage.RawEvent
import com.gomoney.capture.storage.ServerConfiguration
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Assignment sync tests (T040; FR-019/020, SC-005): local-first write, `pending`
 * state, offline retry, and no duplicate transaction when updating an
 * already-delivered capture.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AssignmentSyncTest {

    private lateinit var db: AppDatabase
    private lateinit var server: MockWebServer
    private lateinit var config: ServerConfiguration
    private val txId = "11111111-1111-1111-1111-111111111111"
    private val eventId = "22222222-2222-2222-2222-222222222222"
    private val timestamp = "2026-09-24T08:00:00+03:30"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        server = MockWebServer()
        server.start()
        config = ServerConfiguration(
            serverUrl = server.url("/").toString().removeSuffix("/"),
            bearerToken = "test-token",
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    private suspend fun seedDeliveredTransaction() {
        db.rawEventDao().insert(
            RawEvent(eventId, "notification", "com.example.bank", null, "Bank", "text", timestamp, timestamp),
        )
        db.normalizedTransactionDao().insert(
            NormalizedTransaction(
                id = txId,
                sourceEventId = eventId,
                source = "notification",
                bank = "user",
                accountHint = "com.example.bank",
                type = "expense",
                amountMinor = 500_000L,
                currency = "IRR",
                txAt = timestamp,
                description = "Card purchase",
                rawTextRef = eventId,
                fingerprint = "c".repeat(64),
                parserName = "UserSourceParser",
                confidence = "HIGH",
                accountId = 3,
                gomoneyTxnId = "12",
            ),
        )
        db.deliveryRecordDao().insert(
            DeliveryRecord(
                id = txId,
                state = "sent",
                attempts = 1,
                lastAttemptAt = timestamp,
                nextRetryAt = null,
                errorCategory = null,
                errorDetail = null,
                sourceEventId = eventId,
            ),
        )
    }

    private suspend fun drain(config: ServerConfiguration = this.config) = SyncEngine.drain(
        db = db,
        config = config,
        deliveryRepository = DeliveryRepository(db.deliveryRecordDao()),
        dedupRepository = DedupRepository(db.dedupCacheDao()),
    )

    @Test
    fun `local write persists the choice before any delivery attempt and marks pending`() = runTest {
        seedDeliveredTransaction()

        val updated = db.normalizedTransactionDao().updateAssignment(txId, 7, 5)

        assertEquals(1, updated)
        val tx = db.normalizedTransactionDao().byId(txId)!!
        assertEquals(7, tx.destinationAccountId)
        assertEquals(5, tx.categoryId)
        assertEquals("pending", tx.assignmentSyncState)
        // No bridge traffic yet — the write is strictly local-first (FR-019).
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `pending assignment syncs to the same transaction without creating another`() = runTest {
        seedDeliveredTransaction()
        db.normalizedTransactionDao().updateAssignment(txId, 7, 5)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"status":"updated","gomoneyTxnId":"12"}"""),
        )

        drain()

        val tx = db.normalizedTransactionDao().byId(txId)!!
        assertEquals("synced", tx.assignmentSyncState)
        // The assignment went to the update endpoint, never a second create.
        assertEquals(1, server.requestCount)
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/v1/transactions/assignment", request.path)
        assertEquals(7, org.json.JSONObject(request.body.readUtf8()).getInt("destinationAccountId"))
        assertEquals(1, db.normalizedTransactionDao().count())
    }

    @Test
    fun `assignment stays pending while the bridge is unreachable and syncs on retry`() = runTest {
        seedDeliveredTransaction()
        db.normalizedTransactionDao().updateAssignment(txId, 7, 5)

        val dead = MockWebServer()
        val deadUrl = dead.url("/").toString().removeSuffix("/")
        dead.shutdown()

        drain(config.copy(serverUrl = deadUrl))
        assertEquals("pending", db.normalizedTransactionDao().byId(txId)!!.assignmentSyncState)

        // Bridge reachable again → the pending choice is applied automatically.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"status":"updated","gomoneyTxnId":"12"}"""),
        )
        drain()
        assertEquals("synced", db.normalizedTransactionDao().byId(txId)!!.assignmentSyncState)
    }
}
