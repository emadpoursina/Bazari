package com.gomoney.capture.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Deep link to the system notification-access screen with a fallback.
 * Shared across Home, Events, and Settings.
 */
fun openNotificationAccessSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
