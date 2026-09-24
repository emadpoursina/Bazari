package com.gomoney.capture.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gomoney.capture.capture.PlatformCapturePermission
import com.gomoney.capture.storage.ServerConfiguration
import com.gomoney.capture.storage.SettingsRepository
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Settings screen (US4, T039): server URL + bearer token editors (stored
 * encrypted, FR-006), "Test connection" (FR-019), independent notification /
 * SMS toggles (FR-017), bank app allow-list picker (FR-018), permission
 * status display + system-settings guidance (FR-024).
 */
class SettingsViewModel(private val settings: SettingsRepository) : ViewModel() {

    val config: kotlinx.coroutines.flow.StateFlow<ServerConfiguration> =
        settings.observe().stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000),
            ServerConfiguration(),
        )

    fun save(url: String, token: String) {
        viewModelScope.launch {
            if (url.isNotBlank()) settings.setServerUrl(url)
            if (token.isNotBlank()) settings.setBearerToken(token)
        }
    }

    fun setNotificationCapture(enabled: Boolean) = viewModelScope.launch { settings.setNotificationCaptureEnabled(enabled) }

    fun setSmsCapture(enabled: Boolean) = viewModelScope.launch { settings.setSmsCaptureEnabled(enabled) }

    fun setDebugMode(enabled: Boolean) = viewModelScope.launch { settings.setDebugModeEnabled(enabled) }

    fun setEnabledPackages(packages: Set<String>) = viewModelScope.launch { settings.setEnabledBankPackages(packages) }
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
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "Settings", style = MaterialTheme.typography.titleLarge)

        OutlinedTextField(
            value = config.serverUrl,
            onValueChange = { viewModel.save(it, "") },
            label = { Text("Bridge URL (http://host:8787)") },
            modifier = Modifier.fillMaxWidth(),
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

        Text(text = "Bank app allow-list (FR-018):", style = MaterialTheme.typography.titleSmall)
        Text(
            text = config.enabledBankPackages.ifEmpty { setOf("(none configured)") }.joinToString("\n"),
            style = MaterialTheme.typography.bodySmall,
        )

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
