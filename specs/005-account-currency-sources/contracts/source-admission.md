# Contract: Source-Only Admission & Currency Behavior (in-app)

**Feature**: Account Currency & Source-Only Capture  
**Extends**: [`specs/004-notification-engine/contracts/source-template.md`](../../004-notification-engine/contracts/source-template.md)

Internal Android contract. Template grammar, keyword direction, and parse-error retention are unchanged except where noted.

## Admission

```text
toggles (notification/SMS) → enabled TransactionSource(identifier, channel)
  → UserSourceParser(template)
  → persist tx + delivery (queued | held)
```

- **Match source, enabled, template present** → parse; on success create a transaction; on failure retain parse error (004).
- **No matching enabled source** → ignore; persist nothing. Former allow-list membership is irrelevant.
- **Shipped `ParserRegistry` / `BankParser` implementations MUST NOT run** on this path.
- Disabling or deleting the source is sufficient to stop capture.

`CapturePipeline` constructed from notification/SMS entry points MUST receive `TransactionSourceRepository` and `UserSourceParser`.

## Currency on parse success

`UserSourceParser` sets:

| Source binding | `NormalizedTransaction.currency` | `accountId` | Delivery |
|---|---|---|---|
| Bound id present in catalog with non-blank currency | that code | bound id | `queued` |
| Unbound, stale, or catalog currency blank | `""` | bound id or null as stored | `held` |

Do not hardcode `"IRR"`. Template test preview: if the editor’s selected account has a currency, label the amount with that code; if unbound, show amount without a rial label (unknown).

## Stamp on bind

When a source’s `boundAccountId` changes from null/stale to a valid account with currency `C`:

1. Update the source row (004).
2. For transactions with `sourceId` = this source AND `currency == ""` AND delivery `held`: set `currency = C`, `accountId` = bound id, delivery `held → queued`.
3. Do not update rows with non-empty currency (including `sent`).
4. Enqueue existing expedited sync.

When rebound from account A to B (both with currencies): new captures use B; existing stamped rows unchanged.

## Currency edit (transaction screen)

| Delivery state | Currency control |
|---|---|
| `held`, `queued`, `failed` | Dropdown of distinct non-blank `server_accounts.currency` values; persist local update only |
| `sending`, `sent` | Read-only |

Editing currency on `held` does not queue delivery. Assignment save must not write `currency`.

## Settings

Settings MUST NOT render or mutate a package/sender allow-list. Capture identifiers are sources only.

## Seeded sources

On first run after v4 (and idempotently thereafter), insert former built-in bank sources per [data-model.md](../data-model.md). Skip an `(identifier, channel)` that already exists. Users may edit these like any other source.
