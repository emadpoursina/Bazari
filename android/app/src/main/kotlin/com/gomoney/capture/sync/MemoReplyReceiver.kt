package com.gomoney.capture.sync

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.gomoney.capture.storage.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Saves an inline notification reply locally before requesting a sync. */
class MemoReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val txId = intent.getStringExtra(MemoPromptNotifier.EXTRA_TRANSACTION_ID) ?: return
        val results = RemoteInput.getResultsFromIntent(intent) ?: return
        val reply = results.getCharSequence(MemoPromptNotifier.REMOTE_INPUT_KEY)
            ?.toString()
            ?.trim()
            ?.take(MemoPromptNotifier.MAX_MEMO_LENGTH)
            ?.takeIf { it.isNotBlank() }
            ?: return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val db = AppDatabase.get(context)
                if (db.normalizedTransactionDao().updateMemo(txId, reply) > 0) {
                    MemoPromptNotifier.cancel(context, txId)
                    SyncEngine.enqueueExpedited(context)
                }
            } catch (_: Exception) {
                // Keep the broadcast receiver from crashing the app process.
                // The transaction remains available for editing from Events.
            } finally {
                pendingResult.finish()
            }
        }
    }
}
