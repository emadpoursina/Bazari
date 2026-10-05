package com.gomoney.capture.capture

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Action of the documented scan request intent (contracts/scan-coordination.md §1). */
const val ACTION_SCAN = "com.gomoney.capture.SCAN_ACTIVE_NOTIFICATIONS"

/**
 * Transient Events-tab scan state (006 data-model.md §3). Held in a
 * `StateFlow` — never persisted (spec assumption).
 *
 * ```
 * Idle ──press (permission OK, not running)──▶ Running
 * Running ──loop finished──▶ Result(examined, added, skipped)
 * any press, permission missing──▶ Blocked      (no scan started, FR-006)
 * no service handle / not connected──▶ Unavailable
 * new scan accepted──▶ Running                  (previous banner cleared, FR-005)
 * user leaves Events tab──▶ shown banner consumed → Idle (FR-005)
 * ```
 */
sealed interface ScanUiState {
    /** Nothing shown. */
    data object Idle : ScanUiState

    /** Scan accepted: banner "Scanning active notifications…", button disabled (FR-008). */
    data object Running : ScanUiState

    /** Scan finished; `added + skipped == examined` always (research R4). */
    data class Result(
        val examined: Int,
        val added: Int,
        val skipped: Int,
    ) : ScanUiState

    /** Notification-read access missing on press — never a scan (FR-006). */
    data object Blocked : ScanUiState

    /** Listener not connected / service refused the nudge — guidance, never a silent failure. */
    data object Unavailable : ScanUiState
}

/**
 * The registered service seam (research R1): the UI asks the connected
 * `NotificationCaptureService` to scan; it never calls `getActiveNotifications()`
 * itself.
 */
fun interface ScanPerformer {
    fun performScan()
}

/**
 * One notification's disposition during a scan
 * (contracts/notification-identity.md §3). Every active notification yields
 * exactly one item, so `examined == items.size`.
 */
sealed interface ScanItem {
    /** The pipeline ran and returned [outcome]. */
    data class Processed(val outcome: Outcome) : ScanItem

    /** Identity key already `recorded` — skip, do not run the pipeline. */
    data object AlreadyRecorded : ScanItem

    /** Identity key `pending` younger than 60 s — another attempt owns it. */
    data object ClaimRejected : ScanItem

    /** No extractable text (`release`d — re-examinable later). */
    data object NoExtractableText : ScanItem

    /** `CaptureGate` closed (listener access revoked mid-scan) — `release`d. */
    data object GateClosed : ScanItem

    /** Not processable at all (e.g. missing package name) — `release`d. */
    data object UnusableNotification : ScanItem
}

/** Examined/added/skipped of one scan; the invariant is structural. */
data class ScanCounts(
    val examined: Int,
    val added: Int,
    val skipped: Int,
) {
    init {
        require(added + skipped == examined) { "added + skipped must equal examined" }
        require(added >= 0 && skipped >= 0 && examined >= 0)
    }
}

/**
 * Outcome → count mapping (T021, contracts/notification-identity.md §3,
 * research R4): a visible new Events-tab row counts as **added**, everything
 * else as **skipped**; `added + skipped == examined` always.
 */
fun countScan(items: List<ScanItem>): ScanCounts {
    val added = items.count { it.countsAsAdded() }
    return ScanCounts(examined = items.size, added = added, skipped = items.size - added)
}

/** `QUEUED` / `HELD` / `QUEUED_AS_KNOWN_DUPLICATE` created a visible row. */
fun ScanItem.countsAsAdded(): Boolean = when (this) {
    is ScanItem.Processed -> outcome.countsAsAdded()
    else -> false
}

/** Pure outcome → "added" predicate (contract §3 table). */
fun Outcome.countsAsAdded(): Boolean = when (this) {
    Outcome.QUEUED,
    Outcome.HELD,
    Outcome.QUEUED_AS_KNOWN_DUPLICATE,
    -> true

    Outcome.DUPLICATE_SHORT_CIRCUITED,
    Outcome.RETAINED_PARSE_ERROR,
    Outcome.IGNORED_NO_SOURCE,
    Outcome.IGNORED_DISABLED,
    -> false
}

/**
 * Which outcomes mean the pipeline persisted something, so the identity key
 * must be `commit`ted (terminal); everything else is `release`d
 * (contracts/notification-identity.md §2).
 */
