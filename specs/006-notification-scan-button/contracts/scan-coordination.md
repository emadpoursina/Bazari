# Contract: Scan Coordination (Events UI ↔ NotificationCaptureService)

**Feature**: Notification Scan Button | **Plan**: [plan.md](./plan.md) | **Identity rules**: [notification-identity.md](./notification-identity.md)

Internal Android contract. No bridge/HTTP surface is added or changed.

## 1. Request path

```text
EventsScreen "Scan notifications"
  → ScanCoordinator.requestScan(permission: CapturePermission)
      [1] permission.isNotificationListenerAccessGranted() == false → state = Blocked, STOP (no scan, FR-006)
      [2] state == Running                          → ignore, STOP (single-flight, FR-007)
      [3] state = Running
      [4] registered service handle present         → handle.performScan()
         else                                       → runCatching { context.startService(ACTION_SCAN intent) }
                                                      state = Unavailable (unless the service picks it up)
  → NotificationCaptureService.performScan()        → onStartCommand(ACTION_SCAN) → same entry point
```

- **Action**: `com.gomoney.capture.SCAN_ACTIVE_NOTIFICATIONS`, explicit component `com.gomoney.capture/.capture.NotificationCaptureService`.
- `startService` failures (`SecurityException`, `IllegalStateException`) MUST be caught — never crash the UI (research R1).
- The service MUST guard `performScan()` with its own in-flight flag (FR-007) and MUST run only when `onListenerConnected` has fired (research R9).
- `getActiveNotifications()` MUST be called on a background thread inside the service (never on main).

## 2. Result path (service → UI)

Service publishes to `ScanCoordinator.state: StateFlow<ScanUiState>` (see [data-model.md](../data-model.md) §3 for the full state machine):

| State | Published when | UI obligation |
|---|---|---|
| `Running` | request accepted (step 3) | banner text "Scanning active notifications…"; scan button disabled + progress indicator (FR-008) |
| `Result(examined, added, skipped)` | loop finished | inline banner, first item above the action row (FR-005) |
| `Blocked` | press-time permission check failed | message + button → `Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)` (fallback `Intent(Settings.ACTION_SETTINGS)`); no scan started (FR-006, Q5) |
| `Unavailable` | no service handle / not connected after nudge | same guidance button + "try again" hint |
| `Idle` | banner consumed | nothing rendered |

Banner wording (FR-005, research R4):

- `examined == 0` → `No active notifications.`
- `added == 0 && examined > 0` → `No new events found · Examined X · Skipped X`
- otherwise → `Examined X · Added Y · Skipped Z`

Invariant: `added + skipped == examined` in every `Result`.

## 3. Banner lifecycle (FR-005)

| Trigger | Effect |
|---|---|
| New scan accepted | previous `Result`/`Blocked` replaced by `Running` ("cleared when the next scan starts") |
| User leaves the Events tab (`selectedTab != 1`) | shown banner consumed → `Idle`; a scan still running continues, and a result that completes while the tab is hidden is consumed as `Idle` instead of `Result` |
| User returns to the Events tab | banner reflects only state produced while visible (`Idle` shows nothing) |
| New rows from the scan | arrive through the existing Room Flows — no manual refresh, no restart (FR-009) |

## 4. UI placement (FR-001)

- The scan control lives in the existing top action `Row` of `EventsScreen`, beside **Clear processed** and **Clear all**.
- The banner is the first `LazyColumn` item, directly above that row.
- The empty-list branch (`No captured transactions yet.`) MUST still render the action row (including the scan control) and the banner — P1 must be testable with zero events.

## 5. Out of scope

- No persistence of scan summaries (transient only).
- No new Android permission, no `AndroidManifest.xml` change, no bridge/API change.
- The scan never modifies or deletes existing events (FR-010); `CapturePipeline` remains the only admission gate (no scan-specific matching rules).
