# Tasks: Notification Scan Button

**Input**: Design documents from `/specs/006-notification-scan-button/`

**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md, data-model.md, contracts/

**Tests**: plan.md explicitly requires Android JUnit coverage (identity claim/commit/release + stale takeover, outcome→count mapping with `added + skipped == examined`, single-flight scan guard, banner lifecycle, Room v4→v5 migration) via `make android-gradle-test`. No instrumentation source set exists (`android/app/src/` has only `main` and `test`), so no on-device test tasks are added; device steps live in quickstart.md. Test tasks follow the project precedent from `specs/005-account-currency-sources/tasks.md`: placed with the implementation they lock in, no strict TDD red-phase required.

**Organization**: Tasks grouped by user story for independent implementation and testing.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Confirm branch inputs and the existing test gate before any changes

- [X] T001 Verify branch `notification-events-button` and feature dir `specs/006-notification-scan-button/` contain spec.md, plan.md, data-model.md, research.md, quickstart.md, contracts/ per plan.md
- [X] T002 [P] Confirm Android test command `make android-gradle-test` runs green on the unmodified tree in `android/`
- [X] T003 [P] Confirm `android/app/src/main/AndroidManifest.xml` already declares `NotificationCaptureService` with `BIND_NOTIFICATION_LISTENER_SERVICE` (no manifest edit needed) and no bridge/Go files are in scope per plan.md Constraints and `specs/006-notification-scan-button/contracts/scan-coordination.md` §5

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Per-notification identity guard (Room v4 → v5) and the shared extraction path that both capture paths depend on

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [X] T004 Add `NotificationCaptureRecord` entity (PK `notification_key`, nullable `event_id`, `state` validated to `pending`|`recorded`, `updated_at`) in `android/app/src/main/kotlin/com/gomoney/capture/storage/Entities.kt` per `specs/006-notification-scan-button/data-model.md` §1
- [X] T005 Add `NotificationCaptureRecordDao` (insert-or-take-over pending row, read by key, commit, release/delete, clear-all) in `android/app/src/main/kotlin/com/gomoney/capture/storage/Daos.kt` per `specs/006-notification-scan-button/contracts/notification-identity.md` §2
- [X] T006 Bump Room schema v4 → v5 with `MIGRATION_4_5` (`CREATE TABLE notification_capture_records`, `exportSchema = false` unchanged) in `android/app/src/main/kotlin/com/gomoney/capture/storage/AppDatabase.kt` per data-model.md and research R8
- [X] T007 Create `NotificationIdentityRegistry` implementing claim/commit/release with the 60-second stale-`pending` take-over (no row → grant, `recorded` → reject, `pending` < 60 s → reject, `pending` ≥ 60 s → take over) in `android/app/src/main/kotlin/com/gomoney/capture/capture/NotificationIdentityRegistry.kt` per `specs/006-notification-scan-button/contracts/notification-identity.md` §2, research R2
- [X] T008 [P] Delete `notification_capture_records` rows in `clearAll()` while leaving `clearProcessed()` untouched in `android/app/src/main/kotlin/com/gomoney/capture/storage/MaintenanceRepository.kt` per `specs/006-notification-scan-button/contracts/notification-identity.md` §4, research R8
- [X] T009 [P] Lift `extractText(sbn)` (EXTRA_TEXT / EXTRA_BIG_TEXT, longest non-blank wins) and `safePostedAt(postTime)` into shared top-level functions callable from both capture paths in `android/app/src/main/kotlin/com/gomoney/capture/capture/NotificationCaptureService.kt` per research R3
- [X] T010 Wire the identity guard into the real-time `onNotificationPosted` path — claim → shared extract → `CaptureGate` → `CapturePipeline.process` → commit (persisted outcomes) / release (`IGNORED_*`, no text) — in `android/app/src/main/kotlin/com/gomoney/capture/capture/NotificationCaptureService.kt` per `specs/006-notification-scan-button/contracts/notification-identity.md` §2 (depends on T007, T009)
- [X] T011 [P] Add `NotificationIdentityRegistryTest` covering claim grant on missing row, rejection of `recorded`, rejection of fresh `pending` (<60 s), take-over of stale `pending` (≥60 s), terminal commit, release re-examinable, and the same key deduplicated across both entry points (FR-003) in `android/app/src/test/kotlin/com/gomoney/capture/capture/NotificationIdentityRegistryTest.kt` per plan.md Testing, research R10
- [X] T012 [P] Add Room v5 coverage — migration creates `notification_capture_records`, `clearAll()` wipes rows, `clearProcessed()` keeps them — in `android/app/src/test/kotlin/com/gomoney/capture/storage/AppDatabaseMigrationTest.kt` and `android/app/src/test/kotlin/com/gomoney/capture/storage/MaintenanceRepositoryTest.kt` per research R8, quickstart.md automated checks

