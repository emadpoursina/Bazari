package com.gomoney.capture.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gomoney.capture.logging.Redactor
import com.gomoney.capture.model.DeliveryState
import com.gomoney.capture.model.ErrorCategory
import com.gomoney.capture.storage.AppDatabase
import com.gomoney.capture.storage.DedupRepository
import com.gomoney.capture.storage.DeliveryRepository
import com.gomoney.capture.storage.SettingsRepository
import java.util.concurrent.TimeUnit

/**
 * WorkManager sync worker (US2, T031, FR-011/012/016): unique periodic +
 * expedited on-demand work, NetworkType.CONNECTED constraint, exponential
 * backoff; drains QUEUED/FAILED rows oldest-first; transitions
 * SENDING→SENT/FAILED; auto-retry on reachability without user action;
 * enqueued at pipeline completion and on app start so the queue survives
 * app/phone restarts.
 */
class TransactionSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val db = AppDatabase.get(applicationContext)
        val settings = SettingsRepository(applicationContext)
        val config = settings.current()
        if (config.serverUrl.isBlank()) return Result.success()

        val dao = db.deliveryRecordDao()
        val pendingBefore = dao.pendingForDelivery().size
        val deliveryRepository = DeliveryRepository(dao)
        val dedupRepository = DedupRepository(db.dedupCacheDao())

        SyncEngine.drain(db, config, deliveryRepository, dedupRepository)

        val pendingAfter = dao.pendingForDelivery()
        val banner = SyncBanner.forPending(pendingAfter)
        val sentDelta = (pendingBefore - pendingAfter.size).coerceAtLeast(0)
        runCatching {
            SyncNotifier.maybeNotify(applicationContext, pendingBefore, banner, sentDelta)
        }
        return Result.success()
    }
}

/**
 * Testable drain engine shared by the worker and unit tests (T031/T036):
 * drains QUEUED/FAILED rows oldest-first, one round-trip per item
 * (Q2=A: every success response means recorded — no internal queueing).
 */
object SyncEngine {

    suspend fun drain(
        db: AppDatabase,
        config: com.gomoney.capture.storage.ServerConfiguration,
        deliveryRepository: DeliveryRepository,
        dedupRepository: DedupRepository,
    ) {
        val bridge = BridgeClient()
        val dao = db.deliveryRecordDao()
        val txDao = db.normalizedTransactionDao()

        // Auto-retry (FR-012): FAILED rows with retryable transient errors
        // (network/server, except unauthorized) are requeued to QUEUED so the
        // periodic worker drains them when the bridge is reachable again.
        // Validation/parse/unauthorized failures stay FAILED for manual fix.
        for (record in dao.pendingForDelivery()) {
            if (isAutoRetryable(record)) {
                deliveryRepository.requeue(record.id)
            }
        }

        var pending = dao.pendingForDelivery().filter { it.state == "queued" }
        while (pending.isNotEmpty()) {
            val batch = pending.take(BridgeClient.BULK_MAX_ITEMS)
            val useBulk = pending.size >= BridgeClient.BULK_MIN_ITEMS

            for (record in batch) {
                val tx = txDao.byId(record.id) ?: continue // parse-error rows have no normalized tx

                // US3: local dedup short-circuit BEFORE sending so a restart
                // never re-sends known duplicates (FR-030).
                val known = dedupRepository.lookup(tx.fingerprint)
                if (known != null) {
                    deliveryRepository.markDuplicateAcked(tx.id, "local dedup cache: ${known.outcome}")
                    continue
                }

                // SENDING transition (attempts++ bookkeeping); skip illegal moves.
                deliveryRepository.transition(tx.id, DeliveryState.SENDING) ?: continue

                if (useBulk) {
                    // Drain optimization for many queued items: the whole
                    // batch is sent once; per-item results mapped below.
                    sendBulkMapped(bridge, config, batch, deliveryRepository, dedupRepository, txDao)
                    break
                }

                applyResult(bridge.send(config, tx), tx.id, tx.fingerprint, deliveryRepository, dedupRepository)
            }

            val next = dao.pendingForDelivery().filter { it.state == "queued" }
            if (next.size == pending.size && next == pending) {
                // No progress (all failures) — stop; the next WorkManager run
                // retries with exponential backoff (FR-011).
                return
            }
            pending = next
        }
    }

    /**
     * Retryable without user action: transient network/server failures.
     * Validation/parse failures and unauthorized (wrong token) need a manual
     * fix, so they stay FAILED instead of looping forever.
     */
    fun isAutoRetryable(record: com.gomoney.capture.storage.DeliveryRecord): Boolean {
        if (record.state != "failed") return false
        val category = record.errorCategory ?: return false
        if (category == ErrorCategory.NETWORK_ERROR.name.lowercase()) return true
        if (category == ErrorCategory.SERVER_ERROR.name.lowercase()) {
            return !(record.errorDetail ?: "").contains("unauthorized", ignoreCase = true)
        }
        return false
    }

    private suspend fun applyResult(
        result: BridgeClient.SendResult,
        deliveryId: String,
        fingerprint: String,
        deliveryRepository: DeliveryRepository,
        dedupRepository: DedupRepository,
    ) {
        when (result.status) {
            BridgeClient.SendResult.Status.CREATED -> {
                deliveryRepository.transition(deliveryId, DeliveryState.SENT)
                dedupRepository.record(fingerprint, "sent")
            }
            BridgeClient.SendResult.Status.DUPLICATE -> {
                // Duplicate ack is terminal: state=sent + category=duplicate (§3).
                deliveryRepository.markDuplicateAcked(deliveryId, "bridge duplicate ack")
                dedupRepository.record(fingerprint, "duplicate")
            }
            else -> {
                deliveryRepository.transition(
                    deliveryId,
                    DeliveryState.FAILED,
                    result.errorCategory ?: ErrorCategory.SERVER_ERROR,
                    Redactor.safeDetail(result.errorDetail ?: "delivery error"),
                )
            }
        }
    }

    private suspend fun sendBulkMapped(
        bridge: BridgeClient,
        config: com.gomoney.capture.storage.ServerConfiguration,
        batch: List<com.gomoney.capture.storage.DeliveryRecord>,
        deliveryRepository: DeliveryRepository,
        dedupRepository: DedupRepository,
        txDao: com.gomoney.capture.storage.NormalizedTransactionDao,
    ) {
        val txs = batch.mapNotNull { txDao.byId(it.id) }
        if (txs.isEmpty()) return
        val results = bridge.sendBulk(config, txs)
        txs.forEachIndexed { index, tx ->
            val result = results.getOrNull(index) ?: return@forEachIndexed
            applyResult(result, tx.id, tx.fingerprint, deliveryRepository, dedupRepository)
        }
    }

    fun enqueuePeriodic(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<TransactionSyncWorker>(2, TimeUnit.MINUTES)
                .setConstraints(connected())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build(),
        )
    }

    fun enqueueExpedited(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_EXPEDITED,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<TransactionSyncWorker>()
                .setConstraints(connected())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build(),
        )
    }

    private fun connected() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    private const val UNIQUE_PERIODIC = "transaction-sync-periodic"
    private const val UNIQUE_EXPEDITED = "transaction-sync-expedited"
}
