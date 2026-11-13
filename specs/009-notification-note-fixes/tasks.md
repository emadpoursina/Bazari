---
description: "Task list for quieter capture alerts, deep-link to event, visible note field"
---

# Tasks: Quieter Capture Alerts, Deep-Link to Event, Visible Note Field

**Input**: Design documents from `/specs/009-notification-note-fixes/`

**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md, data-model.md, contracts/

**Tests**: JVM/Robolectric tests are in scope (plan Technical Context + research R8). No new Espresso/instrumented suite. Device USB checks are polish / quickstart, not CI.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

- Android capture app: `android/app/src/main/kotlin/com/gomoney/capture/`
- JVM tests: `android/app/src/test/kotlin/com/gomoney/capture/`
- Go backend and `frontend/` are out of scope

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Stay inside the existing Android capture app; add only shared tap-intent constants

- [ ] T001 Confirm no new Gradle dependencies and no Room schema version bump in `android/app/build.gradle.kts` and `android/app/src/main/kotlin/com/gomoney/capture/storage/AppDatabase.kt`
- [ ] T002 [P] Add shared notification-open extras, data URIs (`gomoney-capture://event/{id}`, `gomoney-capture://leftover`), and activity flags in `android/app/src/main/kotlin/com/gomoney/capture/sync/NotificationOpen.kt`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Launch-mode, nightly DataStore keys, and leftover evaluation primitives that tap-through and the nightly reminder share

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [ ] T003 Set `android:launchMode="singleTop"` on `.ui.MainActivity` in `android/app/src/main/AndroidManifest.xml`
- [ ] T004 Add DataStore keys `nightly_last_check_at` (ISO-8601 instant) and `nightly_last_evaluated_local_date` (`yyyy-MM-dd`) with get/set in `android/app/src/main/kotlin/com/gomoney/capture/storage/SettingsRepository.kt`
- [ ] T005 [P] Implement leftover snapshot + once-per-local-date gate (unsynced any age, in-window blank notes via `raw_events.capturedAt`, leftover id union, tap routing 0/1/N) in `android/app/src/main/kotlin/com/gomoney/capture/sync/NightlyLeftover.kt`
- [ ] T006 [P] JVM round-trip test for absent/set nightly DataStore keys in `android/app/src/test/kotlin/com/gomoney/capture/storage/NightlyPreferencesTest.kt`

**Checkpoint**: Foundation ready — user story implementation can now begin in parallel

---

## Phase 3: User Story 1 - One alert per newly captured transaction (Priority: P1) 🎯 MVP

**Goal**: Exactly one per-event capture prompt (“What was this transaction for?”); silence all daytime sync-status system notifications; skip/auto-dismiss the prompt when a note is saved

**Independent Test**: Capture one new event (online or offline). Shade shows a single capture-app notification for that event and no waiting-to-sync / caught-up / action-needed system notification. Saving a note dismisses that event’s prompt.

### Tests for User Story 1

> **NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [ ] T007 [P] [US1] JVM test that `SyncNotifier.maybeNotify` posts nothing and cancels ids 11/12/13 in `android/app/src/test/kotlin/com/gomoney/capture/sync/SyncNotifierTest.kt`
- [ ] T008 [P] [US1] JVM/Robolectric test that `MemoPromptNotifier.show` skips a non-blank `userMemo` and that `cancel(txId)` removes the shade item in `android/app/src/test/kotlin/com/gomoney/capture/sync/MemoPromptNotifierTest.kt`

### Implementation for User Story 1

- [ ] T009 [US1] Make `maybeNotify` a no-op that cancels leftover sync shade ids 11/12/13 in `android/app/src/main/kotlin/com/gomoney/capture/sync/SyncNotifier.kt` (leave Dashboard `SyncBanner` unchanged)
- [ ] T010 [US1] Skip posting when `tx.userMemo` is already non-blank in `android/app/src/main/kotlin/com/gomoney/capture/sync/MemoPromptNotifier.kt` (`show` still no-ops without `POST_NOTIFICATIONS`; no crash)
- [ ] T011 [US1] Call `MemoPromptNotifier.cancel` after a successful local write in `android/app/src/main/kotlin/com/gomoney/capture/ui/EventActions.kt` `saveMemo` (inline `MemoReplyReceiver` cancel stays)
- [ ] T012 [US1] Update `android/app/src/test/kotlin/com/gomoney/capture/sync/TransactionSyncWorkerTest.kt` so drain tests do not expect daytime sync-status shade items

**Checkpoint**: At this point, User Story 1 should be fully functional and testable independently

---

## Phase 4: User Story 2 - Open the event’s editing screen from the capture alert (Priority: P1)

**Goal**: Tapping the per-event capture notification body opens `EventDetailScreen` for that event (cold start and already-open app); missing id → Events list + brief explanation, no crash

