package com.gomoney.capture.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gomoney.capture.R
import com.gomoney.capture.capture.PlatformCapturePermission
import com.gomoney.capture.storage.BridgeEndpoint
import com.gomoney.capture.storage.DefaultPreferences
import com.gomoney.capture.storage.ServerAccount
import com.gomoney.capture.storage.ServerCategory
import com.gomoney.capture.storage.ServerConfiguration
import com.gomoney.capture.storage.SettingsRepository
import com.gomoney.capture.ui.theme.CaptureThemeTokens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settings: SettingsRepository,
    private val db: com.gomoney.capture.storage.AppDatabase? = null,
) : ViewModel() {

    val config: StateFlow<ServerConfiguration> =
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

    private val _connectionStatus = MutableStateFlow("Bridge: unknown (not tested)")
    val connectionStatus: StateFlow<String> = _connectionStatus

    private val _endpointStatus = MutableStateFlow<Map<String, String>>(emptyMap())
    val endpointStatus: StateFlow<Map<String, String>> = _endpointStatus

    val defaults: StateFlow<DefaultPreferences> =
        settings.observeDefaults().stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            DefaultPreferences(defaultCategoryId = null, defaultAccountId = null),
        )

    fun setDefaultCategoryId(id: Int) =
        viewModelScope.launch { settings.setDefaultCategoryId(id) }

    fun clearDefaultCategory() =
        viewModelScope.launch { settings.clearDefaultCategory() }

    fun setDefaultAccountId(id: Int) =
        viewModelScope.launch { settings.setDefaultAccountId(id) }

    fun clearDefaultAccount() =
        viewModelScope.launch { settings.clearDefaultAccount() }

    fun save(url: String, token: String) {
        viewModelScope.launch {
            if (url.isNotBlank()) settings.setServerUrl(url)
            if (token.isNotBlank()) settings.setBearerToken(token)
        }
    }

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
            if (ping.ok && ping.gomoneyReachable) {
                val database = db
                if (database != null) {
                    runCatching {
                        com.gomoney.capture.sync.CatalogRepository(
                            database.serverAccountDao(),
                            database.serverCategoryDao(),
                        ).refreshAll(configs)
                    }
                }
            }
        }
    }

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

    fun setNotificationCapture(enabled: Boolean) = viewModelScope.launch { settings.setNotificationCaptureEnabled(enabled) }

    fun setSmsCapture(enabled: Boolean) = viewModelScope.launch { settings.setSmsCaptureEnabled(enabled) }

    fun setDebugMode(enabled: Boolean) = viewModelScope.launch { settings.setDebugModeEnabled(enabled) }
}