**Checkpoint**: Foundation ready — identity guard enforced on the real-time path; user story implementation can now begin

---

## Phase 3: User Story 1 - Capture events from notifications already on screen (Priority: P1) 🎯 MVP

**Goal**: A visible scan control in the Events tab reads the active notifications through `NotificationCaptureService` and runs each one through the unchanged real-time capture path, producing new events with no duplicates (FR-001, FR-002, FR-003, FR-009, FR-010).

**Independent Test**: With notification access granted and ≥ 1 uncaptured qualifying notification in the shade, press the scan button → the event appears in the Events list exactly once; press again → no duplicate event (spec.md US1 acceptance 1–4; quickstart.md steps 2–3).

**Acceptance Scenarios**: US1-1 (scan of recognized uncaptured notifications creates events), US1-2 (repeat scan creates no duplicates), US1-4 (list reflects results without a restart/manual refresh). US1-3 (nothing qualifies → informed) lands with the banner in US2.

### Implementation for User Story 1

- [X] T013 [US1] Declare `ScanUiState` sealed states (`Idle`, `Running`, `Result(examined, added, skipped)`, `Blocked`, `Unavailable`) in `android/app/src/main/kotlin/com/gomoney/capture/capture/ScanCoordinator.kt` per `specs/006-notification-scan-button/data-model.md` §3
- [X] T014 [US1] Create `ScanCoordinator` object with `state: StateFlow<ScanUiState>` and `requestScan(permission)` — permission check fails → `Blocked` (no scan), state `Running` → ignore, else publish `Running` and call the registered service `handle.performScan()`; no handle → `runCatching { context.startService(ACTION_SCAN intent) }` falling back to `Unavailable` — in `android/app/src/main/kotlin/com/gomoney/capture/capture/ScanCoordinator.kt` per `specs/006-notification-scan-button/contracts/scan-coordination.md` §1, research R1/R5 (banner rendering of `Result`/`Blocked` arrives in US2)
- [X] T015 [US1] Implement `performScan()` behind `onStartCommand` handling action `com.gomoney.capture.SCAN_ACTIVE_NOTIFICATIONS` in `android/app/src/main/kotlin/com/gomoney/capture/capture/NotificationCaptureService.kt`: `AtomicBoolean` in-flight guard, loop on `Dispatchers.IO`, `getActiveNotifications()` called on the background thread, per notification claim → shared extract → `CaptureGate` → `CapturePipeline.process` → commit/release; publishes `Running` and returns to `Idle` on completion (counts land in US2); never modifies or deletes existing events — per `specs/006-notification-scan-button/contracts/scan-coordination.md` §1, research R7, FR-007–FR-010
- [X] T016 [US1] Register the service handle with `ScanCoordinator` in `onCreate`/`onListenerConnected` and clear it in `onDestroy` in `android/app/src/main/kotlin/com/gomoney/capture/capture/NotificationCaptureService.kt` per research R1
- [X] T017 [US1] Add the "Scan notifications" control to the existing top action `Row` of `android/app/src/main/kotlin/com/gomoney/capture/ui/EventsScreen.kt` beside **Clear processed** / **Clear all**, wired to `ScanCoordinator.requestScan(...)` per `specs/006-notification-scan-button/contracts/scan-coordination.md` §4, FR-001
- [X] T018 [US1] Restructure the `No captured transactions yet.` early-return branch so the action row and scan control still render when the event list is empty in `android/app/src/main/kotlin/com/gomoney/capture/ui/EventsScreen.kt` per contracts/scan-coordination.md §4 (P1 must be testable with zero events)
- [X] T019 [US1] Wire the scan entry in `android/app/src/main/kotlin/com/gomoney/capture/ui/MainActivity.kt` (supply `PlatformCapturePermission` from `capture/PlatformSupport.kt`) and confirm scan-created rows flow through the existing Room Flows into the list without app restart or manual refresh (FR-009)
- [X] T020 [P] [US1] Add `ScanCoordinatorTest` request-path cases — permission OK + handle present → `handle.performScan()`; handle absent → `startService` refusal caught, never crashes; permission missing → `Blocked` with no scan started — in `android/app/src/test/kotlin/com/gomoney/capture/capture/ScanCoordinatorTest.kt` per contracts/scan-coordination.md §1, plan.md Testing

**Checkpoint**: User Story 1 fully functional and testable independently — pressing the button captures active notifications into the Events list exactly once, including with an empty list (SC-001, SC-006)

