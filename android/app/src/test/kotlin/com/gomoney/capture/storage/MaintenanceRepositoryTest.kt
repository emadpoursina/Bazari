package com.gomoney.capture.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gomoney.capture.model.ErrorCategory
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** MaintenanceRepository tests (T040): clear processed edge cases. */
@RunWith(RobolectricTestRunner::class)
class MaintenanceRepositoryTest {

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
        maintenance = MaintenanceRepository(
            rawEventDao = db.rawEventDao(),
            notificationCaptureRecordDao = db.notificationCaptureRecordDao(),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seed(eventId: String, text: String = "text"): RawEvent {
        val event = RawEvent(eventId, "notification", "pkg", null, null, text, "2026-09-23T20:31:22+03:30", "2026-09-23T20:31:22+03:30")
        db.rawEventDao().insert(event)
        db.normalizedTransactionDao().insert(
            NormalizedTransaction(
                id = eventId,
                sourceEventId = eventId,
                source = "notification",
                bank = "mellat",
                accountHint = "****1234",
                type = "expense",
                amountMinor = 500_000L,
                currency = "IRR",
                txAt = event.postedAt,
                description = "desc",
                rawTextRef = eventId,
                fingerprint = "fp-" + eventId,
                parserName = "MellatParser",
                confidence = "HIGH",
            ),
        )
        deliveryRepository.createQueued(eventId, eventId)
        return event
    }

    @Test
    fun `clear removes only sent rows preserving pending and failed`() = runTest {
        seed("sent-1")
        seed("pending-1")
        seed("failed-1")

        deliveryRepository.transition("sent-1", com.gomoney.capture.model.DeliveryState.SENDING)
        deliveryRepository.transition("sent-1", com.gomoney.capture.model.DeliveryState.SENT)
        deliveryRepository.transition("failed-1", com.gomoney.capture.model.DeliveryState.SENDING)
        deliveryRepository.transition("failed-1", com.gomoney.capture.model.DeliveryState.FAILED, ErrorCategory.NETWORK_ERROR)

        val removed = maintenance.clearProcessed()
        assertEquals(1, removed)

        val remaining = db.deliveryRecordDao().pendingForDelivery()
        assertEquals(2, remaining.size)
        assertTrue(remaining.any { it.id == "pending-1" })
        assertTrue(remaining.any { it.id == "failed-1" })
    }

    @Test
    fun `clear all removes every event across states and cascades children`() = runTest {
        seed("sent-2")
        seed("pending-2")
        seed("failed-2")

        deliveryRepository.transition("sent-2", com.gomoney.capture.model.DeliveryState.SENDING)
        deliveryRepository.transition("sent-2", com.gomoney.capture.model.DeliveryState.SENT)
        deliveryRepository.transition("failed-2", com.gomoney.capture.model.DeliveryState.SENDING)
        deliveryRepository.transition("failed-2", com.gomoney.capture.model.DeliveryState.FAILED, ErrorCategory.NETWORK_ERROR)

        val removed = maintenance.clearAll()
        assertEquals(3, removed)

        // Cascade: normalized transactions and delivery records are gone too.
        assertEquals(0, db.normalizedTransactionDao().count())
        assertEquals(0, db.deliveryRecordDao().pendingForDelivery().size)
        assertEquals(0, db.deliveryRecordDao().sentCount())
        assertEquals(0, db.deliveryRecordDao().failedCount())
        assertTrue(db.rawEventDao().byId("sent-2") == null)
        assertTrue(db.rawEventDao().byId("pending-2") == null)
        assertTrue(db.rawEventDao().byId("failed-2") == null)
    }

    @Test
    fun `clear preserves sent transaction with memo waiting to sync`() = runTest {
        seed("memo-pending")
        deliveryRepository.transition("memo-pending", com.gomoney.capture.model.DeliveryState.SENDING)
        deliveryRepository.transition("memo-pending", com.gomoney.capture.model.DeliveryState.SENT)
        db.normalizedTransactionDao().updateMemo("memo-pending", "cash for taxi")

        val removed = maintenance.clearProcessed()

        assertEquals(0, removed)
        assertEquals("sent", db.deliveryRecordDao().byId("memo-pending")?.state)
        assertEquals("cash for taxi", db.normalizedTransactionDao().byId("memo-pending")?.userMemo)
        assertTrue(db.rawEventDao().byId("memo-pending") != null)
    }

    /** 006 T031 / research R8: clearAll() also clears the identity rows. */
    @Test
    fun `clear all removes identity rows so a later scan may re-capture`() = runTest {
        seed("clear-all-event")
        seedIdentity("pkg|1|tag|0", eventId = "clear-all-event")

        maintenance.clearAll()

        assertEquals(0, db.notificationCaptureRecordDao().count())
        // The notification is re-examinable: a later scan may re-create it.
        assertNull(db.notificationCaptureRecordDao().byKey("pkg|1|tag|0"))
    }

    /** 006 T031 / research R8: clearProcessed() leaves identity rows alone. */
    @Test
    fun `clear processed keeps identity rows so cleared events are not resurrected`() = runTest {
        seed("terminal-event")
        deliveryRepository.transition("terminal-event", com.gomoney.capture.model.DeliveryState.SENDING)
        deliveryRepository.transition("terminal-event", com.gomoney.capture.model.DeliveryState.SENT)
        seedIdentity("pkg|2|tag|0", eventId = "terminal-event")

        maintenance.clearProcessed()

        val row = db.notificationCaptureRecordDao().byKey("pkg|2|tag|0")
        assertEquals(NotificationCaptureRecord.STATE_RECORDED, row?.state)
        assertEquals("terminal-event", row?.eventId)
    }

    private suspend fun seedIdentity(key: String, eventId: String) {
        db.notificationCaptureRecordDao().insertIfAbsent(
            NotificationCaptureRecord(
                notificationKey = key,
                eventId = eventId,
                state = NotificationCaptureRecord.STATE_RECORDED,
                updatedAt = "2026-10-05T10:00:00+03:30",
            ),
        )
    }
}
