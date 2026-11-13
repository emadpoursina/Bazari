# Quickstart: 009 Notification Note Fixes

Android capture app only. JVM tests prove leftover/cadence logic; a USB-connected phone proves shade + tap + note field.

## Prerequisites

- Repo root; branch `mobile-tx-notifications`.
- JDK capable of `android/gradlew test`.
- Physical Android phone with USB debugging (operator: already connected) for sections B–D.
- Capture sources already set up on the phone; Go Money bridge optional except for unsynced leftover.

## A. JVM tests (CI-shaped)

```bash
cd android && ./gradlew test
```

Or `make android-gradle-test` from repo root if that target exists.

Expect new/updated tests under `android/app/src/test/kotlin/com/gomoney/capture/sync/` (nightly gate, leftover counts, skip-if-noted, `SyncNotifier` posts nothing).

## B. One capture alert, no daytime sync shade (US1, SC-001, SC-007)

1. Grant notification permission. Disconnect the bridge **or** leave the phone offline so a new capture stays queued.
2. Trigger one bank/payment notification that the capture app records.
3. Shade: **exactly one** Go Money Capture item about the new transaction (note prompt). **No** “waiting to sync” / “caught up” / “action needed”.
4. Capture a second distinct event (income + expense if possible): still **one alert per event**, no sync banners.
5. Open the event, save a note: that event’s capture alert disappears. Capture with a note already present (if you can pre-fill) must not post a prompt.

If `POST_NOTIFICATIONS` is denied, capture still stores the row and does not crash.

## C. Tap opens Set-category / note screen (US2, US3, SC-002, SC-003, SC-004)

1. With a capture alert showing, tap the **body** (not only the inline reply).
2. App foregrounds on that event’s editing screen within ~3s. Amount, category/account, and a **visible note text field** (no “Add note” tap) are there for both income and expense.
3. Type a short note, Save. Reopen: note still there. Events row still shows `Note: …` (and waiting-to-sync while pending).
4. Delete/clear that event, tap a leftover capture notification for its id: Events list + a short “event is gone” explanation, no crash.
5. With the app already open on Dashboard, tap another capture alert: switches to that event’s editor.

## D. Nightly leftover (US4, SC-005, SC-006)

Device clock: temporarily set local time to **20:59** then wait, **or** trigger the leftover worker from a debug path if implement adds one. Production path is 21:00 local.

1. Leave ≥1 unsynced captured event and/or ≥1 same-window event with a blank note. At 21:00: **one** reminder whose text includes both counts (a zero on one side is OK).
2. Clear all leftover before 21:00: **no** reminder.
3. Do not get a second reminder the same local date.
4. Tap with one leftover → editor; tap with several (or after clearing) → Events list.

If the phone was off over 21:00, the reminder may arrive when WorkManager next runs; still at most one for that local date.

## Out of scope here

Go Money web UI, parsers, bridge protocol, and moving the 21:00 time into Settings.
