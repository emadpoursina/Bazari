# Data Model: Notification Source & Template Engine

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md) | Research: [research.md](./research.md)

All new persisted state lives in the Android Room database `gomoney-capture.db` (schema v3). Go Money remains the source of truth for accounts and categories; the app only caches them for selection. Raw message text is stored locally only and is never sent to the bridge or Go Money.

## Entities

### 1. TransactionSource (Android Room `transaction_sources`)

A user-defined capture source (spec Key Entity `TransactionSource`, FR-001/002/003/005/006).

| Field | Type | Constraints |
|---|---|---|
| id | String (uuid) | PK |
| name | String | required, non-blank, ≤100 chars |
| identifier | String | required, non-blank; package id (notification) or SMS sender id |
| channel | String enum: `notification` \| `sms` | required |
| enabled | Boolean | default `true`; disabling stops capture without deleting (FR-003) |
| template | String | required; fill-in-the-blank pattern containing exactly one `{direction}` and one `{amount}` |
| incomeKeywords | String | required, non-empty after normalization; newline/comma-separated words meaning income |
| expenseKeywords | String | required, non-empty after normalization; newline/comma-separated words meaning expense |
| boundAccountId | Int? | server account id from `server_accounts`; null = unbound |
| boundAccountLabel | String? | cached label for display and stale-binding detection |
| createdAt | String ISO-8601 | creation time |
| updatedAt | String ISO-8601 | last edit time |

Indices: unique on `(identifier, channel)` (FR-005, SC-009); non-unique on `enabled`.

Validation (enforced by `SourceValidator` before insert/update):
- `name` non-blank.
- `identifier` non-blank and trimmed.
- `channel` ∈ enum.
- `template` parses to exactly two placeholders, one `{direction}` and one `{amount}`; if a required placeholder is missing the error names it (FR-012, spec edge case).
- `incomeKeywords` and `expenseKeywords` each contain at least one non-empty word (FR-012).
- No other source may share the same `(identifier, channel)`; the error identifies the existing source (FR-005).
- A source with no bound account is savable (FR-013 edge case: server list unavailable/empty).

State transitions:

| Current | Event | Result |
|---|---|---|
| (new) | Save valid source | Created, `enabled=true` |
| enabled | Disable | `enabled=false`; its messages no longer captured; other sources unaffected (FR-003/US4.1) |
| disabled | Enable | `enabled=true`; matching messages captured again (US4.2) |
| any | Remove | Row deleted; its messages no longer captured; already-captured transactions retained (US4.3) |
| any | Bind account | `boundAccountId`/`boundAccountLabel` set; subsequent captures recorded against it (FR-015) |
| any | Bound account deleted/renamed on server | Refresh detects the id is absent/renamed → shown as unbound/stale with a prompt to choose again; capture not lost (spec edge case) |

### 2. MessageTemplate (embedded in TransactionSource, not a table)

The compiled view of `TransactionSource.template` (spec Key Entity `MessageTemplate`, FR-007/008/011).

- **Grammar**: free text with exactly two placeholder tokens — `{direction}` and `{amount}` — each appearing exactly once. Everything else is a literal anchor.
- **Compilation** (`TemplateMatcher.compile`): normalized literal anchors + ordered placeholder slots (segments).
- **Matching** (`TemplateMatcher.match`): normalize the message (digits/separators → ASCII, whitespace collapsed, case-folded), require literal anchors to appear in order, and assign the text between consecutive anchors to each placeholder's segment. Extra text before/after/between anchors is tolerated so dates and reference numbers may differ (spec assumption).
- **Amount**: a positive integer token taken from the `{amount}` segment via `AmountNormalizer`; missing/zero/non-numeric → parse error.
- **Direction**: resolved from the message against the source keyword lists (see §4), not from the placeholder text alone.
- Templates are edited in-app with no code change or release (FR-008).

### 3. ParseErrorMessage (Android Room `parse_errors`)

A message from a defined source that could not be parsed (spec Key Entity `ParseErrorMessage`, FR-010/028, SC-010).

| Field | Type | Constraints |
|---|---|---|
| id | String (uuid) | PK |
| sourceId | String? | originating `TransactionSource.id`; null if the source was removed |
| sourceEventId | String → `raw_events.id` | FK, `ON DELETE CASCADE`, indexed; the local raw message is the display source |
| failureReason | String | sanitized, non-sensitive reason (no raw text) |
| occurredAt | String ISO-8601 | when the message was processed |

Retention and lifecycle:
- Newest-first review list on the Sources screen; each entry shows `failureReason` and is dismissible.
- On insert, prune: delete rows with `occurredAt` older than 30 days, then delete oldest rows beyond the newest 200 (whichever comes first — FR-028/SC-010).
- Dismiss deletes the row immediately.
- Never sent to the server; never logged with message text.

