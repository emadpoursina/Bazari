package com.gomoney.capture.source

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gomoney.capture.storage.AppDatabase
import com.gomoney.capture.storage.TransactionSource
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 005 T025 / FR-021, SC-010: the former built-in banks are seeded as ordinary,
 * editable `TransactionSource` rows, idempotently on `(identifier, channel)`;
 * user edits are never overwritten, and Generic/SampleBank are not seeded.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SeedDefaultsTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: TransactionSourceRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = TransactionSourceRepository(db.transactionSourceDao(), db.parseErrorDao(), db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `former built-in banks are seeded once`() = runTest {
        val inserted = repository.seedDefaults()

        assertEquals(TransactionSourceRepository.DEFAULT_SEEDS.size, inserted)
        val rows = repository.all()
        assertEquals(TransactionSourceRepository.DEFAULT_SEEDS.size, rows.size)

        // The documented former-bank identifiers are all present, on their
        // (identifier, channel) pair, as ordinary enabled sources with a
        // user template.
        val identifiers = rows.map { it.identifier to it.channel }.toSet()
        assertTrue(identifiers.contains("ir.mellat.mellatab" to "notification"))
        assertTrue(identifiers.contains("MELLAT" to "sms"))
        assertTrue(identifiers.contains("ir.bmi.mobilebank" to "notification"))
        assertTrue(identifiers.contains("BMI" to "sms"))
        assertTrue(identifiers.contains("ir.sb24.saman" to "notification"))
        assertTrue(identifiers.contains("SAMAN" to "sms"))
        assertTrue(identifiers.contains("com.samanpr.blu" to "notification"))
        rows.forEach { row ->
            assertTrue(row.enabled)
            assertTrue(row.template.isNotBlank())
        }
    }

    /** Idempotent: a second run inserts nothing (unique (identifier, channel)). */
    @Test
    fun `seeding twice inserts nothing more`() = runTest {
        repository.seedDefaults()
        assertEquals(0, repository.seedDefaults())
        assertEquals(TransactionSourceRepository.DEFAULT_SEEDS.size, repository.all().size)
    }

    /** User edits to seeded rows are never overwritten by re-seeding. */
    @Test
    fun `user edits of seeded rows are not overwritten`() = runTest {
        repository.seedDefaults()
        val mellat = repository.all().single { it.identifier == "ir.mellat.mellatab" }

        // The user renames, disables, and rewrites the template.
        val edited = mellat.copy(name = "My Mellat", enabled = false, template = "{direction} {amount} T")
        assertTrue(repository.save(edited) is SaveResult.Saved)

        assertEquals(0, repository.seedDefaults())

        val after = repository.byId(mellat.id)!!
        assertEquals("My Mellat", after.name)
        assertEquals(false, after.enabled)
        assertEquals("{direction} {amount} T", after.template)
    }

    /** Generic and SampleBank are NOT seeded (FR-021); they capture nothing. */
    @Test
    fun `generic and samplebank are not seeded`() = runTest {
        repository.seedDefaults()
        val rows = repository.all()

        assertNull(rows.firstOrNull { it.identifier == "com.other.bank" })
        assertNull(rows.firstOrNull { it.identifier == "GENERIC" })
        assertNull(rows.firstOrNull { it.identifier == "com.sample.bank" })
        assertNull(rows.firstOrNull { it.identifier == "SAMPLEBANK" })
    }

    /** A user row that already occupies a seed (identifier, channel) is left alone. */
    @Test
    fun `a pre-existing user row is not replaced by the seed`() = runTest {
        val userRow = TransactionSource(
            id = "user-row",
            name = "My Mellat Clone",
            identifier = "ir.mellat.mellatab",
            channel = "notification",
            enabled = false,
            template = "{direction} {amount} T",
            incomeKeywords = "واریز",
            expenseKeywords = "خرید",
            createdAt = "2026-09-24T00:00:00+03:30",
            updatedAt = "2026-09-24T00:00:00+03:30",
        )
        assertTrue(repository.save(userRow) is SaveResult.Saved)

        repository.seedDefaults()

        val row = repository.byId("user-row")!!
        assertEquals("My Mellat Clone", row.name)
        assertEquals(false, row.enabled)
        // All other seeds were inserted (total stays DEFAULT_SEEDS.size: the
        // user row occupies one (identifier, channel) slot, so its seed is skipped).
        assertEquals(TransactionSourceRepository.DEFAULT_SEEDS.size, repository.all().size)
    }
}
