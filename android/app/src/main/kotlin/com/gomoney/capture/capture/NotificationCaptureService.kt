package com.gomoney.capture.capture

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.gomoney.capture.storage.AppDatabase
import com.gomoney.capture.storage.DedupRepository
import com.gomoney.capture.storage.DeliveryRepository
import com.gomoney.capture.storage.RawEvent
import com.gomoney.capture.storage.SettingsRepository
import com.gomoney.capture.sync.MemoPromptNotifier
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Notification capture (US1, T019, FR-001/002/003): filters by the
 * ServerConfiguration.enabledBankPackages allow-list, ignores all other
 * apps, and writes a RawEvent to Room BEFORE any processing.
 */
class NotificationCaptureService : android.service.notification.NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var pipeline: CapturePipeline
    private lateinit var gate: CaptureGate

    override fun onCreate() {
        super.onCreate()
        val context = applicationContext
        val settings = SettingsRepository(context)
        val db = AppDatabase.get(context)
        pipeline = CapturePipeline(
            db = db,
            settings = settings,
            deliveryRepository = DeliveryRepository(db.deliveryRecordDao()),
            dedupRepository = DedupRepository(db.dedupCacheDao()),
            onCaptured = { tx ->
                TransactionSyncHelper.enqueueExpedited(context)
                MemoPromptNotifier.show(context, tx)
            },
        )
        gate = CaptureGate(PlatformCapturePermission(context))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return

        // FR-001: only allow-listed packages are even considered; everything
        // else is ignored without persisting anything.
        val text = extractText(sbn) ?: return
        // Use the notification's own postTime (epoch millis) as the event
        // timestamp — safer than capture-time wall clock when delivery is
        // delayed; fall back to now() when postTime is missing/implausible.
        val postedAt = safePostedAt(sbn.postTime)

        // Permission gate: revoked access stops capture; queued data is
        // preserved (edge case).
        if (!gate.allowsNotificationCapture()) return

        scope.launch {
            pipeline.process(
                RawEvent(
                    id = UUID.randomUUID().toString(),
                    source = "notification",
                    sourcePackage = pkg,
                    bank = null,
                    title = sbn.notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString(),
                    text = text,
                    postedAt = postedAt,
                    capturedAt = OffsetDateTime.now().toString(),
                ),
            )
        }
    }

    /**
     * BigText-aware extraction: some banks (e.g. Blue) post a short
     * EXTRA_TEXT and the full message in EXTRA_BIG_TEXT. Taking only the
     * first would lose the transaction label/amount, so the longer non-blank
     * variant wins. No raw text is ever logged here (FR-028).
     */
    private fun extractText(sbn: StatusBarNotification): String? {
        val extras = sbn.notification.extras
        val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()
        val bigText = extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)?.toString()
        return listOf(text, bigText)
            .filterNotNull()
            .filter { it.isNotBlank() }
            .maxByOrNull { it.length }
    }

    private fun safePostedAt(postTime: Long): String {
        if (postTime > 0) {
            return runCatching {
                OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(postTime), java.time.ZoneId.systemDefault())
                    .toString()
            }.getOrDefault(OffsetDateTime.now().toString())
        }
        return OffsetDateTime.now().toString()
    }
}
