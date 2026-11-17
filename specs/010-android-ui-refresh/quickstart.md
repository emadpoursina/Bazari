# Quickstart: Android UI refresh validation

**Feature**: 010-android-ui-refresh | **Date**: 2026-11-17

Validation/run guide only. Implementation steps live in `tasks.md` (not created by plan).

UI checks **must** use the **physical phone attached to this Mac with a USB-C cable** and `adb`. Do not treat an emulator as the verification surface for this feature.

## Prerequisites

- Repo worktree on git branch `android-ui-ux` (feature dir `specs/010-android-ui-refresh`).
- JDK 17, Android SDK, `android/gradlew` as used by `make android-gradle-test`.
- USB debugging enabled on the phone; cable connected.
- Go Money / android-bridge **not** required for layout walks; required only for SC-008 send/retry checks if you exercise a live queue.

## 0. Confirm the USB device

```bash
adb devices
```

Expect the phone listed as `device` (not `unauthorized` or empty). If empty, fix USB debugging before any UI claim.

Optional once, on **that** device only (then restore):

```bash
adb shell wm size   # record original
# adb shell wm size 1080x1920   # only if you need a ~360 dp width check on this phone
# adb shell wm size reset
```

Do not switch to an AVD to satisfy this guide.

## 1. Unit tests (mappers + regressions)

```bash
make android-gradle-test
```

Expect existing `com.gomoney.capture` tests green, plus new tests for amount grouping, status chips, day grouping, and Home next-step priority. These do **not** replace the device walk.

## 2. Install debug build on the phone

```bash
cd android && ./gradlew installDebug
adb shell am start -n com.gomoney.capture/.ui.MainActivity
```

## 3. Light and dark

```bash
adb shell cmd uimode night no
# walk destinations, then:
adb shell cmd uimode night yes
```

Expect readable body text and amounts in both (SC-006). Restore the user’s night mode when done.

## 4. Walk (physical device)

Contracts: [navigation-chrome.md](./contracts/navigation-chrome.md), [home-health.md](./contracts/home-health.md), [events-inbox.md](./contracts/events-inbox.md), [event-detail.md](./contracts/event-detail.md), [sources-and-settings.md](./contracts/sources-and-settings.md), [visual-system.md](./contracts/visual-system.md).

| Step | Expect |
|---|---|
| Bottom bar | Four items: Home, Events, Sources, Settings; icon + label |
| Home healthy | Today / waiting / failed; recent rows; no charts or spend totals; calm if all-clear |
| Home blocked | One primary control (grant access or Settings), not jargon-only |
| Events | Day headers; formatted amounts; no bulk bar until selection |
| Long-press row | Selection actions appear; delete still confirms |
| Overflow | Clear processed / Clear all stay secondary + confirm |
| Scan | Control present when empty; banner above list when running/blocked |
| Open a row | Detail identity first; one assignment confirm; note save/cancel; no parser internals |
| Failed row | Retry on list and on detail |
| Sources empty | Headline + Add source |
| Settings | Five labeled groups; permission control opens system screen |
| Nested Back | Returns to list; switching tabs closes nested |
| Notification leftover tap | Same routing as 009 (detail or list + dismissible miss) |

## 5. Regression (behavior, not look)

With capture already working on this phone: new events still appear, retry still queues, notes still save via `EventActions`, defaults still stamp only new captures, scan still respects notification access (SC-008). If a live bridge is down, still confirm **queued locally** and UI status chips — do not require production SSH.

## 6. Stop conditions

- `adb devices` does not show the USB phone → **blocked**, do not pass UI on an emulator instead.
- Contrast or clipping on the physical screen → fail SC-005/SC-006.
- Any new missed/duplicated capture in the walk → fail SC-008; UI restyle must roll back the behavioral change.
