---

description: "Task list for Notification Source & Template Engine"
---

# Tasks: Notification Source & Template Engine

**Input**: Design documents from `/specs/004-notification-engine/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/bridge-api.md, contracts/source-template.md, quickstart.md

**Tests**: Tests are included because `plan.md` (Testing section) and `quickstart.md` explicitly require the listed automated suites (`make android-bridge-test`, `make android-gradle-test`). Test tasks are grouped per user story.

**Organization**: Tasks are grouped by user story so each story is independently implementable and testable.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: Which user story this task belongs to (US1, US2, US3, US4)
- Exact file paths are included in every task

## Path Conventions

This feature uses the existing two-artifact structure (see `plan.md` → Project Structure):

- **Android capture app**: `android/app/src/main/kotlin/com/gomoney/capture/` (code), `android/app/src/test/kotlin/com/gomoney/capture/` (unit tests), `android/app/src/test/resources/fixtures/` (fixtures)
- **Android bridge (Go)**: `pkg/androidbridge/`, launched by `cmd/android-bridge/`
- **Feature docs**: `specs/004-notification-engine/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Confirm the existing capture/bridge baseline is green and add the new user-facing strings.

- [X] T001 Verify the baseline builds and tests pass before any change: run `make android-bridge-test` and `make android-gradle-test`, and confirm the Room schema is at v2 (`android/app/src/main/kotlin/com/gomoney/capture/storage/AppDatabase.kt`)
- [X] T002 [P] Add user-facing strings for the Sources tab, source editor, parse-error review list, and assignment drop-downs in `android/app/src/main/res/values/strings.xml`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Schema, repositories, model types, and bridge scaffolding that every user story depends on.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [X] T003 [P] Add `Channel`, `Direction`, and `AssignmentSyncState` enums in `android/app/src/main/kotlin/com/gomoney/capture/model/Enums.kt`
- [X] T004 [P] Add `TransactionSource`, `ParseErrorMessage`, `ServerAccount`, and `ServerCategory` Room entities (with the `(identifier, channel)` unique index) in `android/app/src/main/kotlin/com/gomoney/capture/storage/Entities.kt`
- [X] T005 Extend the `NormalizedTransaction` entity with `sourceId`, `accountId`, `destinationAccountId`, `categoryId`, and `assignmentSyncState` in `android/app/src/main/kotlin/com/gomoney/capture/storage/Entities.kt` (depends on T004)
- [X] T006 Add DAOs for sources, parse errors, server accounts, and server categories, and extend the transaction DAO queries, in `android/app/src/main/kotlin/com/gomoney/capture/storage/Daos.kt` (depends on T004, T005)
- [X] T007 Bump `AppDatabase` to v3 and add `MIGRATION_2_3` for the new tables and extended `normalized_transactions` columns in `android/app/src/main/kotlin/com/gomoney/capture/storage/AppDatabase.kt` (depends on T006)
- [X] T008 [P] Implement `TransactionSourceRepository` (create/update/list as `Flow`, uniqueness pre-check hook) in `android/app/src/main/kotlin/com/gomoney/capture/source/TransactionSourceRepository.kt` (depends on T006)
- [X] T009 [P] Implement `CatalogRepository` (atomic cache replace, cached read, and an "unavailable/stale" flag) in `android/app/src/main/kotlin/com/gomoney/capture/sync/CatalogRepository.kt` (depends on T006)
- [X] T010 [P] Add bridge request/response types for account and category summaries, the assignment-update request, and the extended create payload fields (`accountId`, `destinationAccountId`, `categoryId`) in `pkg/androidbridge/types.go`
- [X] T011 Add `ListCategories` and the account/category label mapping to the Go Money client in `pkg/androidbridge/gomoney.go` (depends on T010)
- [X] T012 Add a Room v2→v3 migration test in `android/app/src/test/kotlin/com/gomoney/capture/storage/AppDatabaseMigrationTest.kt` (depends on T007)
- [X] T013 [P] Add `TransactionSourceRepository` unit tests (persistence, uniqueness, restart survival) in `android/app/src/test/kotlin/com/gomoney/capture/storage/SourceRepositoryTest.kt` (depends on T008)

**Checkpoint**: Schema v3, repositories, and bridge types are ready — user story implementation can begin.

---

## Phase 3: User Story 1 - Define a transaction source and capture its messages with a template (Priority: P1) 🎯 MVP