### 4. Direction resolution (no table; computed)

Direction is derived per FR-026 from `TransactionSource.incomeKeywords` / `expenseKeywords` against the normalized message:

| Income keyword matched | Expense keyword matched | Result |
|---|---|---|
| exactly one | none | `income` |
| none | exactly one | `expense` |
| none | none | unrecognizable → parse error |
| any | any | ambiguous → parse error (spec edge case) |

Matching is case-insensitive whole-token, after digit normalization. Keywords are trimmed, de-duplicated, and non-empty.

### 5. ServerAccount (Android Room `server_accounts`, cache)

A server account offered for selection (spec Key Entity `ServerAccount`, FR-013/014/016/017/021).

| Field | Type | Constraints |
|---|---|---|
| id | Int | PK; Go Money account id |
| label | String | human-readable name |
| currency | String | account currency (used to validate a binding against IRR) |
| type | String | Go Money account type (`expense`/`income`/…) |
| isDefault | Boolean | whether this is Go Money's default account of its type |
| refreshedAt | String ISO-8601 | last successful refresh |

Used for both source binding and a transaction's destination account. Refresh replaces the cache atomically; a failed refresh leaves the cache intact and flags it unavailable (FR-016).

### 6. ServerCategory (Android Room `server_categories`, cache)

A category offered for selection (spec Key Entity `ServerCategory`, FR-018/021).

| Field | Type | Constraints |
|---|---|---|
| id | Int | PK; Go Money category id |
| label | String | human-readable name |
| refreshedAt | String ISO-8601 | last successful refresh |

### 7. Captured transaction assignment (extended `normalized_transactions`)

The destination account and category a user sets on a captured transaction plus its sync state (spec Key Entity "Captured transaction assignment", FR-017/018/019/020).

New/extended fields on the existing `NormalizedTransaction`:

| Field | Type | Constraints |
|---|---|---|
| sourceId | String? | originating user `TransactionSource.id`; null for built-in parser captures |
| accountId | Int? | bound source account id captured at creation; null for the built-in path (bridge falls back to its mappings) |
| destinationAccountId | Int? | user-selected destination account from `server_accounts`; null = default behavior |
| categoryId | Int? | user-selected category from `server_categories`; null = default behavior |
| assignmentSyncState | String enum: `pending` \| `synced` | default `synced`; set `pending` on assignment change, mirrors `memoSyncState` |

Validation/invariants:
- `destinationAccountId`/`categoryId` are optional; unset means the existing Go Money defaults apply (spec assumption).
- A choice is persisted locally **before** any delivery attempt (FR-019) and survives restart/offline.
- Setting or changing the assignment on an already-delivered transaction updates the same Go Money transaction; no second transaction is created (FR-020, SC-005).
- `accountId`, when set, is the account the transaction is recorded against and takes precedence over the bridge's static mappings for that transaction (R7, FR-015).

State transitions (`assignmentSyncState`):

| Current | Event | Result |
|---|---|---|
| synced | User sets/changes destination or category | Write locally → `pending`; enqueue expedited sync |
| pending | Bridge assignment update succeeds | `synced` |
| pending | Offline / bridge error | stays `pending`; retried by the existing worker |
| pending | Transaction not yet delivered | values included in the create payload; becomes `synced` on successful create/duplicate |

## Relationships

```text
TransactionSource 1 ──── 0..N NormalizedTransaction   (sourceId; parse failures have no transaction)
TransactionSource 1 ──── 0..1 ServerAccount            (boundAccountId, optional)
TransactionSource 1 ──── 0..N ParseErrorMessage        (sourceId)
RawEvent         1 ──── 0..N ParseErrorMessage         (sourceEventId, cascade)
ServerAccount    1 ──── 0..N NormalizedTransaction     (destinationAccountId / accountId)
ServerCategory   1 ──── 0..N NormalizedTransaction     (categoryId)
NormalizedTransaction 1 ──── 1 DeliveryRecord          (existing, unchanged)
```

## Retention

- `transaction_sources`: retained until the user removes the source; `parse_errors` referencing it keep `sourceId` (or null it) but do not block removal.
- `parse_errors`: at most the newest 200 entries or 30 days, whichever comes first (FR-028).
- `server_accounts` / `server_categories`: last successful snapshot retained across restarts; replaced on refresh.
- Extended `normalized_transactions` rows follow the existing capture-feature retention (cleared only by explicit user action; pending assignments preserved while pending).
- Raw message text is never transmitted and never logged (FR-023, SC-007).
