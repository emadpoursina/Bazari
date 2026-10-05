# Contract: Per-Notification Identity (at most one event per notification)

**Feature**: Notification Scan Button | **Plan**: [plan.md](./plan.md) | **Protocol**: [scan-coordination.md](./scan-coordination.md)

Internal Android contract shared by **both** capture entry points: the real-time `onNotificationPosted` path and the manual scan loop. Implements FR-003 / clarification Q1.

## 1. Identity

- **Identity key** = `StatusBarNotification.key` (`package|id|tag|userId`).
- Content is NOT part of the identity: an in-place update of the same notification keeps its key, so changed content can never yield a second event.
- One key ↔ at most one `RawEvent` ever (`notification_capture_records.state = 'recorded'`).

## 2. Protocol (identical on both paths)

```text
claim(key)
  ├─ row missing            → claim granted (insert state='pending')
  ├─ row 'recorded'         → REJECT: skip silently (already-recorded → counts as skipped on scans)
  ├─ row 'pending', < 60 s  → REJECT: skip (another attempt owns it; race guard)
  └─ row 'pending', ≥ 60 s  → take over (refresh updated_at) — crash/interruption recovery

… extract text (shared helper) → CaptureGate → CapturePipeline.process(RawEvent) …

outcome ∈ {QUEUED, HELD, QUEUED_AS_KNOWN_DUPLICATE, DUPLICATE_SHORT_CIRCUITED, RETAINED_PARSE_ERROR}
  → commit(key, rawEventId)      // event persisted: terminal, never processed again
outcome ∈ {IGNORED_NO_SOURCE, IGNORED_DISABLED}   → release(key)   // nothing persisted: re-examinable
no extractable text                                → release(key)
```

Rules:

1. `commit` is terminal; there is no un-commit path short of `MaintenanceRepository.clearAll()` (which clears the whole identity table).
2. `release` MUST NOT be called after a persisted outcome — that would reopen an already-recorded notification (FR-003 violation).
3. The pipeline stays the only admission gate: the identity guard never decides *whether* a notification qualifies, only *whether* it may be attempted again.
4. `CaptureGate.allowsNotificationCapture()` (revoked access) short-circuits before the pipeline, exactly as today → `release`, nothing persisted.

## 3. Outcome → count mapping (scans only; real-time path discards counts)

| Outcome | Visible new Events-tab row? | Counted |
|---|---|---|
| `QUEUED` | yes (normalized tx, queued) | **added** |
| `HELD` | yes (normalized tx, held) | **added** |
| `QUEUED_AS_KNOWN_DUPLICATE` | yes (normalized tx row exists) | **added** |
| `DUPLICATE_SHORT_CIRCUITED` | no (fingerprint already present) | skipped |
| `RETAINED_PARSE_ERROR` | parse-error row (could not be processed) | skipped |
| `IGNORED_NO_SOURCE` | no | skipped |
| `IGNORED_DISABLED` | no | skipped |
| identity key already `recorded` | no (already exists) | skipped |
| no extractable text | no | skipped |

`examined` = active notifications returned; `skipped = examined − added` always.

## 4. Lifecycle coupling

| App action | Identity table |
|---|---|
| `clearAll()` (Clear all button) | rows deleted → a later scan may re-create events for notifications still in the shade |
| `clearProcessed()` (Clear processed button) | rows untouched → cleared terminal events are not resurrected by a scan |
| app restart / process death | rows persist (Room); stale `pending` rows are taken over after 60 s |
