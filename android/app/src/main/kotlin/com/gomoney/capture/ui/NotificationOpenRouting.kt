package com.gomoney.capture.ui

import android.content.Intent
import com.gomoney.capture.sync.NightlyLeftover
import com.gomoney.capture.sync.NotificationOpen

/**
 * 009 US2 / US4 (contracts/notification-tap-navigation.md):
 * Resolves notification tap extras into an in-app destination route.
 */
object NotificationOpenRouting {

    const val MISSING_EVENT_MESSAGE = "This transaction is no longer available."

    sealed interface Route {
        object None : Route
        data class Detail(val txId: String) : Route
        data class EventsList(val message: String? = null) : Route
    }

    fun resolveTarget(
        target: String?,
        eventId: String?,
        txExists: (String) -> Boolean,
        leftoverIdsProvider: () -> Set<String>,
    ): Route {
        return when (target) {
            NotificationOpen.TARGET_DETAIL -> {
                if (!eventId.isNullOrBlank() && txExists(eventId)) {
                    Route.Detail(eventId)
                } else {
                    Route.EventsList(MISSING_EVENT_MESSAGE)
                }
            }
            NotificationOpen.TARGET_LEFTOVER -> {
                val leftovers = leftoverIdsProvider()
                when (val tap = NightlyLeftover.resolveLeftoverTap(leftovers)) {
                    is NightlyLeftover.LeftoverTapDestination.Detail -> Route.Detail(tap.txId)
                    is NightlyLeftover.LeftoverTapDestination.EventsList -> Route.EventsList(null)
                }
            }
            else -> Route.None
        }
    }

    fun resolveIntent(
        intent: Intent?,
        txExists: (String) -> Boolean,
        leftoverIdsProvider: () -> Set<String>,
    ): Route {
        if (intent == null) return Route.None
        val target = intent.getStringExtra(NotificationOpen.EXTRA_OPEN_TARGET) ?: return Route.None
        val eventId = intent.getStringExtra(NotificationOpen.EXTRA_OPEN_EVENT_ID)
        return resolveTarget(target, eventId, txExists, leftoverIdsProvider)
    }
}
