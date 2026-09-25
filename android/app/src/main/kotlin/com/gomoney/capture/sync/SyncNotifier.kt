package com.gomoney.capture.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Home reminders (FR-020/023): system notifications so the user never forgets
 * a pending queue when back home. Throttled via SharedPreferences — one buzz
 * per outage/attention state, plus a single caught-up confirmation.
 * No raw bank text ever enters a notification (FR-028).
 */
object SyncNotifier {

    const val CHANNEL_ID = "gomoney_sync"
    private const val PREFS = "sync_notifier"
    private const val KEY_LAST_ATTENTION = "last_attention_count"
    private const val KEY_LAST_OUTAGE_KEY = "last_outage_key"
    private const val ID_PENDING = 11
    private const val ID_ATTENTION = 12
    private const val ID_CAUGHT_UP = 13

    fun maybeNotify(
        context: Context,
        pendingBefore: Int,
        banner: SyncBanner.Banner,
        sentDelta: Int,
    ) {
        if (!canPost(context)) return
        ensureChannel(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        when {
            banner.kind == SyncBanner.Kind.ALL_CLEAR && pendingBefore > 0 -> {
                manager.notify(ID_CAUGHT_UP, build(context, "Go Money: caught up", caughtUpText(sentDelta)))
                prefs.edit().remove(KEY_LAST_OUTAGE_KEY).remove(KEY_LAST_ATTENTION).apply()
                manager.cancel(ID_PENDING)
                manager.cancel(ID_ATTENTION)
            }
            banner.kind == SyncBanner.Kind.NEEDS_ATTENTION -> {
                val last = prefs.getInt(KEY_LAST_ATTENTION, -1)
                if (last != banner.needsAttentionCount) {
                    manager.notify(
                        ID_ATTENTION,
                        build(context, "Go Money: action needed", SyncBanner.message(banner)),
                    )
                    prefs.edit().putInt(KEY_LAST_ATTENTION, banner.needsAttentionCount).apply()
                }
            }
            banner.kind == SyncBanner.Kind.OFFLINE_RETRYABLE -> {
                val key = outageKey(banner)
                if (prefs.getString(KEY_LAST_OUTAGE_KEY, null) != key) {
                    manager.notify(ID_PENDING, build(context, "Go Money: waiting to sync", SyncBanner.message(banner)))
                    prefs.edit().putString(KEY_LAST_OUTAGE_KEY, key).apply()
                }
            }
            else -> Unit
        }
    }

    fun outageKey(banner: SyncBanner.Banner): String =
        "${banner.pendingTotal}|${banner.lastCategory}|${banner.lastAttemptAt}"

    private fun caughtUpText(sentDelta: Int): String =
        if (sentDelta > 0) "Synced $sentDelta transaction${if (sentDelta == 1) "" else "s"} to Go Money."
        else "Queue drained — everything is in Go Money."

    private fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Sync status", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
    }

    private fun build(context: Context, title: String, text: String): android.app.Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            android.app.Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(context)
        }
        return builder
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(android.app.Notification.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .build()
    }
}
