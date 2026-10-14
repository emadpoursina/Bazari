# Data Model: Account Currency & Source-Only Capture

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md) | Research: [research.md](./research.md)

This feature extends `gomoney-capture.db` (Room schema **v4**) and the existing 004 entities. Go Money remains the source of truth for account currencies. Raw message text stays local.

## Entities

### 1. Bound-account currency (not a table)

The `currency` field on cached `server_accounts` for the source’s `boundAccountId`.

| Rule | Behavior |
|---|---|
| Bind | User selects an account; app copies **display** of `label` + `currency`. No separate currency control. |
| Bind rejected | `currency` blank → cannot complete bind; previous `boundAccountId`/`boundAccountLabel` unchanged |
| Capture (bound, currency present) | Copy `ServerAccount.currency` onto the new `NormalizedTransaction` |
| Capture (unbound, stale, or catalog currency blank) | Transaction currency `""`; delivery `held` |
| Catalog refresh | Source UI shows latest known currency for the still-valid id; already-stamped transactions unchanged |
| Stale id | Existing 004 stale prompt; treat new captures as unbound until the user rebinds |

`TransactionSource` fields stay as in 004 (`boundAccountId`, `boundAccountLabel`). No new source column.

### 2. Captured transaction currency (`normalized_transactions.currency`)

Existing column. Semantics change:

| Value | Meaning |
|---|---|
| non-empty (e.g. `IRR`, `USD`) | Stamped at capture, stamped on later bind, or edited before delivery |
| `""` | Unknown; must not be delivered |

Validation:
- Never default to `IRR`.
- User edit allowed only while delivery state ∈ {`held`, `queued`, `failed`}.
- Rebind / catalog currency change MUST NOT update rows that already have a non-empty currency (including `sent`).
- `destinationAccountId` / `categoryId` MUST NOT change `currency`.

Fingerprint does **not** include currency (unchanged 001/004 composition). Changing currency on a local row does not recompute fingerprint.

### 3. TransactionSource (unchanged shape; new admission role)

Same table as 004. Additional rules:

- Capture requires `enabled == true` and a valid user template. No `ParserRegistry` fallback.
- Duplicate `(identifier, channel)` still unique.
- Unbound (`boundAccountId == null`) remains a valid save.
- No default rows: the table starts empty on a fresh install and contains only user-created sources (005 amendment — former-bank seeds removed). Rows are normal rows (user may edit, disable, or delete).

### 4. Settings allow-list (removed)

`ServerConfiguration.enabledBankPackages` is not a capture gate. DataStore value is cleared and unread. Not migrated into `transaction_sources`.

### 5. ServerAccount (unchanged)

| Field | Role in this feature |
|---|---|
| id, label | Binding identity / display |
| currency | Bindability + capture stamp + UI label + currency-edit dropdown (distinct non-blank values) |
| type, isDefault, refreshedAt | Unchanged |

### 6. DeliveryRecord — new state `held`

Existing outbox, schema v3 columns unchanged. Wire value `held`.

```text
captured → parsed → queued → sending → sent
                 ↘ held ↗
sending → failed → queued
```

| Current | Event | Result |
|---|---|---|
| (new capture, source bound with currency) | persist | `queued` (existing) |
| (new capture, source unbound / stale / no currency) | persist | `held`, tx.currency `""` |
| `held` | source bound; stamp empty-currency rows; enqueue sync | `queued` |
| `held` | user edits currency | stays `held` until bind |
| `queued` / `failed` | user edits currency | currency updated; state unchanged |
| `sending` / `sent` | user opens detail | currency read-only |
| `sent` | source rebound | no rewrite |

`TransactionSyncWorker` still drains `queued` only.

## Validation summary

| Check | Where |
|---|---|
| Bind requires non-blank account currency | Source editor / validator |
| Capture currency from catalog or `""` | `UserSourceParser` / pipeline |
| Stamp empty-currency held rows on bind | Source repository + delivery repo (one transaction) |
| Do not stamp non-empty currency | same |
| Bridge: currency non-empty | `validateTransaction` |
| Bridge: currency equals mapped account | existing create handler |
| Currency edit only if not delivered | Event detail |

## Schema migration

**v3 → v4**: no table rewrite required if `held` is only a new string in `delivery_records.state` and currency empty-string is already valid TEXT. Bump Room version so tests and `AppDatabaseMigrationTest` record the semantic change; migration is a no-op SQL (`MIGRATION_3_4`). No source seeding runs at migration or app startup (005 amendment — default former-bank seeds removed).

**Former seed set (removed)** — the sources below were previously inserted by `seedDefaults()` on app start; seeding has been removed, so nothing is inserted on install and deletions stick. The table is historical:

| Name | Identifier | Channel |
|---|---|---|
| Mellat | `ir.mellat.mellatab` | notification |
| Mellat SMS | `MELLAT` | sms |
| Melli | `ir.bmi.mobilebank` | notification |
| Melli SMS | `BMI` | sms |
| Saman | `ir.sb24.saman` | notification |
| Saman SMS | `SAMAN` | sms |
| Blue | `com.samanpr.blu` | notification |

Generic and SampleBank were never part of the set.
