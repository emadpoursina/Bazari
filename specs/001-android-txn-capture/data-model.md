# Data Model: Go Money Android Transaction Capture

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md)

All entities live in two stores: the **Android local Room DB** and the **bridge dedup SQLite store**. Field types are annotated per store. "uuid" means v4 UUID generated at creation.

## Entities

### 1. RawEvent (Android Room)

The immutable, unmodified captured event. Written before any processing (FR-002); retained until its downstream NormalizedTransaction reaches a terminal state (FR-003).

| Field | Type | Constraints |
|---|---|---|
| id | String (uuid) | PK |
| source | String enum: `notification` \| `sms` | required |
| sourcePackage | String | package name (notification) or SMS sender id; required |
| bank | String? | resolved bank identifier, null if no parser claims it |
| title | String? | notification title; null for SMS |
| text | String | full raw text (Persian OK); never logged |
| postedAt | String ISO-8601 with offset | event timestamp from OS |
| capturedAt | String ISO-8601 with offset | write time |

Validation: `source` ∈ enum; `text` non-empty. Never editable after write.

### 2. NormalizedTransaction (Android Room)

Provider-independent parse output (FR-007).

| Field | Type | Constraints |
|---|---|---|
| id | String (uuid) | PK |
| sourceEventId | String → RawEvent.id | FK, indexed |
| source | String enum: `notification` \| `sms` | mirrors source event |
| bank | String | lowercase slug, e.g. `mellat` |
| accountHint | String | masked, e.g. `****1234` |
| type | String enum: `expense` \| `income` | required |
| amountMinor | Long | canonical integer minor units (whole IRR) |
| currency | String | MVP: always `IRR` |
| txAt | String ISO-8601 with offset | transaction time from message |
| description | String | normalized (whitespace-collapsed) description |
| rawTextRef | String → RawEvent.id | reference, not a copy |
| fingerprint | String (64 hex) | see §Fingerprint; unique index |
| parserName | String | e.g. `MellatParser` |
| confidence | String enum: `high` \| `medium` \| `low` | set by parser |
| userMemo | String? | User-authored context, e.g. `groceries`; ≤200 characters |
| memoSyncState | String enum: `pending` \| `synced` | pending until the memo reaches Go Money |
| gomoneyTxnId | String? | Go Money transaction id, retained for later memo edits |

Validation: `amountMinor` > 0; currency = `IRR` (MVP); description and user memo ≤ 200 chars; Persian digits normalized before parse (FR-010).

### 3. DeliveryRecord (Android Room, 1:1 with NormalizedTransaction)

Outbox lifecycle (FR-011). **Single terminal success state `SENT`** — no CONFIRMED (clarified Q2).

States: `CAPTURED → PARSED → QUEUED → SENDING → SENT` and `SENDING → FAILED → (retry) → QUEUED`.

| Field | Type | Constraints |
|---|---|---|
| id | String = NormalizedTransaction.id | PK, FK |
| state | String enum: `captured` \| `parsed` \| `queued` \| `sending` \| `sent` \| `failed` | indexed |
| attempts | Int | ≥ 0 |
| lastAttemptAt | String ISO-8601? | null until first send |
| nextRetryAt | String ISO-8601? | WorkManager backoff mirror |
| errorCategory | String enum: `capture_error` \| `parse_error` \| `validation_error` \| `network_error` \| `server_error` \| `duplicate`? | null while healthy |
| errorDetail | String? | sanitized only — no raw message text |

State transitions (all local, transactional):

```
CAPTURED → PARSED → QUEUED → SENDING → SENT (terminal)
                        ↑  ↓
                        ← FAILED (auto-retry → QUEUED; manual retry → QUEUED)
```

Rules: `SENT` is terminal and immutable; `FAILED` requires non-null `errorCategory`; only `QUEUED`/`FAILED` rows are picked for delivery; duplicate detection on the bridge side sets `errorCategory=duplicate` and marks the local row `SENT`-equivalent... — precisely: a duplicate acknowledgement is terminal; the row is set `state=sent` with `errorCategory=duplicate` (already recorded in Go Money; nothing to deliver).