**Goal**: The user adds a source (name, identifier, channel, fill-in-the-blank template, income/expense keywords) on a Sources screen; matching messages are parsed into transactions through the existing pipeline, and unparseable messages are retained in a review list.

**Independent Test**: Add one source with a package identifier, notification channel, and template; trigger a matching message and verify a transaction with the correct direction and amount; trigger a message from an undefined identifier and verify no transaction is created.

### Tests for User Story 1 ⚠️

> Write these tests first and confirm they FAIL before implementation.

- [X] T014 [P] [US1] Add user-source message fixtures (purchase notification, deposit SMS, Persian-digit amount, missing amount, ambiguous direction, non-matching message) in `android/app/src/test/resources/fixtures/usersource/`
- [X] T015 [P] [US1] Add `TemplateMatcherTest` (compile/match, anchor tolerance, digit normalization, amount/direction failures, sanitized reasons) in `android/app/src/test/kotlin/com/gomoney/capture/source/TemplateMatcherTest.kt`
- [X] T016 [P] [US1] Add `SourceValidatorTest` (required placeholders, keyword lists, duplicate `(identifier, channel)` naming the conflict) in `android/app/src/test/kotlin/com/gomoney/capture/source/SourceValidatorTest.kt`
- [X] T017 [P] [US1] Add `UserSourcePipelineTest` (source resolution, capture, parse-error retention, non-source ignore, channel mismatch ignore) in `android/app/src/test/kotlin/com/gomoney/capture/capture/UserSourcePipelineTest.kt`

### Implementation for User Story 1

- [X] T018 [US1] Implement `TemplateMatcher` (compile to anchors/slots, anchor-and-scan match, shared normalization, sanitized failures) in `android/app/src/main/kotlin/com/gomoney/capture/source/TemplateMatcher.kt`
- [X] T019 [US1] Implement `SourceValidator` (non-blank name/identifier, exactly one `{direction}` and one `{amount}`, non-empty keyword lists, duplicate `(identifier, channel)`) in `android/app/src/main/kotlin/com/gomoney/capture/source/SourceValidator.kt` (depends on T018)
- [X] T020 [P] [US1] Implement `ParseErrorRepository` with insert-time retention pruning (30 days, then newest 200) and dismiss in `android/app/src/main/kotlin/com/gomoney/capture/source/ParseErrorRepository.kt`
- [X] T021 [US1] Implement `UserSourceParser` (match success → `NormalizedTransaction` with `parserName=UserSourceParser`; failure → retained `ParseErrorMessage`) in `android/app/src/main/kotlin/com/gomoney/capture/source/UserSourceParser.kt` (depends on T018, T020)
- [X] T022 [US1] Integrate user-source resolution (identifier + matching channel, enabled only) into `CapturePipeline` before the built-in allow-list fallback in `android/app/src/main/kotlin/com/gomoney/capture/capture/CapturePipeline.kt` (depends on T018, T021)
- [X] T023 [P] [US1] Add `SourcesScreen` (source list, enabled toggle, parse-error review entry point) in `android/app/src/main/kotlin/com/gomoney/capture/ui/SourcesScreen.kt`
- [X] T024 [P] [US1] Add `SourceEditorScreen` (name, identifier, channel selector, template builder from a pasted sample, keyword fields, optional Test button) in `android/app/src/main/kotlin/com/gomoney/capture/ui/SourceEditorScreen.kt` (depends on T018)
- [X] T025 [P] [US1] Add `ParseErrorReviewList` composable (newest-first, failure reason, dismiss) in `android/app/src/main/kotlin/com/gomoney/capture/ui/ParseErrorReviewList.kt`
- [X] T026 [US1] Add `SourcesViewModel` / `SourceEditorViewModel` backing the Sources screens in `android/app/src/main/kotlin/com/gomoney/capture/ui/SourcesViewModel.kt` (depends on T008, T020)
- [X] T027 [US1] Add the Sources tab and navigation wiring in `android/app/src/main/kotlin/com/gomoney/capture/ui/MainActivity.kt` (depends on T023, T024, T025, T026)

**Checkpoint**: User Story 1 is fully functional and independently testable (MVP).

---

## Phase 4: User Story 2 - Bind a source to an account on the server (Priority: P2)

**Goal**: Each source can be bound to a server account from a server-refreshed drop-down, and captured transactions are recorded against that account.

**Independent Test**: Bind a source to a server account, capture a matching message, and verify the transaction is recorded against that account; change the server account list, refresh, and verify the selector reflects it while offline keeps the last known list.

