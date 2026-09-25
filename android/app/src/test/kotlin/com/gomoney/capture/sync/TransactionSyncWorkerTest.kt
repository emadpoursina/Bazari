package com.gomoney.capture.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gomoney.capture.model.DeliveryState
import com.gomoney.capture.model.ErrorCategory
import com.gomoney.capture.storage.AppDatabase
import com.gomoney.capture.storage.DedupRepository
import com.gomoney.capture.storage.DeliveryRepository
import com.gomoney.capture.storage.NormalizedTransaction
import com.gomoney.capture.storage.RawEvent
import com.gomoney.capture.storage.ServerConfiguration
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * TransactionSyncWorker drain tests (T024/T031/T036): response mapping →
 * state transitions with in-memory Room, offline queue → auto-drain.
 */
@RunWith(RobolectricTestRunner::class)
class TransactionSyncWorkerTest {

    private lateinit var db: AppDatabase
    private lateinit var deliveryRepository: DeliveryRepository
    private lateinit var dedupRepository: DedupRepository
    private lateinit var server: MockWebServer
    private lateinit var config: ServerConfiguration

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        deliveryRepository = DeliveryRepository(db.deliveryRecordDao())
        dedupRepository = DedupRepository(db.dedupCacheDao())

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

    private suspend fun seedQueued(eventId: String, fingerprint: String, description: String = "Card purchase"): NormalizedTransaction {
        db.rawEventDao().insert(
            RawEvent(eventId, "notification", "pkg", null, null, "text", "2026-09-23T20:31:22+03:30", "2026-09-23T20:31:22+03:30"),
        )
        val tx = NormalizedTransaction(
            id = eventId,
            sourceEventId = eventId,
            source = "notification",
            bank = "mellat",
            accountHint = "****1234",
            type = "expense",
            amountMinor = 500_000L,
            currency = "IRR",
            txAt = "2026-09-23T20:31:22+03:30",
            description = description,
            rawTextRef = eventId,
            fingerprint = fingerprint,
            parserName = "MellatParser",
            confidence = "HIGH",
        )
        db.normalizedTransactionDao().insert(tx)
        deliveryRepository.createQueued(eventId, eventId)
        return tx
    }

    /** Offline capture stays queued; server back → drained to SENT. */
    @Test
    fun `drain marks sent on 201`() = runTest {
        seedQueued("e1", "a".repeat(64))
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"status":"created","gomoneyTxnId":7}"""))

        SyncEngine.drain(db, config, deliveryRepository, dedupRepository)

        assertEquals(0, db.deliveryRecordDao().pendingForDelivery().size)
        assertEquals("sent", db.deliveryRecordDao().byId("e1")?.state)
        assertEquals("sent", dedupRepository.lookup("a".repeat(64))?.outcome)
    }

    /** 200 duplicate ack → state=sent + errorCategory=duplicate (§3 rules). */
    @Test
    fun `drain maps duplicate ack to sent with duplicate category`() = runTest {
        seedQueued("e2", "b".repeat(64))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"duplicate","gomoneyTxnId":3}"""))

        SyncEngine.drain(db, config, deliveryRepository, dedupRepository)

        val record = db.deliveryRecordDao().byId("e2")
        assertEquals("sent", record?.state)
        assertEquals("duplicate", record?.errorCategory)
        assertEquals("duplicate", dedupRepository.lookup("b".repeat(64))?.outcome)
    }

    /** 400 → validation_error FAILED, not retried automatically. */
    @Test
    fun `drain maps 400 to failed validation_error`() = runTest {
        seedQueued("e3", "c".repeat(64))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"validation","details":["unmapped account"]}"""))

        SyncEngine.drain(db, config, deliveryRepository, dedupRepository)

        val record = db.deliveryRecordDao().byId("e3")
        assertEquals("failed", record?.state)
        assertEquals("validation_error", record?.errorCategory)
    }

    /** 502 → network_error FAILED (WorkManager retries later, FR-011). */
    @Test
    fun `drain maps 502 to failed network_error`() = runTest {
        seedQueued("e4", "d".repeat(64))
        server.enqueue(MockResponse().setResponseCode(502).setBody("""{"error":"gomoney_unreachable"}"""))

        SyncEngine.drain(db, config, deliveryRepository, dedupRepository)

        val record = db.deliveryRecordDao().byId("e4")
        assertEquals("failed", record?.state)
        assertEquals("network_error", record?.errorCategory)
    }

    /** Local dedup cache prevents re-send after restart (US3, FR-030). */
    @Test
    fun `local dedup cache short-circuits without network`() = runTest {
        seedQueued("e5", "e".repeat(64))
        dedupRepository.record("e".repeat(64), "duplicate")

        SyncEngine.drain(db, config, deliveryRepository, dedupRepository)

        assertEquals(0, server.requestCount) // no network call at all
        val record = db.deliveryRecordDao().byId("e5")
        assertEquals("sent", record?.state)
        assertEquals("duplicate", record?.errorCategory)
    }

    /** Bulk drain used when ≥5 items queued (T030). */
    @Test
    fun `bulk drain maps per item results`() = runTest {
        for (i in 0 until 5) {
            seedQueued("bulk-$i", "${"f".repeat(63)}$i", "Purchase $i")
        }
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(200).setBody(
                    """{"results":[{"id":"0","status":"created"},{"id":"1","status":"duplicate"},""" +
                        """{"id":"2","status":"created"},{"id":"3","status":"created"},{"id":"4","status":"created"}]}""",
                )
        }

        SyncEngine.drain(db, config, deliveryRepository, dedupRepository)

        assertEquals("sent", db.deliveryRecordDao().byId("bulk-0")?.state)
        assertEquals("sent", db.deliveryRecordDao().byId("bulk-1")?.state)
    }

    /** Away-from-home outage auto-recovers: FAILED network_error drains when home. */
    @Test
    fun `failed network_error auto-retries on next drain`() = runTest {
        seedQueued("retry-1", "a".repeat(63) + "1")
        deliveryRepository.transition("retry-1", DeliveryState.SENDING)
        deliveryRepository.transition(
            "retry-1",
            DeliveryState.FAILED,
            ErrorCategory.NETWORK_ERROR,
            "network unreachable",
        )
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"status":"created","gomoneyTxnId":9}"""))

        SyncEngine.drain(db, config, deliveryRepository, dedupRepository)

        assertEquals("sent", db.deliveryRecordDao().byId("retry-1")?.state)
        assertEquals(1, server.requestCount)
    }

    /** Validation failures need manual fix — never auto-retried. */
    @Test
    fun `failed validation_error stays failed without network call`() = runTest {
        seedQueued("noretry-1", "a".repeat(63) + "2")
        deliveryRepository.transition("noretry-1", DeliveryState.SENDING)
        deliveryRepository.transition(
            "noretry-1",
            DeliveryState.FAILED,
            ErrorCategory.VALIDATION_ERROR,
            "validation unmapped account",
        )
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"status":"created"}"""))

        SyncEngine.drain(db, config, deliveryRepository, dedupRepository)

        assertEquals("failed", db.deliveryRecordDao().byId("noretry-1")?.state)
        assertEquals(0, server.requestCount)
    }
}
