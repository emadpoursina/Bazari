package com.gomoney.capture.capture

import android.app.Notification
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.gomoney.capture.source.ParseErrorRepository
import com.gomoney.capture.source.TransactionSourceRepository
import com.gomoney.capture.source.UserSourceParser
import com.gomoney.capture.storage.AppDatabase
import com.gomoney.capture.storage.DedupRepository
import com.gomoney.capture.storage.DeliveryRepository
import com.gomoney.capture.storage.RawEvent
import com.gomoney.capture.storage.SettingsRepository
import com.gomoney.capture.sync.MemoPromptNotifier
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Notification capture (US1, T019, FR-001/002/003; 005 FR-013; 006 FR-002):
 * writes a RawEvent through the source-only CapturePipeline — an event is
 * considered only when an enabled TransactionSource claims its
 * identifier/channel; there is no Settings allow-list and no shipped-parser
 * fallback.
 *
 * 006: BOTH entry points — the real-time `onNotificationPosted` and the
 * manual `ACTION_SCAN` loop — run the identical protocol
 * (contracts/notification-identity.md §2): identity claim → shared extract →
 * CaptureGate → CapturePipeline.process → commit/release, so FR-003 (at most
 * one event per notification) holds across scans, real-time posts and
 * in-place content updates.
 */
class NotificationCaptureService : NotificationListenerService(), ScanPerformer {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** FR-007: at most one scan at a time, independent of the UI guard. */
    private val scanInProgress = AtomicBoolean(false)

    @Volatile
    private var listenerConnected = false

    private lateinit var pipeline: CapturePipeline
    private lateinit var gate: CaptureGate
    private lateinit var registry: NotificationIdentityRegistry

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
            sourceRepository = TransactionSourceRepository(db.transactionSourceDao(), db.parseErrorDao(), db),
            userSourceParser = UserSourceParser(ParseErrorRepository(db.parseErrorDao()), db.serverAccountDao()),
        )
        gate = CaptureGate(PlatformCapturePermission(context))
        registry = NotificationIdentityRegistry(db.notificationCaptureRecordDao())
        // Research R1: publish the in-process handle; cleared in onDestroy.
        ScanCoordinator.registerHandle(this)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        // The platform requires onListenerConnected before getActiveNotifications().
        listenerConnected = true
        ScanCoordinator.registerHandle(this)
    }

    override fun onDestroy() {
        listenerConnected = false
        ScanCoordinator.unregisterHandle(this)
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // 006 T010: identical protocol to the scan loop — claim → extract →
        // gate → pipeline → commit/release. Counts are a scan-only concern
        // and are discarded here (contract §3).
        scope.launch { processNotification(sbn) }
    }

    /** Documented scan transport (contracts/scan-coordination.md §1). */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SCAN) performScan()
        return START_NOT_STICKY
    }

    /**
     * Scan loop (006 T015, FR-001/007/008): reads the active notifications on
     * a background thread (this scope is `Dispatchers.IO` — never call
     * `getActiveNotifications()` on main), runs each one through the shared
     * capture path, publishes `Running` and the final `Result` counts.
     * Additive only: an existing event is never modified or deleted (FR-010).
     */
    override fun performScan() {
        // FR-007 single-flight: a press or a second request during a scan is
        // a no-op here, regardless of the UI-side guard.
        if (!scanInProgress.compareAndSet(false, true)) return
        ScanCoordinator.publishRunning()
        scope.launch {
            try {
                scanActiveNotifications()
            } finally {
                scanInProgress.set(false)
            }
        }
    }

    private suspend fun scanActiveNotifications() {
        // Research R9: only a connected listener may read the active list —
        // never report a misleading "0 examined" when disconnected.
        if (!listenerConnected) {
            ScanCoordinator.publishUnavailable()
            return
        }
        val active = runCatching { getActiveNotifications() }
            .getOrElse {
                // Spec assumption: if reading the list is impossible, fail
                // cleanly with guidance rather than partially succeeding.
                ScanCoordinator.publishUnavailable()
                return
            }
            .orEmpty()
        val items = active.map { sbn -> processNotification(sbn) }
        ScanCoordinator.publishResult(countScan(items))
    }

    /**
     * Shared capture path for BOTH entry points (006 T009/T010, research R2/R3,
     * contracts/notification-identity.md §2):
     *
     * ```
     * claim(key) → shared extractText/safePostedAt → CaptureGate →
     * CapturePipeline.process(RawEvent) → commit (persisted) / release
     * ```
     *
     * Returns the notification's scan disposition; the real-time path simply
     * discards it. Raw text is never logged (FR-028).
     */
    private suspend fun processNotification(sbn: StatusBarNotification): ScanItem {
        val key = sbn.key
        when (registry.claim(key)) {
            ClaimOutcome.ALREADY_RECORDED -> return ScanItem.AlreadyRecorded
            ClaimOutcome.IN_PROGRESS -> return ScanItem.ClaimRejected
            ClaimOutcome.GRANTED -> Unit // fall through — we own the key
        }

        val pkg = sbn.packageName
        val text = extractText(sbn)
        if (pkg == null || text == null) {
            // Nothing persisted → re-examinable by a later scan/post.
            registry.release(key)
            return if (pkg == null) ScanItem.UnusableNotification else ScanItem.NoExtractableText
        }

        // Permission gate: revoked access stops capture; queued data is
        // preserved (edge case) and the key is released, not swallowed.
        if (!gate.allowsNotificationCapture()) {
            registry.release(key)
            return ScanItem.GateClosed
        }

        val event = RawEvent(
            id = UUID.randomUUID().toString(),
            source = "notification",
            sourcePackage = pkg,
            bank = null,
            // BigText-aware title, same as always (no scan-specific rules).
            title = sbn.notification.extras
                .getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            text = text,
            // The notification's own postTime, not scan time (data-model §2).
            postedAt = safePostedAt(sbn.postTime),
            capturedAt = OffsetDateTime.now().toString(),
        )
        val outcome = pipeline.process(event)
        if (outcome.isPersisted()) {
            // Terminal: this notification may never produce another event.
            registry.commit(key, event.id)
        } else {
            registry.release(key)
        }
        return ScanItem.Processed(outcome)
    }
}

/**
 * BigText-aware extraction (006 T009): some banks (e.g. Blue) post a short
 * EXTRA_TEXT and the full message in EXTRA_BIG_TEXT. Taking only the first
 * would lose the transaction label/amount, so the longer non-blank variant
 * wins. Shared top-level function so the real-time path and the scan loop
 * can never drift (research R3). No raw text is ever logged here (FR-028).
 */
fun extractText(sbn: StatusBarNotification): String? {
    val extras = sbn.notification.extras
    val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
    val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
    return listOf(text, bigText)
        .filterNotNull()
        .filter { it.isNotBlank() }
        .maxByOrNull { it.length }
}

/**
 * Use the notification's own postTime (epoch millis) as the event timestamp
 * (006 T009, shared by both capture paths) — safer than capture-time wall
 * clock when delivery is delayed; fall back to now() when postTime is
 * missing/implausible.
 */
fun safePostedAt(postTime: Long): String {
    if (postTime > 0) {
        return runCatching {
            OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(postTime), java.time.ZoneId.systemDefault())
                .toString()
        }.getOrDefault(OffsetDateTime.now().toString())
    }
    return OffsetDateTime.now().toString()
}
