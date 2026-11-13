package com.gomoney.capture.sync

import android.app.NotificationManager
import android.content.Context

/**
 * 009 FR-003: Daytime sync-status system notifications are silenced.
 * [maybeNotify] is a no-op that cancels leftover sync shade ids 11/12/13
 * so lingering banners do not remain. In-app Dashboard SyncBanner is unchanged.
 */
object SyncNotifier {

    const val CHANNEL_ID = "gomoney_sync"
    const val ID_PENDING = 11
    const val ID_ATTENTION = 12
    const val ID_CAUGHT_UP = 13

    fun maybeNotify(
        context: Context,
        pendingBefore: Int,
        banner: SyncBanner.Banner,
        sentDelta: Int,
    ) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        manager.cancel(ID_PENDING)
        manager.cancel(ID_ATTENTION)
        manager.cancel(ID_CAUGHT_UP)
    }

    fun outageKey(banner: SyncBanner.Banner): String =
        "${banner.pendingTotal}|${banner.lastCategory}|${banner.lastAttemptAt}"
}