### 4. DedupCache (Android Room)

Local short-circuit so the phone doesn't re-send known duplicates after restarts.

| Field | Type | Notes |
|---|---|---|
| fingerprint | String (64 hex) | PK |
| resolvedAt | String | when acknowledged |
| outcome | String enum: `sent` \| `duplicate` | |

### 5. BankParser (capability — in-code object, not stored)

```kotlin
interface BankParser {
    val name: String                      // "MellatParser"
    val bank: String                      // "mellat"
    fun canParse(event: RawEvent): Boolean
    fun parse(event: RawEvent): ParseResult // Success(NormalizedTransaction) | Failure(reason, confidence)
}
```

Registry iterates in priority order; first `canParse=true` wins; if none matches → `PARSE_ERROR` retained event (spec US6 scenario 2). Adding a bank = new class + fixtures only (SC-005).

### 6. ServerConfiguration (Android DataStore)

| Field | Type | Constraints |
|---|---|---|
| serverUrl | String | `http://host:port` on a trusted LAN or Tailscale network (e.g. `http://192.168.1.10:8787`) |
| bearerToken | String | static token; stored in EncryptedSharedPreferences/DataStore encrypted |
| notificationCaptureEnabled | Boolean | default true |
| smsCaptureEnabled | Boolean | default false |
| enabledBankPackages | Set\<String\> | allow-list of package names / senders (FR-001/018) |
| debugModeEnabled | Boolean | default false |

### 7. FingerprintRegistry (bridge SQLite `dedup.db`)

| Field | Type | Notes |
|---|---|---|
| fingerprint | TEXT | PK (exact sha256) |
| bucketKey | TEXT | indexed secondary key: bank+account+type+amount+ts-2min-bucket |
| gomoneyTxnId | TEXT/INT | id returned by Go Money |
| recordedAt | TEXT | when Go Money acknowledged |
| memoBaseTitle | TEXT | stable pre-memo title used to replace or clear a note |

Lookup: exact fingerprint first; else `bucketKey`-window scan (±2 min) with matching bank+account+type+amount — if found, respond `duplicate`; else insert + forward to Go Money.

## Fingerprint specification (implements FR-009)

```
normalizedDescription := lowercase(trim(collapseWhitespace(description)))
amountMinor           := canonical integer (Persian-normalized)
round(ts)             := ISO-8601 UTC, minute precision

fingerprint = sha256( bank | accountHint | type | amountMinor | round(ts) | normalizedDescription )
bucketKey   = bank | accountHint | type | amountMinor | floor(unix(ts) / 120)
```

`userMemo` is intentionally excluded from the fingerprint: adding or editing a note must never turn one transaction into a new transaction or create a duplicate.

- Same source duplicate → identical fingerprint → exact match.
- Notification + SMS (≤2 min apart) → different `round(ts)` → exact mismatch → bucketKey window match → duplicate.
- Two distinct transactions, same amount, seconds apart → same bucket, but only collapse if descriptions match; if descriptions differ → distinct (edge case honored). If descriptions also match, the risk of collapsing is accepted (spec assumption: "near-identical but genuinely distinct transactions... assumed rare enough").

## Relationships

```
RawEvent 1 ──── 1..0..1 NormalizedTransaction   (parse failure keeps RawEvent alone, flagged parse_error)
NormalizedTransaction 1 ──── 1 DeliveryRecord
NormalizedTransaction 1 ──── 0..1 DedupCache entry (on terminal state)
RawEvent, NormalizedTransaction, DeliveryRecord ∗ ──── 1 ServerConfiguration (singleton settings)
FingerprintRegistry (bridge) 1 ──── 1 Go Money transaction
```

## Retention

- RawEvents + NormalizedTransactions + DeliveryRecords with `state=sent`: retained until user clears ("Clear local processed events" — only sent/terminal rows removed; pending/failed preserved, spec edge case).
- `failed`/`queued` rows: retained indefinitely (no auto-expiry in MVP).
- Bridge `dedup.db`: retained indefinitely; single-user scale makes growth trivial.
