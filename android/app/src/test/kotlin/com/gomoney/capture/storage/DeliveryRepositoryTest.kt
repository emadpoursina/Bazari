package com.gomoney.capture.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gomoney.capture.model.DeliveryState
import com.gomoney.capture.model.ErrorCategory
import java.time.OffsetDateTime
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Delivery state machine tests (T032, data-model.md §3) with in-memory Room. */
@RunWith(RobolectricTestRunner::class)
class DeliveryRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: DeliveryRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = DeliveryRepository(db.deliveryRecordDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seed(eventId: String): String {
        db.rawEventDao().insert(
            RawEvent(eventId, "notification", "pkg", null, null, "text", "2026-09-23T20:31:22+03:30", "2026-09-23T20:31:22+03:30"),
        )
        val record = repository.createQueued(id = eventId, sourceEventId = eventId)
        assertEquals("queued", record.state)
        return eventId
    }

    private val eventId = "event-1"

    @Test
    fun `happy path queued-sending-sent`() = runTest {
        seed(eventId)

        // From queued → sending → sent.
        val sending = repository.transition(eventId, com.gomoney.capture.model.DeliveryState.SENDING)
        assertNotNull(sending)
        assertEquals(1, sending?.attempts)

        val sent = repository.transition(eventId, com.gomoney.capture.model.DeliveryState.SENT)
        assertNotNull(sent)
        assertEquals("sent", sent?.state)
    }

    @Test
    fun `sent is terminal and immutable`() = runTest {
        seed(eventId)
        repository.transition(eventId, com.gomoney.capture.model.DeliveryState.SENDING)
        repository.transition(eventId, com.gomoney.capture.model.DeliveryState.SENT)

        // No further transitions allowed from SENT.
        val illegal = repository.transition(eventId, com.gomoney.capture.model.DeliveryState.QUEUED)
        assertNull(illegal)
        val illegal2 = repository.transition(eventId, com.gomoney.capture.model.DeliveryState.FAILED, ErrorCategory.SERVER_ERROR)
        assertNull(illegal2)

        val current = db.deliveryRecordDao().byId(eventId)
        assertEquals("sent", current?.state)
    }

    @Test
    fun `sending to failed requires category`() = runTest {
        seed(eventId)
        repository.transition(eventId, com.gomoney.capture.model.DeliveryState.SENDING)

        val rejected = repository.transition(eventId, com.gomoney.capture.model.DeliveryState.FAILED)
        assertNull(rejected) // FAILED requires non-null category (§3 rules)

        val accepted = repository.transition(
            eventId,
            com.gomoney.capture.model.DeliveryState.FAILED,
            ErrorCategory.NETWORK_ERROR,
            "network unreachable",
        )
        assertNotNull(accepted)
        assertEquals("network_error", accepted?.errorCategory)
    }

    @Test
    fun `failed requeues to queued`() = runTest {
        seed(eventId)
        repository.transition(eventId, com.gomoney.capture.model.DeliveryState.SENDING)
        repository.transition(
            eventId,
            com.gomoney.capture.model.DeliveryState.FAILED,
            ErrorCategory.NETWORK_ERROR,
            "network unreachable",
        )

        val requeued = repository.requeue(eventId)
        assertNotNull(requeued)
        assertEquals("queued", requeued?.state)
    }

    @Test
    fun `duplicate ack sets state sent with category duplicate`() = runTest {
        seed(eventId)
        val acked = repository.markDuplicateAcked(eventId, "bridge duplicate ack")
        assertNotNull(acked)
        assertEquals("sent", acked?.state)
        assertEquals("duplicate", acked?.errorCategory)

        // Immutable afterwards.
        assertNull(repository.transition(eventId, com.gomoney.capture.model.DeliveryState.QUEUED))
    }

    @Test
    fun `illegal transition from queued to sent rejected`() = runTest {
        seed(eventId)
        // QUEUED → SENT skips SENDING — illegal (§3 diagram).
        assertNull(repository.transition(eventId, com.gomoney.capture.model.DeliveryState.SENT))
    }
}
