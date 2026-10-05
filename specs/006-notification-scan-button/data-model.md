# Data Model: Notification Scan Button

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md) | Research: [research.md](./research.md)

This feature extends `gomoney-capture.db` (Room schema **v4 → v5**) with one identity table. `Event` storage itself is unchanged; scan results are transient UI state, never persisted (spec assumption). Raw message text stays local.

## Entities

### 1. NotificationCaptureRecord (`notification_capture_records`) — NEW

The per-notification identity guard that enforces FR-003 (at most one event per notification, across scans, real-time posts, and in-place content updates — clarification Q1).

| Column | Type | Rules |
|---|---|---|
| `notification_key` | TEXT PK | `StatusBarNotification.key` = `package\|id\|tag\|userId`. Stable for the life of one notification, including in-place updates. Never null, never derived from content. |
| `event_id` | TEXT NULL | `raw_events.id` of the event this notification produced. NULL while `pending`. |
| `state` | TEXT | `pending` \| `recorded` (validation: exactly these two values) |
| `updated_at` | TEXT | ISO-8601 with offset; refreshed on every claim/commit |

Validation / transitions:

| Event | Effect |
|---|---|
| **claim(key)** — no row | insert (`state='pending'`, `event_id=NULL`, `updated_at=now`) → claimant may process |
| **claim(key)** — row `recorded` | rejected → notification is **already-recorded**: skip, count as skipped, do not run the pipeline |
| **claim(key)** — row `pending` younger than 60 s | rejected → another attempt owns it (scan vs. real-time race guard) |
| **claim(key)** — row `pending` older than 60 s | stale: take over (update `updated_at`) → claimant may process (crash/interruption recovery) |
| **commit(key, eventId)** | `state='recorded'`, `event_id=eventId` — terminal; the notification may never produce another event |
| **release(key)** | delete row — pipeline persisted nothing (`IGNORED_NO_SOURCE`, `IGNORED_DISABLED`, no extractable text); the notification is re-examined by future scans/real-time posts |

No foreign key to `raw_events` (see research R8): `MaintenanceRepository.clearAll()` deletes all rows explicitly; `clearProcessed()` leaves them untouched.

Relationships: `event_id` points at an existing `raw_events.id` whenever `state='recorded'` (informational; not enforced by FK).

### 2. Event (existing `raw_events` / `normalized_transactions` / `delivery_records` — unchanged shape)

Scan-created events are **identical** to real-time events (clarification Q2): same tables, same columns, no capture-source flag, no badge.

| Field | Value on scan capture |
|---|---|
| `RawEvent.id` | fresh UUID |
| `RawEvent.source` | `"notification"` (same as real-time) |
| `RawEvent.sourcePackage` | `sbn.packageName` |
| `RawEvent.title` / `text` | extraction unchanged: `EXTRA_TEXT` vs `EXTRA_BIG_TEXT`, longest non-blank wins |
| `RawEvent.postedAt` | `safePostedAt(sbn.postTime)` (notification post time, not scan time) |
| `RawEvent.capturedAt` | `OffsetDateTime.now()` at scan processing |
| `NormalizedTransaction` / `DeliveryRecord` | produced by the unchanged `CapturePipeline` (queued / held / duplicate / parse-error rules) |

Validation from requirements: the scan never updates or deletes existing rows (FR-010); new rows appear through the normal Room Flows, so the Events list refreshes without restart or manual refresh (FR-009, acceptance 4).

### 3. ScanUiState (transient — `StateFlow` in `ScanCoordinator`, NOT a table)

States and transitions:

```text
Idle ──press (permission OK, no scan running)──▶ Running
Running ──all notifications processed──▶ Result(examined, added, skipped)   [added == 0 → "nothing new" wording]
Running ──press with permission missing──▶ Blocked (never reaches Running; press-time check)
Idle/Result ──press without permission──▶ Blocked
Blocked/Unavailable ──press──▶ re-check → Running or Blocked again
any ──new scan starts──▶ Running            (previous Result/Banner cleared — FR-005)
Result/Blocked/Unavailable ──user leaves Events tab──▶ Idle (consumed; not shown on return — FR-005)
Running ──user leaves Events tab──▶ scan continues; if it completes while the tab is not
                                     visible it is consumed as Idle (banner cleared),
                                     but created events remain in the list (FR-009)
```

| State | Fields | Banner text |
|---|---|---|
| `Idle` | — | nothing shown |
| `Running` | — | "Scanning active notifications…"; scan button disabled with progress indicator |
| `Result` | `examined: Int`, `added: Int`, `skipped: Int` | `Examined X · Added Y · Skipped Z`, or `No new events found (X examined)` when `added == 0`, or `No active notifications` when `examined == 0` |
| `Blocked` | — | Notification-read access missing + button opening the system notification-access screen (FR-006, Q5) |
| `Unavailable` | — | Listener not connected: same guidance button + "try again" hint (research R9) |

Validation: `added + skipped == examined` for every `Result` (research R4); `added ≤ examined`; `examined ≥ 0`.

## Counting rules (one scan)

`examined` = `getActiveNotifications().size`.

Per notification, after the identity guard (research R2, R4):

| Condition | Counted as |
|---|---|
| key already `recorded` | skipped (already-recorded, per Q3) |
| no extractable text | skipped (+ `release`, re-examinable later) |
| pipeline `QUEUED`, `HELD`, `QUEUED_AS_KNOWN_DUPLICATE` | **added** (+ `commit`) |
| pipeline `DUPLICATE_SHORT_CIRCUITED`, `RETAINED_PARSE_ERROR`, `IGNORED_NO_SOURCE`, `IGNORED_DISABLED` | skipped (+ `commit` for persisted outcomes, `release` for `IGNORED_*`) |

`added` and `skipped` accumulate across the loop; nothing is written anywhere else.

## Relationships

```text
StatusBarNotification.key ──1:1── NotificationCaptureRecord ──(event_id)──▶ RawEvent ──1:1── NormalizedTransaction ──1:0..1── DeliveryRecord
                                                                             └──1:0..1── parse-error DeliveryRecord (no NormalizedTransaction)
ScanCoordinator.ScanUiState ──(transient, consumed by)──▶ EventsScreen banner
```
