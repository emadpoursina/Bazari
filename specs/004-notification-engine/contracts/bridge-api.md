# Contract: Bridge API Additions (Android ⇄ Bridge)

**Feature**: Notification Source & Template Engine | **Base contract**: [`specs/001-android-txn-capture/contracts/bridge-http-api.md`](../../001-android-txn-capture/contracts/bridge-http-api.md)

**Direction**: Android app (client) → Transaction bridge (server, `cmd/android-bridge`).
**Transport/Auth**: unchanged — plain HTTP on the trusted LAN, `Authorization: Bearer <token>`, `application/json; charset=utf-8`, 401 `{"error":"unauthorized"}` on mismatch.
**Privacy rule (unchanged)**: raw message text never appears in any request, response, or log. All fields below are normalized values or server identifiers.

This document is **additive** to the capture-feature contract. Existing endpoints and semantics are unchanged.

## Endpoints

### `GET /v1/accounts`

Server accounts for the source-binding and destination-account drop-downs (FR-013/014/017/021). Reads Go Money `AccountsService/ListAccounts` with the bridge's service token and returns a minimal projection.

**Response 200**:

```json
{
  "accounts": [
    { "id": 3, "label": "Bank Mellat", "currency": "IRR", "type": "expense", "isDefault": false }
  ]
}
```

- `id`: Go Money account id (used verbatim as `accountId` / `destinationAccountId`).
- `label`: account name.
- `currency`: account currency (a source binding should match the transaction currency, IRR).
- `type`: `expense` | `income` | … (Go Money account type, lowercased).
- `isDefault`: whether this is Go Money's default account of its type.

**Errors**: `502 {"error":"gomoney_unreachable",...}` when Go Money is down; `500 {"error":"gomoney_error",...}` otherwise. The app keeps the cached list and shows an "unavailable" indicator (FR-016).

### `GET /v1/categories`

Server categories for the transaction category drop-down (FR-018/021).

**Response 200**:

```json
{
  "categories": [
    { "id": 5, "label": "Groceries" }
  ]
}
```

**Errors**: same as `GET /v1/accounts`.

### `POST /v1/transactions` (extended)

The existing single-transaction delivery endpoint. Three **optional** fields are added to the request body; when absent, behavior is exactly as before.

```json
{
  "id": "uuid",
  "sourceEventId": "uuid",
  "source": "notification",
  "bank": "user",
  "accountHint": "com.example.bank",
  "type": "expense",
  "amount": 500000,
  "currency": "IRR",
  "txAt": "2026-09-30T20:31:22+03:30",
  "description": "Card purchase",
  "memo": "groceries",
  "fingerprint": "<64 hex>",
  "accountId": 3,
  "destinationAccountId": 7,
  "categoryId": 5
}
```

| Field | Meaning |
|---|---|
| `accountId` | The server account the transaction is recorded against. For a user-defined source this is the bound account (FR-015) and takes precedence over the bridge's `(bank, accountHint)` mappings. Absent → existing mappings lookup (built-in path). |
| `destinationAccountId` | Go Money destination account to set on creation. Absent → Go Money's default counterpart account (existing behavior). |
| `categoryId` | Go Money category to set on creation. Absent → Go Money default. |

Validation: ids, when present, must be positive and resolve to a known server account/category; otherwise `400 {"error":"validation",...}`. Responses are unchanged (`201 created` / `200 duplicate` / `400` / `401` / `502` / `500`), and `gomoneyTxnId` is still returned so later assignment edits can target the same transaction.

### `PUT /v1/transactions/assignment`

Set or change the destination account and/or category of a transaction **already recorded** in Go Money, updating the same transaction and never creating a second one (FR-020, SC-005). Used when a choice is made after delivery; choices made before delivery ride in the create payload.

**Request** (identity fields match the memo-update lookup so exact/cross-source/duplicate-acked captures all resolve):

```json
{
  "fingerprint": "<64 hex>",
  "gomoneyTxnId": "123",
  "bank": "user",
  "accountHint": "com.example.bank",
  "type": "expense",
  "amount": 500000,
  "txAt": "2026-09-30T20:31:22+03:30",
  "description": "Card purchase",
  "destinationAccountId": 7,
  "categoryId": 5
}
```

- `destinationAccountId` and `categoryId` are optional individually; a field that is absent or `null` is left unchanged. Sending both as `null` is a no-op success.
- The bridge looks the transaction up in the dedup registry (exact fingerprint → `gomoneyTxnId` → identity/bucket-window fallback, reusing the memo-update lookup), reuses the current Go Money financial fields, and calls `UpdateTransaction` with only the destination account/category changed.

**Responses**:

| Status | Body | Meaning |
|---|---|---|
| 200 | `{ "status": "updated", "gomoneyTxnId": "123" }` | the same Go Money transaction now carries the assignment |
| 400 | `{ "error": "validation", "details": [...] }` | malformed identity or invalid id; not auto-retried |
| 404 | `{ "error": "validation", "details": ["recorded transaction not found"] }` | no recorded transaction matches |
| 500 / 502 | `{ "error": "gomoney_error" \| "gomoney_unreachable", ... }` | retryable; the app keeps the assignment `pending` and retries |

No new error tokens are introduced; the app maps them with the existing taxonomy (validation → `validation_error`, `gomoney_unreachable` → `network_error`, `gomoney_error` → `server_error`).

## Compatibility

- Additive `/v1/` changes only; older app builds that omit the new fields keep the previous behavior.
- No new credential: the bridge continues to use its single Go Money service token for `ListAccounts`, `ListCategories`, `CreateTransaction`, and `UpdateTransaction`.
- Bridge logging rules are unchanged: timestamp, endpoint, fingerprint prefix (first 8 hex), outcome, latency. Never `description`, `accountHint`, account/category labels, or any message text (FR-023).
