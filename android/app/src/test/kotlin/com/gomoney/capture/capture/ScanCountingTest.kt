package com.gomoney.capture.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 006 outcome→count tests (T026/T028/T029, contracts/notification-identity.md
 * §3, research R4, quickstart automated checks): every row of the mapping
 * table, the `added + skipped == examined` invariant, backlog catch-up,
 * mixed sets and repeat-scan idempotence (FR-004/010, SC-001/002/006).
 *
 * Plain JUnit against fakes (research R10) — no Android runtime needed.
 */
class ScanCountingTest {

    // --- T026: the outcome → count table, row by row ---

    @Test
    fun `queued held and known-duplicate outcomes count as added`() {
        listOf(
            Outcome.QUEUED,
            Outcome.HELD,
            Outcome.QUEUED_AS_KNOWN_DUPLICATE,
        ).forEach { outcome ->
            assertEquals(
                "outcome $outcome must count as added",
                ScanCounts(examined = 1, added = 1, skipped = 0),
                countScan(listOf(ScanItem.Processed(outcome))),
            )
            assertTrue(outcome.countsAsAdded())
        }
    }

    @Test
    fun `duplicate parse-error and ignored outcomes count as skipped`() {
        listOf(
            Outcome.DUPLICATE_SHORT_CIRCUITED,
            Outcome.RETAINED_PARSE_ERROR,
            Outcome.IGNORED_NO_SOURCE,
            Outcome.IGNORED_DISABLED,
        ).forEach { outcome ->
            assertEquals(
                "outcome $outcome must count as skipped",
                ScanCounts(examined = 1, added = 0, skipped = 1),
                countScan(listOf(ScanItem.Processed(outcome))),
            )
        }
    }

    @Test
    fun `already recorded and no-extractable-text count as skipped`() {
        listOf(
            ScanItem.AlreadyRecorded,
            ScanItem.NoExtractableText,
            ScanItem.ClaimRejected,
            ScanItem.GateClosed,
            ScanItem.UnusableNotification,
        ).forEach { item ->
            assertEquals(
                "item $item must count as skipped",
                ScanCounts(examined = 1, added = 0, skipped = 1),
                countScan(listOf(item)),
            )
        }
    }

    @Test
    fun `mixed scan always satisfies added plus skipped equals examined`() {
        val items = listOf(
            ScanItem.Processed(Outcome.QUEUED),
            ScanItem.Processed(Outcome.HELD),
            ScanItem.Processed(Outcome.QUEUED_AS_KNOWN_DUPLICATE),
            ScanItem.Processed(Outcome.DUPLICATE_SHORT_CIRCUITED),
            ScanItem.Processed(Outcome.RETAINED_PARSE_ERROR),
            ScanItem.Processed(Outcome.IGNORED_NO_SOURCE),
            ScanItem.Processed(Outcome.IGNORED_DISABLED),
            ScanItem.AlreadyRecorded,
            ScanItem.NoExtractableText,
        )

        val counts = countScan(items)

        assertEquals(9, counts.examined)
        assertEquals(3, counts.added)
        assertEquals(6, counts.skipped)
        assertEquals(counts.examined, counts.added + counts.skipped)
    }

    @Test
    fun `an empty scan reports zero of everything`() {
        assertEquals(ScanCounts(examined = 0, added = 0, skipped = 0), countScan(emptyList()))
    }

    // --- T028: backlog and mixed-set scenarios (SC-001, US3 acceptance 1-2) ---

    @Test
    fun `backlog of n unrecorded qualifying inputs is captured exactly once each`() {
        val world = FakeScanWorld()
        val backlog = (1..3).map { FakeNotification(key = "backlog-$it") }

        val counts = world.scan(backlog)

        assertEquals(ScanCounts(examined = 3, added = 3, skipped = 0), counts)
        assertEquals(3, world.events.size)
        assertEquals(3, world.recordedKeys.size)
    }

    @Test
    fun `mixed set captures only qualifying inputs and counts the skips`() {
        val world = FakeScanWorld()
        world.recordedKeys += "already-recorded" // committed by an earlier scan/post
        val mixed = listOf(
            FakeNotification(key = "qualifying-1"),
            FakeNotification(key = "unrecognized", qualifying = false),
            FakeNotification(key = "already-recorded"),
            FakeNotification(key = "qualifying-2"),
            FakeNotification(key = "no-text", hasText = false),
        )

        val counts = world.scan(mixed)

        assertEquals(ScanCounts(examined = 5, added = 2, skipped = 3), counts)
        assertEquals(2, world.events.size)
    }

    // --- T029: repeat-scan idempotence (FR-010, SC-002, SC-006) ---

    @Test
    fun `a second scan over identical inputs adds nothing and leaves events untouched`() {
        val world = FakeScanWorld()
        val inputs = (1..3).map { FakeNotification(key = "idem-$it") } +
            FakeNotification(key = "idem-unrecognized", qualifying = false)

        val first = world.scan(inputs)
        assertEquals(ScanCounts(examined = 4, added = 3, skipped = 1), first)
        val eventsAfterFirstScan = world.events.toList()
        val recordedAfterFirstScan = world.recordedKeys.toSet()

        val second = world.scan(inputs)

        // N runs == 1 run: nothing added, everything else already recorded.
        assertEquals(0, second.added)
        assertEquals(second.examined, second.skipped)
        assertEquals(4, second.examined)
        // Existing event rows are untouched — additive only (FR-010).
        assertEquals(eventsAfterFirstScan, world.events)
        assertEquals(recordedAfterFirstScan, world.recordedKeys)
    }

    /** One notification as the scan loop sees it. */
    private data class FakeNotification(
        val key: String,
        val qualifying: Boolean = true,
        val hasText: Boolean = true,
    )

    /**
     * Fake capture world mirroring the service loop's protocol (claim →
     * pipeline → commit/release) so scenarios can be exercised without Room,
     * a service or a device:
     *  - recorded key → `AlreadyRecorded` (skipped, no new row);
     *  - no text / unrecognized source → `release`d (re-examinable, skipped);
     *  - qualifying → one event row + `commit` (added).
     */
    private class FakeScanWorld {
        val recordedKeys = mutableSetOf<String>()
        val events = mutableListOf<String>()
        private var nextEventId = 1

        fun scan(inputs: List<FakeNotification>): ScanCounts {
            val items = inputs.map { notification ->
                when {
                    notification.key in recordedKeys -> ScanItem.AlreadyRecorded
                    !notification.hasText -> ScanItem.NoExtractableText
                    !notification.qualifying -> ScanItem.Processed(Outcome.IGNORED_NO_SOURCE)
                    else -> {
                        val eventId = "event-${nextEventId++}"
                        recordedKeys += notification.key
                        events += eventId
                        ScanItem.Processed(Outcome.QUEUED)
                    }
                }
            }
            return countScan(items)
        }
    }
}