fun Outcome.isPersisted(): Boolean = when (this) {
    Outcome.QUEUED,
    Outcome.HELD,
    Outcome.QUEUED_AS_KNOWN_DUPLICATE,
    Outcome.DUPLICATE_SHORT_CIRCUITED,
    Outcome.RETAINED_PARSE_ERROR,
    -> true

    Outcome.IGNORED_NO_SOURCE,
    Outcome.IGNORED_DISABLED,
    -> false
}

/**
 * UI ↔ service scan coordination (006 contracts/scan-coordination.md).
 *
 * Single-flight, transient, in-process: `requestScan` re-checks permission on
 * every press (SC-004), refuses overlapping scans (FR-007) and nudges the
 * registered service handle — falling back to the documented `ACTION_SCAN`
 * `startService` intent with every failure caught (never crash the UI).
 * The banner is consumed when the user leaves the Events tab (FR-005).
 */
object ScanCoordinator {

    private val _state = MutableStateFlow<ScanUiState>(ScanUiState.Idle)

    /** Events-tab banner state (data-model.md §3). */
    val state: StateFlow<ScanUiState> = _state.asStateFlow()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var handle: ScanPerformer? = null

    /**
     * MainActivity publishes `(selectedTab == 1)`; leaving the Events tab
     * consumes any shown banner, and a result completing while the tab is
     * hidden is consumed as `Idle` (FR-005, research R6).
     */
    var eventsTabVisible: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            if (!value) consumeBanner()
        }

    /** App context for the `startService` fallback (registered by MainActivity). */
    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    /** The listener service registers in `onCreate`/`onListenerConnected` (research R1). */
    fun registerHandle(service: ScanPerformer) {
        handle = service
    }

    /** Cleared in the service's `onDestroy`. */
    fun unregisterHandle(service: ScanPerformer) {
        if (handle === service) handle = null
    }

    /**
     * Request path (contracts/scan-coordination.md §1):
     * [1] permission missing → `Blocked`, no scan; [2] already `Running` →
     * ignore (single-flight); [3] publish `Running`; [4] registered handle →
     * `performScan()`, else `runCatching { startService(ACTION_SCAN) }` and
     * `Unavailable` on refusal (never crash the UI).
     */
    fun requestScan(permission: CaptureGate.CapturePermission) {
        if (!permission.isNotificationListenerAccessGranted()) {
            _state.value = ScanUiState.Blocked
            return
        }
        if (_state.value is ScanUiState.Running) return

        _state.value = ScanUiState.Running
        val service = handle
        if (service != null) {
            service.performScan()
            return
        }
        val context = appContext
        if (context == null) {
            _state.value = ScanUiState.Unavailable
            return
        }
        // A same-app startService may be refused by the service's
        // BIND_NOTIFICATION_LISTENER_SERVICE guard; never crash the UI.
        runCatching { context.startService(scanIntent(context)) }
            .onFailure { _state.value = ScanUiState.Unavailable }
        // On success the service picks it up and publishes Running/Result/
        // Unavailable itself (it knows whether the listener is connected).
    }

    /** Explicit intent for the documented `ACTION_SCAN` transport. */
    fun scanIntent(context: Context): Intent =
        Intent(context, NotificationCaptureService::class.java).setAction(ACTION_SCAN)

    // --- published by NotificationCaptureService ---

    /** Scan accepted / started (single-flight: an in-flight scan keeps Running). */
    fun publishRunning() {
        _state.value = ScanUiState.Running
    }

    /** Loop finished — replaced by `Idle` when the Events tab is hidden. */
    fun publishResult(counts: ScanCounts) {
        _state.value = if (eventsTabVisible) {
            ScanUiState.Result(counts.examined, counts.added, counts.skipped)
        } else {
            ScanUiState.Idle
        }
    }

    /** Listener not connected / list unreadable — clean failure with guidance (R9). */
    fun publishUnavailable() {
        _state.value = ScanUiState.Unavailable
    }

    /** Banner consumed (leaving the tab, or a reset). */
    fun consumeBanner() {
        if (_state.value !is ScanUiState.Running) _state.value = ScanUiState.Idle
    }

    /** Test seam: return the coordinator to a pristine state between cases. */
    internal fun reset() {
        _state.value = ScanUiState.Idle
        handle = null
        appContext = null
        eventsTabVisible = true
    }
}
