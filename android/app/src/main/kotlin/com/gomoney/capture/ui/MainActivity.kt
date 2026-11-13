package com.gomoney.capture.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.gomoney.capture.capture.PlatformCapturePermission
import com.gomoney.capture.capture.ScanCoordinator
import com.gomoney.capture.storage.AppDatabase
import com.gomoney.capture.storage.SettingsRepository
import com.gomoney.capture.sync.NightlyLeftover
import com.gomoney.capture.sync.NotificationOpen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Single-activity host with three tabs: Dashboard (US4), Events (US1), and
 * Settings (US4). Detail/debug views open from the Events tab.
 */
class MainActivity : ComponentActivity() {

    private val intentFlow = MutableStateFlow<Intent?>(null)

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

        intentFlow.value = intent

        setContent {
            MaterialTheme {
                AppTabs(db, settings, permission, intentFlow)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intentFlow.value = intent
    }
}

@Composable
private fun AppTabs(
    db: AppDatabase,
    settings: SettingsRepository,
    permission: PlatformCapturePermission,
    intentFlow: Flow<Intent?>,
) {
    var selectedTab by remember { mutableStateOf(0) }
    var selectedTxId by remember { mutableStateOf<String?>(null) }
    var missingEventMessage by remember { mutableStateOf<String?>(null) }
    val tabs = listOf("Dashboard", "Events", "Sources", "Settings")

    // View models are hoisted above the tab switcher so state survives tab
    // changes (remembered once per composition, not per branch).
    val dashboardViewModel = remember { DashboardViewModel(db, settings, permission) }
    val eventsViewModel = remember { EventsViewModel(db) }
    val settingsViewModel = remember { SettingsViewModel(settings, db) }
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

    // 007: per-event category assignment — cached server catalog + detail nav.
    val catalog = remember {
        com.gomoney.capture.sync.CatalogRepository(db.serverAccountDao(), db.serverCategoryDao())
    }
    val catalogAccounts by catalog.observeAccounts().collectAsStateWithLifecycleCompat(initial = emptyList())
    val catalogCategories by catalog.observeCategories().collectAsStateWithLifecycleCompat(initial = emptyList())
    val catalogState by catalog.state.collectAsStateWithLifecycleCompat(
        initial = com.gomoney.capture.sync.CatalogRepository.CatalogState(),
    )
    val recentTxs by db.normalizedTransactionDao().observeRecent(500)
        .collectAsStateWithLifecycleCompat(initial = emptyList())
    val recentDeliveries by db.deliveryRecordDao().observeRecent(500)
        .collectAsStateWithLifecycleCompat(initial = emptyList())

    // 009 US2 / US4: Consume notification open intents (onCreate and onNewIntent).
    LaunchedEffect(intentFlow) {
        intentFlow.collect { incomingIntent ->
            if (incomingIntent == null) return@collect
            val target = incomingIntent.getStringExtra(NotificationOpen.EXTRA_OPEN_TARGET)
            if (target != null) {
                val eventId = incomingIntent.getStringExtra(NotificationOpen.EXTRA_OPEN_EVENT_ID)
                val route = withContext(Dispatchers.IO) {
                    NotificationOpenRouting.resolveTarget(
                        target = target,
                        eventId = eventId,
                        txExists = { id -> runBlocking { db.normalizedTransactionDao().byId(id) != null } },
                        leftoverIdsProvider = {
                            val candidateRows = runBlocking { db.normalizedTransactionDao().leftoverCandidates() }
                            val lastCheckAt = runBlocking { settings.getNightlyLastCheckAt() }
                            val snapshot = NightlyLeftover.computeLeftoverSnapshot(
                                candidateRows.map {
                                    NightlyLeftover.LeftoverCandidate(
                                        id = it.id,
                                        userMemo = it.userMemo,
                                        memoSyncState = it.memoSyncState,
                                        assignmentSyncState = it.assignmentSyncState,
                                        deliveryState = it.deliveryState,
                                        capturedAt = it.capturedAt,
                                    )
                                },
                                lastCheckAt,
                            )
                            snapshot.allLeftoverIds
                        },
                    )
                }
                when (route) {
                    is NotificationOpenRouting.Route.Detail -> {
                        selectedTab = 1
                        selectedTxId = route.txId
                        missingEventMessage = null
                    }
                    is NotificationOpenRouting.Route.EventsList -> {
                        selectedTab = 1
                        selectedTxId = null
                        missingEventMessage = route.message
                    }
                    NotificationOpenRouting.Route.None -> Unit
                }
            }
        }
    }

    fun refreshCatalogs() {
        scope.launch {
            runCatching { catalog.refreshAll(settings.currentConfigs()) }
        }
    }
    // Refresh the category list whenever the detail editor opens, so the
    // drop-down never offers a stale server list.
    LaunchedEffect(selectedTxId) {
        if (selectedTxId != null) refreshCatalogs()
    }

    // Fresh connection status every time the dashboard is opened, so the
    // halt reason is never stale when the user returns home.
    LaunchedEffect(selectedTab) {
        if (selectedTab == 0) dashboardViewModel.refreshConnection()
        if (selectedTab != 1) {
            selectedTxId = null
            missingEventMessage = null
        }
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
                1 -> {
                    val detailId = selectedTxId
                    if (detailId == null) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            val msg = missingEventMessage
                            if (msg != null) {
                                Surface(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = MaterialTheme.shapes.small,
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = msg,
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.weight(1f),
                                        )
                                        TextButton(onClick = { missingEventMessage = null }) {
                                            Text("Dismiss")
                                        }
                                    }
                                }
                            }
                            Box(modifier = Modifier.weight(1f)) {
                                EventsScreen(
                                    state = eventsState,
                                    onRetry = { deliveryId ->
                                        scope.launch { eventActions.retry(deliveryId) }
                                    },
                                    // 008 US1: the note editor moved to EventDetailScreen —
                                    // no onSaveMemo parameter on list rows anymore.
                                    onClearProcessed = {
                                        scope.launch { eventActions.clearProcessed() }
                                    },
                                    onClearAll = {
                                        scope.launch { eventActions.clearAll() }
                                    },
                                    // 007 FR-004/005: selective actions operate on the
                                    // selection only; unselected rows are untouched.
                                    onSyncSelected = { ids ->
                                        scope.launch { eventActions.syncSelected(ids) }
                                    },
                                    onDeleteSelected = { ids ->
                                        scope.launch { eventActions.deleteSelected(ids) }
                                    },
                                    // 006 FR-001/006: scan entry wired to the coordinator's
                                    // press-time permission check (PlatformCapturePermission).
                                    scanState = scanState,
                                    onScan = { ScanCoordinator.requestScan(permission) },
                                    // 007: per-event category from the server list.
                                    categories = catalogCategories,
                                    onOpenDetail = { selectedTxId = it },
                                )
                            }
                        }
                    } else {
                        val tx = recentTxs.firstOrNull { it.id == detailId }
                        val record = recentDeliveries.firstOrNull { it.id == detailId }
                        EventDetailScreen(
                            tx = tx,
                            record = record,
                            config = dashboardState.config,
                            onRetry = { deliveryId ->
                                scope.launch { eventActions.retry(deliveryId) }
                            },
                            accounts = catalogAccounts,
                            categories = catalogCategories,
                            onSaveAssignment = { transactionId, destinationAccountId, categoryId ->
                                scope.launch {
                                    eventActions.saveAssignment(transactionId, destinationAccountId, categoryId)
                                }
                            },
                            onSaveCurrency = { transactionId, currency ->
                                scope.launch { eventActions.saveCurrency(transactionId, currency) }
                            },
                            // 008 US1: existing save logic only (EventActions.saveMemo
                            // owns trim/limit/pending semantics — R1).
                            onSaveMemo = { transactionId, memo ->
                                scope.launch { eventActions.saveMemo(transactionId, memo) }
                            },
                            onBack = { selectedTxId = null },
                            catalogUnavailable = catalogState.categories ==
                                com.gomoney.capture.sync.CatalogRepository.Status.UNAVAILABLE,
                            onRefreshCatalogs = { refreshCatalogs() },
                        )
                    }
                }
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
                    // 008 US2/US3: cached catalogs + availability for the
                    // "Default category & default account" card (R6).
                    catalogCategories = catalogCategories,
                    catalogAccounts = catalogAccounts,
                    catalogState = catalogState,
                )
            }
        }
    }
}

@Composable
private fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectAsStateWithLifecycleCompat() =
    collectAsState()

@Composable
private fun <T> kotlinx.coroutines.flow.Flow<T>.collectAsStateWithLifecycleCompat(initial: T) =
    collectAsState(initial = initial)

private fun ComponentActivity.requestSyncNotifications() {
    if (Build.VERSION.SDK_INT < 33) return
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    ) {
        return
    }
    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
}