### Tests for User Story 2 ⚠️

- [X] T028 [P] [US2] Add bridge tests for `GET /v1/accounts` (minimal projection, 502/500 mapping) in `pkg/androidbridge/server_test.go`
- [X] T029 [P] [US2] Add `BridgeClient` tests for account refresh and cached-list fallback on failure in `android/app/src/test/kotlin/com/gomoney/capture/sync/BridgeClientTest.kt`
- [X] T030 [P] [US2] Add a bound-account pipeline test (bound source records the transaction against the bound account) in `android/app/src/test/kotlin/com/gomoney/capture/capture/BoundAccountPipelineTest.kt`

### Implementation for User Story 2

- [X] T031 [US2] Implement `handleListAccounts` (Go Money `ListAccounts` → minimal `{id,label,currency,type,isDefault}` projection) in `pkg/androidbridge/handlers.go`
- [X] T032 [US2] Register the `GET /v1/accounts` route in `pkg/androidbridge/server.go` (depends on T031)
- [X] T033 [US2] Accept `accountId` on `POST /v1/transactions` and give it precedence over the `(bank, accountHint)` mappings in `pkg/androidbridge/handlers.go` (depends on T031)
- [X] T034 [US2] Add `fetchAccounts` to the bridge client in `android/app/src/main/kotlin/com/gomoney/capture/sync/BridgeClient.kt`
- [X] T035 [US2] Refresh the account cache and expose stale/unavailable state in `android/app/src/main/kotlin/com/gomoney/capture/sync/CatalogRepository.kt` (depends on T034)
- [X] T036 [US2] Add the server-account drop-down with stale/unbound handling to the source editor in `android/app/src/main/kotlin/com/gomoney/capture/ui/SourceEditorScreen.kt` (depends on T024, T035)
- [X] T037 [US2] Propagate `boundAccountId` as `accountId` on captured transactions in `android/app/src/main/kotlin/com/gomoney/capture/source/UserSourceParser.kt` (depends on T021)

**Checkpoint**: User Stories 1 AND 2 both work independently.

---

## Phase 5: User Story 3 - Set the destination account and category of a captured transaction (Priority: P2)

**Goal**: A captured transaction's destination account and category are chosen from server-provided drop-downs, saved locally first, and pushed to the same Go Money transaction even after delivery or while offline.

**Independent Test**: Open a captured transaction, choose a destination account and category, verify the choices persist across restart, and verify they appear on the same server transaction without creating a second transaction.

### Tests for User Story 3 ⚠️

- [X] T038 [P] [US3] Add bridge tests for `GET /v1/categories` in `pkg/androidbridge/server_test.go`
- [X] T039 [P] [US3] Add bridge tests for `PUT /v1/transactions/assignment` (200 updated / 400 / 404 / 502) in `pkg/androidbridge/server_test.go`
- [X] T040 [P] [US3] Add `AssignmentSyncTest` (local-first write, `pending` state, offline retry, no duplicate transaction) in `android/app/src/test/kotlin/com/gomoney/capture/sync/AssignmentSyncTest.kt`
- [X] T041 [P] [US3] Add assignment persistence tests (survive restart, unset = default behavior) in `android/app/src/test/kotlin/com/gomoney/capture/storage/AssignmentPersistenceTest.kt`

### Implementation for User Story 3

- [X] T042 [US3] Implement `handleListCategories` (Go Money `ListCategories` → minimal `{id,label}` projection) in `pkg/androidbridge/handlers.go`
- [X] T043 [US3] Register the `GET /v1/categories` route in `pkg/androidbridge/server.go` (depends on T042)
- [X] T044 [US3] Implement `handleUpdateAssignment` (dedup-registry lookup, reuse current financials, `UpdateTransaction` with only destination/category changed) in `pkg/androidbridge/handlers.go` (depends on T042)
- [X] T045 [US3] Register the `PUT /v1/transactions/assignment` route in `pkg/androidbridge/server.go` (depends on T044)
- [X] T046 [US3] Accept `destinationAccountId`/`categoryId` on `POST /v1/transactions` and validate they resolve in `pkg/androidbridge/handlers.go` (depends on T044)
- [X] T047 [US3] Add `fetchCategories` and `updateAssignment` to the bridge client in `android/app/src/main/kotlin/com/gomoney/capture/sync/BridgeClient.kt` (depends on T034)
- [X] T048 [US3] Add assignment read/write queries for `destinationAccountId`, `categoryId`, and `assignmentSyncState` in `android/app/src/main/kotlin/com/gomoney/capture/storage/Daos.kt` (depends on T006)
- [X] T049 [US3] Implement pending-assignment sync mirroring `syncPendingMemos` (write local → `pending` → expedited sync → `synced`) in `android/app/src/main/kotlin/com/gomoney/capture/sync/TransactionSyncWorker.kt` (depends on T047, T048)
- [X] T050 [US3] Add destination-account and category drop-downs (catalog-backed, offline-tolerant) to `EventDetailScreen` in `android/app/src/main/kotlin/com/gomoney/capture/ui/EventDetailScreen.kt` (depends on T035, T049)

