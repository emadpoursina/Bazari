# Tasks: Events Selection + Selective Sync/Delete + Fitting Top Bar

**Input**: Design documents from `/specs/007-events-selection-sync/`

## Phase 1: Setup

- [X] T001 Create spec/plan/research/data-model/quickstart for 007

## Phase 2: Foundational (storage + sync scope)

- [X] T002 [US2] Add `RawEventDao.deleteByIds(ids)` in `android/.../storage/Daos.kt`
- [X] T003 [US2] Add `MaintenanceRepository.deleteEvents(ids)` in `android/.../storage/MaintenanceRepository.kt`
- [X] T004 [US2] Add `SyncEngine.drainIds(...)` scoped drain in `android/.../sync/TransactionSyncWorker.kt`
- [X] T005 [US2] Add `EventActions.syncSelected/deleteSelected` in `android/.../ui/EventActions.kt`

## Phase 3: User Story 1+2 — Selection UI + selective actions (P1) 🎯 MVP

- [X] T006 [US1] Per-event checkbox + selection state + counter in `EventsScreen.kt`
- [X] T007 [US1] Select all / Clear selection controls in `EventsScreen.kt`
- [X] T008 [US2] Sync-selected + Delete-selected buttons with counts, disabled states, delete confirmation dialog in `EventsScreen.kt`
- [X] T009 Wire `onSyncSelected/onDeleteSelected` in `MainActivity.kt`

## Phase 4: User Story 3 — Fitting top bar (P2)

- [X] T010 [US3] Replace horizontal-scroll Row with wrapping FlowRow of compact buttons in `EventsScreen.kt`

## Phase 5: Polish

- [X] T011 Tests: `deleteEvents` deletes only selected (Robolectric) + `syncSelected` requeues only selected failures
- [X] T012 Run `make android-gradle-test`, fix regressions; validate quickstart steps 1–7
