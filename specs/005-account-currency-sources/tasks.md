# Tasks: Account Currency & Source-Only Capture

**Input**: Design documents from `/specs/005-account-currency-sources/`

**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md, data-model.md, contracts/

**Tests**: Plan.md explicitly requires Android JUnit coverage (pipeline, bind/stamp, hold/queue, currency edit, allow-list gone, seeded sources, hardcoded-IRR UI gone) via `make android-gradle-test`, and bridge `go test ./pkg/androidbridge` for non-IRR create validation. Test tasks below update/extend existing suites; no new test framework needed.

**Organization**: Tasks grouped by user story for independent implementation and testing.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Confirm branch inputs and test commands before changes

- [X] T001 Verify branch `notification-engine` and feature dir `specs/005-account-currency-sources/` contain spec.md, plan.md, data-model.md, contracts/ per plan.md
- [X] T002 [P] Confirm Android test command `make android-gradle-test` runs green on the unmodified tree in `android/`
- [X] T003 [P] Confirm bridge test command `go test -p 1 -timeout 60s ./pkg/androidbridge/` runs green in `pkg/androidbridge/`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Shared persistence model (Room v4 + `held` state) that all user stories depend on

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [X] T004 Add `HELD` (`held`) to `DeliveryState` in `android/app/src/main/kotlin/com/gomoney/capture/model/Enums.kt` with legal transition `held → queued` only (never `held → sending`) per `specs/005-account-currency-sources/data-model.md`
- [X] T005 Bump Room database `gomoney-capture.db` to version 4 with `MIGRATION_3_4` in `android/app/src/main/kotlin/com/gomoney/capture/storage/AppDatabase.kt` (no-op SQL if `held` is a new state string and empty-string currency is already valid TEXT) per `specs/005-account-currency-sources/data-model.md`
- [X] T006 [P] Add `held → queued` transition support to `DeliveryRepository` in `android/app/src/main/kotlin/com/gomoney/capture/storage/DeliveryRepository.kt` (stamp + transition in one local transaction; `TransactionSyncWorker` still drains `queued` only)
- [X] T007 [P] Record Room v4 semantic change in existing `AppDatabaseMigrationTest` under `android/app/src/test/kotlin/com/gomoney/capture/storage/`

**Checkpoint**: Foundation ready - user story implementation can now begin

---

## Phase 3: User Story 1 - Inherit currency from the bound server account (Priority: P1) 🎯 MVP

**Goal**: Binding a source to a server account copies that account's currency automatically; every capture from that source is recorded in that currency, never a hardcoded rial default (FR-001–FR-008).

**Independent Test**: Bind a source to a non-rial server account, capture a matching message, verify the transaction uses that currency; bind a different source to a rial account and verify rial captures still work (spec.md US1; quickstart.md steps 2–3).

### Implementation for User Story 1

- [X] T008 [US1] Replace hardcoded `currency = "IRR"` with copy of `ServerAccount.currency` for the source's `boundAccountId` from the cached `server_accounts` rows in `android/app/src/main/kotlin/com/gomoney/capture/source/UserSourceParser.kt`
- [X] T009 [US1] Show the selected account's currency automatically (no separate currency control) and reject binds to accounts with blank currency without replacing the previous binding in `android/app/src/main/kotlin/com/gomoney/capture/ui/SourceEditorScreen.kt` and `android/app/src/main/kotlin/com/gomoney/capture/source/SourceValidator.kt`
- [X] T010 [P] [US1] Label template-test preview amounts with the selected account's currency instead of hardcoded `IRR` in `android/app/src/main/kotlin/com/gomoney/capture/ui/SourceEditorScreen.kt`
- [X] T011 [P] [US1] Display captured amounts with `tx.currency` instead of hardcoded `IRR` in `android/app/src/main/kotlin/com/gomoney/capture/ui/EventDetailScreen.kt` and `android/app/src/main/kotlin/com/gomoney/capture/ui/EventsScreen.kt`
- [X] T012 [P] [US1] Update the `currency` column comment (`MVP: always IRR`) to bound-account semantics in `android/app/src/main/kotlin/com/gomoney/capture/storage/Entities.kt`
- [X] T013 [US1] Verify `BridgeClient` sends `tx.currency` unchanged for non-IRR (IRR `/10` divisor only when `currency == "IRR"`) in `android/app/src/main/kotlin/com/gomoney/capture/sync/BridgeClient.kt` (no behavior change expected; plan states already non-IRR-aware)
- [X] T014 [US1] Relax `validateTransaction` in `pkg/androidbridge/handlers.go` from `currency must be IRR` to required non-empty, length 1–16 letters/digits per `specs/005-account-currency-sources/contracts/bridge-api.md` (keep mapped-account currency-match check)
- [X] T015 [P] [US1] Add pipeline test for bound non-IRR capture and bound IRR capture in `android/app/src/test/kotlin/com/gomoney/capture/capture/` per plan.md Testing
- [X] T016 [P] [US1] Add bind-validation test (blank-currency account rejected) in `android/app/src/test/kotlin/com/gomoney/capture/source/` per plan.md Testing
- [X] T017 [P] [US1] Add bridge test accepting USD create when mapped account is USD and 400 on blank currency or mapped mismatch in `pkg/androidbridge/handlers_test.go` (new file alongside `pkg/androidbridge/handlers.go`) per `specs/005-account-currency-sources/contracts/bridge-api.md`

