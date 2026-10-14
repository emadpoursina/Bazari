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
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gomoney.capture.capture.PlatformCapturePermission
import com.gomoney.capture.storage.BridgeEndpoint
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
 * Settings screen (US4, T039): multiple bridge endpoints with ordered
 * fallback (stored encrypted, FR-006), "Test connection" (FR-019) wired to
 * BridgePing fallback, independent notification / SMS toggles (FR-017), and
 * permission status display (FR-024).
 *
 * 005 FR-012: there is NO bank/package/sender allow-list here — capture
 * identifiers are managed exclusively through Sources.
 *
 * Endpoints: users CRUD their own list (e.g. Tailscale first, then LAN,
 * then the rest). Ping/sync try enabled endpoints in order and use the
 * first reachable one for the whole run.
 */
class SettingsViewModel(private val settings: SettingsRepository) : ViewModel() {

    val config: kotlinx.coroutines.flow.StateFlow<ServerConfiguration> =
        settings.observe().stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            ServerConfiguration(),
        )

    val endpoints: StateFlow<List<BridgeEndpoint>> =
        settings.observeEndpoints().stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList(),
        )

    /** Human-readable result of the last connection test (FR-019). */
    private val _connectionStatus = MutableStateFlow("Bridge: unknown (not tested)")
    val connectionStatus: StateFlow<String> = _connectionStatus

    /** Per-endpoint last test result, keyed by endpoint id. */
    private val _endpointStatus = MutableStateFlow<Map<String, String>>(emptyMap())
    val endpointStatus: StateFlow<Map<String, String>> = _endpointStatus

    fun save(url: String, token: String) {
        viewModelScope.launch {
            if (url.isNotBlank()) settings.setServerUrl(url)
            if (token.isNotBlank()) settings.setBearerToken(token)
        }
    }

    /** BridgePing fallback test: tries every enabled endpoint in order. */
    fun testConnection() {
        viewModelScope.launch {
            val configs = settings.currentConfigs()
            val endpoints = settings.currentEndpoints()
            if (configs.none { it.serverUrl.isNotBlank() }) {
                _connectionStatus.value = "Bridge offline: no endpoint configured"
                return@launch
            }
            val selection = BridgePing().pingFirstReachable(configs)
            val ping = selection.result
            val via = selection.index.takeIf { it >= 0 }
                ?.let { endpoints.getOrNull(it)?.name?.ifBlank { null } }
                ?.let { " via $it" } ?: ""
            _connectionStatus.value = when {
                ping.unauthorized -> "Bridge error: unauthorized — check the bearer token$via"
                ping.ok && ping.gomoneyReachable -> "Bridge: connected$via"
                ping.ok -> "Bridge error: up, but Go Money unreachable$via"
                else -> "Bridge offline: unreachable"
            }
        }
    }

    /** Test one endpoint in isolation (does not affect the fallback order). */
    fun testEndpoint(id: String) {
        viewModelScope.launch {
            val endpoint = settings.allEndpoints().firstOrNull { it.id == id } ?: return@launch
            val base = settings.current()
            val config = base.copy(serverUrl = endpoint.url, bearerToken = endpoint.bearerToken)
            val label = endpoint.name.ifBlank { endpoint.url }
            _endpointStatus.value = _endpointStatus.value + (id to "Testing…")
            if (config.serverUrl.isBlank()) {
                _endpointStatus.value = _endpointStatus.value + (id to "URL not configured")
                return@launch
            }
            val ping = BridgePing().ping(config)
            _endpointStatus.value = _endpointStatus.value + (
                id to when {
                    ping.unauthorized -> "$label: unauthorized — check token"
                    ping.ok && ping.gomoneyReachable -> "$label: connected"
                    ping.ok -> "$label: up, Go Money unreachable"
                    else -> "$label: unreachable"
                }
                )
        }
    }

    fun addEndpoint(name: String, url: String, token: String, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            if (url.isBlank()) return@launch
            settings.addEndpoint(name.ifBlank { url.trim() }, url, token)
            onDone()
        }
    }

    fun updateEndpoint(id: String, name: String, url: String, token: String, enabled: Boolean, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            if (url.isBlank()) return@launch
            settings.updateEndpoint(id, name.ifBlank { url.trim() }, url, token.ifBlank { null }, enabled)
            onDone()
        }
    }

    fun deleteEndpoint(id: String) = viewModelScope.launch { settings.deleteEndpoint(id) }

    fun setEndpointEnabled(id: String, enabled: Boolean) =
        viewModelScope.launch { settings.setEndpointEnabled(id, enabled) }

    fun moveEndpoint(id: String, up: Boolean) = viewModelScope.launch { settings.moveEndpoint(id, up) }

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
    val endpoints by viewModel.endpoints.collectAsState()
    val perEndpointStatus by viewModel.endpointStatus.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "Settings", style = MaterialTheme.typography.titleLarge)

        Text(text = "Bridge endpoints (tried in order)", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "First reachable wins for ping + sync. Put Tailscale first, then LAN, then the rest.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (endpoints.isEmpty()) {
            Text(
                text = "No endpoints yet — add one below.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        endpoints.forEachIndexed { index, endpoint ->
            EndpointRow(
                endpoint = endpoint,
                position = index + 1,
                total = endpoints.size,
                lastStatus = perEndpointStatus[endpoint.id],
                onToggle = { viewModel.setEndpointEnabled(endpoint.id, it) },
                onMoveUp = { viewModel.moveEndpoint(endpoint.id, up = true) },
                onMoveDown = { viewModel.moveEndpoint(endpoint.id, up = false) },
                onEdit = { editingId = endpoint.id },
                onDelete = { viewModel.deleteEndpoint(endpoint.id) },
                onTest = { viewModel.testEndpoint(endpoint.id) },
            )
        }

        if (editingId == null) {
            OutlinedButton(onClick = { showAdd = !showAdd }) {
                Text(if (showAdd) "Hide new endpoint" else "Add endpoint")
            }
        }
        if (showAdd && editingId == null) {
            EndpointForm(
                initialName = "",
                initialUrl = "",
                initialToken = "",
                initialEnabled = true,
                confirmLabel = "Add",
                onCancel = { showAdd = false },
                onConfirm = { name, url, token, _ ->
                    viewModel.addEndpoint(name, url, token) { showAdd = false }
                },
            )
        }
        val editing = editingId?.let { id -> endpoints.firstOrNull { it.id == id } }
        if (editing != null) {
            EndpointForm(
                initialName = editing.name,
                initialUrl = editing.url,
                initialToken = "",
                initialEnabled = editing.enabled,
                confirmLabel = "Save",
                tokenPlaceholder = "(unchanged)",
                onCancel = { editingId = null },
                onConfirm = { name, url, token, enabled ->
                    viewModel.updateEndpoint(editing.id, name, url, token, enabled) { editingId = null }
                },
            )
        }

        Button(onClick = onTestConnection) { Text("Test connection (fallback order)") }
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
private fun EndpointRow(
    endpoint: BridgeEndpoint,
    position: Int,
    total: Int,
    lastStatus: String?,
    onToggle: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTest: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = "#$position ${endpoint.name.ifBlank { endpoint.url }}",
                    style = MaterialTheme.typography.titleSmall,
                )
                Switch(checked = endpoint.enabled, onCheckedChange = onToggle)
            }
            Text(text = endpoint.url, style = MaterialTheme.typography.bodySmall)
            if (!endpoint.enabled) {
                Text(text = "Disabled — skipped by fallback", style = MaterialTheme.typography.bodySmall)
            }
            if (lastStatus != null) {
                Text(text = lastStatus, style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onMoveUp, enabled = position > 1) { Text("↑") }
                TextButton(onClick = onMoveDown, enabled = position < total) { Text("↓") }
                TextButton(onClick = onTest) { Text("Test") }
                TextButton(onClick = onEdit) { Text("Edit") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun EndpointForm(
    initialName: String,
    initialUrl: String,
    initialToken: String,
    initialEnabled: Boolean,
    confirmLabel: String,
    tokenPlaceholder: String = "Bearer token (stored encrypted)",
    onCancel: () -> Unit,
    onConfirm: (name: String, url: String, token: String, enabled: Boolean) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    var url by remember(initialUrl) { mutableStateOf(initialUrl) }
    var token by remember { mutableStateOf(initialToken) }
    var enabled by remember(initialEnabled) { mutableStateOf(initialEnabled) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name (e.g. Tailscale, Home LAN)") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Bridge URL (http://host:8787)") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text(tokenPlaceholder) },
                modifier = Modifier.fillMaxWidth(),
            )
            ToggleRow("Enabled", enabled) { enabled = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onConfirm(name, url, token, enabled) }, enabled = url.isNotBlank()) {
                    Text(confirmLabel)
                }
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = label)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
