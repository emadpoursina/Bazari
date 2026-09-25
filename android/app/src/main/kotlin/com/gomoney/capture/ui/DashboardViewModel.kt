package com.gomoney.capture.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gomoney.capture.capture.PlatformCapturePermission
import com.gomoney.capture.storage.AppDatabase
import com.gomoney.capture.storage.ServerConfiguration
import com.gomoney.capture.storage.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
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
    )

    enum class ConnectionStatus { CONNECTED, GO_MONEY_DOWN, DISCONNECTED, UNKNOWN }

    private val connection = MutableStateFlow(ConnectionStatus.UNKNOWN)

    val state: StateFlow<DashboardState> =
        kotlinx.coroutines.flow.combine(
            db.normalizedTransactionDao().observeRecent(500),
            db.deliveryRecordDao().observePending(),
            settings.observe(),
            connection,
        ) { txs, pendingRows, config, connection ->
            DashboardState(
                capturedToday = txs.count { it.txAt.startsWith(today()) }.toLong(),
                pending = pendingRows.size.toLong(),
                sent = txs.count { tx ->
                    pendingRows.none { it.id == tx.id }
                }.toLong(),
                failed = pendingRows.count { it.state == "failed" }.toLong(),
                connection = connection,
                config = config,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardState())

    /**
     * BridgePing-driven connection test (FR-019): updates the observed state
     * so the dashboard reflects connected/offline/error status.
     */
    fun refreshConnection() {
        viewModelScope.launch {
            val config = settings.current()
            if (config.serverUrl.isBlank()) {
                connection.value = ConnectionStatus.DISCONNECTED
                return@launch
            }
            val ping = BridgePing().ping(config)
            connection.value = when {
                // Unauthorized OR unreachable bridge → offline (GO_MONEY_DOWN
                // only applies when the bridge answered but Go Money didn't).
                ping.unauthorized || !ping.ok -> ConnectionStatus.DISCONNECTED
                ping.gomoneyReachable -> ConnectionStatus.CONNECTED
                else -> ConnectionStatus.GO_MONEY_DOWN
            }
        }
    }

    private fun today(): String = java.time.LocalDate.now().toString()
}

/** Minimal ping helper shared by dashboard + settings (FR-019). */
class BridgePing {
    private val client = com.gomoney.capture.sync.BridgeClient()

    suspend fun ping(config: ServerConfiguration): com.gomoney.capture.sync.BridgeClient.PingResult =
        client.ping(config)
}