**Checkpoint**: All three capture/assignment stories work independently.

---

## Phase 6: User Story 4 - Remove or disable a source (Priority: P3)

**Goal**: A source can be temporarily disabled or permanently removed without affecting other sources or already-captured transactions.

**Independent Test**: Disable a source and verify its messages stop being captured while others keep working; re-enable and verify capture resumes; remove it and verify it disappears while captured transactions are retained.

### Tests for User Story 4 ⚠️

- [X] T051 [P] [US4] Add source lifecycle tests (disable stops capture, re-enable resumes, removal retains captured transactions) in `android/app/src/test/kotlin/com/gomoney/capture/storage/SourceLifecycleTest.kt`

### Implementation for User Story 4

- [X] T052 [US4] Implement enable/disable toggling in `android/app/src/main/kotlin/com/gomoney/capture/source/TransactionSourceRepository.kt` (depends on T008)
- [X] T053 [US4] Implement source removal (delete the row, keep captured transactions and null the parse-error reference) in `android/app/src/main/kotlin/com/gomoney/capture/source/TransactionSourceRepository.kt` (depends on T052)
- [X] T054 [US4] Ensure `CapturePipeline` skips disabled sources and ignores removed sources in `android/app/src/main/kotlin/com/gomoney/capture/capture/CapturePipeline.kt` (depends on T022)
- [X] T055 [US4] Add disable/re-enable and remove-with-confirmation actions to `SourcesScreen` in `android/app/src/main/kotlin/com/gomoney/capture/ui/SourcesScreen.kt` (depends on T023, T053)

**Checkpoint**: All four user stories are independently functional.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Documentation, privacy, retention maintenance, and end-to-end validation across stories.

- [X] T056 [P] Update the bridge and feature documentation in `specs/004-notification-engine/contracts/bridge-api.md` and `docs/bridge-runbook.md` to match the shipped endpoints
- [X] T057 [P] Audit redaction so parse-failure reasons and bridge payloads/logs never contain raw text in `android/app/src/main/kotlin/com/gomoney/capture/logging/Redactor.kt` and `pkg/androidbridge/logging.go`
- [X] T058 [P] Wire parse-error retention pruning into the periodic maintenance path in `android/app/src/main/kotlin/com/gomoney/capture/storage/MaintenanceRepository.kt`
- [X] T059 [P] Add unit tests for any uncovered template/validation edge cases in `android/app/src/test/kotlin/com/gomoney/capture/source/`
- [ ] T060 Run the `quickstart.md` scenarios 1–7 end-to-end and record results against the FR/SC mapping
- [X] T061 Performance check: confirm capture-to-queued < 2 s and selector refresh reflects the server in ≥95% of reachable refreshes (SC-006) using `android/app/src/test/kotlin/com/gomoney/capture/sync/BridgeClientTest.kt` and `android/app/src/test/kotlin/com/gomoney/capture/capture/UserSourcePipelineTest.kt`
- [X] T062 Code cleanup and refactor pass across `android/app/src/main/kotlin/com/gomoney/capture/source/`, `.../sync/`, `.../ui/`, and `pkg/androidbridge/`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — can start immediately.
- **Foundational (Phase 2)**: Depends on Setup — BLOCKS all user stories.
- **User Stories (Phase 3–6)**: All depend on Foundational completion.
  - US1 (P1) is the MVP and should complete first.
  - US2 and US3 (both P2) can run in parallel once Foundational is done, but both touch `pkg/androidbridge/handlers.go` and `server.go` and the Android `BridgeClient`/`CatalogRepository`, so serialize the shared-file tasks.
  - US4 (P3) builds on US1's source repository and screen.
