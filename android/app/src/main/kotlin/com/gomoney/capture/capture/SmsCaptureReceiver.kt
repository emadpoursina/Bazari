package com.gomoney.capture.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsMessage
import com.gomoney.capture.storage.AppDatabase
import com.gomoney.capture.storage.SettingsRepository
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * SMS capture (US5, T043, FR-004/017): independently toggleable second source;
 * gated by smsCaptureEnabled (ignored entirely when off); bank SMS sender
 * allow-list from enabledBankPackages-style config; writes RawEvent(source=sms)
 * through the SAME pipeline so dedup fingerprints match notification captures.
 */
class SmsCaptureReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.provider.Telephony.SMS_RECEIVED") return

        val settings = SettingsRepository(context)
        val db = AppDatabase.get(context)
        val pipeline = CapturePipeline(
            db = db,
            settings = settings,
            deliveryRepository = DeliveryRepository(db.deliveryRecordDao()),
            dedupRepository = DedupRepository(db.dedupCacheDao()),
            onCaptured = { TransactionSyncHelper.enqueueExpedited(context) },
        )
        val gate = CaptureGate(PlatformCapturePermission(context))

        val messages = readMessages(intent)
        val now = OffsetDateTime.now().toString()

        val go = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                for (message in messages) {
                    val sender = message.originatingAddress ?: continue
                    // FR-017: SMS ignored entirely when the toggle is off or
                    // the sender is not allow-listed.
                    if (!gate.allowsSmsCapture()) return@launch
                    pipeline.process(
                        RawEvent(
                            id = UUID.randomUUID().toString(),
                            source = "sms",
                            sourcePackage = sender,
                            bank = null,
                            title = null,
                            text = message.messageBody ?: continue,
                            postedAt = now,
                            capturedAt = now,
                        ),
                    )
                }
            } finally {
                go()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun readMessages(intent: Intent): List<SmsMessage> {
        val bundle = intent.extras ?: return emptyList()
        val pdus = bundle.get("pdus") as? Array<*> ?: return emptyList()
        return pdus.mapNotNull { pdu ->
            @Suppress("DEPRECATION")
            SmsMessage.createFromPdu(pdu as ByteArray)
        }
    }
}
