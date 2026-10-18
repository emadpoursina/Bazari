# Research: Move Add-Note into Editing Screen + Default Category/Account

**Date**: 2026-10-18 | **Feature**: 008-default-category-account

All spec clarifications (Session 2026-10-18) are treated as closed; none of the decisions below re-open them — each resolves an *implementation* unknown.

## R1: Mechanics of moving the note control

- Today `EventsScreen.kt` keeps memo state per row (`editingMemo`/`memoDraft`, `remember(row.txId)`) and renders the note text, pending-sync labels, and an `Add note`/`Edit note` button that calls `onSaveMemo(txId, memo)` → `EventActions.saveMemo` (trim, ≤200 chars, `updateMemo` → `memoSyncState='pending'` → `SyncEngine.enqueueExpedited`).
- **Decision**: Delete only the `editingMemo` text-field/button block from the row composable; keep the `Note: …` text and both pending labels untouched (FR-001/FR-003 — display-only rows). Add a memo editor section to `EventDetailScreen` with a new `onSaveMemo: (String, String) -> Unit` parameter, pre-filled from `tx.userMemo`, same 200-char cap, Save/Cancel, mirroring the removed block's UI. `MainActivity` wires it to the already-existing `eventActions.saveMemo` (it currently passes it only to `EventsScreen`; the `EventsScreen.onSaveMemo` param is removed).
- **Alternatives considered**: modal `AlertDialog` on the detail screen (rejected — the spec's scenarios describe inline add/edit on the editing screen and the current inline pattern already exists); duplicating save logic in the composable (rejected — `EventActions.saveMemo` owns trim/limit/pending semantics, FR-003).

## R2: Single source of truth for the note length limit

- `MAX_MEMO_LENGTH = 200` exists twice: private in `EventsScreen.kt` and private in `EventActions`'s companion.
- **Decision**: Promote one internal `const val MAX_MEMO_LENGTH = 200` (kept in `EventActions`'s companion made `internal`, or a small shared file) referenced by `EventDetailScreen` and `EventsScreen`'s remaining display; `EventActions.saveMemo` keeps its existing `.take(MAX_MEMO_LENGTH)` enforcement as the authoritative gate.
- **Alternatives considered**: leaving duplicated constants (drift risk); a new prefs-driven limit (scope creep — spec says "same limit as today").

## R3: Where the default preference is stored

- Spec Assumption: "stored locally on the device, per-user-on-device, not synced to the server; persists across app restarts."
- `SettingsRepository` already backs every app-level preference with DataStore Preferences (`server_url`, capture toggles, debug mode, endpoints) and is already injected into `CapturePipeline` and `SettingsViewModel`.
- **Decision**: two new `intPreferencesKey`s — `default_category_id`, `default_account_id` — observed via `observe()`-style flow (`DefaultPreferences(defaultCategoryId: Int?, defaultAccountId: Int?)`), with `setDefaultCategoryId`/`setDefaultAccountId`/`clearDefaults`-style setters. **Key absent = no default; `remove()` = clear (FR-009).**
- **Alternatives considered**: Room table (rejected — requires a schema migration and a DAO for two scalars with no relational value); `SharedPreferences` like the bridge token (rejected — DataStore is the direction of travel for all observable settings and gives the settings screen a Flow for free); syncing to the server (rejected — out of spec scope, explicitly local).

## R4: Stamp point inside the capture pipeline

- Clarification: "stamp at capture"; spec assumption: "the default fills in only when no assignment exists."
- Parsers currently produce `categoryId = null`, `destinationAccountId = null`; fingerprint does not include either field, so stamping before insert is safe.
- **Decision**: in `CapturePipeline.processSource`, after a successful parse and before `persist(...)`: read `DefaultPreferences` (suspend DataStore read — `process()` is already suspend); resolve each stored ID against `db.serverCategoryDao().byId(id)` / `db.serverAccountDao().byId(id)` (cached catalog — invalid IDs behave as "no default", FR-010); copy onto the tx **only where the field is null**; if either field was stamped, set `assignmentSyncState = 'pending'` (FR-007a). The same validation means a default whose catalog row vanished is silently skipped at capture time (no crash, no pre-assignment).
- **Alternatives considered**: stamping in the UI when the row first renders (rejected — dynamic, violates stamp-at-capture and FR-008); stamping in the bridge (rejected — server untouched); stamping in `persist()` after the insert (rejected — an extra DAO update where an immutable column value at insert costs nothing).

## R5: Sync parity — reuse of the existing assignment path (FR-007a)

- Verified in code: the initial send payload (`BridgeClient`, ~line 491) already includes `destinationAccountId`/`categoryId`; on `CREATED`/`DUPLICATE` the worker calls `markAssignmentSynced(...)`; `pendingAssignments()` (requires `delivery_records.state = 'sent'`) + `updateAssignmentWithFallback` (`PUT /v1/transactions/assignment`) push anything still pending; `EventActions.saveAssignment` marks pending + `SyncEngine.enqueueExpedited`.
- **Decision**: a default-stamped row is byte-for-byte what `saveAssignment` would have produced (`categoryId`/`destinationAccountId` set, `assignmentSyncState='pending'`), so no `sync/` code changes. The existing capture-time `onCaptured → enqueueExpedited` (queued path, T031) is the "existing expedited-sync follow-up" required by FR-007a. **Held rows** (blank currency, 005 T027) keep their existing no-expedited semantics: stamped + pending at insert, cleared by `markAssignmentSynced` when the row eventually reaches `sent` after the source is bound (005 T028 stamps `held → queued` and triggers sync then).
- **Alternatives considered**: a dedicated "default-assignment" sync path (rejected — spec forbids it: "exactly like a user assignment … no separate sync path exists"); enqueueing expedited work for held rows (rejected — contradicts 005's deliberate T027 behavior and would push rows that cannot be delivered yet).

## R6: Settings UI — "Default category & default account" section

- `SettingsScreen` is a single scrolling `Column` of cards; MainActivity already observes `catalog.observeCategories()` / `observeAccounts()` and passes them to the Events/detail screens. `SettingsViewModel(settings, db)` already exists and can expose the defaults flow.
- **Decision**: new card in `SettingsScreen` between the capture toggles and permission status: two dropdown rows (Default category / Default destination account) populated from the catalog lists passed in as parameters, each showing the current label or "None", each with a **Clear** `TextButton` (FR-009), plus a "no longer available" warning line when a saved ID is absent from the current catalog (FR-010, FR-011). When the catalog is empty/unavailable (`CatalogRepository.Status.UNAVAILABLE`), pickers are disabled with an explanatory hint — existing defaults keep applying at capture (validated against the cached tables, R4), which satisfies the offline edge case. `MainActivity` passes `catalogCategories`, `catalogAccounts`, and `catalogState` into tab 3.
- **Alternatives considered**: a separate defaults screen/dialog (rejected — spec fixes the location: a section in the existing Settings tab); picking from DataStore only without catalog validation (rejected — FR-010 needs the catalog comparison at display time too).

## R7: Currency mismatch (FR-007b)

- Currency lives in `tx.currency`; assignment is a separate column pair and `saveAssignment`/`updateAssignment` never touches currency (005 FR-019).
- **Decision**: stamping `destinationAccountId` is independent of `tx.currency` — no comparison, no warning, no gating anywhere. Per-event override keeps working through the existing dropdown + `Save assignment`.
- **Alternatives considered**: blocking or warning on mismatch (rejected — clarified decision explicitly forbids it).

## R8: Persistence / migration impact

- **Decision**: zero Room schema change (all columns exist since features 004/005: `categoryId`, `destinationAccountId`, `assignmentSyncState`, `server_categories`, `server_accounts`). Defaults live in DataStore → no `AppDatabase` version bump, no migration test needed.
- **Alternatives considered**: a `default_preferences` Room table (rejected — see R3).

## R9: Testing strategy

- **Decision**: JVM unit tests following existing suites:
  - `capture/DefaultStampingTest.kt` — extend the `CapturePipelineTest` pattern: defaults set + valid catalog → stamped + pending; no defaults → null/synced; stale default ID → not stamped; pre-existing (non-null) assignment untouched; held (blank-currency) row stamped but not expedited.
  - `storage/DefaultPreferencesTest.kt` — DataStore set/clear/observe round-trip, key-absent default.
  - UI behavior stays logic-light; the fill-if-null and catalog-validation helpers are plain functions (like `currencyEditable`/`distinctCurrencies`) so they are directly testable without Compose.
- **Alternatives considered**: Robolectric UI tests for the settings card (optional extra; not required to prove FRs — quickstart covers manual UI validation).
