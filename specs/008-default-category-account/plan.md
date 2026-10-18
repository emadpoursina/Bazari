# Implementation Plan: Move Add-Note into Category Editing Screen & Default Category/Account for Transactions

**Branch**: `default-category-account` | **Date**: 2026-10-18 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/008-default-category-account/spec.md`

**Note**: `setup-plan.sh --json` reports `BRANCH=008-default-category-account` (derived from the feature directory name); the actual checked-out git branch is `default-category-account`, which is what this plan uses.

## Summary

Two Android-only changes. (1) Move the add/edit-note control out of the Events tab list rows into the per-event editing screen (`EventDetailScreen`), keeping row note text and "waiting to sync" labels untouched — no storage or sync change (FR-001–FR-004). (2) Add user-settable default category and default destination account, persisted locally as two DataStore keys in `SettingsRepository`, managed from a new "Default category & default account" section in the Settings tab, and stamped onto newly captured transactions inside `CapturePipeline` **before** the row insert: fill only when the parsed transaction has no assignment, validate the stored IDs against the cached `server_categories` / `server_accounts` catalogs, and mark `assignmentSyncState = 'pending'` so the assignment rides the exact existing sync path (initial send payload already carries `categoryId`/`destinationAccountId`; `CREATED`/`pendingAssignments` clear it) (FR-005–FR-011).

## Technical Context

**Language/Version**: Kotlin 2.x (Android capture app, min SDK 26), Jetpack Compose (Material 3)

**Primary Dependencies**: Jetpack Room, DataStore (Preferences), WorkManager, Material 3 Compose; existing `SettingsRepository`, `CatalogRepository`, `CapturePipeline`, `EventActions`, `SyncEngine`/`BridgeClient`

**Storage**: Android Room `gomoney-capture.db` (**no schema change, no migration** — only reads of existing `server_categories`/`server_accounts` + writes to existing `normalized_transactions` columns); defaults in DataStore preferences (2 new `intPreferencesKey`s)

**Testing**: Android JVM unit tests via `make android-gradle-test` (`cd android && ./gradlew test`); existing patterns in `android/app/src/test/kotlin/com/gomoney/capture/`

**Target Platform**: Android 8.0+ (API 26)

**Project Type**: Mobile (Android) app only — server, bridge, sync protocol, and other clients untouched (spec Assumptions scope boundary)

**Performance Goals**: Stamping adds ≤2 DataStore/DAO reads per capture (both already in-memory/cached queries); UI changes add no measurable cost

**Constraints**: No new permissions; no Room migration; note limit stays at the existing `MAX_MEMO_LENGTH = 200`; defaults never applied retroactively; default applied regardless of currency mismatch (no warning path)

**Scale/Scope**: ~6–8 source files touched, 1 new DataStore accessor group, ~3 new test files, no navigation change (editing screen already reachable via "Set category")

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- `.specify/memory/constitution.md` is an **unfilled template** (placeholder principle names, no ratified version) — no concrete gates exist to violate. Documented as the same "no gates to violate" finding used by feature 007.
- Additive change consistent with existing patterns: local-first writes + `SyncEngine.enqueueExpedited` follow-up, DataStore-backed settings, catalog-driven dropdowns. No new libraries, no new permissions, no schema migration.
- **Result: PASS** (vacuously — no enforceable gates). Re-checked after Phase 1 design: still PASS; no complexity-tracking entries required.

## Project Structure

### Documentation (this feature)

```text
specs/008-default-category-account/
├── plan.md              # This file (/speckit.plan output)
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
│   ├── defaults-stamping.md    # capture-time stamping + persistence contract
│   └── note-editing-ui.md      # screen-level note editing contract
└── tasks.md             # Phase 2 output (/speckit.tasks — NOT created here)
```

### Source Code (repository root)

```text
android/app/src/main/kotlin/com/gomoney/capture/
├── ui/
│   ├── EventsScreen.kt          # remove memo edit block from rows (keep note text + pending labels)
│   ├── EventDetailScreen.kt     # add memo editor (prefill, 200-char cap, save/cancel)
│   ├── SettingsScreen.kt        # new "Default category & default account" section + SettingsViewModel flows
│   └── MainActivity.kt          # wire onSaveMemo -> eventActions.saveMemo; pass catalogs to SettingsScreen
├── storage/
│   └── SettingsRepository.kt    # defaultCategoryId / defaultAccountId DataStore keys + observe/set/clear
├── capture/
│   └── CapturePipeline.kt       # stamp defaults on parsed tx before insert (fill-if-null + catalog validation)
└── (sync/ untouched — existing pending-assignment path reused as-is)
android/app/src/test/kotlin/com/gomoney/capture/
├── capture/DefaultStampingTest.kt      # new
├── storage/DefaultPreferencesTest.kt   # new
└── ui/... (extend existing behavior tests as needed)
```

**Structure Decision**: Android-only change inside the existing `ui`/`storage`/`capture` split; `sync` is deliberately untouched because FR-007a reuses the existing `assignmentSyncState='pending'` → expedited-sync path (initial send payload and `PUT /v1/transactions/assignment` already exist). No backend work.

## Complexity Tracking

> No constitution violations — table not needed.
