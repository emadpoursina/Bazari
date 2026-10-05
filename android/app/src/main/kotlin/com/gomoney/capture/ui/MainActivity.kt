package com.gomoney.capture.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.gomoney.capture.capture.PlatformCapturePermission
import com.gomoney.capture.capture.ScanCoordinator
import com.gomoney.capture.storage.AppDatabase
import com.gomoney.capture.storage.SettingsRepository
import kotlinx.coroutines.launch

/**
 * Single-activity host with three tabs: Dashboard (US4), Events (US1), and
 * Settings (US4). Detail/debug views open from the Events tab.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestSyncNotifications()

        val db = AppDatabase.get(applicationContext)
        val settings = SettingsRepository(applicationContext)
        val permission = PlatformCapturePermission(applicationContext)

        // 006 R1: the scan fallback needs an app context even before the
        // listener service has created its handle.
        ScanCoordinator.attach(applicationContext)

        setContent {
            MaterialTheme {
                AppTabs(db, settings, permission)
            }
        }
    }
}

@Composable
private fun AppTabs(
    db: AppDatabase,
    settings: SettingsRepository,
    permission: PlatformCapturePermission,
) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Dashboard", "Events", "Sources", "Settings")

    // View models are hoisted above the tab switcher so state survives tab
    // changes (remembered once per composition, not per branch).
    val dashboardViewModel = remember { DashboardViewModel(db, settings, permission) }
    val eventsViewModel = remember { EventsViewModel(db) }
    val settingsViewModel = remember { SettingsViewModel(settings) }
    val sourcesViewModel = remember { SourcesViewModel(db, settings) }
    val sourceEditorViewModel = remember { SourceEditorViewModel(db, settings) }
    val dashboardState by dashboardViewModel.state.collectAsStateWithLifecycleCompat()
    val eventsState by eventsViewModel.state.collectAsStateWithLifecycleCompat()
    // 006 FR-005: the scan banner state is hoisted in ScanCoordinator so it
    // never leaks across tab switches (research R6).
    val scanState by ScanCoordinator.state.collectAsStateWithLifecycleCompat()
    val sourcesState by sourcesViewModel.state.collectAsStateWithLifecycleCompat()
    val editorAccounts by sourceEditorViewModel.accounts.collectAsStateWithLifecycleCompat()
    val editorCatalogState by sourceEditorViewModel.catalogState.collectAsStateWithLifecycleCompat()
    val editorErrors by sourceEditorViewModel.errors.collectAsStateWithLifecycleCompat()
    val editorTestResult by sourceEditorViewModel.testResult.collectAsStateWithLifecycleCompat()
    var editingSource by remember { mutableStateOf<com.gomoney.capture.storage.TransactionSource?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val eventActions = remember { EventActions(context, db) }
    val settingsConnectionStatus by settingsViewModel.connectionStatus.collectAsStateWithLifecycleCompat()

    // Fresh connection status every time the dashboard is opened, so the
    // halt reason is never stale when the user returns home.
    LaunchedEffect(selectedTab) {
        if (selectedTab == 0) dashboardViewModel.refreshConnection()
        // 006 T025 / FR-005: leaving the Events tab consumes any shown
        // banner; a scan finishing while hidden is consumed as Idle.
        ScanCoordinator.eventsTabVisible = selectedTab == 1
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
        bottomBar = {
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, label ->
                    Tab(selected = selectedTab == index, onClick = { selectedTab = index }, text = { Text(label) })
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (selectedTab) {
                0 -> DashboardScreen(
                    state = dashboardState,
                    onRefreshConnection = { dashboardViewModel.refreshConnection() },
                    onSyncNow = { dashboardViewModel.syncNow(context) },
                )
                1 -> EventsScreen(
                    state = eventsState,
                    onRetry = { deliveryId ->
                        scope.launch { eventActions.retry(deliveryId) }
                    },
                    onSaveMemo = { transactionId, memo ->
                        scope.launch { eventActions.saveMemo(transactionId, memo) }
                    },
                    onClearProcessed = {
                        scope.launch { eventActions.clearProcessed() }
                    },
                    onClearAll = {
                        scope.launch { eventActions.clearAll() }
                    },
                    // 006 FR-001/006: scan entry wired to the coordinator's
                    // press-time permission check (PlatformCapturePermission).
                    scanState = scanState,
                    onScan = { ScanCoordinator.requestScan(permission) },
                )
                2 -> {
                    val source = editingSource
                    if (source == null) {
                        SourcesScreen(
                            sources = sourcesState.sources,
                            parseErrors = sourcesState.parseErrors,
                            staleBindingIds = sourcesState.staleBindingIds,
                            onAdd = {
                                sourceEditorViewModel.clearTest()
                                editingSource = sourceEditorViewModel.newSource()
                                sourceEditorViewModel.loadCachedAccounts()
                            },
                            onEdit = { id ->
                                scope.launch {
                                    sourceEditorViewModel.load(id)?.let {
                                        sourceEditorViewModel.clearTest()
                                        editingSource = it
                                        sourceEditorViewModel.loadCachedAccounts()
                                    }
                                }
                            },
                            onToggle = { id, enabled ->
                                scope.launch { sourcesViewModel.setEnabled(id, enabled) }
                            },
                            onRemove = { id -> scope.launch { sourcesViewModel.remove(id) } },
                            onDismissParseError = { id -> scope.launch { sourcesViewModel.dismissParseError(id) } },
                        )
                    } else {
                        SourceEditorScreen(
                            initial = source,
                            accounts = editorAccounts,
                            catalogUnavailable = editorCatalogState.accounts ==
                                com.gomoney.capture.sync.CatalogRepository.Status.UNAVAILABLE,
                            errors = editorErrors,
                            testResult = editorTestResult,
                            onSave = { updated ->
                                scope.launch {
                                    if (sourceEditorViewModel.save(updated) is
                                        com.gomoney.capture.source.SaveResult.Saved
                                    ) {
                                        editingSource = null
                                    }
                                }
                            },
                            onCancel = { editingSource = null },
                            onTest = { template, message, income, expense ->
                                sourceEditorViewModel.test(template, message, income, expense)
                            },
                            onRefreshAccounts = { sourceEditorViewModel.refreshAccounts() },
                        )
                    }
                }
                3 -> SettingsScreen(
                    viewModel = settingsViewModel,
                    permission = permission,
                    onTestConnection = { settingsViewModel.testConnection() },
                    connectionStatus = settingsConnectionStatus,
                )
            }
        }
    }
}

@Composable
private fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectAsStateWithLifecycleCompat() =
    collectAsState()

private fun ComponentActivity.requestSyncNotifications() {
    if (Build.VERSION.SDK_INT < 33) return
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    ) {
        return
    }
    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
}
