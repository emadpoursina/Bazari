---
description: "Implementation tasks for per-currency exchange rate modes"
---

# Tasks: Per-Currency Exchange Rate Modes

**Input**: Design documents from `specs/003-currency-rate-modes/`

**Prerequisites**: `plan.md`, `spec.md`, `research.md`, `data-model.md`, `contracts/currency-rate-mode.md`, `quickstart.md`

**Tests**: Included because `plan.md` calls for regression tests and `quickstart.md` defines Go and Angular validation.

**Organization**: Tasks are grouped by user story so each story can be implemented and verified as an increment.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel because it touches separate files and has no dependency on incomplete tasks.
- **[Story]**: User story from `spec.md`.
- All implementation and test tasks include their target file paths.

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Make the shared API contract available to both application clients.

- [ ] T001 Add `CurrencyRateMode` and append `rate_mode` fields to the shared `Currency` and `UpdateCurrencyRequest` schema in the `xskydev/go-money-pb` Buf module, following `specs/003-currency-rate-modes/contracts/currency-rate-mode.md`, then publish compatible generated Go and TypeScript clients. **Deferred:** external repository/publishing is unavailable and explicitly out of this repo-only implementation.
- [ ] T002 Update the application dependencies to the published shared-client version in `go.mod`, `go.sum`, `frontend/package.json`, and `frontend/package-lock.json`. **Deferred:** depends on T001; dependencies remain pinned to the available clients.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Persist the mode and safely migrate existing data before implementing rate-mode behavior.

- [X] T003 [P] Add a migration regression test covering existing non-base rate preservation, Manual backfill/default, and the base rate/mode invariant in `pkg/database/migrations_test.go`.
- [X] T004 [P] Add a nullable persisted rate-mode field with the Manual default to `database.Currency` in `pkg/database/struct.go`.
- [X] T005 Add an ordered Gormigrate migration that adds `rate_mode` with a Manual database default, preserves existing non-base rates while setting them to Manual, and sets the configured base row to rate `1` with a null mode in `pkg/database/migrations.go` (depends on T003 and T004).

**Checkpoint**: The shared API clients and database representation/migration are ready for story implementation.

---

## Phase 3: User Story 1 - Keep My Edited Rate (Priority: P1) 🎯 MVP

**Goal**: A changed user rate becomes Manual and cannot be replaced by a feed refresh, including when that refresh is already in flight; users can identify the mode in the currency list.

**Independent Test**: Change a non-base rate, refresh with a different valid rate, and confirm the saved rate remains and displays Manual. Also save a manual rate after fetch but before the refresh upsert, then confirm the refresh does not overwrite it; confirm the rate and mode persist after restart.

### Tests for User Story 1

- [X] T006 [P] [US1] Add service tests for changed-rate updates setting Manual, unchanged-rate updates preserving the current mode, and the local-to-protobuf `UNSPECIFIED` mapping in `pkg/currency/service_test.go` and `pkg/currency/service_rate_mode_test.go`. **Note:** response mode reporting depends on deferred T001/T002.
- [X] T007 [P] [US1] Add sync tests proving Manual rates are skipped and a manual save during an in-flight refresh wins at the database write in `pkg/currency/sync_test.go`.
- [ ] T008 [P] [US1] Add an Angular list-component test that verifies non-base rate modes are visible in `frontend/src/app/pages/currencies/currencies-list.component.spec.ts`. **Deferred:** pinned response clients expose no mode field (T001/T002).

### Implementation for User Story 1

- [X] T009 [US1] Add the service's single persisted-mode-to-protobuf-`UNSPECIFIED` mapping point and atomically save a changed rate with Manual mode in `pkg/currency/service.go`. **Note:** the pinned response message has no mode field; returning actual Manual/Automatic values depends on deferred T001/T002.
- [X] T010 [US1] Validate feed rates and guard PostgreSQL conflict updates so only rows still in Automatic mode receive a feed rate in `pkg/currency/sync.go`.
- [ ] T011 [P] [US1] Display each non-base currency's returned Manual/Automatic mode in `frontend/src/app/pages/currencies/currencies-list.component.ts` and `frontend/src/app/pages/currencies/currencies-list.component.html`. **Deferred:** pinned response clients expose no mode field (T001/T002).

