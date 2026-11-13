package com.gomoney.capture.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.gomoney.capture.ui.MainActivity

object NotificationOpen {
    const val EXTRA_OPEN_EVENT_ID = "com.gomoney.capture.extra.OPEN_EVENT_ID"
    const val EXTRA_OPEN_TARGET = "com.gomoney.capture.extra.OPEN_TARGET"

    const val TARGET_DETAIL = "detail"
    const val TARGET_LEFTOVER = "leftover"

    const val URI_SCHEME = "gomoney-capture"
    const val URI_HOST_EVENT = "event"
    const val URI_HOST_LEFTOVER = "leftover"

    const val ACTIVITY_FLAGS =
        Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_SINGLE_TOP or
            Intent.FLAG_ACTIVITY_CLEAR_TOP

    fun eventUri(txId: String): Uri = Uri.parse("$URI_SCHEME://$URI_HOST_EVENT/$txId")

    fun leftoverUri(): Uri = Uri.parse("$URI_SCHEME://$URI_HOST_LEFTOVER")

    fun createDetailIntent(context: Context, txId: String): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = eventUri(txId)
            flags = ACTIVITY_FLAGS
            putExtra(EXTRA_OPEN_TARGET, TARGET_DETAIL)
            putExtra(EXTRA_OPEN_EVENT_ID, txId)
        }

    fun createLeftoverIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = leftoverUri()
            flags = ACTIVITY_FLAGS
            putExtra(EXTRA_OPEN_TARGET, TARGET_LEFTOVER)
        }
}
