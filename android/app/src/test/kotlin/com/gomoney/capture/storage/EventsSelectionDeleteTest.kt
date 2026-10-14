package com.gomoney.capture.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gomoney.capture.model.DeliveryState
import com.gomoney.capture.model.ErrorCategory
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 007 T011: selective delete removes only the chosen events (cascade),
 * empty selection is a no-op.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EventsSelectionDeleteTest {

    private lateinit var db: AppDatabase
    private lateinit var deliveryRepository: DeliveryRepository
    private lateinit var maintenance: MaintenanceRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        deliveryRepository = DeliveryRepository(db.deliveryRecordDao())
        maintenance = MaintenanceRepository(rawEventDao = db.rawEventDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seed(eventId: String) {
        db.rawEventDao().insert(
            RawEvent(eventId, "notification", "pkg", null, null, "text $eventId", "2026-09-23T20:31:22+03:30", "2026-09-23T20:31:22+03:30"),
        )
        db.normalizedTransactionDao().insert(
            NormalizedTransaction(
                id = eventId,
                sourceEventId = eventId,
                source = "notification",
                bank = "mellat",
                accountHint = "****1234",
                type = "expense",
                amountMinor = 1000L,
                currency = "IRR",
                txAt = "2026-09-23T20:31:22+03:30",
                description = "desc",
                rawTextRef = eventId,
                fingerprint = "fp-$eventId",
                parserName = "Test",
                confidence = "HIGH",
            ),
        )
        deliveryRepository.createQueued(eventId, eventId)
    }

    @Test
    fun `deleteEvents removes only selected and cascades`() = runTest {
        seed("ev-1")
        seed("ev-2")
        seed("ev-3")

        val removed = maintenance.deleteEvents(setOf("ev-1"))
        assertEquals(1, removed)

        assertNull(db.rawEventDao().byId("ev-1"))
        assertNull(db.normalizedTransactionDao().byId("ev-1"))
        assertNull(db.deliveryRecordDao().byId("ev-1"))

        assertNotNull(db.rawEventDao().byId("ev-2"))
        assertNotNull(db.rawEventDao().byId("ev-3"))
        assertEquals(2, db.normalizedTransactionDao().count())
    }

    @Test
    fun `deleteEvents with empty set is a no-op`() = runTest {
        seed("ev-1")
        assertEquals(0, maintenance.deleteEvents(emptySet()))
        assertNotNull(db.rawEventDao().byId("ev-1"))
    }

    @Test
    fun `sync requeue scope touches only selected failures`() = runTest {
        seed("fail-1")
        seed("fail-2")
        seed("queue-1")
        for (id in listOf("fail-1", "fail-2")) {
            deliveryRepository.transition(id, DeliveryState.SENDING)
            deliveryRepository.transition(id, DeliveryState.FAILED, ErrorCategory.NETWORK_ERROR)
        }

        // Simulate EventActions.syncSelected requeue loop (no network in test).
        var requeued = 0
        for (id in setOf("fail-1")) {
            if (deliveryRepository.requeue(id) != null) requeued++
        }
        assertEquals(1, requeued)
        assertEquals("queued", db.deliveryRecordDao().byId("fail-1")?.state)
        assertEquals("failed", db.deliveryRecordDao().byId("fail-2")?.state)
        assertEquals("queued", db.deliveryRecordDao().byId("queue-1")?.state)
    }

    @Test
    fun `drainIds with empty selection is a no-op`() = runTest {
        seed("ev-9")
        com.gomoney.capture.sync.SyncEngine.drainIds(
            db,
            emptyList(),
            deliveryRepository,
            DedupRepository(db.dedupCacheDao()),
            emptySet(),
        )
        assertEquals("queued", db.deliveryRecordDao().byId("ev-9")?.state)
    }
}