**Checkpoint**: User Story 1 fully functional and testable independently — mixed IRR + non-IRR captures each carry their own bound-account currency (SC-001, SC-002, SC-007)

---

## Phase 4: User Story 2 - Capture only from defined sources; drop the settings allow-list (Priority: P1)

**Goal**: Sources are the only admission gate; Settings has no bank allow-list; shipped parsers never run; former built-in banks exist as editable seeded sources (FR-012–FR-017, FR-021).

**Independent Test**: Confirm Settings has no allow-list; capture via an enabled source with user template; confirm messages from non-source identifiers produce nothing, including former allow-list entries and former parser-only identifiers (spec.md US2; quickstart.md steps 1, 7).

### Implementation for User Story 2

- [X] T018 [US2] Remove the bank allow-list section (add/remove package fields) from `android/app/src/main/kotlin/com/gomoney/capture/ui/SettingsScreen.kt`, keeping server URL/token, capture toggles, debug mode, permission status
- [X] T019 [US2] Stop reading `ServerConfiguration.enabledBankPackages` as a capture gate and clear the leftover DataStore set on settings load in `android/app/src/main/kotlin/com/gomoney/capture/storage/SettingsRepository.kt` (no migration of entries into sources)
- [X] T020 [US2] Replace the allow-list gate plus `ParserRegistry` fallback with source-only admission (enabled `TransactionSource` + user template via `UserSourceParser`, ignore-without-persist otherwise) in `android/app/src/main/kotlin/com/gomoney/capture/capture/CapturePipeline.kt`
- [X] T021 [US2] Drop the allow-list membership test and pass every event to `pipeline.process` (keeping permission/toggle checks) in `android/app/src/main/kotlin/com/gomoney/capture/capture/NotificationCaptureService.kt` and `android/app/src/main/kotlin/com/gomoney/capture/capture/SmsCaptureReceiver.kt`, wiring `TransactionSourceRepository` + `UserSourceParser` into the pipeline
- [X] T022 [P] [US2] Seed former built-in bank sources (Mellat, Melli, Saman, Blue per `specs/005-account-currency-sources/data-model.md` table) as ordinary idempotent `TransactionSource` rows on `(identifier, channel)` in `android/app/src/main/kotlin/com/gomoney/capture/source/TransactionSourceRepository.kt` (do not seed Generic or SampleBank)
- [X] T023 [P] [US2] Add pipeline test that non-source messages are ignored without persisting (including former allow-list and parser-only identifiers) in `android/app/src/test/kotlin/com/gomoney/capture/capture/` per plan.md Testing
- [X] T024 [P] [US2] Add UI test that Settings renders no allow-list in `android/app/src/test/kotlin/com/gomoney/capture/ui/` per plan.md Testing
- [X] T025 [P] [US2] Add seed test (former banks inserted once, user edits not overwritten) in `android/app/src/test/kotlin/com/gomoney/capture/source/` per plan.md Testing
- [X] T026 [US2] Retire or repoint existing parser fixture tests that assume a live `ParserRegistry` in the capture path under `android/app/src/test/kotlin/com/gomoney/capture/` per plan.md Testing

**Checkpoint**: User Stories 1 AND 2 both work independently — currency comes from bound accounts and only enabled sources capture (SC-003, SC-004, SC-010)

---

## Phase 5: User Story 3 - Unbound sources and later rebinding (Priority: P2)

**Goal**: Unbound captures are held locally with empty currency (never invented rial); binding later stamps still-held empty-currency rows and queues them; rebinds affect only new captures (FR-009–FR-011, FR-008).

**Independent Test**: Capture from an unbound source (held, no rial, not delivered); bind an account and verify the held row is stamped and deliverable plus new captures use it; rebind to a different currency and verify stamped rows unchanged (spec.md US3; quickstart.md steps 4–5).

**Depends on**: Phase 3 (US1 currency copy) — stamp logic reuses the bound-account currency read.

