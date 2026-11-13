# Research: Quieter Capture Alerts, Deep-Link to Event, Visible Note Field

**Date**: 2026-11-12 | **Feature**: 009-notification-note-fixes

Spec clarifications (Session 2026-11-12) are closed. Items below resolve **implementation** unknowns only.

## R1: Why two system notifications fire today

- After a successful capture, `NotificationCaptureService` / `SmsCaptureReceiver` `onCaptured` calls `MemoPromptNotifier.show` (title “What was this transaction for?”) **and** `TransactionSyncHelper.enqueueExpedited`.
- `TransactionSyncWorker` then calls `SyncNotifier.maybeNotify`, which posts “Go Money: waiting to sync” (or later “caught up” / “action needed”) on a second channel (`gomoney_sync`).
- `MemoPromptNotifier` has an inline RemoteInput action but **no** `setContentIntent`, so a body tap does not open the event.

**Decision**: Keep `MemoPromptNotifier` as the single per-event capture alert. Make `SyncNotifier.maybeNotify` a no-op (and cancel ids 11/12/13 if still showing) so daytime sync-status **system** notifications stop. Leave `SyncBanner` + Dashboard in-app labels unchanged (FR-004). Do not merge the two channels into one notification.

**Alternatives considered**: Deleting `SyncNotifier` entirely (rejected — larger diff, tests/call sites; a no-op preserves the type for a later settings toggle). Throttling sync notices instead of silencing (rejected — clarification: silence all daytime sync-status system notifications).

## R2: Skip and auto-dismiss the per-event capture alert when a note exists

- `MemoPromptNotifier.cancel(context, txId)` already exists; `MemoReplyReceiver` already calls it after a non-blank inline reply.
- `EventActions.saveMemo` does **not** cancel the shade item today.
- `show()` does not look at `tx.userMemo`.

**Decision**: `show()` posts only when `tx.userMemo` is null/blank. `EventActions.saveMemo` calls `MemoPromptNotifier.cancel` after a successful local write (including blanking a note — cancel is still correct; a blank note is leftover for nightly, but the capture prompt’s job is done). Inline reply keeps its existing cancel. Notification id stays `tx.id.hashCode() and 0x7fffffff`.

**Alternatives considered**: Only cancel on non-blank saves (rejected — spec says dismiss when a note is saved; blanking is still a save from the editor). A second “hasPrompted” Room column (rejected — id mapping from tx id is enough).

## R3: Deep link / tap-through without a NavHost

- `MainActivity` is a single-activity tab host; event detail is `selectedTxId: String?` in `AppTabs`, cleared when leaving the Events tab.
- Manifest activity has only `MAIN`/`LAUNCHER`; default launch mode is `standard`.

**Decision**:
- Add `PendingIntent.getActivity` as `contentIntent` on the per-event notification: `MainActivity`, unique `Uri` `gomoney-capture://event/{txId}` (request-code uniqueness), extras `EXTRA_OPEN_EVENT_ID` + `EXTRA_OPEN_TARGET=detail`.
- Set `android:launchMode="singleTop"` on `MainActivity` and handle `onNewIntent` as well as `onCreate`.
- Intent flags: `NEW_TASK | SINGLE_TOP | CLEAR_TOP`.
- Lift `selectedTxId` (and a one-shot missing-event message) so both first composition and later intents can set Events tab + detail. If `normalizedTransactionDao().byId(id)` is null, `selectedTxId = null`, Events tab, short in-app explanation (Snackbar or one-line text), no crash.
- Nightly reminder uses the same activity extras with `EXTRA_OPEN_TARGET=leftover`; the activity **re-queries leftover ids at tap time** (not the ids frozen in the notification) so 0 leftovers → list, 1 → detail, 2+ → list (FR-016).

**Alternatives considered**: Navigation Compose (rejected — no NavHost today). `taskAffinity` / a dedicated EventActivity (rejected — duplicates catalogs/save wiring). Reading leftover ids only from extras (rejected — spec: leftovers may have been cleared after the reminder was posted).

## R4: How to hit 21:00 local once per date without exact alarms

- WorkManager periodic minimum is 15 minutes and is inexact. Existing drain work is every 2 minutes but piggybacking **fails** the “device was off at 21:00, deliver when possible” edge if the gate is `now >= 21:00` only (a 10:00 wakeup the next morning would wait until that day’s 21:00 and skip the missed date).
- Exact alarms need `SCHEDULE_EXACT_ALARM` / user-facing settings on API 31+.

**Decision**: Unique **one-time** `NightlyLeftoverWorker`, delay = next 21:00 local (`Duration.between(now, next2100)`). Enqueue from `CaptureApplication.onCreate` (`ExistingWorkPolicy.KEEP` unless we need to refresh delay — use `UPDATE` only when replacing a stale delay is required; prefer compute-next-21:00 on every `onCreate` with `REPLACE` if the stored scheduled time is wrong). After each run, enqueue the following day’s 21:00. Evaluation gate (pure, testable):

