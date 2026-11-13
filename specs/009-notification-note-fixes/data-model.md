# Data Model: 009 Notification Note Fixes

**Date**: 2026-11-12 | **Feature**: 009-notification-note-fixes

No Room schema version bump. Captured events already have the columns this feature reads. New durable state is two DataStore preference keys.

## 1. Captured event (existing)

Source of truth: `normalized_transactions` + `delivery_records` + parent `raw_events`.

| Field | Location | Role in this feature |
|---|---|---|
| `id` | `normalized_transactions` | Per-event notification id / deep-link extra |
| `type` | `normalized_transactions` | `income` \| `expense` — same note field either way |
| `userMemo` | `normalized_transactions` | Blank/null → eligible for capture prompt and in-window missing-note leftover |
| `memoSyncState` | `normalized_transactions` | `pending` counts as unsynced leftover |
| `assignmentSyncState` | `normalized_transactions` | `pending` counts as unsynced leftover |
| `state` | `delivery_records` | Not `sent` (`queued`, `failed`, `sending`, `held`) counts as unsynced leftover |
| `capturedAt` | `raw_events` | Window start for missing-note leftover (not `txAt`) |

**Validation (unchanged):** memo max length 200 (`EventActions.MAX_MEMO_LENGTH`); trim; blank → stored null.

**Not leftover as captured events:** parse-error / `delivery_records` without a `normalized_transactions` row.

## 2. Per-event capture notification (ephemeral)

Not a Room row. Posted by `MemoPromptNotifier`.

| Field | Rule |
|---|---|
| System notification id | `tx.id.hashCode() and 0x7fffffff` |
| Channel | existing `transaction_memo_prompts` |
| Posted when | new captured `NormalizedTransaction` **and** `userMemo` blank **and** `POST_NOTIFICATIONS` granted |
| Content | title/body already privacy-safe; **no** raw bank text |
| Actions | existing RemoteInput “Add note”; **new** body `contentIntent` → event detail |
| Dismiss | `cancel(txId)` on in-app or inline note save; autoCancel on user dismiss |

State transitions: `eligible → posted → dismissed` (save, skip-if-already-noted, user clear, or missing permission so never posted).

## 3. Nightly leftover reminder (ephemeral + DataStore)

### 3a. DataStore keys (new)

| Key | Type | Meaning |
|---|---|---|
| `nightly_last_check_at` | string (ISO-8601 instant) | Previous successful leftover **evaluation** time; missing → first install (missing-note window = all captured events) |
| `nightly_last_evaluated_local_date` | string (`yyyy-MM-dd` in device zone) | Last local date that was evaluated; missing → never; used for once-per-date + missed-date catch-up |

Not synced to Go Money. Per device.

### 3b. Derived leftover snapshot (not stored)

Computed at evaluation and again at tap:

```
unsyncedCount = |{ tx | delivery not sent OR memo pending OR assignment pending }|
missingNoteCount = |{ tx | blank memo AND capturedAt >= lastCheckAt (or all if lastCheckAt absent) }|
leftover = unsyncedCount > 0 OR missingNoteCount > 0
leftoverIds = union of those two id sets
```

| Field | Rule |
|---|---|
| System notification id | fixed `21` (one shade item) |
| Channel | dedicated `nightly_leftover` (low importance) |
| Posted when | leftover is true **and** this local date has not already been evaluated/posted |
| Text | both counts, e.g. `{unsyncedCount} unsynced, {missingNoteCount} missing a note.` Zero on one side still shown. No bank text |
| Tap | re-query `leftoverIds`: 1 → detail, 0 or 2+ → Events list |

State transitions: `idle → evaluated (no post) | posted` once per `targetDate`; user dismiss does not retrigger that date.

## 4. Event editing screen (UI state only)

Compose state on `EventDetailScreen`: `memoDraft` keyed on `tx.id` / `tx.userMemo`. No new persistence. `editingMemo` reveal flag is **removed** — the text field is always shown when `tx != null`.

## Relationships

```text
RawEvent (capturedAt)
  └── NormalizedTransaction (note, sync flags)
        └── DeliveryRecord (delivery state)
              └── (optional) per-event Notification (id from tx.id)
Nightly DataStore ──evaluates──► leftover snapshot ──may post──► nightly Notification
Notification tap ──extras──► MainActivity ──selectedTxId──► EventDetailScreen
```

## Integrity / edge rules

- Saving a note must not change `categoryId`, `destinationAccountId`, `assignmentSyncState`, `currency`, or delivery state (existing `saveMemo`).
- Deleting an event (already cascades from `raw_events`) → tap of a stale notification opens Events list + explanation.
- Nightly evaluation stamps DataStore even when leftover is false (so the date cannot fire again).
