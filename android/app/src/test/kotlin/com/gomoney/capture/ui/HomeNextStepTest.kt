package com.gomoney.capture.ui

import com.gomoney.capture.ui.theme.HomeNextStep
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeNextStepTest {

    @Test
    fun missingNotificationAccessHasHighestPriority() {
        // Even if destination is missing and queue has failed items, notification access comes first
        val step = HomeNextStep.resolve(
            notificationAccessGranted = false,
            hasDestinationConfigured = false,
            queueNeedsAttention = true,
        )
        assertEquals(HomeNextStep.GrantNotificationAccess, step)
    }

    @Test
    fun blankDestinationHasSecondPriority() {
        // Notification access is granted, but destination is blank
        val step = HomeNextStep.resolve(
            notificationAccessGranted = true,
            hasDestinationConfigured = false,
            queueNeedsAttention = true,
        )
        assertEquals(HomeNextStep.ConfigureDestination, step)
    }

    @Test
    fun queueNeedsAttentionHasThirdPriority() {
        // Notification access granted, destination configured, but queue has failed sends
        val step = HomeNextStep.resolve(
            notificationAccessGranted = true,
            hasDestinationConfigured = true,
            queueNeedsAttention = true,
        )
        assertEquals(HomeNextStep.ViewEvents, step)

        val stepWithCount = HomeNextStep.resolve(
            notificationAccessGranted = true,
            hasDestinationConfigured = true,
            failedCount = 2L,
        )
        assertEquals(HomeNextStep.ViewEvents, stepWithCount)
    }

    @Test
    fun waitingToSendDoesNotTriggerRecoveryAction() {
        // Waiting items stay a count and do not trigger recovery; connection status stays visible
        val step = HomeNextStep.resolve(
            notificationAccessGranted = true,
            hasDestinationConfigured = true,
            failedCount = 0L,
        )
        assertEquals(HomeNextStep.None, step)
    }

    @Test
    fun healthyReturnsNone() {
        // Everything granted, configured, queue clear
        val step = HomeNextStep.resolve(
            notificationAccessGranted = true,
            hasDestinationConfigured = true,
            queueNeedsAttention = false,
        )
        assertEquals(HomeNextStep.None, step)
    }
}
