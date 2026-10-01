package com.gomoney.capture.ui

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gomoney.capture.model.DeliveryState
import com.gomoney.capture.storage.AppDatabase
import com.gomoney.capture.storage.DeliveryRecord
import com.gomoney.capture.storage.NormalizedTransaction
import com.gomoney.capture.storage.RawEvent
import com.gomoney.capture.storage.deliveryStateOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 005 T033 / FR-022: EventActions.saveCurrency persists a pre-delivery currency
 * edit for held/queued/failed rows (a held row STAYS held — no held → queued
 * move) and rejects edits to sending/sent rows. Assignment values are never
 * touched by a currency edit (FR-019).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EventActionsCurrencyTest {

    private lateinit var db: AppDatabase
    private lateinit var actions: EventActions

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        actions = EventActions(context, db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seed(id: String, state: String, currency: String = "") {
        db.rawEventDao().insert(
            RawEvent(id, "notification", "pkg", null, null, "text", "2026-09-23T20:31:22+03:30", "2026-09-23T20:31:22+03:30"),
        )
        db.normalizedTransactionDao().insert(
            NormalizedTransaction(
                id = id,
                sourceEventId = id,
                source = "notification",
                bank = "user",
                accountHint = "com.example.bank",
                type = "expense",
                amountMinor = 500_000L,
                currency = currency,
                txAt = "2026-09-23T20:31:22+03:30",
                description = "Card purchase",
                rawTextRef = id,
                fingerprint = "a".repeat(60) + id.hashCode().toString(16).padStart(4, '0'),
                parserName = "UserSourceParser",
                confidence = "HIGH",
                destinationAccountId = 5,
                categoryId = 7,
            ),
        )
        db.deliveryRecordDao().insert(
            DeliveryRecord(
                id = id,
                state = state,
                attempts = if (state == "sending" || state == "sent") 1 else 0,
                lastAttemptAt = null,
                nextRetryAt = null,
                errorCategory = null,
                errorDetail = null,
                sourceEventId = id,
            ),
        )
    }

    @Test
    fun `held row keeps its held state after a currency edit`() = runTest {
        seed("e1", "held")

        assertTrue(actions.saveCurrency("e1", "USD"))

        assertEquals("USD", db.normalizedTransactionDao().byId("e1")?.currency)
        assertEquals("held", db.deliveryRecordDao().byId("e1")?.state)
        // Assignment untouched (FR-019).
        assertEquals(5, db.normalizedTransactionDao().byId("e1")?.destinationAccountId)
        assertEquals(7, db.normalizedTransactionDao().byId("e1")?.categoryId)
    }

    @Test
    fun `queued and failed rows accept a currency edit`() = runTest {
        seed("e2", "queued", currency = "IRR")
        seed("e3", "failed", currency = "IRR")

        assertTrue(actions.saveCurrency("e2", "USD"))
        assertTrue(actions.saveCurrency("e3", "EUR"))

        assertEquals("USD", db.normalizedTransactionDao().byId("e2")?.currency)
        assertEquals("queued", db.deliveryRecordDao().byId("e2")?.state)
        assertEquals("EUR", db.normalizedTransactionDao().byId("e3")?.currency)
        assertEquals("failed", db.deliveryRecordDao().byId("e3")?.state)
    }

    @Test
    fun `sending and sent rows are read-only`() = runTest {
        seed("e4", "sending", currency = "IRR")
        seed("e5", "sent", currency = "IRR")

        assertFalse(actions.saveCurrency("e4", "USD"))
        assertFalse(actions.saveCurrency("e5", "USD"))

        assertEquals("IRR", db.normalizedTransactionDao().byId("e4")?.currency)
        assertEquals("IRR", db.normalizedTransactionDao().byId("e5")?.currency)
    }

    @Test
    fun `blank currency edits are rejected`() = runTest {
        seed("e6", "held")
        assertFalse(actions.saveCurrency("e6", "   "))
        assertEquals("", db.normalizedTransactionDao().byId("e6")?.currency)
    }

    /** Terminal duplicate-acked rows (sent) are immutable like any other sent row. */
    @Test
    fun `duplicate-acked sent rows are read-only`() = runTest {
        seed("e7", "sent", currency = "IRR")
        db.normalizedTransactionDao().updateCurrency("e7", "IRR") // noop guard
        assertFalse(actions.saveCurrency("e7", "USD"))
        assertEquals("IRR", db.normalizedTransactionDao().byId("e7")?.currency)
        assertEquals(DeliveryState.SENT, db.deliveryRecordDao().byId("e7")?.deliveryStateOf())
    }
}
