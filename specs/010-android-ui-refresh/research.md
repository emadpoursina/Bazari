# Research: Android Capture App UI Refresh

**Date**: 2026-11-17 | **Feature**: 010-android-ui-refresh

Clarify session 2026-11-17 is closed. This file records implementation choices, not product questions.

## R1. Product look: capture companion, not finance dashboard

**Decision**: Visual and interaction patterns from list-first money apps (bottom bar, large amounts, day groups, status color, one-action empty states) apply **only as chrome**. Home is health + recent activity. No period totals, charts, budgets, widgets, or “this month spent”.

**Rationale**: Spec Q on similar-product look and Home purpose. Competing apps that look “good” are often spending overviews; this app’s job is capture and send to Go Money.

**Alternatives considered**: Spendee-style Home with donut + monthly total (rejected by spec). Keep current Dashboard jargon (`Bridge:`, raw `Pending`/`Failed`) (fails SC-001).

## R2. Navigation: Material 3 `NavigationBar`, keep tab index

**Decision**: Replace `TabRow` + text-only tabs in `MainActivity.AppTabs` with `NavigationBar` / `NavigationBarItem` (icon + short label). Keep `selectedTab` 0..3 and the existing nested `selectedTxId` / `editingSource` flags. Leaving Events (tab ≠ 1) still clears detail + missing-event banner; leaving Sources still clears the editor.

**Rationale**: Spec FR-001/US6. Current `TabRow` has no icons and still says “Dashboard”. Navigation-Compose would rewrite the host for no behavior gain.

**Alternatives considered**: Navigation-Compose graph (heavier). Hide bottom bar on nested screens (allowed by FR-001 “top-level only”, but current host already shows tabs on detail; **keep bar visible** so destination switching still closes nested screens as today).

## R3. Home copy and data (no new Room queries required)

**Decision**:
- Rename user-facing destination **Home** (code may keep `Dashboard*` types).
- Connection: `CONNECTED` → reachable; `DISCONNECTED` / `UNKNOWN` with blank URL → not set up yet; otherwise not reachable. Do **not** headline endpoint URLs or the word “Bridge”.
- Counts: today’s capture, waiting (`pending` including failed? Spec wants waiting **and** failed separately — use existing `pending` minus failed for waiting, plus `failed`).
- Surface **notification-listener blocked** using `PlatformCapturePermission` already injected into `DashboardViewModel` but unused on the screen.
- Recent list: take first N (5) of already-observed recent txs + delivery state; tap → Events tab + `selectedTxId`.
- One primary next step when blocked (grant access, add destination, or open Events). Healthy Home is calm (no error colors; “Sync now” is secondary or omitted when all-clear).

**Rationale**: Spec US1/FR-003/FR-004. ViewModel already has counts, connection, permission.

**Alternatives considered**: New Room DAO for “today” (unnecessary). Spending widgets (forbidden).

## R4. Amount display without changing storage

**Decision**: Format `amountMinor` with grouping separators (`DecimalFormat` / `NumberFormat` for the default locale). Append the event’s `currency` code when non-blank. Blank currency → grouped number only, never invent a code. Do **not** rescale integers (IRR is stored as whole rials). Income/expense: minus sign or “Expense”/“Income” label **plus** color.

**Rationale**: Spec assumptions + FR-005/FR-017. `AmountNormalizer` remains parse-only.

**Alternatives considered**: `NumberFormat.getCurrencyInstance` with ISO (invents symbols/names and assumes fractional minor units). Hardcoded “rial” (already forbidden in 005).

## R5. Status chips from existing delivery states

**Decision**: Map wire `delivery_records.state` (+ parse-error rows) to four user chips:

| Chip | Internal |
|---|---|
| Sent | `sent` |
| Waiting | `queued`, `sending`, `held`, `captured` (if shown) |
| Couldn't send | `failed` |
| Parse problem | parse-error rows (`parsed` without normalized tx, or `errorCategory` parse) |

Default UI never prints raw `QUEUED`/`FAILED`. Debug may still show wire values on `DebugEventScreen`.

**Rationale**: FR-016. Same mapping on Home recent rows, Events list, detail header.

**Alternatives considered**: Per-screen ad-hoc strings (inconsistent). Exposing `held` as its own chip (too internal for default UI; “Waiting” is enough).

## R6. Events inbox: grouping + selection mode

