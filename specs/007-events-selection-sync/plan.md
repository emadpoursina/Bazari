# Implementation Plan: Events Selection + Selective Sync/Delete + Fitting Top Bar

**Branch**: `feature/events-selection-sync` | **Date**: 2026-10-14 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/007-events-selection-sync/spec.md`

## Summary

Add transient per-event checkbox selection to the Android Events tab (Select all / Clear + counter), scope Sync and Delete to the selected IDs only, and replace the horizontally-scrolling top action row with a wrapping `FlowRow` of compact buttons that fits a 360dp phone without clipping. Storage adds `RawEventDao.deleteByIds`; `MaintenanceRepository.deleteEvents(ids)` wraps it; `SyncEngine.drainIds(...)` drains only the given IDs; `EventActions.syncSelected/deleteSelected` orchestrate requeue + scoped drain + expedited trigger.

## Technical Context

**Language/Version**: Kotlin 2.x (Android capture app, min SDK 26)

**Primary Dependencies**: Jetpack Room, WorkManager, DataStore, Material 3 Compose (FlowRow), existing `BridgeClient`/`SyncEngine`

**Storage**: Android Room `gomoney-capture.db` (no version bump — new `DELETE ... WHERE id IN` query only)

**Testing**: Android JUnit + Robolectric via `make android-gradle-test` (`./gradlew test`)

**Target Platform**: Android 8.0+ (API 26)

**Project Type**: Mobile (Android) + Go bridge (no bridge change)

**Performance Goals**: Selection toggle <16ms; scoped drain same per-item cost as full drain

**Constraints**: No new permissions; no schema migration; no navigation change; selection is transient UI state

**Scale/Scope**: Events tab only; ~5 files touched; 1 new test file

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- No constitution file populated (template only) — no gates to violate.
- Change is additive, preserves existing clear/scan behavior, uses existing cascade + drain patterns. No new libraries.

## Project Structure

### Documentation (this feature)

```text
specs/007-events-selection-sync/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
└── tasks.md             # Phase 2 output
```

### Source Code (repository root)

```text
android/app/src/main/kotlin/com/gomoney/capture/
├── storage/
│   ├── Daos.kt                  # RawEventDao.deleteByIds
│   └── MaintenanceRepository.kt # deleteEvents(ids)
├── sync/
│   └── TransactionSyncWorker.kt # SyncEngine.drainIds
├── ui/
│   ├── EventsScreen.kt          # selection + FlowRow top bar + dialogs
│   ├── EventActions.kt          # syncSelected / deleteSelected
│   └── MainActivity.kt          # wire new callbacks
android/app/src/test/kotlin/com/gomoney/capture/
├── storage/EventsSelectionDeleteTest.kt
└── sync/SelectedSyncTest.kt (or single file)
```

**Structure Decision**: Android-only change; follows existing `storage`/`sync`/`ui` split. No backend, no schema change.

## Complexity Tracking

> No constitution violations — table not needed.