**Independent Test**: With one capture notification visible, tap its body. App lands on that event’s editing screen (amount, category, note) rather than a generic home/list with no selection. Stale id opens Events list with an explanation.

### Tests for User Story 2

- [ ] T013 [P] [US2] JVM test for intent extras → detail vs missing-event routing helper in `android/app/src/test/kotlin/com/gomoney/capture/ui/NotificationOpenRoutingTest.kt`

### Implementation for User Story 2

- [ ] T014 [US2] Attach `PendingIntent.getActivity` `contentIntent` (unique URI, `EXTRA_OPEN_EVENT_ID`, `EXTRA_OPEN_TARGET=detail`) on the per-event notification in `android/app/src/main/kotlin/com/gomoney/capture/sync/MemoPromptNotifier.kt` (RemoteInput action unchanged)
- [ ] T015 [US2] Consume `onCreate` / `onNewIntent` extras in `android/app/src/main/kotlin/com/gomoney/capture/ui/MainActivity.kt`: Events tab + `selectedTxId` when `normalizedTransactionDao().byId` exists; else list + short missing-event explanation
- [ ] T016 [US2] Lift `selectedTxId` and one-shot missing-event message so first composition and later intents both switch screens in `android/app/src/main/kotlin/com/gomoney/capture/ui/MainActivity.kt`

**Checkpoint**: At this point, User Stories 1 AND 2 should both work independently

---

## Phase 5: User Story 3 - Note field on the Set category screen (Priority: P1)

**Goal**: `EventDetailScreen` always shows a usable note `OutlinedTextField` for income and expense; no “Add note” / “Edit note” reveal step; existing save/cap/sync semantics stay

**Independent Test**: From Events, tap Set category on an income row and an expense row. Both screens show a note field immediately; save persists on reopen.

### Implementation for User Story 3

- [ ] T017 [US3] Always render the note `OutlinedTextField` (prefill `tx.userMemo.orEmpty()`, live 200-char cap) and remove `editingMemo` / Add-or-Edit-note reveal in `android/app/src/main/kotlin/com/gomoney/capture/ui/EventDetailScreen.kt`
- [ ] T018 [US3] Keep Save → `onSaveMemo` and Cancel = revert draft with the field still visible; do not branch on income vs expense in `android/app/src/main/kotlin/com/gomoney/capture/ui/EventDetailScreen.kt`
- [ ] T019 [US3] Confirm Events-tab rows stay display-only (`Note: …` + waiting-to-sync) in `android/app/src/main/kotlin/com/gomoney/capture/ui/MainActivity.kt` (no add/edit note control on the list)

**Checkpoint**: At this point, User Stories 1, 2, and 3 should all work independently

---

## Phase 6: User Story 4 - One end-of-night reminder for leftover work (Priority: P2)

**Goal**: At 21:00 device-local, once per local calendar date, post one leftover reminder with both counts (unsynced any age; blank notes since previous check) or post none; tap re-queries leftovers (1 → detail, else Events list)

**Independent Test**: Leave ≥1 unsynced event or ≥1 in-window blank note until 21:00 local → one reminder naming both counts. Clear both conditions first → no reminder. Same date does not fire twice.

### Tests for User Story 4

> **NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [ ] T020 [P] [US4] JVM tests for first-install window, older blank notes excluded, unsynced any age, both counts, skip same local date, missed-date `targetDate = yesterday`, tap routing 0/1/N in `android/app/src/test/kotlin/com/gomoney/capture/sync/NightlyLeftoverTest.kt`

### Implementation for User Story 4

- [ ] T021 [US4] Post one nightly reminder (channel `nightly_leftover`, id 21, both-count text, no bank text, leftover `contentIntent`) in `android/app/src/main/kotlin/com/gomoney/capture/sync/NightlyLeftoverNotifier.kt`
- [ ] T022 [US4] Add one-time WorkManager worker: delay to next 21:00 local, evaluate/stamp DataStore even when leftover is false, re-enqueue next day, in `android/app/src/main/kotlin/com/gomoney/capture/sync/NightlyLeftoverWorker.kt`
- [ ] T023 [US4] Enqueue unique leftover work from `onCreate` in `android/app/src/main/kotlin/com/gomoney/capture/CaptureApplication.kt` (no `SCHEDULE_EXACT_ALARM`)
- [ ] T024 [US4] On `EXTRA_OPEN_TARGET=leftover`, re-query leftover ids at tap time (1 → detail, 0 or 2+ → Events list) in `android/app/src/main/kotlin/com/gomoney/capture/ui/MainActivity.kt`

**Checkpoint**: All user stories should now be independently functional

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: In-app labels stay, JVM suite green, USB quickstart for shade/tap/note/nightly

