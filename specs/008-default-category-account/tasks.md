---

description: "Task list for 008-default-category-account (note editing relocation + default category/account)"
---

# Tasks: Move Add-Note into Category Editing Screen & Default Category/Account for Transactions

**Input**: Design documents from `/specs/008-default-category-account/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/ (all present), quickstart.md

**Tests**: **Requested** — research.md R9 specifies a JVM unit-test strategy (`DefaultStampingTest.kt`, `DefaultPreferencesTest.kt`) and quickstart.md gates on `make android-gradle-test`. Test tasks are therefore included (written FIRST, expected to FAIL before implementation). Manual UI validation is delegated to quickstart.md in the Polish phase.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- _Iteration-1 hygiene fix (analyze F5): T017's `[P]` marker removed — it shares SettingsScreen.kt with T018, which depends on it; T017→T018 must run sequentially._
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

Single Android project: `android/app/src/main/kotlin/com/gomoney/capture/` (sources), `android/app/src/test/kotlin/com/gomoney/capture/` (JVM tests), per plan.md. Build/test gate: `make android-gradle-test` (runs `cd android && ./gradlew test`).

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Baseline the build and hoist the one shared constant both note-editing surfaces need (research R2).

- [X] T001 Establish a green baseline by running `make android-gradle-test` from repo root and confirming existing suites (CapturePipelineTest, AssignmentSyncTest, AssignmentPersistenceTest) pass before any changes
- [X] T002 [P] Promote `MAX_MEMO_LENGTH` to a single shared `internal const val` (keep it in `EventActions`'s companion made `internal`, per research R2) in android/app/src/main/kotlin/com/gomoney/capture/ui/EventActions.kt so EventDetailScreen can reference the same limit

**Checkpoint**: Build is known-green; single source of truth for the 200-char note limit exists.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Default-preference persistence and the capture-time stamping rule that User Story 2 and User Story 3 both require.

**⚠️ CRITICAL**: US2 and US3 cannot be completed until this phase is complete. **US1 is unaffected by this phase** (the note relocation depends only on Phase 1) — this is a deliberate, documented deviation from the default "blocks all stories" rule to preserve US1 independence.

Tests-first (research R9 — write these tests, run them, confirm they FAIL):

- [X] T003 [P] Write failing unit tests for DataStore default persistence (set/clear/observe round-trip, key-absent = null field, clearing one default leaves the other intact — FR-009) in android/app/src/test/kotlin/com/gomoney/capture/storage/DefaultPreferencesTest.kt
- [X] T005 [P] Write failing unit tests for the stamping rule (defaults set + valid catalog → stamped + `assignmentSyncState='pending'`; no defaults → null/'synced'; stale default ID → not stamped and stored value untouched (FR-010); pre-existing non-null assignment untouched; held/blank-currency row stamped but not expedited; Outcome distribution unaffected) in android/app/src/test/kotlin/com/gomoney/capture/capture/DefaultStampingTest.kt (extend the CapturePipelineTest pattern per R9)

Implementation:

- [X] T004 Implement `DefaultPreferences` data class, `default_category_id` / `default_account_id` `intPreferencesKey`s, `observeDefaults()`, `currentDefaults()`, `setDefaultCategoryId`, `setDefaultAccountId`, `clearDefaultCategory`, `clearDefaultAccount` (per-key `remove()`) in android/app/src/main/kotlin/com/gomoney/capture/storage/SettingsRepository.kt (depends T003)
- [X] T006 Implement the pure stamping helper (fill-if-null on `categoryId`/`destinationAccountId`, catalog validation via `serverCategoryDao().byId` / `serverAccountDao().byId`, set `assignmentSyncState='pending'` only when a field was stamped, never touch currency/fingerprint/memoSyncState/delivery state — contracts/defaults-stamping.md §2) in android/app/src/main/kotlin/com/gomoney/capture/capture/CapturePipeline.kt (depends T005)
- [X] T007 Integrate stamping into `CapturePipeline.processSource` after successful parse and before `normalizedTransactionDao().insert(tx)` — read `currentDefaults()`, validate IDs against cached catalogs, apply helper; zero `sync/` changes (research R5) in android/app/src/main/kotlin/com/gomoney/capture/capture/CapturePipeline.kt (depends T006)

**Checkpoint**: Foundation ready — `DefaultStampingTest` and `DefaultPreferencesTest` green; US2/US3 UI work can begin; US1 could already start after Phase 1.

---

## Phase 3: User Story 1 - Add/Edit a note from the category-editing screen (Priority: P1) 🎯 MVP

**Goal**: The add/edit-note control moves from Events tab rows into `EventDetailScreen`; rows keep displaying note text and pending-sync labels exactly as today; storage/sync untouched (FR-001–FR-004).

**Independent Test**: Open an event from the Events tab → add/edit note on the editing screen → save → note displayed and persisted; Events tab rows show no note button but still show "Note: …" and "waiting to sync" (quickstart section A; SC-001, SC-002).

### Tests for User Story 1

> **NOTE**: No new JVM test file is specified for US1 (research R9: UI stays logic-light; save/limit/pending semantics already live in tested `EventActions.saveMemo`). Verification is the existing suite plus quickstart section A in the Polish phase.

- [X] T011 [US1] Verify relocation with the existing suite by running `cd android && ./gradlew test` and confirming all UI-adjacent tests still pass after the EventsScreen/EventDetailScreen signature changes in android/app/src/main/kotlin/com/gomoney/capture/ui/EventsScreen.kt and android/app/src/main/kotlin/com/gomoney/capture/ui/EventDetailScreen.kt (depends T010)

### Implementation for User Story 1

- [X] T008 [P] [US1] Remove the memo edit block from list rows — delete the `editingMemo`/`memoDraft` text field, `Save note`/`Cancel` buttons, the `Add note`/`Edit note` button, and the `onSaveMemo` parameter; RETAIN the `Note: <text>` line and both pending-sync labels ("Saved on this phone · waiting to sync" / "Note removal saved on this phone · waiting to sync") — contracts/note-editing-ui.md §1 in android/app/src/main/kotlin/com/gomoney/capture/ui/EventsScreen.kt
- [X] T009 [P] [US1] Add a note-editing section (prefill keyed on `tx.id` from `tx.userMemo.orEmpty()`, live 200-char cap via the shared `MAX_MEMO_LENGTH` from T002, Save/Cancel, pending label when `memoSyncState == "pending"`, `tx?.let` guard) placed below the category/destination assignment controls and above the failed-delivery retry block, with new `onSaveMemo: (txId: String, memo: String) -> Unit` parameter — contracts/note-editing-ui.md §2 in android/app/src/main/kotlin/com/gomoney/capture/ui/EventDetailScreen.kt (depends T002)
- [X] T010 [US1] Rewire `onSaveMemo` in MainActivity: pass it to `EventDetailScreen` (calling the existing `eventActions.saveMemo`) and remove the now-deleted `onSaveMemo` parameter from the `EventsScreen` call — no new save logic in android/app/src/main/kotlin/com/gomoney/capture/ui/MainActivity.kt (depends T008, T009)

**Checkpoint**: At this point, User Story 1 is fully functional and testable independently — quickstart section A can be run end to end.

---

## Phase 4: User Story 2 - Set a default category for new transactions (Priority: P2)

**Goal**: A "Default category & default account" card in the Settings tab lets the user pick a default category from the cached server category list; new captures are pre-assigned it via the Foundational stamping (FR-005, US2 scenarios 1–4).

**Independent Test**: Set a default category in Settings → capture a new transaction → its category is pre-assigned (pending → synced via existing path); changing/clearing the default affects zero existing transactions (quickstart B1–B5, B7; SC-003, SC-004, SC-005).

### Tests for User Story 2

> **NOTE**: JVM coverage for this story's logic lives in the Foundational `DefaultPreferencesTest` (T003/T004) and `DefaultStampingTest` (T005–T007); the settings card itself is validated per R9 by quickstart manual steps (Polish phase).

- [X] T015 [US2] Re-run the defaults test suites against the wired UI state by running `cd android && ./gradlew test --tests '*DefaultStampingTest*' --tests '*DefaultPreferencesTest*'` and confirming green in android/app/src/test/kotlin/com/gomoney/capture/storage/DefaultPreferencesTest.kt and android/app/src/test/kotlin/com/gomoney/capture/capture/DefaultStampingTest.kt (depends T014)

### Implementation for User Story 2

- [X] T012 [P] [US2] Expose defaults state and category actions in `SettingsViewModel` — collect `settings.observeDefaults()` plus the setter/clearer calls (`setDefaultCategoryId`, `clearDefaultCategory`) in android/app/src/main/kotlin/com/gomoney/capture/ui/SettingsScreen.kt
- [X] T013 [P] [US2] Add the "Default category & default account" card between the capture toggles and the permission status — Default category dropdown populated from the passed-in category list, showing current label or "None" (FR-011), independent `Clear` TextButton (FR-009), "no longer available" warning line when the stored ID is absent from the catalog (FR-010), pickers disabled with an explanatory hint when the catalog is empty/`Status.UNAVAILABLE` while stored defaults keep applying — contracts/defaults-stamping.md §3 in android/app/src/main/kotlin/com/gomoney/capture/ui/SettingsScreen.kt (depends T004)
- [X] T014 [US2] Pass `catalogCategories`, `catalogAccounts`, and the catalog state into the Settings tab (tab 3) alongside the existing `observeCategories()`/`observeAccounts()` collections in android/app/src/main/kotlin/com/gomoney/capture/ui/MainActivity.kt (depends T012, T013)

**Checkpoint**: At this point, User Story 2 works independently — quickstart section B category steps pass; US3 can ship later or in parallel.

---

## Phase 5: User Story 3 - Set a default destination account for new transactions (Priority: P2)

**Goal**: The same settings card gains a Default destination account picker; new captures are pre-assigned it at capture time regardless of currency mismatch (FR-006, FR-007b, US3 scenarios 1–4).

**Independent Test**: Set a default account in Settings → capture a new transaction (including one with a different currency — no warning, still stamped) → destination pre-assigned; per-event override changes only that event; clearing the default reverts new captures (quickstart B2–B8; SC-003, SC-004).

### Tests for User Story 3

- [X] T016 [P] [US3] Add a regression test method asserting a default destination account is stamped even when its currency differs from the transaction's, with no currency comparison anywhere on the path (FR-007b) in android/app/src/test/kotlin/com/gomoney/capture/capture/DefaultStampingTest.kt

### Implementation for User Story 3

- [X] T017 [US3] Add the Default destination account dropdown row to the existing defaults card — current label or "None", independent `Clear` TextButton, "no longer available" warning for a stale stored ID, picker disabled on unavailable catalog — reusing the card introduced in T013 in android/app/src/main/kotlin/com/gomoney/capture/ui/SettingsScreen.kt (depends T013)
- [X] T018 [US3] Wire the account actions (`setDefaultAccountId`, `clearDefaultAccount`) through `SettingsViewModel` and the MainActivity tab-3 parameters so selection persists immediately (local-first, no save button) in android/app/src/main/kotlin/com/gomoney/capture/ui/SettingsScreen.kt and android/app/src/main/kotlin/com/gomoney/capture/ui/MainActivity.kt (depends T017, T004)
- [X] T019 [US3] Verify the account path end to end in the JVM suites by running `cd android && ./gradlew test --tests '*DefaultStampingTest*' --tests '*DefaultPreferencesTest*'` (includes T016 currency-mismatch case) in android/app/src/test/kotlin/com/gomoney/capture/capture/DefaultStampingTest.kt (depends T016, T018)

**Checkpoint**: All user stories independently functional — quickstart section B passes end to end.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Regression safety and full manual validation across both changes.

- [X] T020 [P] Run the full regression sweep `make android-gradle-test` from repo root and confirm existing suites (CapturePipelineTest, AssignmentSyncTest, AssignmentPersistenceTest, plus all new defaults tests) stay green in android/app/src/test/kotlin/com/gomoney/capture/
- [ ] T021 Execute quickstart.md sections A (note relocation), B (defaults, including stale-default and offline-catalog edge cases), and C (regression sweep) on an API 26+ device/emulator per specs/008-default-category-account/quickstart.md
  - _Implement note (iteration 1): JVM portion of section C done via `make android-gradle-test` (green). Sections A/B require an API 26+ device/emulator — none attached in this environment; left open for manual on-device validation._
- [X] T022 [P] Audit FR-001–FR-011 and SC-001–SC-006 traceability against the completed tasks and update specs/008-default-category-account/tasks.md checkboxes/notes if any gap is found
  - _Audit result: FR-001/002 → T008–T010; FR-003/004 → existing saveMemo path + detail-screen prefill (quickstart A covers SC-001/SC-002 on device); FR-005/006 → T004/T012–T014/T018; FR-007 → T006/T007; FR-007a → stamped `pending` + existing expedited follow-up for queued rows, held rows stay `pending` without expedited sync per research R5/contracts (advisory F1 resolved in favor of research/contracts); FR-007b → T016 test (no currency check on path); FR-008 → stamping runs only in `processSource` (existing rows never rewritten); FR-009 → per-key `remove()` + independent Clear buttons (T003/T004 tests); FR-010 → catalog `byId` validation at stamp time + settings warning (tests + UI); FR-011 → settings card shows current labels. SC-003/SC-004 covered by JVM tests; SC-001/SC-002/SC-005 partly on-device (T021 open); SC-006 covered by stale-default test + warning line. Gap found: none requiring new tasks._

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion — **BLOCKS US2 and US3 only** (US1 needs just Phase 1; documented deviation to preserve story independence)
- **User Story 1 (Phase 3)**: Depends on Phase 1 only — no dependencies on other stories
- **User Story 2 (Phase 4)**: Depends on Phase 2 (persistence T004 + stamping T006/T007)
- **User Story 3 (Phase 5)**: Depends on Phase 2, and on T013/T017 card structure for its picker row; the destination-stamping logic itself is Foundational, so US3 remains independently shippable without US2's category UI
- **Polish (Phase 6)**: Depends on all desired user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: Start immediately after Phase 1 — fully independent of US2/US3
- **User Story 2 (P2)**: Start after Foundational — no dependency on US1
- **User Story 3 (P2)**: Start after Foundational — shares only the settings card scaffold (T013) with US2; capture-side behavior is Foundational

### Within Each User Story

- Tests (where specified) are written FIRST and must FAIL before implementation (T003/T005 before T004/T006/T007)
- Models/persistence before pipeline integration
- Screen composable changes before MainActivity wiring (composables define the params MainActivity fills)
- Story complete before moving to next priority

### Parallel Opportunities

- T001 and T002 run in parallel (Setup)
- T003 and T005 run in parallel (different test files); after they fail, T004 and T006 run in parallel (different source files), then T007
- T008 and T009 run in parallel (different files); T010 after both
- T012 and T013 run in parallel (same file but disjoint regions; separate commits) — if avoiding same-file conflicts, run sequentially (T012 → T013)
- T016 and T017 run in parallel (different files)
- Once Phase 2 completes: US1, US2, and US3 can be worked on concurrently by different people

---

## Parallel Example: User Story 1

```bash
# Launch both screen changes together (different files, no shared state):
Task: "T008 Remove memo edit block from list rows in android/app/src/main/kotlin/com/gomoney/capture/ui/EventsScreen.kt"
Task: "T009 Add note-editing section to EventDetailScreen in android/app/src/main/kotlin/com/gomoney/capture/ui/EventDetailScreen.kt"

