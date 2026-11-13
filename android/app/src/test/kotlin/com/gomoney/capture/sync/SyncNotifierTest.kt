package com.gomoney.capture.sync

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncNotifierTest {

    private lateinit var context: Context
    private lateinit var notificationManager: NotificationManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    @Test
    fun `maybeNotify posts nothing and cancels lingering daytime sync notifications`() {
        // Seed preexisting notifications for IDs 11, 12, 13
        val dummyNotification = Notification.Builder(context)
            .setContentTitle("Preexisting")
            .setContentText("Status")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        notificationManager.notify(SyncNotifier.ID_PENDING, dummyNotification)
        notificationManager.notify(SyncNotifier.ID_ATTENTION, dummyNotification)
        notificationManager.notify(SyncNotifier.ID_CAUGHT_UP, dummyNotification)

        assertEquals(3, notificationManager.activeNotifications.size)

        // Call maybeNotify with OFFLINE_RETRYABLE
        val bannerWaiting = SyncBanner.Banner(kind = SyncBanner.Kind.OFFLINE_RETRYABLE, pendingTotal = 2)
        SyncNotifier.maybeNotify(context, pendingBefore = 2, banner = bannerWaiting, sentDelta = 0)

        // Should post nothing and cancel 11, 12, 13
        assertEquals(0, notificationManager.activeNotifications.size)

        // Seed again and test with ALL_CLEAR / caught up
        notificationManager.notify(SyncNotifier.ID_PENDING, dummyNotification)
        val bannerClear = SyncBanner.Banner(kind = SyncBanner.Kind.ALL_CLEAR)
        SyncNotifier.maybeNotify(context, pendingBefore = 1, banner = bannerClear, sentDelta = 1)
        assertEquals(0, notificationManager.activeNotifications.size)

        // Seed again and test with NEEDS_ATTENTION
        notificationManager.notify(SyncNotifier.ID_ATTENTION, dummyNotification)
        val bannerAttention = SyncBanner.Banner(kind = SyncBanner.Kind.NEEDS_ATTENTION, needsAttentionCount = 1)
        SyncNotifier.maybeNotify(context, pendingBefore = 1, banner = bannerAttention, sentDelta = 0)
        assertEquals(0, notificationManager.activeNotifications.size)
    }
}