---

## Phase 4: User Story 2 - Understand the result of a scan (Priority: P2)

**Goal**: Inline banner at the top of the Events list reports examined/added/skipped (or "nothing new"), shows progress while scanning, and gives actionable guidance when permission is missing or the listener is unavailable; only one scan runs at a time (FR-005, FR-006, FR-007, FR-008).

**Independent Test**: Run scans in each condition — new events found, nothing found, empty shade, permission missing, press during a running scan — and verify the banner wording and guidance match what actually happened (spec.md US2 acceptance 1–3; quickstart.md steps 1, 4, 5, 6).

**Acceptance Scenarios**: US2-1 (2 new → "2 added" reported), US2-2 (no permission → clear message + settings guidance, no partial state), US2-3 (press during a scan → no overlapping second scan).

### Implementation for User Story 2

- [X] T021 [US2] Add the pure outcome→count mapping (`QUEUED`/`HELD`/`QUEUED_AS_KNOWN_DUPLICATE` → added; `DUPLICATE_SHORT_CIRCUITED`, `RETAINED_PARSE_ERROR`, `IGNORED_NO_SOURCE`, `IGNORED_DISABLED`, already-recorded, no-extractable-text → skipped; invariant `added + skipped == examined`) as a top-level helper in `android/app/src/main/kotlin/com/gomoney/capture/capture/ScanCoordinator.kt` per `specs/006-notification-scan-button/contracts/notification-identity.md` §3, data-model.md counting rules, research R4
- [X] T022 [US2] Publish `Result(examined, added, skipped)` from `performScan()` on completion (replacing the US1 return-to-`Idle`) in `android/app/src/main/kotlin/com/gomoney/capture/capture/NotificationCaptureService.kt` per `specs/006-notification-scan-button/contracts/scan-coordination.md` §2, FR-005
- [X] T023 [US2] Render the inline banner as the first `LazyColumn` item directly above the action row in `android/app/src/main/kotlin/com/gomoney/capture/ui/EventsScreen.kt` — `Running`: "Scanning active notifications…" with the scan button disabled and showing a progress indicator; `Result`: `Examined X · Added Y · Skipped Z`, or `No new events found · Examined X · Skipped X` when `added == 0`, or `No active notifications.` when `examined == 0` — per contracts/scan-coordination.md §2, FR-005, FR-008
- [X] T024 [US2] Render `Blocked` and `Unavailable` guidance in the banner — explanation text plus a button launching `Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)` with `runCatching` fallback `Intent(Settings.ACTION_SETTINGS)`, and the "try again" hint for `Unavailable` — in `android/app/src/main/kotlin/com/gomoney/capture/ui/EventsScreen.kt` per FR-006, research R5/R9, contracts/scan-coordination.md §2
- [X] T025 [US2] Tie banner visibility to the Events tab: publish `ScanCoordinator.eventsTabVisible = (selectedTab == 1)` from `android/app/src/main/kotlin/com/gomoney/capture/ui/MainActivity.kt`; leaving the tab consumes any shown banner → `Idle`, and a scan completing while the tab is hidden is consumed as `Idle` per FR-005, research R6, contracts/scan-coordination.md §3
- [X] T026 [P] [US2] Add `ScanCountingTest` covering every row of the outcome→count table plus the `added + skipped == examined` invariant in `android/app/src/test/kotlin/com/gomoney/capture/capture/ScanCountingTest.kt` per plan.md Testing, quickstart.md automated checks
- [X] T027 [P] [US2] Extend `ScanCoordinatorTest` with single-flight (press while `Running` is a no-op, no second `performScan()`), press-without-permission (`Blocked`, no scan started), and banner lifecycle (new scan replaces previous `Result`; leaving the tab consumes the banner; completion while hidden → `Idle`) in `android/app/src/test/kotlin/com/gomoney/capture/capture/ScanCoordinatorTest.kt` per data-model.md §3, contracts/scan-coordination.md §3

**Checkpoint**: User Stories 1 AND 2 both work independently — scans capture events and every outcome (added, nothing new, blocked, running) is visible without reading logs (SC-004, SC-005)

---

## Phase 5: User Story 3 - Recover from interrupted or partial capture (Priority: P3)

**Goal**: One press catches up a backlog: multiple missed qualifying notifications each become exactly one event, mixed sets capture only qualifying ones, interrupted scans recover cleanly, and the clear buttons behave per research R8 (FR-003, FR-004; SC-001, SC-002, SC-006).

**Independent Test**: Several uncaptured qualifying notifications on the device → one press → all present exactly once; a second press adds 0; a killed scan's leftovers are captured by the next scan (spec.md US3 acceptance 1–2; quickstart.md steps 2–3, 7).

