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
    "title": "<description> [<bank>/<accountHint>] — <memo>",
    "transaction_type": "TRANSACTION_TYPE_EXPENSE" | "TRANSACTION_TYPE_INCOME",
    "source_account_id": <resolved>,          // expense: bank account; income: default income account
    "source_amount": "-<amount in source account currency>",
    "source_currency": "<source account currency>",
    "destination_account_id": <resolved>,     // expense: default expense account; income: bank account
    "destination_amount": "<amount in destination account currency as decimal string>",
    "destination_currency": "<destination account currency>",
    "transaction_date_time": "<txAt ISO-8601>",
    "tag_ids": []                              // no tags in MVP
  }
}
```

Type mapping: `expense → TRANSACTION_TYPE_EXPENSE`, `income → TRANSACTION_TYPE_INCOME`.

The ` — <memo>` suffix is present only for a non-empty user-authored memo. The bridge keeps the stable title before the suffix in its dedup registry. A later memo edit reads the current Go Money transaction and calls `TransactionsService/UpdateTransaction` with the same financial fields and updated title; it never creates a second transaction or recalculates the accounting legs.

Amounts follow Go Money's double-entry sign convention: source is negative and
destination is positive. The bridge lists accounts through
`AccountsService/ListAccounts`, uses the explicitly mapped bank account on the
bank side, and selects the account marked default for the opposite transaction
type (`Default Expense` for expense, `Default Income` for income). The mapped
bank account currency must match the captured transaction currency. If the
default counterpart uses a different currency, the bridge reads the active rates
and decimal precision through `CurrencyService/GetCurrencies`, converts the
counterpart amount using Go Money's configured rates, and rounds to the target
currency's decimal places. For example, an IRR bank withdrawal can be stored
against a USD default expense account while preserving the IRR bank leg. Missing
or invalid rates return HTTP 400 before transaction creation. The converted
target amount is stored on the transaction, so later rate changes do not change
that original amount.

### Account resolution (accountHint → Go Money account id)

The bridge maintains a user-managed mapping file/table `mappings.json`:

```json
{ "mellat|****1234": 1, "saman|****9876": 2 }
```

- Exact `(bank, accountHint)` → bank-side account ID (source for expenses,
  destination for income).
- A missing mapped account, missing default counterpart account, or mapped
  account currency mismatch returns HTTP 400 `validation` before creating a
  transaction. A different default-account currency is supported when both
  currencies have valid configured rates.
- Unmatched hint → bridge returns HTTP 400 `validation` (`details: ["unmapped account: mellat/****1234"]`) **without calling Go Money**; the app marks the item `SERVER_ERROR`-category... precisely `VALIDATION_ERROR` (not retried until mapping added). The user edits mappings and retries manually.
- `GET /v1/mappings` + `PUT /v1/mappings` (bearer-token protected, localhost-oriented convenience endpoints) let the user manage mappings without editing the file while the bridge runs. MVP: read/write the JSON file atomically.

## Dedup enforcement (bridge-side, FR-015)

Before calling CreateTransaction, the bridge consults its SQLite registry (data-model.md §7): exact fingerprint → `duplicate` response (200). Fingerprint + bucket-window (±2 min) match → `duplicate`. Miss → insert row, call Go Money, then update `gomoneyTxnId` on success. On Go Money failure the fingerprint row is rolled back (so a later retry can succeed).

## Acknowledgement semantics (clarified Q2)

`CreateTransaction` is synchronous: when Go Money responds success, the transaction exists. The bridge's 201/200 responses therefore always mean "recorded in Go Money". If Go Money succeeds but the HTTP reply to the app is lost, the app retries, hits the fingerprint registry, and receives `duplicate` → `SENT` — either way exactly one Go Money transaction (FR-030).

`UpdateTransaction` is also synchronous for memo changes. Its request is assembled from the existing Go Money transaction so amount, currencies, accounts, date, tags, and other financial fields remain unchanged.

## Replaceability (FR-013)

The Go Money client is an interface:

```go
type GoMoneyClient interface {
    CreateTransaction(ctx context.Context, in *transaction.Request) (*gomoneypb.transactions.v1.CreateTransactionResponse, error)
    GetTransactionByID(ctx context.Context, id int64) (*gomoneypb.v1.Transaction, error)
    UpdateTransaction(ctx context.Context, in *transactionsv1.UpdateTransactionRequest) (*transactionsv1.UpdateTransactionResponse, error)
}
```

Bridge code depends only on this interface; a different finance backend requires only a new implementation.
