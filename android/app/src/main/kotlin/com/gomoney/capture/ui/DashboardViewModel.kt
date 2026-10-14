package com.gomoney.capture.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gomoney.capture.capture.PlatformCapturePermission
import com.gomoney.capture.storage.AppDatabase
import com.gomoney.capture.storage.ServerConfiguration
import com.gomoney.capture.storage.SettingsRepository
import com.gomoney.capture.sync.SyncBanner
import com.gomoney.capture.sync.SyncEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Dashboard (US4, T037, FR-020): connection status + captured-today / pending /
 * sent / failed counts, all via Room Flow queries so the dashboard matches
 * reality within seconds (SC-006).
 */
class DashboardViewModel(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val permission: PlatformCapturePermission,
) : ViewModel() {

    data class DashboardState(
        val capturedToday: Long = 0,
        val pending: Long = 0,
        val sent: Long = 0,
        val failed: Long = 0,
        val connection: ConnectionStatus = ConnectionStatus.UNKNOWN,
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
        kotlinx.coroutines.flow.combine(
            db.normalizedTransactionDao().observeRecent(500),
            db.deliveryRecordDao().observePending(),
            settings.observe(),
            connection,
            activeEndpointName,
        ) { txs, pendingRows, config, connection, activeEndpoint ->
            val banner = SyncBanner.forPending(pendingRows)
            DashboardState(
                capturedToday = txs.count { it.txAt.startsWith(today()) }.toLong(),
                pending = pendingRows.size.toLong(),
                sent = txs.count { tx ->
                    pendingRows.none { it.id == tx.id }
                }.toLong(),
                failed = pendingRows.count { it.state == "failed" }.toLong(),
                connection = connection,
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
            // Surface which endpoint answered, for multi-endpoint setups.
            activeEndpointName.value = selection.index.takeIf { it >= 0 }
                ?.let { endpoints.getOrNull(it)?.name?.ifBlank { null } }
            connection.value = when {
                // Unauthorized OR unreachable bridge → offline (GO_MONEY_DOWN
                // only applies when the bridge answered but Go Money didn't).
                ping.unauthorized || !ping.ok -> ConnectionStatus.DISCONNECTED
                ping.gomoneyReachable -> ConnectionStatus.CONNECTED
                else -> ConnectionStatus.GO_MONEY_DOWN
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
