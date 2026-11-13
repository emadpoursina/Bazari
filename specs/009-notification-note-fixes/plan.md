# Implementation Plan: Quieter Capture Alerts, Deep-Link to Event, Visible Note Field

**Branch**: `mobile-tx-notifications` | **Date**: 2026-11-12 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/009-notification-note-fixes/spec.md`

**Note**: `setup-plan.sh --json` reports `BRANCH=009-notification-note-fixes` (derived from the feature directory name); the actual checked-out git branch is `mobile-tx-notifications`, which is what this plan uses.

## Summary

Android capture-app only. Four coordinated fixes, no Go Money server/web/parser changes:

1. **Cadence (P1)**: Keep the existing per-event “What was this transaction for?” system notification; stop posting daytime sync-status system notifications (`SyncNotifier` waiting-to-sync / caught-up / action-needed). In-app Dashboard `SyncBanner` labels stay. Skip or auto-dismiss a capture alert when that event already has a note (or a note is saved).
2. **Tap-through (P1)**: Put a `contentIntent` on the per-event notification so a body tap opens `MainActivity` on that event’s `EventDetailScreen` (same surface as **Set category**). Missing id → Events list + a brief explanation, no crash. `singleTop` + `onNewIntent` so an already-open app switches screens.
3. **Note field (P1)**: On `EventDetailScreen`, show the note `OutlinedTextField` immediately (income and expense). Remove the “Add note” / “Edit note” reveal step. Save still goes through `EventActions.saveMemo` (200-char cap, pending sync).
4. **Nightly leftover (P2)**: At 21:00 device-local, once per local calendar date, evaluate leftover work (unsynced captured events of any age **or** blank-note events captured since the previous nightly check). If either count is non-zero, post **one** reminder whose text includes both counts (zeros allowed on one side). Tap: one leftover → detail; several or none left → Events list. Missed 21:00 follows ordinary WorkManager delay; still at most one evaluation/post per local date.

No Room schema change. Nightly bookkeeping is two DataStore keys. No new permissions (`SCHEDULE_EXACT_ALARM` not added). Validation later is **converge only** (critic/tester skipped). A USB-connected phone is available for device checks in implement/converge, not during this plan.

## Technical Context

**Language/Version**: Kotlin 2.x, Jetpack Compose Material 3, minSdk 26 / compileSdk 35 / targetSdk 35

**Primary Dependencies**: Existing Android stack — Room, DataStore Preferences, WorkManager, NotificationManager / RemoteInput; `MemoPromptNotifier`, `MemoReplyReceiver`, `SyncNotifier`, `SyncEngine`/`TransactionSyncWorker`, `EventActions.saveMemo`, `EventDetailScreen`, `MainActivity`

**Storage**: Room `gomoney-capture.db` unchanged (read existing `normalized_transactions`, `delivery_records`, `raw_events.capturedAt`). Nightly last-check instant + last-evaluated local date in DataStore (same `SettingsRepository` / preferences store pattern as 008 defaults)

**Testing**: JVM unit tests via `make android-gradle-test` (`cd android && ./gradlew test`), Robolectric + in-memory Room as in `android/app/src/test/kotlin/com/gomoney/capture/`. Pure leftover/cadence helpers without Compose. Device USB checks are for quickstart / converge, not a new instrumented suite in this plan

**Target Platform**: Android 8.0+ (API 26) capture app (“Go Money Capture”)

**Project Type**: Mobile (Android) app only — server, bridge HTTP, web app, and parsers untouched

**Performance Goals**: Capture path stays one extra boolean check before `MemoPromptNotifier.show`; nightly check is a handful of Room counts once per day; tap-through must land on the editing screen within 3s of foreground (SC-002)

**Constraints**: No raw bank notification body in capture-app alerts (existing FR-028); `POST_NOTIFICATIONS` denial must not crash capture; note cap stays `EventActions.MAX_MEMO_LENGTH` (200); in-app “waiting to sync” row/detail labels remain; no user-facing nightly time picker; no exact-alarm permission; no Room migration

**Scale/Scope**: ~8–12 Kotlin files in `android/app` (notifier, worker, MainActivity, EventDetailScreen, EventActions, SettingsRepository, tests). No new Gradle dependencies

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- `.specify/memory/constitution.md` is an **unfilled template** (placeholder principle names, no ratified version) — no concrete gates exist to violate. Same finding as features 007/008.
- Design stays inside the existing Android capture app: local-first memo writes, existing notification channels, WorkManager already initialized in `CaptureApplication`. No new libraries, no new dangerous permissions, no schema migration.
- **Result: PASS** (vacuously — no enforceable gates). Re-checked after Phase 1 design: still PASS; Complexity Tracking unused.

## Project Structure

### Documentation (this feature)

```text
specs/009-notification-note-fixes/
├── plan.md              # This file (/speckit.plan output)
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
│   ├── notification-cadence.md
│   ├── notification-tap-navigation.md
│   ├── note-field-ui.md
│   └── nightly-leftover.md
└── tasks.md             # Phase 2 output (/speckit.tasks — NOT created here)
```

### Source Code (repository root)

```text
android/app/src/main/kotlin/com/gomoney/capture/
├── CaptureApplication.kt          # also enqueue unique nightly leftover work
├── ui/
│   ├── MainActivity.kt            # singleTop, consume open-event extras, missing-event hint
│   ├── EventDetailScreen.kt       # always-visible note field (no reveal tap)
│   └── EventActions.kt            # cancel per-event capture notification on saveMemo
├── sync/
│   ├── MemoPromptNotifier.kt      # contentIntent; skip show when note already present
│   ├── MemoReplyReceiver.kt       # already cancels; keep
│   ├── SyncNotifier.kt            # maybeNotify becomes a no-op; cancel leftover sync shade items
│   ├── TransactionSyncWorker.kt   # stop calling SyncNotifier (or call no-op)
│   ├── NightlyLeftover.kt         # NEW: leftover counts + once-per-date gate (pure)
│   └── NightlyLeftoverWorker.kt   # NEW: WorkManager one-time, delay to next 21:00 local
├── storage/
│   ├── SettingsRepository.kt      # last nightly check instant + last evaluated local date
│   └── Daos.kt                    # optional count queries; or reuse existing lists in the evaluator
android/app/src/main/AndroidManifest.xml  # launchMode singleTop on MainActivity
android/app/src/test/kotlin/com/gomoney/capture/
├── sync/NightlyLeftoverTest.kt           # NEW: window, counts, once-per-date, tap routing
├── sync/MemoPromptNotifierTest.kt        # NEW or extend: skip-if-noted, cancel id
└── ui/EventDetailNoteVisibleTest.kt      # NEW: always-visible field behavior helper if extracted
```

**Structure Decision**: Stay in the existing `ui` / `sync` / `storage` split. Capture entry points (`NotificationCaptureService`, `SmsCaptureReceiver`) keep calling `MemoPromptNotifier.show`; skip/dismiss logic lives inside the notifier + `saveMemo`. Nightly work is a sibling of `TransactionSyncWorker`, not a piggyback on the 2-minute drain (see research R4). Go backend and `frontend/` are out of scope.

## Complexity Tracking

> No constitution violations — table not needed.