/**
 * Settings screen (US5, T030, T031, T032, contracts/sources-and-settings.md §3):
 * Five labeled sections:
 * 1. Connection destinations
 * 2. Capture
 * 3. Defaults
 * 4. Permissions
 * 5. Diagnostics
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    permission: PlatformCapturePermission,
    onTestConnection: () -> Unit,
    connectionStatus: String,
    catalogCategories: List<ServerCategory> = emptyList(),
    catalogAccounts: List<ServerAccount> = emptyList(),
    catalogState: com.gomoney.capture.sync.CatalogRepository.CatalogState =
        com.gomoney.capture.sync.CatalogRepository.CatalogState(),
) {
    val config by viewModel.config.collectAsState()
    val endpoints by viewModel.endpoints.collectAsState()
    val perEndpointStatus by viewModel.endpointStatus.collectAsState()
    val defaults by viewModel.defaults.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val extendedColors = CaptureThemeTokens.extendedColors

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.nav_settings),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )

        // 1. Connection destinations
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_group_destinations),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "First reachable wins for ping + sync. Put Tailscale first, then LAN, then the rest.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (endpoints.isEmpty()) {
                    Text(
                        text = "No endpoints yet — add one below.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    OutlinedButton(
                        onClick = { showAdd = !showAdd },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) {
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

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = onTestConnection,
                        modifier = Modifier.height(48.dp),
                    ) {
                        Text("Test connection")
                    }
                }
                Text(
                    text = connectionStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 2. Capture toggles
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_group_capture),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                ToggleRow("Notification capture", config.notificationCaptureEnabled) {
                    viewModel.setNotificationCapture(it)
                }
                ToggleRow("SMS capture", config.smsCaptureEnabled) {
                    viewModel.setSmsCapture(it)
                }
            }
        }

        // 3. Defaults
        DefaultsCard(
            defaults = defaults,
            categories = catalogCategories,
            accounts = catalogAccounts,
            categoriesUnavailable = catalogState.categories ==
                com.gomoney.capture.sync.CatalogRepository.Status.UNAVAILABLE,
            accountsUnavailable = catalogState.accounts ==
                com.gomoney.capture.sync.CatalogRepository.Status.UNAVAILABLE,
            onSelectCategory = { viewModel.setDefaultCategoryId(it) },
            onClearCategory = { viewModel.clearDefaultCategory() },
            onSelectAccount = { viewModel.setDefaultAccountId(it) },
            onClearAccount = { viewModel.clearDefaultAccount() },
        )

        // 4. Permissions
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_group_permissions),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                val notifGranted = permission.isNotificationListenerAccessGranted()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Notification access", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = if (notifGranted) stringResource(R.string.settings_permission_granted) else stringResource(R.string.settings_permission_missing),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (notifGranted) extendedColors.success else extendedColors.warning,
                        fontWeight = FontWeight.Medium,
                    )
                }
                if (!notifGranted) {
                    Text(
                        text = stringResource(R.string.notification_access_explanation),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { openNotificationAccessSettings(context) },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) {
                        Text(stringResource(R.string.notification_access_open_settings))
                    }
                }

                val smsGranted = permission.isSmsPermissionGranted()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("SMS permission", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = if (smsGranted) stringResource(R.string.settings_permission_granted) else "Optional (not granted)",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (smsGranted) extendedColors.success else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // 5. Diagnostics
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_group_diagnostics),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                ToggleRow("Debug mode", config.debugModeEnabled) {
                    viewModel.setDebugMode(it)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun DefaultsCard(
    defaults: DefaultPreferences,
    categories: List<ServerCategory>,
    accounts: List<ServerAccount>,
    categoriesUnavailable: Boolean,
    accountsUnavailable: Boolean,
    onSelectCategory: (Int) -> Unit,
    onClearCategory: () -> Unit,
    onSelectAccount: (Int) -> Unit,
    onClearAccount: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_group_defaults),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Applied to newly captured transactions only — existing events are never changed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DefaultPickerRow(
                label = "Default category",
                selectedId = defaults.defaultCategoryId,
                selectedLabel = defaults.defaultCategoryId?.let { id ->
                    categories.firstOrNull { it.id == id }?.label
                },
                options = categories.map { it.id to it.label },
                unavailable = categoriesUnavailable,
                onSelect = onSelectCategory,
                onClear = onClearCategory,
            )
            DefaultPickerRow(
                label = "Default destination account",
                selectedId = defaults.defaultAccountId,
                selectedLabel = defaults.defaultAccountId?.let { id ->
                    accounts.firstOrNull { it.id == id }?.label
                },
                options = accounts.map { it.id to it.label },
                unavailable = accountsUnavailable,
                onSelect = onSelectAccount,
                onClear = onClearAccount,
            )
        }
    }
}

@Composable
private fun DefaultPickerRow(
    label: String,
    selectedId: Int?,
    selectedLabel: String?,
    options: List<Pair<Int, String>>,
    unavailable: Boolean,
    onSelect: (Int) -> Unit,
    onClear: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val stale = selectedId != null && options.none { (id, _) -> id == selectedId }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = label, style = MaterialTheme.typography.labelMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                OutlinedButton(
                    onClick = { menuOpen = true },
                    enabled = !unavailable && options.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Text(selectedLabel ?: "None")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    options.forEach { (id, optionLabel) ->
                        DropdownMenuItem(
                            text = { Text(optionLabel) },
                            onClick = {
                                onSelect(id)
                                menuOpen = false
                            },
                        )
                    }
                }
            }
            TextButton(
                onClick = onClear,
                enabled = selectedId != null,
                modifier = Modifier.height(48.dp),
            ) {
                Text("Clear")
            }
        }
        if (stale) {
            Text(
                text = "$label is no longer available — pick a new one or Clear.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (unavailable || options.isEmpty()) {
            Text(
                text = "Server list unavailable — pickers disabled. Saved defaults still apply to new captures.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "#$position ${endpoint.name.ifBlank { endpoint.url }}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                Switch(checked = endpoint.enabled, onCheckedChange = onToggle)
            }
            Text(text = endpoint.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!endpoint.enabled) {
                Text(text = "Disabled — skipped by fallback", style = MaterialTheme.typography.bodySmall)
            }
            if (lastStatus != null) {
                Text(text = lastStatus, style = MaterialTheme.typography.bodySmall)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
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
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { onConfirm(name, url, token, enabled) },
                    enabled = url.isNotBlank(),
                    modifier = Modifier.height(48.dp),
                ) {
                    Text(confirmLabel)
                }
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.height(48.dp),
                ) {
                    Text("Cancel")
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(48.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
