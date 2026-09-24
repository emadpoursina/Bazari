# Contract: Bridge HTTP API (Android ⇄ Bridge)

**Direction**: Android app (client) → Transaction bridge (server, `cmd/android-bridge`).
**Transport**: Plain HTTP on the trusted LAN (clarified Q1=B). **Auth**: static bearer token — `Authorization: Bearer <token>` on every request; 401 with `{"error":"unauthorized"}` on mismatch. Content type: `application/json; charset=utf-8`.

Semantic rule (clarified Q2): **every success response means the transaction is already recorded in Go Money**. There is no "accepted but not recorded" intermediate ack.

## Endpoints

### `POST /v1/ping`

Connection test (FR-019 "test connection").

**Request**: empty JSON object `{}`.
**Response 200**:
```json
{ "ok": true, "gomoneyReachable": true, "serverTime": "2026-09-24T20:31:22+03:30" }
```
`gomoneyReachable=false` → 200 with `ok:false` variant: `{ "ok": false, "gomoneyReachable": false, "serverTime": "..." }` (bridge up, Go Money down → app treats as `SERVER_ERROR`/retryable).

### `POST /v1/transactions`

Deliver one normalized transaction. Idempotent by fingerprint.

**Request** (mirrors data-model.md §2, `rawTextRef` omitted — raw text never leaves the phone):

```json
{
  "id": "uuid",
  "sourceEventId": "uuid",
  "source": "notification",
  "bank": "mellat",
  "accountHint": "****1234",
  "type": "expense",
  "amount": 500000,
  "currency": "IRR",
  "txAt": "2026-09-23T20:31:22+03:30",
  "description": "Card purchase",
  "fingerprint": "<64 hex>"
}
```

**Responses**:

| Status | Body | Meaning / app state |
|---|---|---|
| 201 | `{ "status": "created", "gomoneyTxnId": 123 }` | recorded in Go Money → app sets `SENT` |
| 200 | `{ "status": "duplicate", "gomoneyTxnId": 123 }` | fingerprint already recorded → app sets `SENT` (terminal, `errorCategory=duplicate`) |
| 400 | `{ "error": "validation", "details": ["amount must be > 0"] }` | `VALIDATION_ERROR`; NOT retried automatically |
| 401 | `{ "error": "unauthorized" }` | `SERVER_ERROR` (config problem); surfaced in UI |
| 502 | `{ "error": "gomoney_unreachable", "details": "..." }` | `NETWORK_ERROR`; retry with backoff |
| 500 | `{ "error": "gomoney_error", "details": "..." }` | `SERVER_ERROR`; retry allowed |

Notes: the bridge returns 201 only **after** Go Money's `CreateTransaction` succeeds (synchronous single round-trip). No async queueing inside the bridge — offline tolerance is the app outbox's job.

### `POST /v1/transactions/bulk`

Optional drain optimization for many queued items (bridge may still record one-by-one; the app uses it when ≥5 items are queued).

**Request**: `{ "transactions": [ <same objects as /v1/transactions>, ... ] }` (max 50 per call).
**Response 200**: `{ "results": [ { "id": "uuid", "status": "created" | "duplicate", "gomoneyTxnId": 123 }, ... ] }` — per-item results, order-preserving. Partial failures: HTTP 200 with per-item `status:"error", "error": "<category>"` entries; the app marks those items failed.
Other statuses (401/502/500): whole batch failed, app falls back to single sends with backoff.

## Error model

Errors are JSON with `error` ∈ {`validation`, `unauthorized`, `gomoney_unreachable`, `gomoney_error`, `duplicate`} — mapped 1:1 to the app's error categories (FR-023). The bridge must never echo raw bank message text in `details` (the app should not send it, the bridge must not log it — FR-028).

## Versioning & compatibility

`/v1/` prefix; additive changes only for the MVP. The Android app pins the base URL + token in settings; a `/v1/ping` failure with 401 should prompt the user to check the token.

## Logging rules (FR-028)

Bridge logs record: timestamp, endpoint, fingerprint prefix (first 8 hex), outcome, latency. Never: full payloads, `description`, `accountHint`.
