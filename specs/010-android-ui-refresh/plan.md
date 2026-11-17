# Implementation Plan: Android Capture App UI Refresh

**Branch**: `android-ui-ux` | **Date**: 2026-11-17 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/010-android-ui-refresh/spec.md`

**Note**: `setup-plan.sh --json` reports `BRANCH=010-android-ui-refresh` (derived from the feature directory name); the actual checked-out git branch is `android-ui-ux`, which is what this plan uses. Base branch is `feature/bazari`. `master` is read-only.

Clarify answers from session 2026-11-17 are already encoded in the spec. This plan does not reopen them.

## Summary

Restyle and restructure the Android capture companion (`android/app`, package `com.gomoney.capture`) so it reads as a **health + inbox + send** product, not a default Material demo and not a spending dashboard.

Technical approach (Compose Material 3 only; no Room schema change; no capture/parse/delivery rule changes):

1. **Chrome**: Replace text-only `TabRow` with a four-item `NavigationBar` (Home, Events, Sources, Settings). Keep the existing single-activity tab index and nested back behavior.
2. **Home**: Restyle `DashboardScreen` as capture health (reachable / not reachable / not set up; blocked permission) plus today’s count, waiting, failed, and a short recent list. One in-place next step — no wizard, no charts.
3. **Events**: Day-grouped inbox rows with formatted amounts and status chips. Hide bulk actions until selection mode. Scan stays primary; scan feedback stays a banner. Empty state with one action.
4. **Event detail (first-class redesign)**: Identity header → send assignment (one confirm) → note (explicit save/cancel) → retry. Hide parser internals unless debug. Wire the existing unused `DebugEventScreen` behind debug mode.
5. **Sources / Settings**: Same behaviors, quieter grouping, empty states, permission actions that open system settings.
6. **Visual system**: System light/dark, shared type/spacing/chips, income vs expense by sign/label **and** color, RTL-safe `start`/`end` layouts, 360 dp primary controls visible.

Verification: JVM unit tests for extracted mappers; **physical USB-C device over `adb`** for UI walks. Emulator-only verification is not acceptable.

## Technical Context

**Language/Version**: Kotlin 2.1, Jetpack Compose (BOM 2024.12.01), Material 3, minSdk 26 / compileSdk 35 / targetSdk 35

**Primary Dependencies**: Existing Android stack — Compose Material 3 (`NavigationBar`, chips, theme), Room, DataStore, WorkManager, ViewModels already in `com.gomoney.capture.ui`. No new Gradle libraries unless Material Icons Core is missing from the BOM graph (prefer `Icons.Filled` already on the classpath).

**Storage**: Room `gomoney-capture.db` **unchanged**. Display formatting reads existing `amountMinor`, `currency`, `type`, delivery `state`, notes, catalogs. No migration.

**Testing**: JVM unit tests via `make android-gradle-test` (`cd android && ./gradlew test`), Robolectric + existing `android/app/src/test/kotlin/com/gomoney/capture/ui/` helpers. Extract pure functions for amount format, status chips, day grouping, Home next-step, selection-mode action visibility. **UI acceptance on a USB-connected physical phone** (`adb devices`, install debug APK, light/dark, all destinations). Do not treat an emulator as the verification surface.

**Target Platform**: Physical Android 8.0+ phone running “Go Money Capture”, connected to this Mac over USB-C.

**Project Type**: Mobile (Android) app only — Go Money server, `cmd/android-bridge`, web frontend, and parsers untouched except display of existing fields.

**Performance Goals**: Home paints health + recent list from already-observed Room flows (same 500-row cap as today). Events grouping is an in-memory partition of the existing 200-row list. Detail remains one composition. No extra network on tab switch beyond current catalog/connection refreshes.

**Constraints**:
- FR-020: no budgets, charts, totals dashboards, widgets, or ledger clone.
- English copy this pass; layouts must not clip if strings grow or RTL is added later.
- No capture/parse/dedup/delivery behavior change; `EventActions` save/retry/clear semantics stay.
- Privacy: sanitized copy only on default screens; raw text only on debug.
- Primary tap targets usable one-handed; no horizontal scroll of primary controls at ~360 dp.
- Destructive actions keep existing confirmation dialogs.
- Physical-device UI checks over USB/`adb` (user constraint).

**Scale/Scope**: All current Compose destinations (~`MainActivity`, Home/Dashboard, Events, Event detail, Sources, Source editor, Settings, Debug, theme). About 8–15 Kotlin files plus strings/theme XML and unit tests. Not a new navigation library, not Navigation-Compose unless the current index model becomes unmaintainable (research: keep index).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- `.specify/memory/constitution.md` is an **unfilled template** (placeholder principle names, no ratified version) — no concrete gates exist to violate. Same finding as features 007–009.
- Design stays inside the existing Android capture app: Compose UI + display helpers. No new dangerous permissions, no schema migration, no analytics SDKs, no Go Money API change.
- Privacy/redaction rules from 001 still apply (no raw bank text on default UI).
- **Result: PASS** (vacuously — no enforceable gates). Re-checked after Phase 1 design: still PASS; Complexity Tracking unused.

## Project Structure

### Documentation (this feature)

```text
specs/010-android-ui-refresh/
├── plan.md              # This file (/speckit.plan command output)
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
│   ├── navigation-chrome.md
│   ├── visual-system.md
│   ├── home-health.md
│   ├── events-inbox.md
│   ├── event-detail.md
│   └── sources-and-settings.md
└── tasks.md             # Phase 2 output (/speckit.tasks — NOT created here)
```

### Source Code (repository root)

```text
android/app/src/main/kotlin/com/gomoney/capture/
├── ui/
│   ├── MainActivity.kt              # NavigationBar; tab labels Home/Events/Sources/Settings
│   ├── DashboardScreen.kt           # Home health + recent list + one next step
│   ├── DashboardViewModel.kt        # Expose permission/blocked + recent preview (existing counts)
│   ├── EventsScreen.kt              # Day groups, selection mode, overflow, scan banner
│   ├── EventDetailScreen.kt         # First-class identity → assignment → note → send
│   ├── DebugEventScreen.kt          # Internals only when debug on (wire from detail)
│   ├── SourcesScreen.kt             # Empty state, row chrome, unparsed section
│   ├── SourceEditorScreen.kt        # Titled editor; formatted test amount
│   ├── SettingsScreen.kt            # Labeled groups + permission actions
│   ├── EventActions.kt              # Unchanged semantics (callers only)
│   └── theme/                       # NEW: CaptureTheme, color/type/status tokens
│       ├── CaptureTheme.kt
│       ├── AmountFormat.kt          # grouping + optional currency code
│       └── StatusPresentation.kt    # internal state → chip label/tone
android/app/src/main/res/values/
├── strings.xml                      # English user-facing copy (chips, Home, empty states)
├── themes.xml                       # DayNight / system light-dark, not Light-only
android/app/src/test/kotlin/com/gomoney/capture/ui/
├── AmountFormatTest.kt              # NEW
├── StatusPresentationTest.kt        # NEW
├── EventDayGroupingTest.kt          # NEW
├── HomeNextStepTest.kt              # NEW
└── (existing routing/gating tests unchanged)
```

**Structure Decision**: Stay in the existing single-module `android/app` `ui/` package. Add a small `ui/theme` folder for theme + pure presentation helpers so list/detail/home share one mapping. Do not introduce Navigation-Compose, a second Activity, or server packages. Go backend and `frontend/` are out of scope.

## Complexity Tracking

> No constitution violations — table not needed.

## Phase 0 / Phase 1

Research decisions: [research.md](./research.md). Display-only entities: [data-model.md](./data-model.md). UI contracts: [contracts/](./contracts/). Device verification: [quickstart.md](./quickstart.md).
