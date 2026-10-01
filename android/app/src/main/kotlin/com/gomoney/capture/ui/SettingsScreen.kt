package com.gomoney.capture.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gomoney.capture.capture.PlatformCapturePermission
import com.gomoney.capture.storage.ServerConfiguration
import com.gomoney.capture.storage.SettingsRepository
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Settings screen (US4, T039): server URL + bearer token editors (stored
 * encrypted, FR-006), "Test connection" (FR-019) wired to BridgePing,
 * independent notification / SMS toggles (FR-017), and permission status
 * display (FR-024).
 *
 * 005 FR-012: there is NO bank/package/sender allow-list here — capture
 * identifiers are managed exclusively through Sources.
 */
class SettingsViewModel(private val settings: SettingsRepository) : ViewModel() {

    val config: kotlinx.coroutines.flow.StateFlow<ServerConfiguration> =
        settings.observe().stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            ServerConfiguration(),
        )

    /** Human-readable result of the last connection test (FR-019). */
    private val _connectionStatus = MutableStateFlow("Bridge: unknown (not tested)")
    val connectionStatus: StateFlow<String> = _connectionStatus

    fun save(url: String, token: String) {
        viewModelScope.launch {
            if (url.isNotBlank()) settings.setServerUrl(url)
            if (token.isNotBlank()) settings.setBearerToken(token)
        }
    }

    /** BridgePing test: connected / offline / error surfaced in the UI. */
    fun testConnection() {
        viewModelScope.launch {
            val config = settings.current()
            _connectionStatus.value = when {
                config.serverUrl.isBlank() -> "Bridge offline: URL not configured"
                else -> {
                    val ping = BridgePing().ping(config)
                    when {
                        ping.unauthorized -> "Bridge error: unauthorized — check the bearer token"
                        ping.ok && ping.gomoneyReachable -> "Bridge: connected"
                        ping.ok -> "Bridge error: up, but Go Money unreachable"
                        else -> "Bridge offline: unreachable"
                    }
                }
            }
        }
    }

    /**
     * 005 FR-015: the Settings allow-list is removed entirely — identifiers
     * are captured only via Sources, so there is nothing to add or remove here.
     */

    fun setNotificationCapture(enabled: Boolean) = viewModelScope.launch { settings.setNotificationCaptureEnabled(enabled) }

    fun setSmsCapture(enabled: Boolean) = viewModelScope.launch { settings.setSmsCaptureEnabled(enabled) }

    fun setDebugMode(enabled: Boolean) = viewModelScope.launch { settings.setDebugModeEnabled(enabled) }
}

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    permission: PlatformCapturePermission,
    onTestConnection: () -> Unit,
    connectionStatus: String,
) {
    val config by viewModel.config.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "Settings", style = MaterialTheme.typography.titleLarge)

        OutlinedTextField(
            value = config.serverUrl,
            onValueChange = { viewModel.save(it, "") },
            label = { Text("Bridge URL (http://host:8787)") },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "Connect this phone and your PC to the same Tailscale network first.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = config.bearerToken,
            onValueChange = { viewModel.save("", it) },
            label = { Text("Bearer token (stored encrypted)") },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = onTestConnection) { Text("Test connection") }
        Text(text = connectionStatus, style = MaterialTheme.typography.bodySmall)

        ToggleRow("Notification capture", config.notificationCaptureEnabled) { viewModel.setNotificationCapture(it) }
        ToggleRow("SMS capture", config.smsCaptureEnabled) { viewModel.setSmsCapture(it) }
        ToggleRow("Debug mode", config.debugModeEnabled) { viewModel.setDebugMode(it) }

        // 005 FR-012: no bank/package/sender allow-list here. Capture
        // identifiers are Sources only — see the Sources tab.

        Text(
            text = if (permission.isNotificationListenerAccessGranted()) {
                "Notification access: granted"
            } else {
                "Notification access: NOT granted — open system settings → Notification access"
            },
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = if (permission.isSmsPermissionGranted()) "SMS permission: granted" else "SMS permission: not granted (optional)",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = label)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
