# Phase 0 Research: Notification Scan Button

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md)

All Technical Context items are resolved here. Findings are grounded in `android/app/src/main/kotlin/com/gomoney/capture/` as it exists on `notification-events-button` after `specs/005-account-currency-sources`.

## R1 — The UI asks the service; it never calls `getActiveNotifications()` itself

**Decision**: `getActiveNotifications()` is called only inside `NotificationCaptureService`. The UI triggers a scan through a new in-process `ScanCoordinator` object that owns (a) a `StateFlow<ScanUiState>` and (b) a registered service handle: the service publishes `this` in `onCreate`/`onListenerConnected` and clears it in `onDestroy`. `NotificationCaptureService` also implements `onStartCommand` handling `ACTION_SCAN` (`com.gomoney.capture.SCAN_ACTIVE_NOTIFICATIONS`) and calls the same `performScan()` entry point. When no handle is registered, `ScanCoordinator` attempts `context.startService(action intent)` wrapped in `runCatching` (a same-app `startService` may be refused by the service's `BIND_NOTIFICATION_LISTENER_SERVICE` guard on some builds; a refusal must never crash the UI) and, if no service picks it up, publishes an "unavailable" state with the same settings guidance as the missing-permission path.

**Rationale**: The listener service runs in the app's own process (no `android:process` in the manifest), so an in-process handle is exact and testable, and it sidesteps an uncertain platform behavior (whether `startService` passes the signature-level permission guard for same-UID callers). The `onStartCommand` action keeps the conventional, documented interface as the transport of record, exactly as the task guidance suggests.

**Alternatives considered**: `bindService` from the UI (blocked — the service declares `android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"`, a signature permission the app does not hold); calling `getActiveNotifications()` from the UI (throws / returns nothing outside the listener); a bound AIDL service (heavyweight for a single local call); making `startService` the *only* path (crash or silent-stall risk if the platform refuses it).

## R2 — At-most-once per notification = identity guard keyed by `StatusBarNotification.key`

**Decision**: New Room table `notification_capture_records` with PK `notification_key` = `sbn.key` (`package|id|tag|userId` — stable across in-place updates of the same notification). Three operations: **claim** (insert `pending` row with `updated_at`, taking over a `pending` row older than 60 s), **commit** (set `state = 'recorded'` + `event_id` once the pipeline persisted something), **release** (delete the row when the pipeline persisted nothing, e.g. `IGNORED_NO_SOURCE`, `IGNORED_DISABLED`, or extraction found no text). Both the scan loop *and* the real-time `onNotificationPosted` path run claim → process → commit/release, so FR-003 holds across scans *and* real-time captures and across content changes (clarification Q1).

**Rationale**: Fingerprint dedup alone is insufficient: it catches identical content, but an in-place update with changed content yields a different fingerprint and would create a second event — forbidden by FR-003. The claim/commit protocol also closes the race between a real-time post and a concurrent scan for the same key, and the stale-claim takeover keeps an interrupted (crashed) scan from permanently swallowing a notification (spec edge case: after interruption the user can simply scan again).

**Alternatives considered**: Fingerprint-only dedup (fails on content-changed updates); recording the key only after processing without a claim (race window: two concurrent paths both pass the lookup and both persist); a `flag` column on `raw_events` (cannot cover notifications that persist nothing yet still need the race guard, and couples identity to a row that may be cleared); marking the key before processing and never releasing it (a scan killed mid-flight would hide notifications that never produced events).

## R3 — One extraction/timestamp path shared by both entry points

**Decision**: Lift `extractText(sbn)` (EXTRA_TEXT / EXTRA_BIG_TEXT, longest non-blank wins) and `safePostedAt(sbn.postTime)` out of `onNotificationPosted` into shared top-level functions in the `capture` package (same file is fine — `NotificationCaptureService`), and call them from both the real-time path and the scan loop. Scan-produced `RawEvent`s are indistinguishable from real-time ones: `source = "notification"`, `sourcePackage = sbn.packageName`, `title`, `text`, `postedAt = safePostedAt(sbn.postTime)`, `capturedAt = now`, fresh UUID (clarification Q2; spec Key Entity "Event").

**Rationale**: FR-002 requires "same source recognition, same content extraction, same persistence path". Duplicating the extraction logic in a scan-only helper is exactly how the two paths would drift.

**Alternatives considered**: Making the scan call `onNotificationPosted(sbn)` directly (mixes side-effect entry with scan bookkeeping and skips scan counting hooks); a separate `ScanPipeline` (two admission gates to keep in sync — forbidden by reuse).

## R4 — Counting: examined / added / skipped (clarification Q3)

**Decision**: `examined` = number of active notifications returned by `getActiveNotifications()` (0 → "No active notifications"). Per notification, **added** increments only when the pipeline outcome created a visible Events-tab row — i.e. `QUEUED`, `HELD`, `QUEUED_AS_KNOWN_DUPLICATE` (a `NormalizedTransaction` row exists). **skipped** = `examined − added`, covering `IGNORED_NO_SOURCE`, `IGNORED_DISABLED`, `DUPLICATE_SHORT_CIRCUITED`, `RETAINED_PARSE_ERROR`, no extractable text, and — per clarification Q3 — notifications whose identity key was already recorded (already-recorded counted inside skipped). Completion message: `Examined X · Added Y · Skipped Z`, or an explicit "No new events found (X examined)" when `added == 0` (FR-005).

**Rationale**: The spec puts parse errors ("malformed content") in the skip bucket and groups "skipped or could not be processed" (US2). A parse-error delivery row *is* shown in the Events list, but counting it as added would tell the user an event was created from content that failed to parse; the edge-case wording ("skipped … the summary reflects skips") decides it. `examined = added + skipped` always holds, so the banner is self-consistent and testable.

**Alternatives considered**: A fourth "failed" counter (contradicts Q3's three counts); counting `RETAINED_PARSE_ERROR` as added (misleading, and contradicts the edge case); counting only notifications from recognized sources as "examined" (the user cannot tell whether unrecognized notifications were seen at all).

## R5 — Missing permission: inline message + button to the system settings screen (clarifications Q4/Q5)

**Decision**: `ScanCoordinator.requestScan()` re-checks `PlatformCapturePermission.isNotificationListenerAccessGranted()` on **every** press. Not granted → state `Blocked`; no scan starts, no partial work happens (FR-006). The banner shows the explanation plus a button launching `Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)` (runCatching fallback: `Intent(Settings.ACTION_SETTINGS)`).

**Rationale**: SC-004 demands the guidance on 100% of presses; the existing `PlatformCapturePermission` is already the app's single source of truth for listener access (used by `CaptureGate` and `SettingsScreen`), so the press-time check reuses it instead of inventing another. `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS` is the standard deep link to the Notification access screen (used as `android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS` in common practice).

**Alternatives considered**: Checking permission only at app start (stale; revocation between start and press would silently scan); opening the app's own settings page (does not grant notification access); a runtime permission dialog (notification access is not a runtime permission).

## R6 — Banner placement, empty-list state, and lifecycle

**Decision**: The scan control lives in the existing top action row of `EventsScreen` beside **Clear processed** / **Clear all**, and the result banner is the first `LazyColumn` item directly above it. The current early-return "No captured transactions yet." branch is restructured so the button and banner (including the `Blocked` guidance) are visible even when the list is empty — P1 acceptance must be testable with zero events. Banner state comes from `ScanCoordinator.state`: `Idle` (nothing), `Running` (progress text; button disabled with an indicator — FR-008), `Result(examined, added, skipped)` (FR-005 wording), `Blocked`/`Unavailable` (message + settings button). Starting a new scan replaces any previous banner (`Running` overwrite — "cleared when the next scan starts"). `MainActivity` publishes `ScanCoordinator.eventsTabVisible = (selectedTab == 1)`; leaving the tab hides a shown banner, and a scan that *finishes* while the tab is not visible is consumed as `Idle` rather than surfaced later (FR-005 "cleared … when the user leaves the tab").

**Rationale**: The banner must be inline and transient by spec; hoisting visibility in `ScanCoordinator` keeps it a single testable state machine instead of composition-scattered `remember` state (the ViewModels are deliberately hoisted above the tab switcher, so composable-local state alone would leak the banner across tab visits).

**Alternatives considered**: A `Snackbar` (transient but not inline at the top of the list, and easy to miss — Q4 says banner); a dialog (not inline, blocks browsing); keeping banner state in `EventsScreen` locals (leaks across tab switches because the tab content is re-composed from hoisted state).

## R7 — Single-flight + responsiveness (FR-007/008, SC-003)

**Decision**: One `AtomicBoolean` in-flight guard in the service (`performScan()` returns immediately if already scanning) plus a `Running` check in `ScanCoordinator` (presses while `Running` are no-ops). The loop processes notifications sequentially inside the service's `Dispatchers.IO` scope; `getActiveNotifications()` is invoked on that background thread (binder call — never on main). Each iteration is bounded work (Room writes), so dozens of notifications finish well inside the 10 s budget; the Compose list is untouched while scanning.

**Rationale**: The pipeline is already suspending/IO-bound, and sequential processing keeps counting deterministic (no interleaved claims). A foreground service/notification for a < 10 s local scan would be noise (and would add a user-visible notification the feature does not ask for).

**Alternatives considered**: `async`/parallel per-notification processing (nondeterministic counts, no throughput gain for Room-bound work); running the scan in the Activity (cannot reach `getActiveNotifications()`); WorkManager (heavyweight, has its own scheduling delays, overkill for a button press).

## R8 — Identity table lifetime vs. Clear buttons

**Decision**: `notification_capture_records` is an independent table (no FK to `raw_events`). `MaintenanceRepository.clearAll()` deletes its rows along with everything else, so after **Clear all** a scan can re-create events for notifications still in the shade. `clearProcessed()` leaves the identity rows alone, so cleared terminal events are not resurrected by a later scan. Room schema bumps v4 → v5 via `MIGRATION_4_5` (pure `CREATE TABLE`), `exportSchema = false` unchanged.

**Rationale**: If the identity rows died with `clearAll`, the "at most one event per notification" history would be wrong in the direction users notice (Clear all → scan → nothing ever appears again). Tying them to `raw_events` by FK would also make `clearProcessed` silently re-open those notifications. Keeping the table independent and clearing it explicitly in `clearAll` matches user intent for both buttons.

**Alternatives considered**: FK cascade from `raw_events` (auto-clean, but re-opens cleared notifications on `clearProcessed`); never clearing (a scan after Clear all reports 0 added forever — confusing); storing the guard in DataStore (second store for data that is naturally relational).

## R9 — Permission/connected-state degradation

**Decision**: The scan runs only when (a) listener access is granted and (b) the service handle exists and reports `onListenerConnected` (the platform requires waiting for `onListenerConnected` before `getActiveNotifications()`). Case (a) false → `Blocked` (R5). Case (a) true but (b) false → `Unavailable`: attempt the `startService` nudge (R1), then show the same message + settings button (toggling access re-binds the listener) — never a silent failure or an empty "success" result.

**Rationale**: Spec assumption "if the platform makes reading the active list impossible, the scan fails cleanly with user guidance rather than partially succeeding."

**Alternatives considered**: `NotificationListenerService.requestRebind()` as the recovery (public API but its failure mode is a no-op; the settings screen covers the same ground for the user); reporting "0 examined" when disconnected (misleading — SC-005 forbids it).

## R10 — Testing seams

**Decision**: Keep three pure/testable units: `NotificationIdentityRegistry` (takes a DAO/record store — claim/commit/release/stale-takeover), count computation (a pure function `Outcome -> Counted` plus the already-recorded skip), and `ScanCoordinator` (single-flight, permission check via a fake `CapturePermission`, tab-visibility consumption) — all exercised with plain JUnit against fakes, matching the existing test style (`CapturePipelineTest`, `CrossSourceDedupTest`). The scan loop itself is a thin orchestrator over these units.

**Rationale**: No instrumentation test source set exists (`android/app/src/` has only `main` and `test`), and `make android-gradle-test` is the project's automated gate.

**Alternatives considered**: Robolectric UI tests (new dependency, not in the current stack); testing only on-device (slow, non-deterministic for race cases).
