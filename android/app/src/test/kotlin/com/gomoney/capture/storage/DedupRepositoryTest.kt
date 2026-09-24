package com.gomoney.capture.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** App-side dedup tests (T034, FR-015/030) with in-memory Room. */
@RunWith(RobolectricTestRunner::class)
class DedupRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var dedupRepository: DedupRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dedupRepository = DedupRepository(db.dedupCacheDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `lookup miss then record then hit`() = runTest {
        assertNull(dedupRepository.lookup("fp-1"))

        dedupRepository.record("fp-1", "sent")

        val hit = dedupRepository.lookup("fp-1")
        assertNotNull(hit)
        assertEquals("sent", hit?.outcome)
    }

    @Test
    fun `duplicate outcome recorded`() = runTest {
        dedupRepository.record("fp-2", "duplicate")
        assertEquals("duplicate", dedupRepository.lookup("fp-2")?.outcome)
    }

    @Test
    fun `cache persists across repository instances simulating restart`() = runTest {
        dedupRepository.record("fp-3", "sent")

        // New repository instance over the same DB (simulated app restart).
        val second = DedupRepository(db.dedupCacheDao())
        assertEquals("sent", second.lookup("fp-3")?.outcome)
    }

    @Test
    fun `fingerprint reuse across sources via shared Fingerprint`() = runTest {
        // Same fingerprint inputs regardless of source: the fingerprint does
        // NOT include the capture source, so notification+SMS collapse.
        val fpNotification = com.gomoney.capture.parser.Fingerprint.compute(
            "mellat", "****1234", com.gomoney.capture.model.TxType.EXPENSE,
            500_000L, "2026-09-23T20:31:22+03:30", "Card purchase",
        )
        val fpSms = com.gomoney.capture.parser.Fingerprint.compute(
            "mellat", "****1234", com.gomoney.capture.model.TxType.EXPENSE,
            500_000L, "2026-09-23T20:31:22+03:30", "Card purchase",
        )
        assertEquals(fpNotification, fpSms)

        dedupRepository.record(fpNotification, "duplicate")
        assertEquals("duplicate", dedupRepository.lookup(fpSms)?.outcome)
    }
}