### Implementation for User Story 3

- [X] T027 [US3] Persist captures from unbound/stale/no-currency sources with `currency = ""` and delivery `held` (no expedited sync enqueue) in `android/app/src/main/kotlin/com/gomoney/capture/capture/CapturePipeline.kt` and `android/app/src/main/kotlin/com/gomoney/capture/storage/DeliveryRepository.kt`
- [X] T028 [US3] On bind, stamp the new account's currency onto this source's still-held rows with empty currency, set `accountId` to the bound id, transition `held → queued`, then enqueue expedited sync — leaving non-empty-currency rows (including `sent`) untouched — in `android/app/src/main/kotlin/com/gomoney/capture/source/TransactionSourceRepository.kt` with `android/app/src/main/kotlin/com/gomoney/capture/storage/DeliveryRepository.kt`
- [X] T029 [US3] Treat new captures from stale bindings as unbound (`held`, empty currency) until rebind in `android/app/src/main/kotlin/com/gomoney/capture/capture/CapturePipeline.kt` reusing `CatalogRepository.isBindingStale` in `android/app/src/main/kotlin/com/gomoney/capture/sync/CatalogRepository.kt`
- [X] T030 [P] [US3] Add hold/queue test (unbound capture held with empty currency, sync worker skips `held`) in `android/app/src/test/kotlin/com/gomoney/capture/capture/` and `android/app/src/test/kotlin/com/gomoney/capture/sync/` per plan.md Testing
- [X] T031 [P] [US3] Add stamp-on-bind test (held rows stamped + queued, non-empty rows untouched, rebind does not rewrite) in `android/app/src/test/kotlin/com/gomoney/capture/source/` and `android/app/src/test/kotlin/com/gomoney/capture/storage/` per plan.md Testing

**Checkpoint**: Unbound hold, stamp-on-bind, and rebind-no-rewrite all independently verified (SC-005, SC-006, SC-008)

---

## Phase 6: User Story 4 - Edit currency before delivery (Priority: P2)

**Goal**: Currency editable on the transaction screen while local and undelivered; read-only after delivery (FR-022).

**Independent Test**: Change currency on a local undelivered transaction and confirm the new value is what would be delivered; open a delivered transaction and confirm currency is not editable (spec.md US4; quickstart.md step 6).

**Depends on**: Phase 3 (US1 currency semantics); hold-state edit path also touches Phase 5 (US3) states.

### Implementation for User Story 4

- [X] T032 [US4] Show a currency dropdown of distinct non-blank `server_accounts.currency` values for delivery states `held`/`queued`/`failed` (local update only; no `held → queued` move) and read-only text for `sending`/`sent` in `android/app/src/main/kotlin/com/gomoney/capture/ui/EventDetailScreen.kt`, keeping destination/category assignment independent of currency
- [X] T033 [P] [US4] Add currency-edit gating test (editable when held/queued/failed, read-only when sending/sent) in `android/app/src/test/kotlin/com/gomoney/capture/ui/` per plan.md Testing

**Checkpoint**: Pre-delivery currency correction verified without touching delivered history (SC-009)

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Final validation across all stories

- [X] T034 [P] Remove remaining hardcoded-IRR UI labels and comments found by searching `IRR` in `android/app/src/main/kotlin/com/gomoney/capture/` outside `AmountNormalizer.kt`, `BridgeClient.kt` (IRR-only scaling boundary), and parser history classes
- [X] T035 [P] Run `make android-gradle-test` and `go test -p 1 -timeout 60s ./pkg/androidbridge/` and fix regressions
- [X] T036 Run `specs/005-account-currency-sources/quickstart.md` validation walkthrough (steps 1–8) covering SC-001–SC-010

  **T036 validation mapping (automated; device walkthrough optional)**:
  - SC-001 bound capture carries the account's currency — `BoundAccountPipelineTest`, `UserSourcePipelineTest` (quickstart step 3)
  - SC-002 bind UI shows the account's currency automatically, blank-currency accounts not bindable — `SourceValidatorTest`, blank-currency rows disabled in the account dropdown (quickstart step 2)
  - SC-003 Settings has no allow-list — `SettingsAllowListRemovedTest` (quickstart step 1)
  - SC-004 no matching enabled source → nothing captured/persisted — `UserSourcePipelineTest`, `CapturePipelineTest` (quickstart step 7)
  - SC-005 unbound capture held with empty currency, never delivered — `HeldNotDeliveredTest` (quickstart step 4)
  - SC-006 rebind never rewrites stamped rows — `BindStampTest` (quickstart step 5)
  - SC-007 mixed IRR + non-IRR captures coexist — `BoundAccountPipelineTest` + bridge `handlers_test.go` USD-accept/IRR-match cases (quickstart step 3)
  - SC-008 stamp-on-bind queues held rows — `BindStampTest`, `HeldNotDeliveredTest`, `DeliveryRepositoryTest` (quickstart step 4)
  - SC-009 currency editable only before delivery — `CurrencyEditGatingTest`, `EventActionsCurrencyTest` (quickstart step 6)
  - SC-010 shipped parsers never run; former banks are seeded sources — `SeedDefaultsTest`, no `ParserRegistry` in the capture path (`CapturePipeline.kt` source-only admission), parser fixtures kept as history-only unit tests (quickstart step 7)
  - Quickstart step 8 (privacy, no raw text off-device) unchanged by this feature; covered by existing bridge privacy tests
  - Commands: `make android-gradle-test` → BUILD SUCCESSFUL; `go test -p 1 -count=1 -timeout 60s ./pkg/androidbridge/` → ok

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion - BLOCKS all user stories
- **User Stories (Phases 3–6)**: All depend on Foundational phase completion
  - US1 (Phase 3) and US2 (Phase 4) are mutually independent - either order or parallel (if staffed)
  - US3 (Phase 5) depends on US1 (stamp reuses bound-account currency read)
  - US4 (Phase 6) depends on US1 (currency semantics); held-edit path also assumes US3 states exist
  - Suggested sequential order: US1 → US2 → US3 → US4