**Decision**:
- Group `EventRow`s by **device-local calendar day** of `txAt` (ISO instant → `LocalDate`). Sticky section headers (`stickyHeader` in `LazyColumn`).
- Default: no checkboxes, no Select all / Sync selected / Delete selected / Clear all bar.
- Enter selection via long-press on a row or a list overflow “Select”. Then checkboxes + selection actions in the top bar.
- Rare: **Clear processed** and **Clear all** in overflow (`DropdownMenu`), still with existing confirm dialogs.
- **Scan notifications** remains a primary Events control (top of list or app-bar action), even when empty. Banner stays first list item (006 contract).
- Empty: headline + one sentence + primary Scan (or Add source as the single primary if scan is blocked — prefer Scan as the empty primary; Sources is the explanation link).
- Row tap → detail. Failed rows keep inline Retry.
- Selection state stays in `EventsScreen` `remember` (not ViewModel) so leaving the destination already clears it (composition disposed).

**Rationale**: US2/FR-007/FR-008/FR-009. Mail/files pattern. Current wrapping `FlowRow` is the “awful” bar.

**Alternatives considered**: Always-visible checkboxes (today; fails FR-007). FAB for scan only (scan is not a create-item action; keep explicit labeled control).

## R7. Event detail as a first-class screen

**Decision**: Single column, top to bottom:

1. Back
2. Identity: formatted amount, Income/Expense, source (`bank`), time (human), status chip
3. Send assignment: destination account, category, currency (gated by existing `currencyEditable`). **One** confirm control for destination+category (`onSaveAssignment`). Currency still saves on pick (existing `onSaveCurrency`) because it is already immediate and independent of assignment.
4. Note: always-visible field, Save note / Cancel (009 contract unchanged)
5. Retry if failed (primary button)
6. Pending-sync hints and stale-category warning stay under assignment

Hide `parserName`, `confidence`, unlabeled “Delivery state: queued”. When `debugModeEnabled`, show a control that opens `DebugEventScreen` (currently unused) or an internals section — not in the header.

**Rationale**: US3/FR-010/FR-011. Today the screen leads with raw minor units and always shows parser internals.

**Alternatives considered**: Merge note into the assignment confirm (rejected: spec keeps explicit note save/cancel). Navigation-Compose detail route (unnecessary).

## R8. Theme, contrast, RTL-capable layout

**Decision**: Introduce `CaptureTheme` wrapping `MaterialTheme` with light and dark `ColorScheme` from `isSystemInDarkTheme()` (no in-app theme picker). Replace `Theme.GomoneyCapture` parent `Theme.Material.Light.NoActionBar` with a DayNight/Material3 no-action-bar parent so the system setting actually reaches the window. Semantic colors: expense, income, success (sent), warning (waiting), error (failed/parse) — always paired with text/sign. Spacing 8-dp grid; amounts use `headline`/`title` roles. Use `start`/`end` padding, `Arrangement.Start`, and `weight` so 360 dp and future RTL do not clip primary actions. Minimum tap size 48 dp.

**Rationale**: FR-016/FR-017/FR-022, SC-005/SC-006.

**Alternatives considered**: Dynamic Material You only (fine as extra on API 31+, but semantic income/expense must not depend on wallpaper). In-app theme toggle (out of scope).

## R9. Setup without a wizard

**Decision**: No onboarding Activity. Home, Events scan-blocked banner, and Settings permission group share the **same** explanation + “Open notification access settings” (`ACTION_NOTIFICATION_LISTENER_SETTINGS`, existing helper in `EventsScreen`). Blank endpoints: Home primary action switches to Settings (tab 3). Empty Sources: “Add source”.

**Rationale**: Clarify: no separate wizard.

**Alternatives considered**: First-launch pager (rejected).

## R10. Testing and USB verification (user constraint)

**Decision**:
- Extract pure Kotlin mappers; cover with JUnit (no Compose runtime required).
- Keep existing ui tests (`NotificationOpenRouting`, currency gating, assignment, sources stale).
- **Do not** add an emulator-only CI gate as the UI proof for this feature.
- Implement/converge UI walks use the **USB-C attached physical phone**: `adb devices` must list it; `adb install -r` the debug APK; exercise Home, Events (including selection + detail), Sources, Settings, light and dark (`adb shell cmd uimode night yes|no`). If the device width is >360 dp, still verify on that hardware; optionally `adb shell wm size` only as a **temporary** density check on that same device, then restore. Emulators are optional developer convenience, not the recorded verification path.

**Rationale**: User constraint for this flow. Robolectric cannot prove contrast, bottom-bar hit targets, or system settings handoff.

**Alternatives considered**: Espresso instrumented suite (heavier than needed for a restyle; optional later). Screenshot tests on emulator CI (not the user’s constraint).

## R11. Scope freeze (non-UI)

**Decision**: No changes to parsers, `CapturePipeline`, `SyncEngine`, bridge HTTP, Room entities, fingerprint, default-stamping rules, notification-open routing outcomes (presentation of missing-event banner may restyle). `EventActions` method signatures stay.

**Rationale**: FR-020/FR-019/SC-008.

**Alternatives considered**: Drive-by catalog/sync refactors (rejected).
