package com.gomoney.capture.ui.theme

/**
 * Single primary recovery next step on Home / DashboardScreen.
 *
 * Only failed sends, missing notification access, or a missing destination
 * get a recovery action. Waiting-to-send items stay a count and do not
 * trigger a recovery action so connection status remains visible.
 */
sealed interface HomeNextStep {
    data object None : HomeNextStep
    data object GrantNotificationAccess : HomeNextStep
    data object ConfigureDestination : HomeNextStep
    data object ViewEvents : HomeNextStep

    companion object {
        fun resolve(
            notificationAccessGranted: Boolean,
            hasDestinationConfigured: Boolean,
            queueNeedsAttention: Boolean = false,
        ): HomeNextStep {
            return when {
                !notificationAccessGranted -> GrantNotificationAccess
                !hasDestinationConfigured -> ConfigureDestination
                queueNeedsAttention -> ViewEvents
                else -> None
            }
        }

        fun resolve(
            notificationAccessGranted: Boolean,
            hasDestinationConfigured: Boolean,
            failedCount: Long,
        ): HomeNextStep = resolve(
            notificationAccessGranted = notificationAccessGranted,
            hasDestinationConfigured = hasDestinationConfigured,
            queueNeedsAttention = failedCount > 0,
        )
    }
}