- **Polish (Phase 7)**: Depends on all desired user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: Can start after Foundational (Phase 2) - No dependencies on other stories
- **User Story 2 (P1)**: Can start after Foundational (Phase 2) - Independently testable; touches the same `CapturePipeline.kt` as US1/US3 so coordinate edits if parallel
- **User Story 3 (P2)**: Depends on US1 currency-copy behavior for stamp logic - independently testable once US1 done
- **User Story 4 (P2)**: Depends on US1 (and US3 `held` state for the full edit matrix) - independently testable once US1/US3 done

### Within Each User Story

- Models/state before pipeline/services before UI
- Core implementation before tests that lock it in (tests here extend existing suites per plan.md; no strict TDD red-phase required)
- Story complete before moving to next priority

### Parallel Opportunities

- T002 + T003 (setup verification) can run in parallel
- T006 + T007 (foundational) can run in parallel
- US1 and US2 phases can be worked in parallel by different developers (shared file: `CapturePipeline.kt` - coordinate)
- All tasks marked [P] within a story touch different files and can run in parallel
- T015/T016/T017, T023/T024/T025, T030/T031 test tasks are file-independent and parallelizable

---

## Parallel Example: User Story 1

```bash
# Launch all US1 UI/test tasks together (different files, no dependencies):
Task: "Label template-test preview with account currency in SourceEditorScreen.kt (T010)"
Task: "Display tx.currency in EventDetailScreen.kt and EventsScreen.kt (T011)"
Task: "Pipeline test for bound non-IRR and IRR capture (T015)"
Task: "Bind-validation test in source/ (T016)"
Task: "Bridge USD-accept test in pkg/androidbridge (T017)"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup
2. Complete Phase 2: Foundational (CRITICAL - blocks all stories)
3. Complete Phase 3: User Story 1 (bound-account currency end to end, Android + bridge)
4. **STOP and VALIDATE**: Mixed IRR/non-IRR captures each carry the correct bound-account currency
5. Deploy/demo if ready

### Incremental Delivery

1. Complete Setup + Foundational → Foundation ready
2. Add User Story 1 → Test independently → Deploy/Demo (MVP: correct currencies!)
3. Add User Story 2 → Test independently → Deploy/Demo (no more allow-list confusion)
4. Add User Story 3 → Test independently → Deploy/Demo (unbound hold + stamp)
5. Add User Story 4 → Test independently → Deploy/Demo (pre-delivery correction)
6. Each story adds value without breaking previous stories

### Parallel Team Strategy

With multiple developers:

1. Team completes Setup + Foundational together
2. Once Foundational is done:
   - Developer A: User Story 1 (currency copy + bridge)
   - Developer B: User Story 2 (admission + seeding)
3. Then Developer A or B: User Story 3 (needs US1), then User Story 4 (needs US1/US3)
4. Stories complete and integrate independently

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- Each user story independently completable and testable per its Independent Test
- Raw message text never leaves the device and is never logged (unchanged constraint)
- Commit after each task or logical group
- Stop at any checkpoint to validate story independently
- Avoid: defaulting any new capture to IRR; rewriting stamped/delivered rows; converting allow-list entries into sources; running shipped parsers on the capture path
