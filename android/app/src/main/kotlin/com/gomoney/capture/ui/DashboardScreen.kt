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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gomoney.capture.ui.DashboardViewModel.DashboardState

/**
 * Dashboard screen (US4, T037): counts + connection status at a glance
 * (SC-006), no sensitive raw text exposed (FR-023 sanitized only).
 */
@Composable
fun DashboardScreen(
    state: DashboardState,
    onRefreshConnection: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "Go Money Capture", style = MaterialTheme.typography.titleLarge)

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CountCard("Captured today", state.capturedToday, Modifier.weight(1f))
            CountCard("Pending", state.pending, Modifier.weight(1f))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CountCard("Sent", state.sent, Modifier.weight(1f))
            CountCard("Failed", state.failed, Modifier.weight(1f))
        }

        Button(onClick = onRefreshConnection) {
            Text("Test connection")
        }
        Text(
            text = when (state.connection) {
                DashboardViewModel.ConnectionStatus.CONNECTED -> "Bridge: connected"
                DashboardViewModel.ConnectionStatus.GO_MONEY_DOWN -> "Bridge error: up, but Go Money unreachable"
                DashboardViewModel.ConnectionStatus.DISCONNECTED -> "Bridge offline: not configured/reachable"
                DashboardViewModel.ConnectionStatus.UNKNOWN -> "Bridge: unknown (not tested)"
            },
            style = MaterialTheme.typography.bodyMedium,
        )

        if (state.config.serverUrl.isBlank()) {
            Text("Configure the bridge in Settings to start capturing.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CountCard(label: String, count: Long, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = count.toString(), style = MaterialTheme.typography.headlineSmall)
            Text(text = label, style = MaterialTheme.typography.labelMedium)
        }
    }
}
