package com.gomoney.capture.sync

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import com.gomoney.capture.storage.NormalizedTransaction

/** Quiet, privacy-safe inline prompt to add context while it is still fresh. */
object MemoPromptNotifier {

    const val CHANNEL_ID = "transaction_memo_prompts"
    const val EXTRA_TRANSACTION_ID = "transaction_id"
    const val REMOTE_INPUT_KEY = "transaction_memo"
    const val MAX_MEMO_LENGTH = 200

    fun show(context: Context, tx: NormalizedTransaction) {
        if (!canPost(context)) return
        ensureChannel(context)

        val requestCode = tx.id.hashCode() and 0x7fffffff
        val intent = Intent(context, MemoReplyReceiver::class.java)
            .setData(Uri.parse("gomoney-capture://memo/${tx.id}"))
            .putExtra(EXTRA_TRANSACTION_ID, tx.id)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val remoteInput = RemoteInput.Builder(REMOTE_INPUT_KEY)
            .setLabel("e.g. groceries, lunch, taxi")
            .build()
        val action = Notification.Action.Builder(
            Icon.createWithResource(context, android.R.drawable.ic_menu_edit),
            "Add note",
            pendingIntent,
        ).addRemoteInput(remoteInput).build()

        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("What was this transaction for?")
            .setContentText("Reply with a short note, or add one later in Events.")
            .setStyle(
                Notification.BigTextStyle()
                    .bigText("Add a short note while it is fresh. The note is saved on this phone and synced to Go Money."),
            )
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .addAction(action)
            .build()

        manager(context).notify(requestCode, notification)
    }

    fun cancel(context: Context, txId: String) {
        manager(context).cancel(txId.hashCode() and 0x7fffffff)
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
                    "Add transaction context",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Reply to add a private note to a captured transaction."
                },
            )
        }
    }
}
