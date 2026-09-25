package com.gomoney.capture.sync

import com.gomoney.capture.storage.DeliveryRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncBannerTest {

    private fun record(
        id: String,
        state: String = "failed",
        category: String? = null,
        detail: String? = null,
        lastAttemptAt: String? = "2026-09-24T18:00:00+03:30",
    ) = DeliveryRecord(id, state, 1, lastAttemptAt, null, category, detail, "event-$id")

    @Test
    fun `empty queue is all clear`() {
        val banner = SyncBanner.forPending(emptyList())
        assertEquals(SyncBanner.Kind.ALL_CLEAR, banner.kind)
    }

    @Test
    fun `network failure shows offline retryable reason`() {
        val banner = SyncBanner.forPending(listOf(record("1", category = "network_error", detail = "network unreachable")))
        assertEquals(SyncBanner.Kind.OFFLINE_RETRYABLE, banner.kind)
        assertEquals(1, banner.pendingTotal)
        assertEquals("network_error", banner.lastCategory)
    }

    @Test
    fun `validation failure needs attention, not silent retry`() {
        val banner = SyncBanner.forPending(
            listOf(
                record("1", category = "network_error"),
                record("2", category = "validation_error", detail = "validation unmapped account"),
            ),
        )
        assertEquals(SyncBanner.Kind.NEEDS_ATTENTION, banner.kind)
        assertEquals(1, banner.needsAttentionCount)
        assertEquals("validation_error", banner.lastCategory)
    }

    @Test
    fun `unauthorized counts as needs attention`() {
        val banner = SyncBanner.forPending(
            listOf(record("1", category = "server_error", detail = "unauthorized")),
        )
        assertEquals(SyncBanner.Kind.NEEDS_ATTENTION, banner.kind)
    }

    @Test
    fun `fresh queued rows show waiting`() {
        val banner = SyncBanner.forPending(listOf(record("1", state = "queued")))
        assertEquals(SyncBanner.Kind.WAITING, banner.kind)
    }

    @Test
    fun `retryable filter keeps validation failed`() {
        val network = record("1", category = "network_error")
        val validation = record("2", category = "validation_error")
        val unauthorized = record("3", category = "server_error", detail = "unauthorized")
        val server = record("4", category = "server_error", detail = "gomoney_error")
        assertEquals(true, SyncEngine.isAutoRetryable(network))
        assertEquals(false, SyncEngine.isAutoRetryable(validation))
        assertEquals(false, SyncEngine.isAutoRetryable(unauthorized))
        assertEquals(true, SyncEngine.isAutoRetryable(server))
        assertEquals(false, SyncEngine.isAutoRetryable(record("5", state = "queued", category = "network_error")))
    }
}
