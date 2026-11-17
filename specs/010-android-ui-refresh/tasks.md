---
description: "Task list for Android Capture App UI Refresh"
---

# Tasks: Android Capture App UI Refresh

**Input**: Design documents from `/specs/010-android-ui-refresh/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: JVM unit tests for extracted display mappers are required by plan.md / research.md R10 (`AmountFormat`, `StatusPresentation`, `EventDayGrouping`, `HomeNextStep`). Do **not** add emulator-only UI tests as the acceptance gate. Physical USB-C phone over `adb` is the UI verification surface (quickstart.md).

**Organization**: Shared chrome (theme, chips, amounts, bottom bar) is Foundational because those contracts apply to every destination. User-story phases then restyle each destination. Do not change Room schema, parsers, `CapturePipeline`, `SyncEngine`, or `EventActions` signatures (FR-020, research R11).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

- Android capture app: `android/app/src/main/kotlin/com/gomoney/capture/`
- Resources: `android/app/src/main/res/`
- JVM tests: `android/app/src/test/kotlin/com/gomoney/capture/ui/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Theme package, English copy, and system DayNight parent so later screens share one visual system

- [X] T001 Create `android/app/src/main/kotlin/com/gomoney/capture/ui/theme/` (empty package for `CaptureTheme`, `AmountFormat`, `StatusPresentation`, and related helpers per plan.md)
- [X] T002 [P] Add English user-facing strings (nav Home/Events/Sources/Settings, chips Sent/Waiting/Couldn't send/Parse problem, Home health copy, empty-state headlines, shared notification-access explanation) in `android/app/src/main/res/values/strings.xml`
- [X] T003 [P] Switch `Theme.GomoneyCapture` from `Theme.Material.Light.NoActionBar` to a DayNight / Material3 no-action-bar parent in `android/app/src/main/res/values/themes.xml` (add `android/app/src/main/res/values-night/themes.xml` only if the parent requires a night overlay)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Shared visual system, amount/status mappers, and `NavigationBar` chrome that every story must use

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

### Mapper tests (write first; they must fail before implementation)

- [X] T004 [P] Add failing unit tests for grouping separators, blank currency (number only, never invent a code), and no minor-unit rescale in `android/app/src/test/kotlin/com/gomoney/capture/ui/AmountFormatTest.kt`
- [X] T005 [P] Add failing unit tests mapping delivery/parse states to Sent / Waiting / Couldn't send / Parse problem (never raw `QUEUED`/`FAILED`) in `android/app/src/test/kotlin/com/gomoney/capture/ui/StatusPresentationTest.kt`

### Shared presentation

- [X] T006 Implement `formatAmountMinor` (locale grouping + optional currency code) in `android/app/src/main/kotlin/com/gomoney/capture/ui/theme/AmountFormat.kt` until T004 passes
- [X] T007 Implement chip label/tone mapping in `android/app/src/main/kotlin/com/gomoney/capture/ui/theme/StatusPresentation.kt` until T005 passes (data-model.md §3)
- [X] T008 [P] Add `CaptureTheme` wrapping `MaterialTheme` with `isSystemInDarkTheme()` light/dark schemes, 8 dp spacing, type roles, and income/expense/success/warning/error tokens in `android/app/src/main/kotlin/com/gomoney/capture/ui/theme/CaptureTheme.kt`
- [X] T009 [P] Add a shared status-chip composable used by Home/Events/detail in `android/app/src/main/kotlin/com/gomoney/capture/ui/theme/StatusChip.kt`
- [X] T010 Extract `openNotificationAccessSettings` from `android/app/src/main/kotlin/com/gomoney/capture/ui/EventsScreen.kt` into `android/app/src/main/kotlin/com/gomoney/capture/ui/NotificationAccess.kt` (same `ACTION_NOTIFICATION_LISTENER_SETTINGS` intent; Home/Events/Settings will call it)

### Host chrome

- [X] T011 Wrap `setContent` with `CaptureTheme` instead of bare `MaterialTheme` in `android/app/src/main/kotlin/com/gomoney/capture/ui/MainActivity.kt`
- [X] T012 Replace text-only `TabRow` (`Dashboard`/`Events`/`Sources`/`Settings`) with Material 3 `NavigationBar` + `NavigationBarItem` (icon + short label Home/Events/Sources/Settings, selectedTab 0..3 unchanged, bar stays visible on nested screens, `ScanCoordinator.eventsTabVisible` still `selectedTab == 1`) in `android/app/src/main/kotlin/com/gomoney/capture/ui/MainActivity.kt` per `contracts/navigation-chrome.md`

**Checkpoint**: Foundation ready — theme, chips, amounts, four-item bottom bar, shared permission intent. Destination screens still old layouts until story phases.

---

## Phase 3: User Story 1 - Open the app and know it is working (Priority: P1) 🎯 MVP

**Goal**: Home (`DashboardScreen`) reads as capture health + recent activity with one in-place next step — not a spending dashboard and not Bridge jargon

**Independent Test**: On the USB-connected phone, open Home with mixed sent/pending/failed events and confirm a first-time visitor can state health, today’s volume, and the next action without leaving Home. No charts or period spend totals.

### Tests for User Story 1

- [X] T013 [P] [US1] Add failing unit tests for next-step priority (notification access missing → grant; blank destination → Settings; else queue attention → Events; healthy → none) in `android/app/src/test/kotlin/com/gomoney/capture/ui/HomeNextStepTest.kt`

### Implementation for User Story 1

- [X] T014 [US1] Implement next-step selection in `android/app/src/main/kotlin/com/gomoney/capture/ui/theme/HomeNextStep.kt` until T013 passes (`contracts/home-health.md` §2)
- [X] T015 [US1] Expose `captureBlocked` from unused `PlatformCapturePermission`, `waiting` as pending minus failed, connection as Reachable / Not reachable / Not set up yet (blank URL; no endpoint URL headline), and first 5 recent rows in `android/app/src/main/kotlin/com/gomoney/capture/ui/DashboardViewModel.kt` — no new Room queries or spend aggregations
- [X] T016 [US1] Restyle Home as health + counts (Captured today, Waiting to send, Couldn't send) + recent list (formatted amount, source, time, status chip) + calm healthy surfaces in `android/app/src/main/kotlin/com/gomoney/capture/ui/DashboardScreen.kt`; forbid charts/totals/budgets (FR-020)
- [X] T017 [US1] Wire Home recent-row tap to Events tab + `selectedTxId`, blocked CTA to `NotificationAccess` or Settings tab 3, and optional secondary Sync only when `SyncBanner` is not `ALL_CLEAR`, in `android/app/src/main/kotlin/com/gomoney/capture/ui/MainActivity.kt`

**Checkpoint**: User Story 1 is independently testable on the physical device (Home only). Bottom bar from Phase 2 is already present.

---

## Phase 4: User Story 2 - Review and complete captured events quickly (Priority: P1)

**Goal**: Events list is a day-grouped capture inbox; bulk tools hidden until selection; scan stays primary with banner feedback

**Independent Test**: On the USB phone, with a populated list spanning two days, find yesterday’s charge and open detail in one flow; long-press to select two rows and delete only after confirm; casual scroll must not show Select all / Delete selected / Clear all.

### Tests for User Story 2

- [X] T018 [P] [US2] Add failing unit tests grouping rows by device-local calendar day of `txAt` (newest day first) in `android/app/src/test/kotlin/com/gomoney/capture/ui/EventDayGroupingTest.kt`

### Implementation for User Story 2

- [X] T019 [US2] Implement day grouping helper in `android/app/src/main/kotlin/com/gomoney/capture/ui/theme/EventDayGrouping.kt` until T018 passes
- [X] T020 [US2] Restyle default (non-selecting) Events list: sticky day headers, formatted amounts + currency, source, truncated note, time, `StatusChip`, whole-row tap → detail, inline Retry on failed rows, no always-on checkboxes or wrapping bulk `FlowRow`, in `android/app/src/main/kotlin/com/gomoney/capture/ui/EventsScreen.kt`
- [X] T021 [US2] Add opt-in selection mode (long-press or overflow Select → checkboxes + Select all / Sync selected / Delete selected with existing confirm; Clear processed / Clear all only in overflow with existing dialogs; selection in `remember` so leaving Events clears it) in `android/app/src/main/kotlin/com/gomoney/capture/ui/EventsScreen.kt`
- [X] T022 [US2] Keep Scan notifications as a labeled primary control (including empty list); restyle scan + missing-event banners as first `LazyColumn` items; empty state = headline + one sentence + Scan; blocked/unavailable banner uses `NotificationAccess.kt`; do not replace bottom bar, in `android/app/src/main/kotlin/com/gomoney/capture/ui/EventsScreen.kt` per `contracts/events-inbox.md`
- [X] T023 [US2] Confirm `NotificationOpenRouting` outcomes stay (detail vs list + dismissible miss) in `android/app/src/main/kotlin/com/gomoney/capture/ui/MainActivity.kt` and existing `android/app/src/test/kotlin/com/gomoney/capture/ui/` routing tests — restyle banner chrome only (FR-019)

**Checkpoint**: User Stories 1 and 2 both work independently on device.

---

## Phase 5: User Story 3 - Finish an event on a focused detail screen (Priority: P1)

**Goal**: Event detail is a first-class transaction story: identity → assignment (one confirm) → note (save/cancel) → retry; parser internals only in debug

**Independent Test**: On the USB phone, open a queued expense, change category and note, save, go back, list reflects both; open a sent event and confirm currency is not editable; first-time tester never sees parser name/confidence on default detail.

### Implementation for User Story 3

- [X] T024 [US3] Redesign scan order in `android/app/src/main/kotlin/com/gomoney/capture/ui/EventDetailScreen.kt`: Back; identity (formatted amount, Income/Expense + color/sign, source, human time, `StatusChip`); send assignment (account, category, gated currency) with **one** labeled confirm calling existing `onSaveAssignment`; note always visible with Save note / Cancel (009); Retry primary when failed; pending-sync and stale-category warnings stay; `currencyEditable` / `EventActions` semantics unchanged (`contracts/event-detail.md`)
- [X] T025 [US3] Hide `parserName`, `confidence`, fingerprint, and raw `Delivery state:` on default detail in `android/app/src/main/kotlin/com/gomoney/capture/ui/EventDetailScreen.kt`; sanitized `errorDetail` only near Retry
- [X] T026 [US3] Pass `config.debugModeEnabled` and raw event from `android/app/src/main/kotlin/com/gomoney/capture/ui/MainActivity.kt` so a secondary Debug control opens currently unused `android/app/src/main/kotlin/com/gomoney/capture/ui/DebugEventScreen.kt` (not in the identity header)

**Checkpoint**: User Stories 1–3 independently functional. MVP+daily loop complete.

---

## Phase 6: User Story 4 - Set up sources and permissions without feeling lost (Priority: P2)

**Goal**: Sources empty state, clearer rows, titled editor with formatted test amount; same notification-access explanation as Home/Events (no wizard)

**Independent Test**: On the USB phone, from an empty source list add a source through a titled editor, bind an account, run a test (formatted amount), save, and see enabled state + bound account; dismiss one parse error. Permission missing still opens system settings from Home/Events (already wired) using the same copy.

### Implementation for User Story 4

- [X] T027 [P] [US4] Restyle Sources empty (why sources matter + primary Add source, bottom bar visible) and populated rows (name, channel, identifier, bound account or “No account bound”, enabled, stale-binding warning, Edit/Remove with existing confirm) plus distinct unparsed section via existing `ParseErrorReviewList` in `android/app/src/main/kotlin/com/gomoney/capture/ui/SourcesScreen.kt`
- [X] T028 [US4] Title Add source / Edit source; keep field set and validators; put validation next to fields; show test success with `AmountFormat` + direction (not raw minor units) in `android/app/src/main/kotlin/com/gomoney/capture/ui/SourceEditorScreen.kt`
- [X] T029 [US4] Reuse the same notification-access explanation string and `NotificationAccess.kt` on Home (already T017), Events scan-blocked (T022), and prepare Settings call site in `android/app/src/main/kotlin/com/gomoney/capture/ui/SettingsScreen.kt` (full Settings grouping is US5)

**Checkpoint**: Sources setup is independently testable; permission copy is shared.

---

## Phase 7: User Story 5 - Configure the companion without a settings dump (Priority: P2)

**Goal**: Settings is five labeled groups; endpoint actions stay on cards; missing permission has a direct system-settings control; behaviors unchanged

**Independent Test**: On the USB phone, change default category, add an endpoint, toggle notification capture; each group is visually distinct and still behaves as today.

### Implementation for User Story 5

- [X] T030 [US5] Regroup `android/app/src/main/kotlin/com/gomoney/capture/ui/SettingsScreen.kt` into labeled sections in scroll order: Connection destinations, Capture, Defaults, Permissions, Diagnostics (`contracts/sources-and-settings.md` §3)
- [X] T031 [US5] Keep reorder/test/edit/delete/secret handling on each endpoint card (not competing with capture toggles) in `android/app/src/main/kotlin/com/gomoney/capture/ui/SettingsScreen.kt`; do not restore a bank/package allow-list
- [X] T032 [US5] Add Permissions group status plus missing-notification-access control calling `NotificationAccess.kt`; keep defaults stamping (008) and quieter Diagnostics debug switch in `android/app/src/main/kotlin/com/gomoney/capture/ui/SettingsScreen.kt`

**Checkpoint**: All destinations exist with new chrome; Settings is independently usable.

---

## Phase 8: User Story 6 - The whole app feels like one product (Priority: P2)

**Goal**: Shared type/spacing/chips/cards, system light/dark, RTL-capable 360 dp layouts, income vs expense by sign/label **and** color, nested back without losing destination model

**Independent Test**: Walk every destination on the USB phone in light and dark (`adb shell cmd uimode night no|yes`). Confirm four-item bottom bar, nested Back, readable amounts, and destructive confirms still appear.

### Implementation for User Story 6

- [X] T033 [P] [US6] Replace physical `Left`/`Right` padding with `start`/`end`, 16 dp screen padding, ≥48 dp primary tap targets, and no horizontal scroll of primary controls at ~360 dp across `android/app/src/main/kotlin/com/gomoney/capture/ui/DashboardScreen.kt`, `EventsScreen.kt`, `EventDetailScreen.kt`, `SourcesScreen.kt`, `SourceEditorScreen.kt`, and `SettingsScreen.kt`
- [X] T034 [US6] Apply income/expense sign-or-label **plus** theme tokens (never color alone) on Home recent rows, Events rows, and detail identity in those same screens using `CaptureTheme.kt` / `AmountFormat.kt`
- [X] T035 [US6] Keep existing destructive `AlertDialog`s (clear all, delete selected, remove source) in `android/app/src/main/kotlin/com/gomoney/capture/ui/EventsScreen.kt` and `SourcesScreen.kt`; nested Back still clears `selectedTxId` / `editingSource` in `android/app/src/main/kotlin/com/gomoney/capture/ui/MainActivity.kt`

**Checkpoint**: Visual system contract satisfied across destinations.

---

## Phase 9: Polish & Cross-Cutting Concerns

**Purpose**: Mapper suite, physical-device walk, behavior freeze

- [X] T036 Run `make android-gradle-test` and keep existing `com.gomoney.capture` UI tests green (routing, currency gating, assignment, sources stale) plus T004/T005/T013/T018
- [X] T037 Confirm no Room migration, no parser/`CapturePipeline`/`SyncEngine`/`EventActions` signature changes (diff should be Compose UI + display helpers + strings/theme only)
- [X] T038 USB verification per `specs/010-android-ui-refresh/quickstart.md`: `adb devices` must list the cable-connected phone; `cd android && ./gradlew installDebug`; `adb shell am start -n com.gomoney.capture/.ui.MainActivity`; walk Home/Events/selection/detail/Sources/Settings; light and dark; restore `uimode` (and `wm size` if temporarily changed). **Do not pass UI on an emulator if the phone is missing — that is blocked**
- [X] T039 Regression walk on the same phone: new captures still appear, retry still queues, notes still save, defaults still stamp only new captures, scan still respects notification access (SC-008); chips may show Waiting without a live bridge

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — can start immediately
- **Foundational (Phase 2)**: Depends on Setup — **BLOCKS all user stories** (nav, theme, chips, amounts, shared permission intent)
- **User Stories (Phase 3+)**: All depend on Foundational completion
  - Sequential default: US1 → US2 → US3 (all P1) → US4 → US5 → US6
  - After Foundational, US4 Sources layout can proceed in parallel with US1 Home **if** it does not edit `MainActivity.kt` at the same time as T017/T026
- **Polish (Phase 9)**: Depends on US1–US6 for the full USB walk; T036 can run after mapper stories (US1–US2) for an early JVM gate

### User Story Dependencies

- **User Story 1 (P1)**: After Foundational — Home only; uses `NavigationBar`, `StatusChip`, `AmountFormat`, `HomeNextStep`
- **User Story 2 (P1)**: After Foundational — Events list; integrates with US1 only for Home→detail taps (T017). Independently testable with Events tab
- **User Story 3 (P1)**: After Foundational — detail redesign; list tap from US2 is the usual entry, but detail can be opened via existing `selectedTxId` routing
- **User Story 4 (P2)**: After Foundational — Sources/editor; permission helper from T010
- **User Story 5 (P2)**: After Foundational — Settings groups; should land after T010/T029 for shared permission CTA
- **User Story 6 (P2)**: After US1–US5 screens exist so the consistency pass has surfaces to audit; theme/nav already from Phase 2

### Within Each User Story

- Mapper tests MUST be written and FAIL before mapper implementation (T004→T006, T005→T007, T013→T014, T018→T019)
- ViewModel/display helpers before screen restyle
- Screen restyle before `MainActivity` wiring for that destination
- Do not change `EventActions` method signatures

### Parallel Opportunities

- T002 and T003 after T001
- T004 and T005 together; T008, T009, T010 together after T001
- T027 (Sources list) can overlap US1 if `MainActivity.kt` is left to T017/T012
- T033 can start once each screen file has its story restyle

---

## Parallel Example: Foundational mappers

```bash
# After T001, launch mapper tests together:
Task: "Add failing AmountFormatTest in android/app/src/test/kotlin/com/gomoney/capture/ui/AmountFormatTest.kt"
Task: "Add failing StatusPresentationTest in android/app/src/test/kotlin/com/gomoney/capture/ui/StatusPresentationTest.kt"

# Then implementations (different files):
Task: "Implement AmountFormat.kt"
Task: "Implement StatusPresentation.kt"
Task: "Implement CaptureTheme.kt"
Task: "Implement StatusChip.kt"
```

## Parallel Example: User Story 1

```bash
Task: "Add failing HomeNextStepTest.kt"
# then
Task: "Implement HomeNextStep.kt and DashboardViewModel.kt (T014–T015 sequential with T016)"
```

## Parallel Example: User Story 4 vs User Story 1

```bash
Task: "Restyle SourcesScreen.kt (T027)"  # does not need Home
Task: "HomeNextStep + DashboardScreen (T013–T016)"  # different files
# serialize MainActivity.kt edits (T012, T017, T026)
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup
2. Complete Phase 2: Foundational (bottom bar + theme + chips — CRITICAL)
3. Complete Phase 3: User Story 1 (Home health)
4. **STOP and VALIDATE** on the USB-C phone (`quickstart.md` Home rows)
5. Demo: “is capture working?” in 15 seconds (SC-001)

### Incremental Delivery

1. Setup + Foundational → four-item bar and DayNight theme
2. US1 Home → health MVP
3. US2 Events inbox → daily list
4. US3 Event detail → complete/send
5. US4 Sources → setup without wizard
6. US5 Settings groups
7. US6 consistency + light/dark USB walk
8. Polish: `make android-gradle-test` + SC-008 on device

### Parallel Team Strategy

1. Together: Phase 1–2
2. Then: A = US1 Home, B = US2 Events (avoid same-time `EventsScreen.kt`/`MainActivity.kt` edits), C = US4 Sources
3. US3 detail after Events row tap exists; US5 after T010; US6 last

---

## Notes

- [P] tasks = different files, no dependencies on incomplete work
- [Story] label maps task to spec.md US1–US6
- UI acceptance: physical phone over USB/`adb` only (user constraint). Empty `adb devices` → blocked, not emulator substitution
- English copy this pass; layouts `start`/`end` for later RTL
- Analyze was requested by plan across nav/chip/visual contracts; this tasks stage does **not** run `/speckit-analyze`
- Avoid: Navigation-Compose rewrite, Room migrations, spending dashboards, in-app theme picker, wizard Activity

## Phase 10: Convergence

- [X] T040 Restrict queue attention in HomeNextStep and DashboardViewModel to failedCount > 0 so waiting items stay a count and connection status remains visible, and update HomeNextStepTest per US1 (contradicts)
- [X] T041 Increase failed-row Retry tap target in EventsScreen.kt to at least 48.dp per FR-022 (contradicts)
- [X] T042 Align missing delivery fallback state in EventsScreen.kt with DashboardViewModel.kt to "sent" so identical events display consistent status chips per FR-016 (contradicts)

## Phase 11: Convergence

- [X] T043 Keep connection status visible alongside the recovery CTA on DashboardScreen.kt across all HomeNextStep states per FR-003 (contradicts)
- [X] T044 Pass fallback "sent" state to StatusChip in EventDetailScreen.kt when delivery record is null per FR-016 (contradicts)

## Phase 12: Convergence

- [X] T045 Add a visible Back control and BackHandler on SourceEditorScreen calling onCancel to return to Sources per FR-002 (missing)

## Phase 13: Convergence

- [X] T046 Add BackHandler(onBack = onBack) in EventDetailScreen to return to Events per FR-002 (missing)
- [X] T047 Add BackHandler in debug overlay in MainActivity to return to event detail per FR-002 (missing)