```
lastDate = stored evaluated local date or null
today = LocalDate.now(zone)
nowTime = LocalTime.now(zone)
if lastDate == null:
  run only if nowTime >= 21:00; stamp today
else:
  targetDate = if (nowTime >= 21:00) today else today.minusDays(1)
  if lastDate >= targetDate: skip
  else: evaluate leftover using lastCheckInstant window, maybe notify, stamp lastDate = targetDate and lastCheckInstant = now
```

First install before 21:00 never notifies. A missed 21:00 that runs at 10:00 next day uses `targetDate = yesterday` (at most one for that date); today’s check still runs at 21:00. Do **not** add `SCHEDULE_EXACT_ALARM`. Delay inaccuracy is the spec’s “standard OS behavior”.

**Alternatives considered**: AlarmManager exact (rejected — new permission / OEM quirks). Only piggyback on `TransactionSyncWorker` (rejected — missed-date delivery is awkward and couples leftover UX to sync). A 15-minute periodic leftover worker (rejected — extra wakeups all day).

## R5: Leftover definitions mapped onto Room

**Unsynced (any age)** — count distinct `NormalizedTransaction.id` where **any** of:

- matching `delivery_records.state` is not `sent` (`queued`, `failed`, `sending`, `held`);
- `memoSyncState == 'pending'`;
- `assignmentSyncState == 'pending'`.

Exclude delivery rows with no normalized transaction (parse-error / `parsed` rows). Those are not “unsynced transactions” and are already excluded from missing-note (spec assumption).

**Missing note (in window)** — `NormalizedTransaction` with blank/null `userMemo` whose `raw_events.capturedAt` ≥ previous `lastCheckInstant` (or all such rows if no previous check — first install). Use `RawEvent.capturedAt`, not `txAt` (bank time can be earlier than capture).

**Reminder text**: one line, both counts, no raw bank text. Example: `3 unsynced, 2 missing a note.` A zero on one side is still shown when the other is non-zero (FR-015). Channel: reuse `transaction_memo_prompts` **or** a dedicated low-importance `nightly_leftover` channel; prefer a dedicated channel so per-event prompts and the daily nudge do not share importance/id space. Fixed notification id (e.g. `21`). Auto-cancel on tap.

**Tap leftover set**: union of unsynced ids and in-window blank-note ids, re-queried at tap time.

**Alternatives considered**: Counting only `pendingForDelivery()` (queued/failed) (rejected — misses `held`, `sending`, and sent-but-pending memo/assignment). Using `txAt` for the window (rejected — not “captured since”). Including parse errors as unsynced (rejected — not captured events).

## R6: Always-visible note field (008 regression)

- 008 moved note editing onto `EventDetailScreen` but kept the two-step: display text + “Add note” / “Edit note” sets `editingMemo`. That is the bug the user reported (spec assumption).
- Income vs expense already share this composable; no type branch.

**Decision**: Always render the `OutlinedTextField` (prefill `tx.userMemo.orEmpty()`, live 200-char cap). Keep Save (and Cancel = revert draft, field stays visible). Keep pending-sync labels. Do not change `EventActions.saveMemo` semantics except R2 cancel. Events list rows stay display-only (`Note: …` + waiting-to-sync).

**Alternatives considered**: Auto-save on back (rejected — existing explicit Save is the contract; smaller, safer). Separate income/expense layouts (rejected — spec: same field).

## R7: Permissions, privacy, capture resilience

- `POST_NOTIFICATIONS` already requested in `MainActivity.requestSyncNotifications`. `canPost` already used in both notifiers. Capture `onCaptured` must still succeed if `show()` returns early (already true).
- No raw bank body in prompt or nightly text (already true for `MemoPromptNotifier`; nightly counts only).
- No new manifest permissions besides leaving `POST_NOTIFICATIONS` as-is. `RECEIVE_BOOT_COMPLETED` not required: `CaptureApplication.onCreate` re-enqueues WorkManager.

**Decision**: Keep existing permission UX. Nightly and per-event `show`/`notify` no-op when permission denied.

## R8: Testing strategy

**Decision**: JVM/Robolectric tests, no new Espresso suite in this change:

- `NightlyLeftoverTest` — first-install window; blank notes older than last check excluded; unsynced any age included; both counts; skip same local date; missed-date `targetDate = yesterday`; tap routing 0/1/N.
- `MemoPromptNotifier` skip-if-noted + notification id cancel (Robolectric `NotificationManager`).
- `SyncNotifier.maybeNotify` posts nothing (existing worker tests should not expect shade sync banners).
- Note-field: if a tiny helper `noteEditorVisible = true` is extracted, unit-test it; otherwise quickstart + converge on USB phone.

Device USB (operator note) is the manual path in [quickstart.md](./quickstart.md).

**Alternatives considered**: Instrumenting notification shade on CI (rejected — no device farm in this repo’s Gradle test task).
