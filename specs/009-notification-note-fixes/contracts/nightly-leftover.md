# Contract: Nightly leftover reminder

**Feature**: 009-notification-note-fixes | **Date**: 2026-11-12

Clock: **21:00 in the device default timezone**. No user time picker.

## 1. Schedule

- Unique WorkManager one-time work, initial delay to the next 21:00 local.
- Re-enqueue next 21:00 from `CaptureApplication.onCreate` and after each worker run.
- No `SCHEDULE_EXACT_ALARM`. OS delay is acceptable.

## 2. Once-per-local-date gate

See research R4. Stamp `nightly_last_evaluated_local_date` and `nightly_last_check_at` after an evaluation **whether or not** a notification is posted. Never post twice for the same local date.

First install before 21:00: do not evaluate. Missed 21:00 delivered later the same night or next morning counts as that **target** local date only.

## 3. Leftover predicate

Leftover is true if **either** count is > 0:

| Count | Definition |
|---|---|
| Unsynced | Distinct captured transactions (`normalized_transactions`) whose delivery state is not `sent`, **or** `memoSyncState='pending'`, **or** `assignmentSyncState='pending'`. Any capture age. Exclude parse-error rows with no normalized tx. |
| Missing note | Distinct captured transactions with blank/null `userMemo` and `raw_events.capturedAt` ≥ previous `nightly_last_check_at`. If that key is absent (first evaluation), all blank-note captured events count. Older blank notes after a check has run do **not** count by themselves. |

## 4. Notification

| Rule | Value |
|---|---|
| If leftover | Exactly one shade item for that local date |
| If not leftover | Post none |
| Text | Both counts, zeros allowed on one side; no raw bank text |
| Id | Fixed (e.g. 21) |
| Permission denied | Skip post; still stamp evaluated date so we do not retry-spam when permission returns the same night (optional: stamp anyway — **do stamp** to match “at most one evaluation per date”) |

## 5. Tap

`EXTRA_OPEN_TARGET=leftover`. Re-query leftover id union at tap time: 1 → detail, else Events list ([notification-tap-navigation.md](./notification-tap-navigation.md)).
