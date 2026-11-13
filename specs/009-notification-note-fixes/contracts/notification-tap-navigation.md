# Contract: Notification tap → event editing or Events list

**Feature**: 009-notification-note-fixes | **Date**: 2026-11-12

Host: `MainActivity` (`singleTop`). Event detail is the existing `EventDetailScreen` (same as **Set category**).

## 1. Intent extras

| Extra | Type | Meaning |
|---|---|---|
| `EXTRA_OPEN_EVENT_ID` | String | Transaction id for a per-event capture alert |
| `EXTRA_OPEN_TARGET` | String | `detail` (per-event) or `leftover` (nightly reminder) |

Data URI (PendingIntent uniqueness): `gomoney-capture://event/{id}` for per-event; `gomoney-capture://leftover` for nightly.

Flags: `FLAG_ACTIVITY_NEW_TASK \| SINGLE_TOP \| CLEAR_TOP`. Handle `onCreate` and `onNewIntent`.

## 2. Per-event body tap (`EXTRA_OPEN_TARGET=detail`)

1. Select Events tab.
2. If `normalizedTransactionDao().byId(id)` exists → `selectedTxId = id` (editing screen).
3. Else → `selectedTxId = null`, Events list, brief explanation that the event is gone. No crash.

Must work from process-cold start and from an already-open different tab/screen (SC-002: editing screen within 3s of foreground).

Inline RemoteInput action is unchanged and does **not** need to open the activity.

## 3. Nightly reminder tap (`EXTRA_OPEN_TARGET=leftover`)

Re-query leftover ids **now** (see [nightly-leftover.md](./nightly-leftover.md) snapshot):

| Leftover id count | Destination |
|---|---|
| 1 | That event’s `EventDetailScreen` |
| 0 or ≥ 2 | Events list (`selectedTxId = null`) |

Do not trust leftover ids frozen into the notification at 21:00.

## 4. Missing / malformed extras

Treat as a normal launcher open (Dashboard or last UI). Do not crash.