# Then the single wiring task (depends on both):
Task: "T010 Rewire onSaveMemo in android/app/src/main/kotlin/com/gomoney/capture/ui/MainActivity.kt"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (T001–T002)
2. Complete Phase 3: User Story 1 (T008–T011) — note relocation is deliverable without any Phase 2 work
3. **STOP and VALIDATE**: quickstart section A — 0 note buttons on rows; add/edit note from the editing screen round-trips
4. Deploy/demo if ready

### Incremental Delivery

1. Setup → Foundational (defaults persistence + stamping, tests green) → foundation ready
2. Add User Story 1 → validate → deploy/demo (MVP!)
3. Add User Story 2 → validate (quickstart B) → deploy/demo — default category live
4. Add User Story 3 → validate (quickstart B incl. currency-mismatch) → deploy/demo — both defaults live
5. Polish: full regression + quickstart A–C sweep

### Parallel Team Strategy

With multiple developers:

1. Team completes Setup together; one person runs Foundational (Phase 2) while another starts US1 right after Phase 1
2. Once Foundational is done:
   - Developer A: US2 (settings card category UI)
   - Developer B: US3 (account row + currency regression test)
3. Stories complete and integrate independently — no `sync/` file is ever touched by any story

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- Each user story is independently completable and testable; tests fail before implementation
- Commit after each task or logical group; stop at any checkpoint to validate a story independently
- Avoid: vague tasks, same-file conflicts, cross-story dependencies that break independence
- Hard constraints (from plan.md): no Room schema change/migration, no new permissions, no `sync/` changes, note limit stays 200, defaults never applied retroactively, currency mismatch never blocks or warns