**Depends on**: Phase 3 (US1 scan loop) and Phase 4 (US2 counting publishes `Result`).

**Acceptance Scenarios**: US3-1 (3 missed → 3 events after one press), US3-2 (mixed qualifying/non-qualifying → only qualifying, each exactly once).

### Tests / Verification for User Story 3

> The mechanism is delivered by US1/US2; this phase locks in the backlog-recovery guarantees using plain JUnit against fakes (research R10).

- [X] T028 [P] [US3] Add backlog and mixed-set scenarios to `ScanCountingTest` — N unrecorded qualifying inputs → N captured exactly once (`Result(N, N, 0)`); mixed qualifying + unrecognized + already-recorded inputs → only qualifying captured, skips counted — in `android/app/src/test/kotlin/com/gomoney/capture/capture/ScanCountingTest.kt` per SC-001, US3 acceptance 1–2, FR-004
- [X] T029 [US3] Add a repeat-scan idempotence scenario — a second scan over identical inputs yields `added == 0` and leaves existing event rows untouched (FR-010) — in `android/app/src/test/kotlin/com/gomoney/capture/capture/ScanCountingTest.kt` per SC-002, SC-006 (extends T026's file)
- [X] T030 [P] [US3] Add an interruption-recovery scenario — stale `pending` rows left by a killed scan are taken over after 60 s and the notification is captured exactly once on the next scan — in `android/app/src/test/kotlin/com/gomoney/capture/capture/NotificationIdentityRegistryTest.kt` per research R2, spec edge case (interruption)
- [X] T031 [P] [US3] Add clear-button scan semantics — identity rows removed by `clearAll()` let a later scan re-capture still-shaded notifications; rows kept by `clearProcessed()` prevent resurrection of cleared terminal events — in `android/app/src/test/kotlin/com/gomoney/capture/storage/MaintenanceRepositoryTest.kt` per research R8, quickstart.md step 7
- [X] T032 [US3] Verify scan-captured events surface in the list without restart or manual invalidation in `android/app/src/main/kotlin/com/gomoney/capture/ui/EventsScreen.kt` (rows sorted `sortedByDescending { it.txAt }` with `txAt = postedAt`): recent captures appear at the top per quickstart step 2, older backlog entries sort chronologically by post time (identical to real-time events) — adjust ordering only if the list fails to refresh (FR-009)

**Checkpoint**: All user stories independently functional — backlog catch-up, idempotence, and clear semantics verified (SC-001, SC-002, SC-006)

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Constitution-style gates (privacy, reuse, no new permission) and final validation across all stories

- [X] T033 [P] Privacy/constraint sweep — confirm no raw notification text is logged by the new scan code in `android/app/src/main/kotlin/com/gomoney/capture/capture/` and that `android/app/src/main/AndroidManifest.xml` is unchanged (no new permission) per plan.md Constraints, Constitution Check
- [X] T034 [P] Reuse gate — confirm `CapturePipeline` remains the only admission gate and no scan-specific matching/allow-list rules were added, by reviewing `android/app/src/main/kotlin/com/gomoney/capture/capture/NotificationCaptureService.kt`, `ScanCoordinator.kt`, `NotificationIdentityRegistry.kt` per FR-002, plan.md Post-Phase 1 re-check
- [X] T035 Run `make android-gradle-test` and fix regressions in `android/`
- [ ] T036 Run `specs/006-notification-scan-button/quickstart.md` validation walkthrough (steps 1–8) covering SC-001–SC-006

  **T036 validation mapping (automated + device walkthrough)**:
  - SC-001 ≥ 3 uncaptured qualifying notifications → 3 events, zero duplicates — quickstart step 2 + `ScanCountingTest` backlog scenario (T028)
  - SC-002 immediate re-scan adds 0 new events — quickstart step 3 + idempotence scenario (T029)
  - SC-003 50 active notifications summarized in < 10 s, tab responsive — quickstart step 5 (device)
  - SC-004 100% of presses without notification-read permission show guidance with a settings button — quickstart step 1 + `ScanCoordinatorTest` Blocked cases (T027)
  - SC-005 outcome (added/skipped/blocked) determinable from the banner alone — quickstart steps 2, 4, 6 + `ScanCountingTest` (T026)
  - SC-006 every scan idempotent (N runs = 1 run) — quickstart step 3 + `NotificationIdentityRegistryTest` (T011, T030)
  - Quickstart step 7 (additive only + clear semantics) — `MaintenanceRepositoryTest` (T031); step 8 (toggle-off uses real-time rules) — reuses `CaptureGate` path, no scan-specific logic (T034)
  - Command: `make android-gradle-test` → BUILD SUCCESSFUL

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion — BLOCKS all user stories
- **User Stories (Phases 3–5)**: All depend on Foundational phase completion
  - US1 (Phase 3) must precede US2 (Phase 4) and US3 (Phase 5): the banner reports the scan's counts, and backlog tests exercise the US1 loop with US2's `Result`
  - US2 (Phase 4) must precede US3 (Phase 5): counting/`Result` published by T022 is asserted by T028/T029
  - Suggested sequential order: US1 → US2 → US3
- **Polish (Phase 6)**: Depends on all desired user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: Can start after Foundational (Phase 2) — no dependencies on other stories
- **User Story 2 (P2)**: Depends on US1 (scan loop publishes state; button/banner share `EventsScreen.kt` and `ScanCoordinator.kt`) — independently testable once US1 is done
- **User Story 3 (P3)**: Depends on US1 (loop) + US2 (counting) — independently testable once US1/US2 are done

### Within Each User Story

- Models/state before service before UI
- Core implementation before the tests that lock it in (project precedent from specs/005; no strict TDD red-phase required)
- Story complete before moving to the next priority

### Parallel Opportunities

- T002 + T003 (setup verification) can run in parallel
- T008 + T009 (foundational, different files) can run in parallel; T011 + T012 (tests) can run in parallel
- T020 (US1 test) is file-independent of T013–T019
- T026 + T027 (US2 tests) can run in parallel
- T028's file work is parallel with T030 + T031; T033 + T034 (polish reviews) can run in parallel
- All tasks marked [P] touch different files and can run in parallel

---

## Parallel Example: User Story 1

```bash
# Launch US1 tasks that touch different files together:
Task: "Declare ScanUiState in capture/ScanCoordinator.kt (T013)"
Task: "Add scan control to ui/EventsScreen.kt (T017)"
Task: "Add ScanCoordinatorTest request-path cases in test capture/ (T020)"

# Then the service-side chain (same file, sequential):
Task: "ACTION_SCAN + performScan loop in capture/NotificationCaptureService.kt (T015)"
Task: "Register service handle in capture/NotificationCaptureService.kt (T016)"
```

---

## Parallel Example: User Story 2

```bash
# Launch US2 test tasks together (different files, no dependencies):
Task: "ScanCountingTest outcome→count table in test capture/ (T026)"
Task: "Extend ScanCoordinatorTest with single-flight/lifecycle cases in test capture/ (T027)"
```

---

## Parallel Example: User Story 3

```bash
# Launch US3 scenario tests together (different files):
Task: "Backlog/idempotence scenarios in ScanCountingTest (T028, then T029 in same file)"
Task: "Interruption-recovery scenario in NotificationIdentityRegistryTest (T030)"
Task: "Clear-button semantics in MaintenanceRepositoryTest (T031)"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup
2. Complete Phase 2: Foundational (CRITICAL — blocks all stories)
3. Complete Phase 3: User Story 1 (scan button captures active notifications into the Events list exactly once)
4. **STOP and VALIDATE**: Press the button with uncaptured notifications → events appear once; re-press → no duplicates; works with an empty list
5. Deploy/demo if ready

### Incremental Delivery

1. Complete Setup + Foundational → Foundation ready
2. Add User Story 1 → Test independently → Deploy/Demo (MVP: manual recovery works!)
3. Add User Story 2 → Test independently → Deploy/Demo (users can trust and debug the scan)
4. Add User Story 3 → Test independently → Deploy/Demo (backlog catch-up guarantees locked in)
5. Each story adds value without breaking previous stories

### Parallel Team Strategy

With multiple developers:

1. Team completes Setup + Foundational together
2. Once Foundational is done:
   - Developer A: User Story 1 (coordinator + service loop + button)
   - Developer B: US2 test scaffolding (T026) can start against the counting contract once T021 lands
3. Then Developer A: User Story 2 (counts + banner + lifecycle), then User Story 3 (scenarios)
4. Stories complete and integrate independently

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- Each user story independently completable and testable per its Independent Test
- Scan is additive only: never modify or delete existing events (FR-010); `CapturePipeline` stays the only admission gate (FR-002)
- Raw notification text is never logged and never leaves the device (unchanged constraint)
- No new Android permission; `AndroidManifest.xml` untouched
- Commit after each task or logical group
- Stop at any checkpoint to validate a story independently
- Avoid: scan-specific matching rules, duplicate identity keys, persisting scan summaries, blocking the UI during a scan, showing stale banners across tab switches
