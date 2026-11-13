package com.gomoney.capture.sync

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * 009 FR-012–FR-016 (contracts/nightly-leftover.md):
 * Posts at most one end-of-night reminder for leftover work (fixed id 21).
 * Channel 'nightly_leftover' (IMPORTANCE_LOW).
 * Tapping opens MainActivity with target=leftover.
 */
object NightlyLeftoverNotifier {

    const val CHANNEL_ID = "nightly_leftover"
    const val NOTIFICATION_ID = 21

    fun notify(context: Context, unsyncedCount: Int, missingNoteCount: Int) {
        if (!canPost(context)) return
        ensureChannel(context)

        val intent = NotificationOpen.createLeftoverIntent(context)
        val contentIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val text = NightlyLeftover.formatReminderText(unsyncedCount, missingNoteCount)
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Go Money: leftover work")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setCategory(Notification.CATEGORY_REMINDER)
            .build()

        manager(context).notify(NOTIFICATION_ID, notification)
    }

    fun cancel(context: Context) {
        manager(context).cancel(NOTIFICATION_ID)
    }

    private fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun manager(context: Context): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val notificationManager = manager(context)
        if (notificationManager.getNotificationChannel(CHANNEL_ID) == null) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Nightly leftover reminder",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "End-of-night reminder for unsynced transactions or blank notes."
                },
            )
        }
    }
}