**Checkpoint**: A rate edit is durable, shown as Manual, and protected from both later and in-flight refreshes.

---

## Phase 4: User Story 2 - Choose Which Rates Follow the Feed (Priority: P2)

**Goal**: Users can switch a non-base currency between Manual and Automatic without changing its current rate until a valid refresh arrives.

**Independent Test**: Switch Manual to Automatic and verify the current rate stays until a successful valid refresh changes it; switch to Manual and verify later refreshes leave it unchanged. Verify an unchanged-rate update with an unspecified mode preserves the current mode, and a changed rate takes precedence over a simultaneous Automatic request.

### Tests for User Story 2

- [ ] T012 [P] [US2] Add service tests for explicit mode changes, unspecified-mode compatibility, invalid mode handling, and changed-rate precedence in `pkg/currency/service_test.go`. **Deferred:** the pinned request client has no `rate_mode` field (T001/T002).
- [X] T013 [P] [US2] Add sync tests for Automatic feed updates and for invalid/omitted rates retaining the stored state in `pkg/currency/sync_test.go`. **Note:** the Manual-to-Automatic UI transition depends on deferred T001/T002.
- [ ] T014 [P] [US2] Add an Angular edit-component test for the Manual/Automatic control and its absence for the base currency in `frontend/src/app/pages/currencies/currencies-upsert.component.spec.ts`. **Deferred:** the pinned request client has no mode field (T001/T002).

### Implementation for User Story 2

- [ ] T015 [US2] Apply explicit Manual/Automatic requests only when the rate is unchanged, treat `UNSPECIFIED` as no change, and keep a changed rate's Manual precedence in `pkg/currency/service.go`. **Deferred:** the pinned request client has no mode field (T001/T002).
- [ ] T016 [US2] Add a non-base Manual/Automatic selector and submit its value through `UpdateCurrencyRequest` in `frontend/src/app/pages/currencies/currencies-upsert.component.ts` and `frontend/src/app/pages/currencies/currencies-upsert.component.html`. **Deferred:** the pinned request client has no mode field (T001/T002).

**Checkpoint**: Users can resume feed updates or take control without an implicit rate change.

---

## Phase 5: User Story 3 - Start Safely and Keep the Base Rate Fixed (Priority: P3)

**Goal**: New feed currencies start Automatic, currencies created manually start Manual, and the configured base remains rate `1` without a mode.

**Independent Test**: Verify migrated currencies keep their rates as Manual, new valid feed currencies are Automatic, manually created currencies are Manual, invalid or omitted feed rates leave stored values and modes unchanged, and base edits/refreshes leave rate `1` with no mode.

### Tests for User Story 3

- [X] T017 [P] [US3] Add service tests for manually created currencies defaulting to Manual and for the configured base remaining at rate `1` with a null local mode in `pkg/currency/service_test.go`.
- [X] T018 [P] [US3] Add sync tests for new feed currencies starting Automatic, invalid/omitted rates retaining the last valid state, and the base remaining at `1` in `pkg/currency/sync_test.go`.

### Implementation for User Story 3

- [X] T019 [US3] Set newly inserted non-base feed currencies to Automatic while preserving the base-currency invariant in `pkg/currency/sync.go`.
- [X] T020 [US3] Set currencies created through the administration service to Manual and ensure the configured base is stored with rate `1` and no local mode in `pkg/currency/service.go`.

**Checkpoint**: Rollout, creation defaults, incomplete feeds, and the base-currency invariant are all verified.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Validate the integrated Go API, database, generated clients, and Angular application.

- [X] T021 Run the automated Go, Angular test, lint, and build commands documented in `specs/003-currency-rate-modes/quickstart.md`. **Results:** Go packages and Angular tests/build passed; lint ran but failed on existing errors across unrelated files (details in implementation report).

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: T001/T002 are deferred external coordination tasks. The explicitly authorized local-first work proceeds without them.
- **Foundational (Phase 2)**: T003 and T004 can run in parallel; T005 follows both. Local persistence/sync stories depend on this foundation.
- **User Stories (Phases 3-5)**: Local service/sync tasks proceed in priority order. Response-mode reporting and Manual/Automatic UI selection depend on T001/T002 and remain open.
- **Polish (Phase 6)**: Depends on the desired user stories being complete.

