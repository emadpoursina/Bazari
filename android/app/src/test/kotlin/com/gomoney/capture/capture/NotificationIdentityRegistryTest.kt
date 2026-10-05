package com.gomoney.capture.capture

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gomoney.capture.storage.AppDatabase
import com.gomoney.capture.storage.NotificationCaptureRecord
import com.gomoney.capture.storage.NotificationCaptureRecordDao
import java.time.OffsetDateTime
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 006 identity-guard tests (T011/T030, research R2/R10, quickstart automated
 * checks): claim grant/rejection/stale take-over, terminal commit, release,
 * dedup across both entry points (FR-003) and interrupted-scan recovery.
 */
@RunWith(RobolectricTestRunner::class)
class NotificationIdentityRegistryTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: NotificationCaptureRecordDao
    private var now: OffsetDateTime = OffsetDateTime.parse("2026-10-05T10:00:00+03:30")

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.notificationCaptureRecordDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun registry() = NotificationIdentityRegistry(dao, clock = { now })

    private fun advanceSeconds(seconds: Long) {
        now = now.plusSeconds(seconds)
    }

    @Test
    fun `claim grants on missing row and records a pending claim`() = runTest {
        val registry = registry()

        assertEquals(ClaimOutcome.GRANTED, registry.claim("pkg|1|tag|0"))

        val row = dao.byKey("pkg|1|tag|0")
        assertNotNull(row)
        assertEquals(NotificationCaptureRecord.STATE_PENDING, row?.state)
        assertNull(row?.eventId)
        assertEquals(now.toString(), row?.updatedAt)
    }

    @Test
    fun `claim rejects an already recorded key`() = runTest {
        val registry = registry()
        assertEquals(ClaimOutcome.GRANTED, registry.claim("key-1"))
        registry.commit("key-1", "event-1")

        assertEquals(ClaimOutcome.ALREADY_RECORDED, registry.claim("key-1"))

        val row = dao.byKey("key-1")
        assertEquals(NotificationCaptureRecord.STATE_RECORDED, row?.state)
        assertEquals("event-1", row?.eventId)
    }

    @Test
    fun `claim rejects a fresh pending key younger than sixty seconds`() = runTest {
        val registry = registry()
        assertEquals(ClaimOutcome.GRANTED, registry.claim("key-2"))

        advanceSeconds(59)
        assertEquals(ClaimOutcome.IN_PROGRESS, registry.claim("key-2"))
        // The claim timestamp is untouched by the rejected attempt.
        assertEquals(now.minusSeconds(59).toString(), dao.byKey("key-2")?.updatedAt)
    }

    @Test
    fun `claim takes over a stale pending key older than sixty seconds`() = runTest {
        val registry = registry()
        assertEquals(ClaimOutcome.GRANTED, registry.claim("key-3"))

        advanceSeconds(60)
        assertEquals(ClaimOutcome.GRANTED, registry.claim("key-3"))

        val row = dao.byKey("key-3")
        assertEquals(NotificationCaptureRecord.STATE_PENDING, row?.state)
        assertEquals(now.toString(), row?.updatedAt)
    }

    @Test
    fun `commit is terminal — the key can never be claimed again`() = runTest {
        val registry = registry()
        registry.claim("key-4")
        registry.commit("key-4", "event-4")

        assertEquals(ClaimOutcome.ALREADY_RECORDED, registry.claim("key-4"))
        assertTrue(registry.isRecorded("key-4"))
    }

    @Test
    fun `release deletes the row so the key is re-examinable`() = runTest {
        val registry = registry()
        registry.claim("key-5")
        registry.release("key-5")

        assertNull(dao.byKey("key-5"))
        assertFalse(registry.isRecorded("key-5"))
        assertEquals(ClaimOutcome.GRANTED, registry.claim("key-5"))
    }

    @Test
    fun `same key is deduplicated across both entry points (FR-003)`() = runTest {
        val registry = registry()

        // Real-time path claims and commits first…
        assertEquals(ClaimOutcome.GRANTED, registry.claim("shared-key"))
        registry.commit("shared-key", "event-shared")

        // …the scan path (or a later real-time post) must never process it again.
        assertEquals(ClaimOutcome.ALREADY_RECORDED, registry.claim("shared-key"))

        // And the reverse race: both paths claim concurrently — only one wins.
        assertEquals(ClaimOutcome.GRANTED, registry.claim("race-key"))
        assertEquals(ClaimOutcome.IN_PROGRESS, registry.claim("race-key"))
    }

    @Test
    fun `interrupted scan leaves a stale pending row that the next scan recovers (T030)`() = runTest {
        val registry = registry()

        // A scan is killed mid-flight: claim taken, never committed/released.
        assertEquals(ClaimOutcome.GRANTED, registry.claim("killed-key"))
        advanceSeconds(61)

        // The next scan takes the stale claim over and captures exactly once.
        assertEquals(ClaimOutcome.GRANTED, registry.claim("killed-key"))
        registry.commit("killed-key", "event-recovered")
        assertEquals(ClaimOutcome.ALREADY_RECORDED, registry.claim("killed-key"))

        val row = dao.byKey("killed-key")
        assertEquals(NotificationCaptureRecord.STATE_RECORDED, row?.state)
        assertEquals("event-recovered", row?.eventId)
    }
}
