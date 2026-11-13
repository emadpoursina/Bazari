package com.gomoney.capture.ui

import android.content.Intent
import com.gomoney.capture.sync.NotificationOpen
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationOpenRoutingTest {

    @Test
    fun `detail target with existing event id routes to Detail`() {
        val route = NotificationOpenRouting.resolveTarget(
            target = NotificationOpen.TARGET_DETAIL,
            eventId = "tx-123",
            txExists = { it == "tx-123" },
            leftoverIdsProvider = { emptySet() },
        )

        assertEquals(NotificationOpenRouting.Route.Detail("tx-123"), route)
    }

    @Test
    fun `detail target with missing event id routes to EventsList with explanation`() {
        val route = NotificationOpenRouting.resolveTarget(
            target = NotificationOpen.TARGET_DETAIL,
            eventId = "tx-missing",
            txExists = { false },
            leftoverIdsProvider = { emptySet() },
        )

        assertEquals(
            NotificationOpenRouting.Route.EventsList(NotificationOpenRouting.MISSING_EVENT_MESSAGE),
            route,
        )
    }

    @Test
    fun `detail target with null or blank event id routes to EventsList with explanation`() {
        val routeNull = NotificationOpenRouting.resolveTarget(
            target = NotificationOpen.TARGET_DETAIL,
            eventId = null,
            txExists = { true },
            leftoverIdsProvider = { emptySet() },
        )
        assertEquals(
            NotificationOpenRouting.Route.EventsList(NotificationOpenRouting.MISSING_EVENT_MESSAGE),
            routeNull,
        )

        val routeBlank = NotificationOpenRouting.resolveTarget(
            target = NotificationOpen.TARGET_DETAIL,
            eventId = "  ",
            txExists = { true },
            leftoverIdsProvider = { emptySet() },
        )
        assertEquals(
            NotificationOpenRouting.Route.EventsList(NotificationOpenRouting.MISSING_EVENT_MESSAGE),
            routeBlank,
        )
    }

    @Test
    fun `leftover target with exactly one leftover routes to Detail`() {
        val route = NotificationOpenRouting.resolveTarget(
            target = NotificationOpen.TARGET_LEFTOVER,
            eventId = null,
            txExists = { true },
            leftoverIdsProvider = { setOf("tx-single-leftover") },
        )

        assertEquals(NotificationOpenRouting.Route.Detail("tx-single-leftover"), route)
    }

    @Test
    fun `leftover target with zero leftovers routes to EventsList with no message`() {
        val route = NotificationOpenRouting.resolveTarget(
            target = NotificationOpen.TARGET_LEFTOVER,
            eventId = null,
            txExists = { true },
            leftoverIdsProvider = { emptySet() },
        )

        assertEquals(NotificationOpenRouting.Route.EventsList(null), route)
    }

    @Test
    fun `leftover target with multiple leftovers routes to EventsList with no message`() {
        val route = NotificationOpenRouting.resolveTarget(
            target = NotificationOpen.TARGET_LEFTOVER,
            eventId = null,
            txExists = { true },
            leftoverIdsProvider = { setOf("tx-1", "tx-2") },
        )

        assertEquals(NotificationOpenRouting.Route.EventsList(null), route)
    }

    @Test
    fun `unknown or null target routes to None`() {
        val routeNull = NotificationOpenRouting.resolveTarget(
            target = null,
            eventId = "tx-123",
            txExists = { true },
            leftoverIdsProvider = { emptySet() },
        )
        assertEquals(NotificationOpenRouting.Route.None, routeNull)

        val routeUnknown = NotificationOpenRouting.resolveTarget(
            target = "other",
            eventId = "tx-123",
            txExists = { true },
            leftoverIdsProvider = { emptySet() },
        )
        assertEquals(NotificationOpenRouting.Route.None, routeUnknown)
    }

    @Test
    fun `resolveIntent extracts extras and routes accordingly`() {
        val intent = Intent().apply {
            putExtra(NotificationOpen.EXTRA_OPEN_TARGET, NotificationOpen.TARGET_DETAIL)
            putExtra(NotificationOpen.EXTRA_OPEN_EVENT_ID, "tx-456")
        }

        val route = NotificationOpenRouting.resolveIntent(
            intent = intent,
            txExists = { it == "tx-456" },
            leftoverIdsProvider = { emptySet() },
        )

        assertEquals(NotificationOpenRouting.Route.Detail("tx-456"), route)
    }
}