### User Story Dependencies

- **User Story 1 (P1)**: Local persistence and Manual write protection start after Phase 2; visible list state depends on the shared response field.
- **User Story 2 (P2)**: Sync behavior can be verified locally; explicit service/UI mode selection depends on the shared request field.
- **User Story 3 (P3)**: Follows US2 because its creation defaults extend the same service and sync files. The safe database backfill is foundational so P1 never runs against unclassified legacy rows.

### Within Each User Story

- Write and run the story's tests so they fail before implementing that story's behavior.
- Complete tests before implementation; keep independent files parallel where indicated.
- Validate the independent test criteria at each story checkpoint.

### Parallel Opportunities

- Phase 2: T003 and T004.
- US1: T006-T008 tests in parallel; after they fail, T009-T011 can be implemented in parallel.
- US2: T012-T014 tests in parallel; after they fail, T015-T016 can be implemented in parallel.
- US3: T017-T018 tests in parallel; after they fail, T019-T020 can be implemented in parallel.

### Parallel Example: User Story 1

```text
Task: "Add changed-rate and response-mode service tests in pkg/currency/service_test.go"
Task: "Add manual-mode and in-flight refresh sync tests in pkg/currency/sync_test.go"
Task: "Add mode-display test in frontend/src/app/pages/currencies/currencies-list.component.spec.ts"

After the tests fail, implement independently:
Task: "Update mode mapping and changed-rate writes in pkg/currency/service.go"
Task: "Guard feed upserts by stored mode in pkg/currency/sync.go"
Task: "Display mode in frontend/src/app/pages/currencies/currencies-list.component.ts and frontend/src/app/pages/currencies/currencies-list.component.html"
```

### Parallel Example: User Story 2

```text
Task: "Add mode-update service tests in pkg/currency/service_test.go"
Task: "Add Automatic/invalid-feed sync tests in pkg/currency/sync_test.go"
Task: "Add mode-selector test in frontend/src/app/pages/currencies/currencies-upsert.component.spec.ts"

After the tests fail, implement independently:
Task: "Handle explicit mode updates in pkg/currency/service.go"
Task: "Add the Manual/Automatic selector in frontend/src/app/pages/currencies/currencies-upsert.component.ts and frontend/src/app/pages/currencies/currencies-upsert.component.html"
```

### Parallel Example: User Story 3

```text
Task: "Add manual-create and base-currency service tests in pkg/currency/service_test.go"
Task: "Add new-feed-currency and base-rate sync tests in pkg/currency/sync_test.go"

After the tests fail, implement independently:
Task: "Set new feed rows to Automatic in pkg/currency/sync.go"
Task: "Set manual-create defaults and enforce the base invariant in pkg/currency/service.go"
```

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Defer shared-client setup and complete the foundational database mode/migration tasks.
2. Complete implementable US1 service/sync behavior and validate it with Go tests; the Angular list test waits for the shared response field.
3. Confirm a changed user rate remains Manual after both a normal refresh and an in-flight refresh before proceeding.

### Incremental Delivery

1. Defer external Setup; complete Foundational so the migration safely classifies existing rows.
2. Deliver the local US1 rate-protection increment.
3. Complete local US2 sync coverage; defer user-controlled Manual/Automatic switching until compatible request clients exist.
4. Complete US3 creation defaults, invalid/incomplete feeds, and the base-currency invariant.
5. Run the available quickstart validation; complete API/UI coverage when its blockers are resolved.

## Notes

- `[P]` tasks touch different files and have no dependency on unfinished tasks.
- The shared protobuf/Buf source is external to this repository. T001/T002 and UI/API tasks that require the new fields remain deferred; local database/service/sync work is implemented without editing generated clients or dependencies.
- The phone app remains out of scope.