- [ ] T025 [P] Confirm Dashboard in-app sync labels still render from `android/app/src/main/kotlin/com/gomoney/capture/sync/SyncBanner.kt` and are not removed by the `SyncNotifier` no-op
- [ ] T026 Run `cd android && ./gradlew test` (or `make android-gradle-test` from repo root if that target exists)
- [ ] T027 Walk `specs/009-notification-note-fixes/quickstart.md` sections B–D on a USB-connected phone (one capture alert, tap-through, always-visible note, nightly leftover)
- [ ] T028 Confirm Go Money server, `frontend/`, parsers, and bridge protocol were not modified for this feature

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion — BLOCKS all user stories
- **User Stories (Phase 3+)**: All depend on Foundational phase completion
  - User stories can then proceed in parallel (if staffed)
  - Or sequentially in priority order (P1 US1 → P1 US2 → P1 US3 → P2 US4)
- **Polish (Final Phase)**: Depends on all desired user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: After Foundational — no dependency on US2/US3/US4. Cadence + skip/cancel only.
- **User Story 2 (P1)**: After Foundational — uses `NotificationOpen.kt` and `MemoPromptNotifier` from US1 for `contentIntent`. Independently testable once a per-event notification exists.
- **User Story 3 (P1)**: After Foundational — only `EventDetailScreen` / list display. No dependency on notifiers. Tap-through from US2 lands on this screen.
- **User Story 4 (P2)**: After Foundational — uses `NightlyLeftover.kt` + DataStore keys; tap handling shares `MainActivity` extras from US2. Independently testable with leftover fixtures.

### Within Each User Story

- Tests (where included) MUST be written and FAIL before implementation
- Notifier/evaluator before Activity wiring
- Core implementation before worker schedule
- Story complete before moving to next priority (unless staffed in parallel)

### Parallel Opportunities

- T001 and T002 can run in parallel
- T005 and T006 can run in parallel after T004
- T007 and T008 can run in parallel
- Once Foundational completes, US1 cadence, US3 note field, and US4 leftover tests can start in parallel (US2 shares `MemoPromptNotifier.kt` / `MainActivity.kt` with US1/US4 — avoid same-file conflicts)
- T017 and T018 are sequential in the same Compose file

---

## Parallel Example: User Story 1

```bash
# Launch US1 tests together:
Task: "JVM test that SyncNotifier.maybeNotify posts nothing in android/app/src/test/kotlin/com/gomoney/capture/sync/SyncNotifierTest.kt"
Task: "JVM test MemoPromptNotifier skip-if-noted + cancel in android/app/src/test/kotlin/com/gomoney/capture/sync/MemoPromptNotifierTest.kt"

# After tests fail, implementation is sequential on overlapping notifiers:
Task: "No-op SyncNotifier.maybeNotify in android/app/src/main/kotlin/com/gomoney/capture/sync/SyncNotifier.kt"
Task: "Skip MemoPromptNotifier.show when note exists in android/app/src/main/kotlin/com/gomoney/capture/sync/MemoPromptNotifier.kt"
```

## Parallel Example: User Story 3 vs User Story 1

```bash
# Different files — can run beside US1 after Phase 2:
Task: "Always-visible note field in android/app/src/main/kotlin/com/gomoney/capture/ui/EventDetailScreen.kt"
Task: "SyncNotifier no-op in android/app/src/main/kotlin/com/gomoney/capture/sync/SyncNotifier.kt"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup
2. Complete Phase 2: Foundational (CRITICAL — blocks all stories)
3. Complete Phase 3: User Story 1 (quiet capture alerts)
4. **STOP and VALIDATE**: One capture → one prompt; no daytime sync shade
5. Usable product increment for the original “two notifs per capture” complaint

### Incremental Delivery

1. Complete Setup + Foundational → Foundation ready
2. Add User Story 1 → Test independently → quieter shade (MVP cadence)
3. Add User Story 2 → Test independently → tap opens the event
4. Add User Story 3 → Test independently → note field visible (completes the P1 job)
5. Add User Story 4 → Test independently → 21:00 leftover reminder
6. Each story adds value without breaking previous stories

### Parallel Team Strategy

With multiple developers:

1. Team completes Setup + Foundational together
2. Once Foundational is done:
   - Developer A: User Story 1 (`SyncNotifier`, `MemoPromptNotifier`, `EventActions`)
   - Developer B: User Story 3 (`EventDetailScreen`)
   - Developer C: User Story 4 tests + `NightlyLeftoverWorker` (defer `MainActivity` leftover tap until US2 extras exist)
3. User Story 2 owns `MainActivity` intent consumption last, then US4 leftover tap lands in the same activity

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- Each user story should be independently completable and testable
- Verify tests fail before implementing
- Commit after each task or logical group
- Stop at any checkpoint to validate story independently
- Avoid: vague tasks, same-file conflicts (`MainActivity.kt` / `MemoPromptNotifier.kt` shared by US1/US2/US4), cross-story dependencies that break independence
- Do not add `SCHEDULE_EXACT_ALARM`; do not change Go Money server or web UI