- **Polish (Phase 7)**: Depends on all desired user stories being complete.

### User Story Dependencies

- **US1 (P1)**: Starts after Foundational — no dependency on other stories.
- **US2 (P2)**: Starts after Foundational — reuses US1's source editor; independently testable.
- **US3 (P2)**: Starts after Foundational — reuses US2's catalog plumbing and bridge client; independently testable.
- **US4 (P3)**: Starts after Foundational — extends US1's repository and screen; independently testable.

### Within Each User Story

- Tests first (confirm failing) → models/entities → services → endpoints/UI → integration.
- Bridge handler before its route registration.
- Local persistence before the sync/UI that consumes it.

### Parallel Opportunities

- Setup: T002 runs in parallel with T001.
- Foundational: T003, T004, T008, T009, T010 can start together; T005→T006→T007 are a chain.
- US1: all four test tasks (T014–T017) run in parallel; T020, T023, T024, T025 run in parallel after their dependencies.
- US2: T028–T030 in parallel; US3: T038–T041 in parallel.
- US4: T051 is independent.
- Polish: T056–T059 run in parallel.

---

## Parallel Example: User Story 1

```bash
# Launch all US1 tests together (write first, expect failures):
Task: "Add user-source fixtures in android/app/src/test/resources/fixtures/usersource/"
Task: "Add TemplateMatcherTest in android/app/src/test/kotlin/com/gomoney/capture/source/TemplateMatcherTest.kt"
Task: "Add SourceValidatorTest in android/app/src/test/kotlin/com/gomoney/capture/source/SourceValidatorTest.kt"
Task: "Add UserSourcePipelineTest in android/app/src/test/kotlin/com/gomoney/capture/capture/UserSourcePipelineTest.kt"

# Launch independent UI units together after T018:
Task: "Add SourcesScreen in android/app/src/main/kotlin/com/gomoney/capture/ui/SourcesScreen.kt"
Task: "Add ParseErrorReviewList in android/app/src/main/kotlin/com/gomoney/capture/ui/ParseErrorReviewList.kt"
```

## Parallel Example: User Story 3

```bash
# Launch all US3 tests together:
Task: "Add GET /v1/categories bridge tests in pkg/androidbridge/server_test.go"
Task: "Add PUT /v1/transactions/assignment bridge tests in pkg/androidbridge/server_test.go"
Task: "Add AssignmentSyncTest in android/app/src/test/kotlin/com/gomoney/capture/sync/AssignmentSyncTest.kt"
Task: "Add assignment persistence tests in android/app/src/test/kotlin/com/gomoney/capture/storage/AssignmentPersistenceTest.kt"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup.
2. Complete Phase 2: Foundational (CRITICAL — blocks all stories).
3. Complete Phase 3: User Story 1.
4. **STOP and VALIDATE**: run `quickstart.md` Scenarios 1–3 and the US1 tests.
5. Demo the in-app source definition and template capture.

### Incremental Delivery

1. Setup + Foundational → foundation ready.
2. US1 → validate → deploy (MVP: define sources and capture).
3. US2 → validate → deploy (bind sources to server accounts).
4. US3 → validate → deploy (destination account and category).
5. US4 → validate → deploy (disable/remove sources).
6. Polish → docs, privacy audit, retention maintenance, quickstart validation.

### Parallel Team Strategy

1. Team completes Setup + Foundational together.
2. After Foundational: Developer A takes US1 (MVP), Developer B takes US2, Developer C takes US3 — serializing the shared `handlers.go`/`server.go`/`BridgeClient.kt` edits.
3. US4 and Polish follow once US1–US3 land.

---

## Notes

- [P] tasks touch different files and have no dependencies on incomplete tasks.
- [Story] labels map each task to its user story for traceability.
- Every task names an exact file path.
- Verify tests fail before implementing.
- Raw message text must never leave the device or appear in logs (FR-023, SC-007).
- Commit after each task or logical group; stop at any checkpoint to validate a story independently.

---

## Phase 8: Convergence

- [X] T063 Surface stale source-account bindings in the Sources UI (thread the cached server accounts or a ViewModel-derived stale flag via `CatalogRepository.isBindingStale` into `SourcesScreen`/`SourceEditorScreen`, show the existing `sources_binding_stale` prompt when a source's `boundAccountId` is absent from the server list, and cover it with a UI/ViewModel test) per spec edge case (bound account deleted/renamed) + data-model §1 (partial)
