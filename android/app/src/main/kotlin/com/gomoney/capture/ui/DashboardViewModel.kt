package com.gomoney.capture.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gomoney.capture.capture.PlatformCapturePermission
import com.gomoney.capture.storage.AppDatabase
import com.gomoney.capture.storage.ServerConfiguration
import com.gomoney.capture.storage.SettingsRepository
import com.gomoney.capture.sync.CatalogRepository
import com.gomoney.capture.sync.SyncBanner
import com.gomoney.capture.sync.SyncEngine
import com.gomoney.capture.ui.theme.HomeNextStep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class HomeConnectionStatus {
    Reachable,
    NotReachable,
    NotSetUpYet,
}

/**
 * Dashboard / Home (US1, US4, T015, FR-020): connection status + captured-today / waiting /
 * failed counts + recent activity preview + single next step, backed by Room Flow queries.
 * No spending totals, budgets, or charts (FR-020).
 */
class DashboardViewModel(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val permission: PlatformCapturePermission,
) : ViewModel() {

    data class DashboardState(
        val capturedToday: Long = 0,
        val pending: Long = 0,
        val waiting: Long = 0,
        val sent: Long = 0,
        val failed: Long = 0,
        val connection: ConnectionStatus = ConnectionStatus.UNKNOWN,
        val connectionStatus: HomeConnectionStatus = HomeConnectionStatus.NotSetUpYet,
        val captureBlocked: Boolean = false,
        val nextStep: HomeNextStep = HomeNextStep.None,
        val recentRows: List<EventRow> = emptyList(),
        val config: ServerConfiguration = ServerConfiguration(),
        val activeEndpoint: String? = null,
        val banner: SyncBanner.Banner = SyncBanner.Banner(SyncBanner.Kind.ALL_CLEAR),
        val bannerMessage: String = SyncBanner.message(SyncBanner.Banner(SyncBanner.Kind.ALL_CLEAR)),
    )

    enum class ConnectionStatus { CONNECTED, GO_MONEY_DOWN, DISCONNECTED, UNKNOWN }

    private val connection = MutableStateFlow(ConnectionStatus.UNKNOWN)

    /** Name of the endpoint that answered the last ping (null = none/unknown). */
    private val activeEndpointName = MutableStateFlow<String?>(null)
    val activeEndpoint: StateFlow<String?> = activeEndpointName

    val state: StateFlow<DashboardState> =
        combine(
            db.normalizedTransactionDao().observeRecent(500),
            db.deliveryRecordDao().observePending(),
            settings.observe(),
            connection,
            activeEndpointName,
        ) { txs, pendingRows, config, connection, activeEndpoint ->
            val pendingById = pendingRows.associateBy { it.id }
            val banner = SyncBanner.forPending(pendingRows)
            val failedCount = pendingRows.count { it.state == "failed" }.toLong()
            val waitingCount = maxOf(0L, pendingRows.size.toLong() - failedCount)
            val captureBlocked = !permission.isNotificationListenerAccessGranted()
            val hasDest = config.serverUrl.isNotBlank()

            val connectionStatus = when {
                !hasDest -> HomeConnectionStatus.NotSetUpYet
                connection == ConnectionStatus.CONNECTED -> HomeConnectionStatus.Reachable
                else -> HomeConnectionStatus.NotReachable
            }

            val nextStep = HomeNextStep.resolve(
                notificationAccessGranted = !captureBlocked,
                hasDestinationConfigured = hasDest,
                queueNeedsAttention = failedCount > 0,
            )

            val recentRows = txs.take(5).map { tx ->
                val pending = pendingById[tx.id]
                EventRow(
                    amountMinor = tx.amountMinor,
                    bank = tx.bank,
                    txAt = tx.txAt,
                    state = pending?.state ?: "sent",
                    errorCategory = pending?.errorCategory,
                    txId = tx.id,
                    type = tx.type,
                    description = tx.description,
                    userMemo = tx.userMemo,
                    memoSyncState = tx.memoSyncState,
                    currency = tx.currency,
                    destinationAccountId = tx.destinationAccountId,
                    categoryId = tx.categoryId,
                    assignmentSyncState = tx.assignmentSyncState,
                )
            }

            DashboardState(
                capturedToday = txs.count { it.txAt.startsWith(today()) }.toLong(),
                pending = pendingRows.size.toLong(),
                waiting = waitingCount,
                sent = txs.count { tx ->
                    pendingRows.none { it.id == tx.id }
                }.toLong(),
                failed = failedCount,
                connection = connection,
                connectionStatus = connectionStatus,
                captureBlocked = captureBlocked,
                nextStep = nextStep,
                recentRows = recentRows,
                config = config,
                activeEndpoint = activeEndpoint,
                banner = banner,
                bannerMessage = SyncBanner.message(banner),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardState())

    /**
     * BridgePing-driven connection test (FR-019): updates the observed state
     * so the dashboard reflects connected/offline/error status.
     * Ordered fallback: tries every enabled endpoint (Tailscale → LAN →
     * rest) and reports the first reachable one.
     */
    fun refreshConnection() {
        viewModelScope.launch {
            val configs = settings.currentConfigs()
            val endpoints = settings.currentEndpoints()
            if (configs.none { it.serverUrl.isNotBlank() }) {
                connection.value = ConnectionStatus.DISCONNECTED
                return@launch
            }
            val selection = BridgePing().pingFirstReachable(configs)
            val ping = selection.result
            activeEndpointName.value = selection.index.takeIf { it >= 0 }
                ?.let { endpoints.getOrNull(it)?.name?.ifBlank { null } }
            connection.value = when {
                ping.unauthorized || !ping.ok -> ConnectionStatus.DISCONNECTED
                ping.gomoneyReachable -> ConnectionStatus.CONNECTED
                else -> ConnectionStatus.GO_MONEY_DOWN
            }
            if (connection.value == ConnectionStatus.CONNECTED) {
                runCatching {
                    CatalogRepository(db.serverAccountDao(), db.serverCategoryDao())
                        .refreshAll(configs)
                }
            }
        }
    }

    /** One-tap manual drain for "I'm home, sync now" (FR-022). */
    fun syncNow(context: android.content.Context) {
        SyncEngine.enqueueExpedited(context)
        refreshConnection()
    }

    private fun today(): String = java.time.LocalDate.now().toString()
}

/** Minimal ping helper shared by dashboard + settings (FR-019). */
class BridgePing {
    private val client = com.gomoney.capture.sync.BridgeClient()

    suspend fun ping(config: ServerConfiguration): com.gomoney.capture.sync.BridgeClient.PingResult =
        client.ping(config)

    suspend fun pingFirstReachable(
        configs: List<ServerConfiguration>,
    ): com.gomoney.capture.sync.BridgeClient.PingSelection =
        client.pingFirstReachable(configs)
}
