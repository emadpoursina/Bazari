# Implementation Plan: Notification Scan Button

**Branch**: `notification-events-button` | **Date**: 2026-10-05 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/006-notification-scan-button/spec.md`

**Note**: This template is filled in by the `/speckit.plan` command; its definition describes the execution workflow. Git branch is `notification-events-button` (spec header); `setup-plan.sh` reports the logical id `006-notification-scan-button`, which is the feature directory name.

## Summary

Add a **Scan notifications** control to the Events tab of the Android capture app. Pressing it asks the already-connected `NotificationCaptureService` to read the notifications currently in the shade (`getActiveNotifications()` — only callable inside the listener service) and run each one through the *existing* capture path (same `extractText` longest-wins extraction, same `safePostedAt` timestamping, same `CaptureGate` permission gate, same source-gated `CapturePipeline.process(RawEvent)`). A new Room table keyed by `StatusBarNotification.key` guarantees **at most one event per notification** across all scans and all real-time posts, even when the notification is updated in place (FR-003, clarifications Q1). Scan outcomes are counted as examined / added / skipped (Q3) and shown in an **inline banner** at the top of the Events list (Q4); events created by a scan are stored exactly like real-time events (Q2, no flag). When notification-read access is missing, pressing the button starts no scan and instead shows a message with a **button that opens the system notification-access settings screen** (FR-006, Q5). One scan at a time, non-blocking, transient summary — no new permissions, no manifest changes.

## Technical Context

**Language/Version**: Kotlin 2.x (Android capture app, min SDK 26), Jetpack Compose Material 3

**Primary Dependencies**: Existing stack only — `NotificationListenerService`, Jetpack Room, coroutines/Flow, DataStore, WorkManager. No new libraries.

**Storage**: Android Room `gomoney-capture.db`, schema **v4 → v5**: new table `notification_capture_records` (per-notification identity guard). Scan summary/counters are transient `StateFlow` state, never persisted (spec assumption).

**Testing**: Android JUnit unit tests (existing `android/app/src/test/...`), `make android-gradle-test`. New tests: identity claim/commit/release, outcome→count mapping, single-flight scan guard, banner lifecycle rules. No instrumentation suite exists (none added).

**Target Platform**: Android phone (single user); listener service + existing app UI

**Project Type**: mobile-app (single artifact; no bridge/server changes)

**Performance Goals**: SC-003 — 50 active notifications scanned and summarized in < 10 s with the Events tab responsive; scan runs on `Dispatchers.IO` inside the service scope, one notification at a time.

**Constraints**:
- Scan reuses the real-time capture rules unchanged — no new matching/allow-list logic (FR-002, spec assumptions).
- Scan is additive only: never modifies or removes existing events (FR-010).
- Raw notification text is never logged and never leaves the device (FR-028, unchanged).
- No new platform permission; `AndroidManifest.xml` already declares `BIND_NOTIFICATION_LISTENER_SERVICE` for the service and needs no edit.
- Only one scan at a time (FR-007); UI never blocks (FR-008).
- Banner is transient: cleared when the next scan starts or the user leaves the Events tab (FR-005).
- Constitution is an unfilled template; de-facto gates are privacy, reuse of existing paths, and no new permissions.

**Scale/Scope**: One new button + banner in one screen; one new Room table + DAO; scan loop inside the existing service; roughly 6 touched files, 2–3 new files.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is still an **unfilled template**. No project-specific gates can be evaluated or violated — consistent with `specs/001-android-txn-capture/plan.md`, `specs/004-notification-engine/plan.md`, and `specs/005-account-currency-sources/plan.md`.

| Gate | Status | Notes |
|------|--------|-------|
| Template constitution placeholders | N/A | No ratified principles |
| Privacy (no raw text to server/logs) | PASS | Scan reuses the same pipeline; no logging of text added; summary carries counts only |
| Reuse over new paths | PASS | Same extraction, gate, pipeline, persistence; the only new path is reading the active-notification list |
| No new permission / no new credentials | PASS | Notification listener access already used; manifest untouched |
| Post-Phase 1 re-check | PASS | See re-evaluation below |

**Verdict**: PASS (no active gates). No Complexity Tracking entries required.

### Post-Phase 1 re-check

The design adds one Room table (v5 migration) and one transient UI state holder — no new service, no new screen, no bridge/API change, no new matching rules. Dedup is an identity guard layered on the existing pipeline rather than a second admission gate (the pipeline remains the only admission gate). Privacy and reuse gates still hold.

## Project Structure

### Documentation (this feature)

```text
specs/006-notification-scan-button/
├── plan.md              # This file (/speckit.plan command output)
├── research.md          # Phase 0 output (/speckit.plan command)
├── data-model.md        # Phase 1 output (/speckit.plan command)
├── quickstart.md        # Phase 1 output (/speckit.plan command)
├── contracts/
│   ├── scan-coordination.md        # UI ↔ service scan request/result protocol
│   └── notification-identity.md    # Per-notification at-most-once rule
├── spec.md              # input (with 2026-10-05 clarifications)
└── tasks.md             # Phase 2 output (/speckit.tasks command - NOT created here)
```

### Source Code (repository root)

```text
android/app/src/main/kotlin/com/gomoney/capture/
├── capture/
│   ├── NotificationCaptureService.kt   # +onStartCommand(ACTION_SCAN), scan loop, shared extractText/safePostedAt, registered handle
│   ├── ScanCoordinator.kt              # NEW: single-flight request + ScanUiState StateFlow
│   ├── NotificationIdentityRegistry.kt # NEW: claim/commit/release over notification_capture_records
│   ├── CapturePipeline.kt              # unchanged (still the only admission gate)
│   └── PlatformSupport.kt              # PlatformCapturePermission reused for the press-time check
├── storage/
│   ├── Entities.kt                     # +NotificationCaptureRecord entity
│   ├── Daos.kt                         # +NotificationCaptureRecordDao
│   ├── AppDatabase.kt                  # v4 → v5, MIGRATION_4_5
│   └── MaintenanceRepository.kt        # clearAll() also clears the identity table
└── ui/
    ├── EventsScreen.kt                 # Scan button + inline banner; empty-list state still shows both
    └── MainActivity.kt                 # wiring, banner visibility tied to the Events tab

android/app/src/test/kotlin/com/gomoney/capture/
├── capture/   # NEW: NotificationIdentityRegistryTest, ScanCoordinatorTest, ScanCountingTest
└── storage/   # migration/DAO coverage for notification_capture_records
```

**Structure Decision**: All work stays inside the existing Android app (`android/`). No bridge, Go, or manifest changes. The scan loop lives in `NotificationCaptureService` because `getActiveNotifications()` is only valid there; `ScanCoordinator` is the UI-facing seam (testable without a service) and the identity registry is the shared FR-003 guard used by both scan and real-time paths.

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

None.
