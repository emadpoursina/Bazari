package com.gomoney.capture.sync

import com.gomoney.capture.sync.NightlyLeftover.LeftoverCandidate
import com.gomoney.capture.sync.NightlyLeftover.LeftoverTapDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class NightlyLeftoverTest {

    @Test
    fun `first-install window includes all blank notes when lastCheckAt is absent`() {
        val candidates = listOf(
            LeftoverCandidate(
                id = "tx-1",
                userMemo = null,
                memoSyncState = "synced",
                assignmentSyncState = "synced",
                deliveryState = "sent",
                capturedAt = "2026-11-10T10:00:00Z",
            ),
            LeftoverCandidate(
                id = "tx-2",
                userMemo = "already noted",
                memoSyncState = "synced",
                assignmentSyncState = "synced",
                deliveryState = "sent",
                capturedAt = "2026-11-12T10:00:00Z",
            ),
            LeftoverCandidate(
                id = "tx-3",
                userMemo = "   ",
                memoSyncState = "synced",
                assignmentSyncState = "synced",
                deliveryState = "sent",
                capturedAt = "2026-11-12T12:00:00Z",
            ),
        )

        val snapshot = NightlyLeftover.computeLeftoverSnapshot(candidates, lastCheckAt = null)

        assertEquals(setOf("tx-1", "tx-3"), snapshot.missingNoteIds)
        assertEquals(emptySet<String>(), snapshot.unsyncedIds)
        assertEquals(2, snapshot.missingNoteCount)
        assertEquals(0, snapshot.unsyncedCount)
        assertTrue(snapshot.hasLeftover)
    }

    @Test
    fun `older blank notes excluded when captured before lastCheckAt`() {
        val lastCheck = "2026-11-12T21:00:00Z"
        val candidates = listOf(
            LeftoverCandidate(
                id = "tx-old-blank",
                userMemo = null,
                memoSyncState = "synced",
                assignmentSyncState = "synced",
                deliveryState = "sent",
                capturedAt = "2026-11-11T15:00:00Z",
            ),
            LeftoverCandidate(
                id = "tx-new-blank",
                userMemo = null,
                memoSyncState = "synced",
                assignmentSyncState = "synced",
                deliveryState = "sent",
                capturedAt = "2026-11-12T22:30:00Z",
            ),
        )

        val snapshot = NightlyLeftover.computeLeftoverSnapshot(candidates, lastCheckAt = lastCheck)

        assertEquals(setOf("tx-new-blank"), snapshot.missingNoteIds)
        assertEquals(1, snapshot.missingNoteCount)
        assertEquals(0, snapshot.unsyncedCount)
        assertTrue(snapshot.hasLeftover)
    }

    @Test
    fun `unsynced any age included in unsynced count`() {
        val lastCheck = "2026-11-12T21:00:00Z"
        val candidates = listOf(
            LeftoverCandidate(
                id = "tx-old-failed",
                userMemo = "groceries",
                memoSyncState = "synced",
                assignmentSyncState = "synced",
                deliveryState = "failed",
                capturedAt = "2026-10-01T10:00:00Z",
            ),
            LeftoverCandidate(
                id = "tx-old-memo-pending",
                userMemo = "dinner",
                memoSyncState = "pending",
                assignmentSyncState = "synced",
                deliveryState = "sent",
                capturedAt = "2026-10-15T12:00:00Z",
            ),
            LeftoverCandidate(
                id = "tx-old-assignment-pending",
                userMemo = "gas",
                memoSyncState = "synced",
                assignmentSyncState = "pending",
                deliveryState = "sent",
                capturedAt = "2026-10-20T14:00:00Z",
            ),
            LeftoverCandidate(
                id = "tx-sent-clean",
                userMemo = "coffee",
                memoSyncState = "synced",
                assignmentSyncState = "synced",
                deliveryState = "sent",
                capturedAt = "2026-11-12T10:00:00Z",
            ),
        )

        val snapshot = NightlyLeftover.computeLeftoverSnapshot(candidates, lastCheckAt = lastCheck)

        assertEquals(setOf("tx-old-failed", "tx-old-memo-pending", "tx-old-assignment-pending"), snapshot.unsyncedIds)
        assertEquals(3, snapshot.unsyncedCount)
        assertEquals(0, snapshot.missingNoteCount)
        assertTrue(snapshot.hasLeftover)
    }

    @Test
    fun `snapshot computes both counts and formats reminder text correctly`() {
        val lastCheck = "2026-11-12T21:00:00Z"
        val candidates = listOf(
            LeftoverCandidate(
                id = "tx-unsynced-and-missing",
                userMemo = null,
                memoSyncState = "pending",
                assignmentSyncState = "synced",
                deliveryState = "queued",
                capturedAt = "2026-11-13T10:00:00Z",
            ),
            LeftoverCandidate(
                id = "tx-missing-only",
                userMemo = "",
                memoSyncState = "synced",
                assignmentSyncState = "synced",
                deliveryState = "sent",
                capturedAt = "2026-11-13T11:00:00Z",
            ),
            LeftoverCandidate(
                id = "tx-unsynced-only",
                userMemo = "lunch",
                memoSyncState = "synced",
                assignmentSyncState = "synced",
                deliveryState = "queued",
                capturedAt = "2026-11-13T12:00:00Z",
            ),
        )

        val snapshot = NightlyLeftover.computeLeftoverSnapshot(candidates, lastCheckAt = lastCheck)

        assertEquals(2, snapshot.unsyncedCount)
        assertEquals(2, snapshot.missingNoteCount)
        assertEquals(setOf("tx-unsynced-and-missing", "tx-missing-only", "tx-unsynced-only"), snapshot.allLeftoverIds)

        val text = NightlyLeftover.formatReminderText(snapshot.unsyncedCount, snapshot.missingNoteCount)
        assertEquals("2 unsynced, 2 missing a note.", text)
    }

    @Test
    fun `empty leftovers yields hasLeftover false`() {
        val snapshot = NightlyLeftover.computeLeftoverSnapshot(emptyList(), lastCheckAt = null)
        assertFalse(snapshot.hasLeftover)
        assertEquals(0, snapshot.unsyncedCount)
        assertEquals(0, snapshot.missingNoteCount)
        assertEquals(emptySet<String>(), snapshot.allLeftoverIds)
    }

    @Test
    fun `first install before 21-00 does not evaluate`() {
        val now = LocalDateTime.of(2026, 11, 12, 14, 30)
        val decision = NightlyLeftover.evaluateGate(lastEvaluatedLocalDate = null, now = now)

        assertFalse(decision.shouldEvaluate)
        assertNull(decision.targetDate)
    }

    @Test
    fun `first install at or after 21-00 evaluates for today`() {
        val now = LocalDateTime.of(2026, 11, 12, 21, 5)
        val decision = NightlyLeftover.evaluateGate(lastEvaluatedLocalDate = null, now = now)

        assertTrue(decision.shouldEvaluate)
        assertEquals(LocalDate.of(2026, 11, 12), decision.targetDate)
    }

    @Test
    fun `skip same local date when already evaluated`() {
        val now = LocalDateTime.of(2026, 11, 12, 23, 15)
        val decision = NightlyLeftover.evaluateGate(lastEvaluatedLocalDate = "2026-11-12", now = now)

        assertFalse(decision.shouldEvaluate)
        assertEquals(LocalDate.of(2026, 11, 12), decision.targetDate)
    }

    @Test
    fun `missed date caught up next morning evaluates for yesterday`() {
        // Device was off at 21:00 on Nov 12; woke up at 08:30 on Nov 13.
        val now = LocalDateTime.of(2026, 11, 13, 8, 30)
        val decision = NightlyLeftover.evaluateGate(lastEvaluatedLocalDate = "2026-11-11", now = now)

        assertTrue(decision.shouldEvaluate)
        assertEquals(LocalDate.of(2026, 11, 12), decision.targetDate)
    }

    @Test
    fun `missed date already evaluated earlier today does not re-evaluate before 21-00`() {
        val now = LocalDateTime.of(2026, 11, 13, 10, 0)
        // Nov 12 was evaluated earlier this morning
        val decision = NightlyLeftover.evaluateGate(lastEvaluatedLocalDate = "2026-11-12", now = now)

        assertFalse(decision.shouldEvaluate)
        assertEquals(LocalDate.of(2026, 11, 12), decision.targetDate)
    }

    @Test
    fun `next day at 21-00 evaluates for today`() {
        val now = LocalDateTime.of(2026, 11, 13, 21, 0)
        val decision = NightlyLeftover.evaluateGate(lastEvaluatedLocalDate = "2026-11-12", now = now)

        assertTrue(decision.shouldEvaluate)
        assertEquals(LocalDate.of(2026, 11, 13), decision.targetDate)
    }

    @Test
    fun `tap routing routes 0 to EventsList`() {
        val dest = NightlyLeftover.resolveLeftoverTap(emptySet())
        assertEquals(LeftoverTapDestination.EventsList, dest)
    }

    @Test
    fun `tap routing routes exactly 1 leftover to Detail`() {
        val dest = NightlyLeftover.resolveLeftoverTap(setOf("tx-single"))
        assertEquals(LeftoverTapDestination.Detail("tx-single"), dest)
    }

    @Test
    fun `tap routing routes 2 or more leftovers to EventsList`() {
        val dest = NightlyLeftover.resolveLeftoverTap(setOf("tx-1", "tx-2"))
        assertEquals(LeftoverTapDestination.EventsList, dest)
    }
}
