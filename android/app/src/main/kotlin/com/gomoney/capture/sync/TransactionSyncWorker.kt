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
import java.time.Duration
import java.time.OffsetDateTime
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
        val configs = settings.currentConfigs()
        if (configs.none { it.serverUrl.isNotBlank() }) return Result.success()

        val dao = db.deliveryRecordDao()
        val pendingBefore = dao.pendingForDelivery().size
        val deliveryRepository = DeliveryRepository(dao)
        val dedupRepository = DedupRepository(db.dedupCacheDao())

        // Every sync run implies a reachable bridge: refresh the server
        // account + category caches first so per-event selectors stay fresh.
        runCatching { SyncEngine.refreshCatalogs(db, configs) }

        SyncEngine.drain(db, configs, deliveryRepository, dedupRepository)

        // Periodic maintenance: enforce the parse-error retention cap (FR-028).
        runCatching {
            com.gomoney.capture.storage.MaintenanceRepository(
                rawEventDao = db.rawEventDao(),
                parseErrorDao = db.parseErrorDao(),
            ).pruneParseErrors()
        }

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
    ) = drain(db, listOf(config), deliveryRepository, dedupRepository)

    /**
     * Ordered-fallback drain: [configs] are tried in order and the first
     * reachable endpoint serves every call in this run (ping selection is
     * implicit — each `*WithFallback` call walks the same order, so a dead
     * first endpoint transparently falls to the next for sends, memos,
     * assignments and bulk).
     */
    suspend fun drain(
        db: AppDatabase,
        configs: List<com.gomoney.capture.storage.ServerConfiguration>,
        deliveryRepository: DeliveryRepository,
        dedupRepository: DedupRepository,
    ) {
        val bridge = BridgeClient()
        val dao = db.deliveryRecordDao()
        val txDao = db.normalizedTransactionDao()
        if (configs.none { it.serverUrl.isNotBlank() }) return

        // Orphaned-SENDING recovery: a run killed between the SENDING
        // transition and its terminal result parks rows in `sending`, which
        // neither requeue nor pendingForDelivery ever touches again — the
        // row would stay stuck showing a stale error forever. Requeue stale
        // ones so the outbox self-heals on the next run.
        val staleCutoff = OffsetDateTime.now().minus(STALE_SENDING_AFTER)
        for (record in dao.sending()) {
            deliveryRepository.requeueStaleSending(record.id, staleCutoff)
        }

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
                    sendBulkMapped(bridge, configs, batch, dao, deliveryRepository, dedupRepository, txDao)
                    break
                }

                applyResult(bridge.sendWithFallback(configs, tx), tx, deliveryRepository, dedupRepository, txDao)
            }

            val next = dao.pendingForDelivery().filter { it.state == "queued" }
            if (next.size == pending.size && next == pending) {
                // No progress (all failures) — stop; the next WorkManager run
                // retries with exponential backoff (FR-011).
                break
            }
            pending = next
        }

        syncPendingMemos(bridge, configs, txDao)
        syncPendingAssignments(bridge, configs, txDao)
    }

    /**
     * 007 FR-004: scoped drain of only [ids]. Same per-item logic as [drain]
     * but every stage is filtered to the selected set, so unselected rows are
     * never requeued, transitioned, sent, or memo/assignment-synced by this run.
     */
    suspend fun drainIds(
        db: AppDatabase,
        configs: List<com.gomoney.capture.storage.ServerConfiguration>,
        deliveryRepository: DeliveryRepository,
        dedupRepository: DedupRepository,
        ids: Set<String>,
    ) {
        if (ids.isEmpty()) return
        if (configs.none { it.serverUrl.isNotBlank() }) return
        val bridge = BridgeClient()
        val dao = db.deliveryRecordDao()
        val txDao = db.normalizedTransactionDao()

        // Orphaned-SENDING recovery, scoped to the selection only (007
        // FR-004: unselected rows are never touched by this run).
        val staleCutoff = OffsetDateTime.now().minus(STALE_SENDING_AFTER)
        for (record in dao.sending()) {
            if (record.id !in ids) continue
            deliveryRepository.requeueStaleSending(record.id, staleCutoff)
        }

        for (record in dao.pendingForDelivery()) {
            if (record.id !in ids) continue
            if (isAutoRetryable(record)) {
                deliveryRepository.requeue(record.id)
            }
        }

        var pending = dao.pendingForDelivery().filter { it.state == "queued" && it.id in ids }
        while (pending.isNotEmpty()) {
            val batch = pending.take(BridgeClient.BULK_MAX_ITEMS)
            val useBulk = pending.size >= BridgeClient.BULK_MIN_ITEMS

            for (record in batch) {
                val tx = txDao.byId(record.id) ?: continue
                val known = dedupRepository.lookup(tx.fingerprint)
                if (known != null) {
                    deliveryRepository.markDuplicateAcked(tx.id, "local dedup cache: ${known.outcome}")
                    continue
                }
                deliveryRepository.transition(tx.id, DeliveryState.SENDING) ?: continue
                if (useBulk) {
                    sendBulkMapped(bridge, configs, batch, dao, deliveryRepository, dedupRepository, txDao)
                    break
                }
                applyResult(bridge.sendWithFallback(configs, tx), tx, deliveryRepository, dedupRepository, txDao)
            }

            val next = dao.pendingForDelivery().filter { it.state == "queued" && it.id in ids }
            if (next.size == pending.size && next == pending) break
            pending = next
        }

        for (tx in txDao.pendingMemos()) {
            if (tx.id !in ids) continue
            val result = bridge.updateMemoWithFallback(configs, tx)
            if (result.ok) txDao.markMemoSynced(tx.id, tx.userMemo)
        }
        for (tx in txDao.pendingAssignments()) {
            if (tx.id !in ids) continue
            val result = bridge.updateAssignmentWithFallback(configs, tx)
            if (result.ok) {
                txDao.markAssignmentSynced(tx.id, tx.destinationAccountId, tx.categoryId)
            }
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
        tx: com.gomoney.capture.storage.NormalizedTransaction,
        deliveryRepository: DeliveryRepository,
        dedupRepository: DedupRepository,
        txDao: com.gomoney.capture.storage.NormalizedTransactionDao,
    ) {
        when (result.status) {
            BridgeClient.SendResult.Status.CREATED -> {
                deliveryRepository.transition(tx.id, DeliveryState.SENT)
                result.gomoneyTxnId?.let { txDao.updateGomoneyTxnId(tx.id, it) }
                txDao.markMemoSynced(tx.id, tx.userMemo)
                txDao.markAssignmentSynced(tx.id, tx.destinationAccountId, tx.categoryId)
                dedupRepository.record(tx.fingerprint, "sent")
            }
            BridgeClient.SendResult.Status.DUPLICATE -> {
                // Duplicate ack is terminal: state=sent + category=duplicate (§3).
                deliveryRepository.markDuplicateAcked(tx.id, "bridge duplicate ack")
                result.gomoneyTxnId?.let { txDao.updateGomoneyTxnId(tx.id, it) }
                if (tx.memoSyncState != "pending") txDao.markMemoSynced(tx.id, tx.userMemo)
                txDao.markAssignmentSynced(tx.id, tx.destinationAccountId, tx.categoryId)
                dedupRepository.record(tx.fingerprint, "duplicate")
            }
            else -> {
                deliveryRepository.transition(
                    tx.id,
                    DeliveryState.FAILED,
                    result.errorCategory ?: ErrorCategory.SERVER_ERROR,
                    Redactor.safeDetail(result.errorDetail ?: "delivery error"),
                )
            }
        }
    }

    private suspend fun sendBulkMapped(
        bridge: BridgeClient,
        configs: List<com.gomoney.capture.storage.ServerConfiguration>,
        batch: List<com.gomoney.capture.storage.DeliveryRecord>,
        deliveryDao: com.gomoney.capture.storage.DeliveryRecordDao,
        deliveryRepository: DeliveryRepository,
        dedupRepository: DedupRepository,
        txDao: com.gomoney.capture.storage.NormalizedTransactionDao,
    ) {
        val txs = batch.mapNotNull { record ->
            val current = deliveryDao.byId(record.id) ?: return@mapNotNull null
            if (current.state == "queued") {
                deliveryRepository.transition(record.id, DeliveryState.SENDING) ?: return@mapNotNull null
            } else if (current.state != "sending") {
                return@mapNotNull null
            }
            txDao.byId(record.id)
        }
        if (txs.isEmpty()) return
        val results = bridge.sendBulkWithFallback(configs, txs)
        txs.forEachIndexed { index, tx ->
            val result = results.getOrNull(index) ?: return@forEachIndexed
            applyResult(result, tx, deliveryRepository, dedupRepository, txDao)
        }
    }

    /**
     * Best-effort catalog refresh: pull the server account + category lists
     * into the offline-tolerant cache. Called on every sync run (which only
     * runs when a bridge endpoint is reachable) so the per-event selectors
     * stay fresh. Never throws; a failure keeps the last known lists.
     * Returns true only when both sides refreshed.
     */
    suspend fun refreshCatalogs(
        db: AppDatabase,
        configs: List<com.gomoney.capture.storage.ServerConfiguration>,
    ): Boolean = runCatching {
        CatalogRepository(db.serverAccountDao(), db.serverCategoryDao()).refreshAll(configs)
    }.getOrDefault(false)

    private suspend fun syncPendingMemos(
        bridge: BridgeClient,
        configs: List<com.gomoney.capture.storage.ServerConfiguration>,
        txDao: com.gomoney.capture.storage.NormalizedTransactionDao,
    ) {
        for (tx in txDao.pendingMemos()) {
            val result = bridge.updateMemoWithFallback(configs, tx)
            if (result.ok) txDao.markMemoSynced(tx.id, tx.userMemo)
        }
    }

    /**
     * Push destination account/category choices made after delivery to the same
     * Go Money transaction (FR-020, SC-005), mirroring the memo-sync pattern.
     */
    private suspend fun syncPendingAssignments(
        bridge: BridgeClient,
        configs: List<com.gomoney.capture.storage.ServerConfiguration>,
        txDao: com.gomoney.capture.storage.NormalizedTransactionDao,
    ) {
        for (tx in txDao.pendingAssignments()) {
            val result = bridge.updateAssignmentWithFallback(configs, tx)
            if (result.ok) {
                txDao.markAssignmentSynced(tx.id, tx.destinationAccountId, tx.categoryId)
            }
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

    /**
     * A SENDING row older than this is an orphan from an interrupted run:
     * real sends are bounded by OkHttp's connect/read timeouts, so anything
     * past a few minutes means the process died before the terminal
     * SENT/FAILED transition was written.
     */
    private val STALE_SENDING_AFTER: Duration = Duration.ofMinutes(5)

    private const val UNIQUE_PERIODIC = "transaction-sync-periodic"
    private const val UNIQUE_EXPEDITED = "transaction-sync-expedited"
}
