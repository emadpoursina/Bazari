# Contract: Capture-time and daytime notification cadence

**Feature**: 009-notification-note-fixes | **Date**: 2026-11-12

## 1. Per-event capture alert (`MemoPromptNotifier`)

**Callers (unchanged):** `NotificationCaptureService.onCaptured`, `SmsCaptureReceiver.onCaptured`.

| Rule | Value |
|---|---|
| Posts | At most one system notification per captured `NormalizedTransaction.id` |
| Purpose | Prompt to add/edit a note (“What was this transaction for?”) — not sync status |
| Skip | Do not post if `userMemo` is already non-blank |
| Permission | If `POST_NOTIFICATIONS` denied, return without throwing; capture still persisted |
| Privacy | No raw bank notification body / title / SMS text in the shade item |
| Dismiss | `cancel(txId)` after successful `EventActions.saveMemo` or inline `MemoReplyReceiver` save |

Burst capture: N events → at most N capture alerts, never N extra sync-status alerts.

## 2. Daytime sync-status system notifications (`SyncNotifier`)

| Rule | Value |
|---|---|
| `maybeNotify` | MUST NOT post waiting-to-sync, caught-up, or action-needed shade items |
| Existing shade items | Cancel notification ids 11 / 12 / 13 when the no-op path runs (or once at worker start) so old banners do not linger |
| In-app | `SyncBanner` on Dashboard and row/detail “waiting to sync” labels **unchanged** |

`TransactionSyncWorker` may keep calling `maybeNotify` if it is a no-op, or drop the call; both satisfy FR-003.

## 3. Invariants vs nightly

Until the nightly leftover worker runs for a local date, **no** other capture-app system notification may be used for sync-queue status. The nightly reminder is specified in [nightly-leftover.md](./nightly-leftover.md).
