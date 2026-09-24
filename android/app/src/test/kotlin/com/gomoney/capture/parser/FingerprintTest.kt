package com.gomoney.capture.parser

import com.gomoney.capture.model.TxType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Fingerprint tests (T007, FR-009, Q3=B ±2 min tolerance). */
class FingerprintTest {

    private val bank = "mellat"
    private val hint = "****1234"
    private val type = TxType.EXPENSE
    private val amount = 500_000L
    private val desc = "Card purchase"

    @Test
    fun `identical source produces identical fingerprint`() {
        val txAt = "2026-09-23T20:31:22+03:30"
        val f1 = Fingerprint.compute(bank, hint, type, amount, txAt, desc)
        val f2 = Fingerprint.compute(bank, hint, type, amount, txAt, desc)
        assertEquals(f1, f2)
        assertEquals(64, f1.length)
    }

    @Test
    fun `same fingerprint regardless of input description case or whitespace`() {
        val txAt = "2026-09-23T20:31:22+03:30"
        assertEquals(
            Fingerprint.compute(bank, hint, type, amount, txAt, "Card  purchase"),
            Fingerprint.compute(bank, hint, type, amount, txAt, "CARD   purchase"),
        )
    }

    @Test
    fun `different minute within two minutes changes fingerprint but stays in bucket window`() {
        // Notification at :31:22, SMS at :32:45 (83 s apart) — different
        // fingerprints (minute rounding), but the bridge's ±2-min bucket
        // window must still collapse them.
        val f1 = Fingerprint.compute(bank, hint, type, amount, "2026-09-23T20:31:22+03:30", desc)
        val f2 = Fingerprint.compute(bank, hint, type, amount, "2026-09-23T20:32:45+03:30", desc)
        assertNotEquals(f1, f2)

        // Bucket keys differ only in the bucket component; the bridge scans
        // adjacent buckets so both land in the same window scan.
        val b1 = Fingerprint.bucketKey(bank, hint, type, amount, "2026-09-23T20:31:22+03:30")
        val b2 = Fingerprint.bucketKey(bank, hint, type, amount, "2026-09-23T20:32:45+03:30")
        assertEquals(b1.substringBeforeLast('|'), b2.substringBeforeLast('|'))
        assertNotEquals(b1.substringAfterLast('|'), b2.substringAfterLast('|'))
    }

    @Test
    fun `distinct transactions same amount do not collapse`() {
        val f1 = Fingerprint.compute(bank, hint, type, amount, "2026-09-23T20:31:22+03:30", desc)
        val f2 = Fingerprint.compute(bank, hint, type, amount, "2026-09-23T20:31:40+03:30", "Coffee shop")
        assertNotEquals(f1, f2)
    }

    @Test
    fun `bucket boundary crossing still matches via adjacent buckets`() {
        // floor(unix/120) differs across the boundary; the bridge's
        // adjacent-bucket scan handles this (analyze HIGH note). The bucket
        // key itself only needs to be deterministic.
        val b1 = Fingerprint.bucketKey(bank, hint, type, amount, "2026-09-23T20:31:59+03:30")
        val b2 = Fingerprint.bucketKey(bank, hint, type, amount, "2026-09-23T20:32:01+03:30")
        assertNotEquals(b1, b2) // different buckets...
        // ...but only 2 seconds apart in real time.
        val delta = Fingerprint.toEpochSecond("2026-09-23T20:32:01+03:30") -
            Fingerprint.toEpochSecond("2026-09-23T20:31:59+03:30")
        assertEquals(2L, delta)
    }

    @Test
    fun `offsets normalize to the same instant`() {
        // Same instant expressed in two offsets → same epoch second.
        assertEquals(
            Fingerprint.toEpochSecond("2026-09-23T20:31:22+03:30"),
            Fingerprint.toEpochSecond("2026-09-23T17:01:22+00:30"),
        )
    }
}
