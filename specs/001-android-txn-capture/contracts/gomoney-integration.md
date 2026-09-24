# Contract: Bridge ⇄ Go Money Integration (Go-side adapter)

**Direction**: `pkg/androidbridge` → Go Money server (this repo's public API, see `docs/api/endpoints.md`).
**Protocol**: ConnectRPC over HTTP — `POST /<package>.<Service>/<Method>` with JSON bodies and header `Authorization: Bearer <serviceToken>`.

## Authentication

The bridge uses a Go Money **service token** (not user credentials — FR-006 forbids storing passwords):
- Created once by the user via Go Money UI/`ConfigurationService.CreateServiceToken` (`POST /gomoneypb.configuration.v1.ConfigurationService/CreateServiceToken`).
- Passed to the bridge via env var `GOMONEY_URL` + `GOMONEY_SERVICE_TOKEN`, or flags `--gomoney-url` / `--gomoney-token`.

## Mapping: NormalizedTransaction → CreateTransaction

`POST /gomoneypb.transactions.v1.TransactionsService/CreateTransaction`

```json
{
  "transaction": {
    "title": "<description> [<bank>/<accountHint>]",
    "transaction_type": "TRANSACTION_TYPE_EXPENSE" | "TRANSACTION_TYPE_INCOME",
    "source_account_id": <resolved>,          // expense: the bank account
    "destination_account_id": <resolved>,     // income target account; expense → counterparty category default
    "destination_amount": "<amount as decimal string>",
    "destination_currency": "IRR",
    "transaction_date_time": "<txAt ISO-8601>",
    "tag_ids": []                              // no tags in MVP
  }
}
```

Type mapping: `expense → TRANSACTION_TYPE_EXPENSE`, `income → TRANSACTION_TYPE_INCOME`.

### Account resolution (accountHint → Go Money account id)

The bridge maintains a user-managed mapping file/table `mappings.json`:

```json
{ "mellat|****1234": 1, "saman|****9876": 2 }
```

- Exact `(bank, accountHint)` → `source_account_id`.
- Unmatched hint → bridge returns HTTP 400 `validation` (`details: ["unmapped account: mellat/****1234"]`) **without calling Go Money**; the app marks the item `SERVER_ERROR`-category... precisely `VALIDATION_ERROR` (not retried until mapping added). The user edits mappings and retries manually.
- `GET /v1/mappings` + `PUT /v1/mappings` (bearer-token protected, localhost-oriented convenience endpoints) let the user manage mappings without editing the file while the bridge runs. MVP: read/write the JSON file atomically.

## Dedup enforcement (bridge-side, FR-015)

Before calling CreateTransaction, the bridge consults its SQLite registry (data-model.md §7): exact fingerprint → `duplicate` response (200). Fingerprint + bucket-window (±2 min) match → `duplicate`. Miss → insert row, call Go Money, then update `gomoneyTxnId` on success. On Go Money failure the fingerprint row is rolled back (so a later retry can succeed).

## Acknowledgement semantics (clarified Q2)

`CreateTransaction` is synchronous: when Go Money responds success, the transaction exists. The bridge's 201/200 responses therefore always mean "recorded in Go Money". If Go Money succeeds but the HTTP reply to the app is lost, the app retries, hits the fingerprint registry, and receives `duplicate` → `SENT` — either way exactly one Go Money transaction (FR-030).

## Replaceability (FR-013)

The Go Money client is an interface:

```go
type GoMoneyClient interface {
    CreateTransaction(ctx context.Context, in *transaction.Request) (*gomoneypb.transactions.v1.CreateTransactionResponse, error)
}
```

Bridge code depends only on this interface; a different finance backend requires only a new implementation.
