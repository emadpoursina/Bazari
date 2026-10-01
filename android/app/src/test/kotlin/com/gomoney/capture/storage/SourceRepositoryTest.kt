package com.gomoney.capture.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gomoney.capture.source.SaveResult
import com.gomoney.capture.source.TransactionSourceRepository
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TransactionSourceRepository tests (T013; FR-005/006, SC-009): persistence,
 * uniqueness pre-check + index backstop, and survival across an app restart.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SourceRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: TransactionSourceRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = TransactionSourceRepository(db.transactionSourceDao(), db.parseErrorDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun source(id: String = UUID.randomUUID().toString(), name: String = "Example Bank") = TransactionSource(
        id = id,
        name = name,
        identifier = "com.example.bank",
        channel = "notification",
        enabled = true,
        template = "خرید مبلغ {amount} ریال {direction}",
        incomeKeywords = "واریز",
        expenseKeywords = "خرید",
        createdAt = "2026-09-24T00:00:00+03:30",
        updatedAt = "2026-09-24T00:00:00+03:30",
    )

    @Test
    fun `a valid source is persisted and lists as a flow`() = runTest {
        assertTrue(repository.save(source()) is SaveResult.Saved)
        assertEquals(1, repository.all().size)
        assertEquals(1, repository.enabled().size)
        assertEquals("Example Bank", repository.all().first().name)
    }

    @Test
    fun `invalid source is rejected without persisting`() = runTest {
        val result = repository.save(source(name = "  "))
        assertTrue(result is SaveResult.Invalid)
        assertEquals(0, repository.all().size)
    }

    @Test
    fun `duplicate identifier and channel is blocked naming the conflict`() = runTest {
        repository.save(source(id = "existing", name = "Old Bank"))
        val result = repository.save(source(id = "new", name = "New Bank"))

        assertTrue(result is SaveResult.Invalid)
        val error = (result as SaveResult.Invalid).errors.first { it.field == "identifier" }
        assertTrue(error.message.contains("Old Bank"))
        assertEquals(1, repository.all().size)
    }

    @Test
    fun `editing an existing source updates it in place`() = runTest {
        repository.save(source(id = "src-1"))
        assertTrue(repository.save(source(id = "src-1", name = "Renamed")) is SaveResult.Saved)
        assertEquals(1, repository.all().size)
        assertEquals("Renamed", repository.byId("src-1")?.name)
    }

    @Test
    fun `resolve finds a source by identifier and channel`() = runTest {
        repository.save(source())
        assertEquals(
            "Example Bank",
            repository.resolve("com.example.bank", com.gomoney.capture.model.Channel.NOTIFICATION)?.name,
        )
        assertEquals(
            null,
            repository.resolve("com.example.bank", com.gomoney.capture.model.Channel.SMS),
        )
    }

    /** FR-006: definitions survive app/device restart (file-backed database). */
    @Test
    fun `definitions survive a database restart`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "source-restart-${UUID.randomUUID()}.db"

        val first = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
        try {
            TransactionSourceRepository(first.transactionSourceDao(), first.parseErrorDao())
                .save(source(id = "persisted", name = "Persisted Bank"))
        } finally {
            first.close()
        }

        val second = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
        try {
            val reloaded = TransactionSourceRepository(second.transactionSourceDao(), second.parseErrorDao())
            assertEquals(1, reloaded.all().size)
            assertEquals("Persisted Bank", reloaded.byId("persisted")?.name)
        } finally {
            second.close()
            context.deleteDatabase(name)
        }
    }

    /** Timestamps come from the injected clock so tests stay deterministic. */
    @Test
    fun `save stamps createdAt once and refreshes updatedAt`() = runTest {
        val times = ArrayDeque(listOf(
            OffsetDateTime.parse("2026-09-24T00:00:01+03:30"),
            OffsetDateTime.parse("2026-09-25T00:00:02+03:30"),
        ))
        val clocked = TransactionSourceRepository(
            db.transactionSourceDao(),
            db.parseErrorDao(),
            clock = { times.removeFirst() },
        )

        clocked.save(source(id = "src-clock"))
        clocked.save(source(id = "src-clock", name = "Edited"))

        val stored = clocked.byId("src-clock")!!
        assertEquals(OffsetDateTime.parse("2026-09-24T00:00:00+03:30"), OffsetDateTime.parse(stored.createdAt))
        assertEquals(OffsetDateTime.parse("2026-09-25T00:00:02+03:30"), OffsetDateTime.parse(stored.updatedAt))
    }
}
