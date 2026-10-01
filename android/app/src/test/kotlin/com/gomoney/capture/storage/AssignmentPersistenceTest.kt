package com.gomoney.capture.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Assignment persistence tests (T041; FR-019, spec assumption "unset = default
 * behavior"): destination/category selections survive an app restart and an
 * unset assignment leaves the existing defaults untouched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AssignmentPersistenceTest {

    private val eventId = "assignment-event"
    private val txId = "assignment-transaction"
    private val timestamp = "2026-09-24T08:00:00+03:30"

    private suspend fun seed(db: AppDatabase) {
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
                fingerprint = "d".repeat(64),
                parserName = "UserSourceParser",
                confidence = "HIGH",
            ),
        )
    }

    @Test
    fun `unset assignment keeps the default behavior`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "assignment-default-${UUID.randomUUID()}.db"
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).allowMainThreadQueries().build()
        try {
            seed(db)
            val tx = db.normalizedTransactionDao().byId(txId)!!
            assertNull(tx.destinationAccountId)
            assertNull(tx.categoryId)
            assertEquals("synced", tx.assignmentSyncState)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun `destination and category survive an app restart`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "assignment-restart-${UUID.randomUUID()}.db"

        val first = Room.databaseBuilder(context, AppDatabase::class.java, name).allowMainThreadQueries().build()
        try {
            seed(first)
            first.normalizedTransactionDao().updateAssignment(txId, 7, 5)
        } finally {
            first.close()
        }

        val second = Room.databaseBuilder(context, AppDatabase::class.java, name).allowMainThreadQueries().build()
        try {
            val tx = second.normalizedTransactionDao().byId(txId)!!
            assertEquals(7, tx.destinationAccountId)
            assertEquals(5, tx.categoryId)
            assertEquals("pending", tx.assignmentSyncState)
        } finally {
            second.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun `clearing an assignment returns to the defaults and re-syncs`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "assignment-clear-${UUID.randomUUID()}.db"
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).allowMainThreadQueries().build()
        try {
            seed(db)
            db.normalizedTransactionDao().updateAssignment(txId, 7, 5)
            db.normalizedTransactionDao().markAssignmentSynced(txId, 7, 5)
            assertEquals("synced", db.normalizedTransactionDao().byId(txId)!!.assignmentSyncState)

            db.normalizedTransactionDao().updateAssignment(txId, null, null)
            val cleared = db.normalizedTransactionDao().byId(txId)!!
            assertNull(cleared.destinationAccountId)
            assertNull(cleared.categoryId)
            assertEquals("pending", cleared.assignmentSyncState)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
