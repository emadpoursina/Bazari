# Contract: Bridge API — Currency (Android ⇄ Bridge)

**Feature**: Account Currency & Source-Only Capture  
**Base**: [`specs/004-notification-engine/contracts/bridge-api.md`](../../004-notification-engine/contracts/bridge-api.md) and [`specs/001-android-txn-capture/contracts/bridge-http-api.md`](../../001-android-txn-capture/contracts/bridge-http-api.md)

Transport, auth, and the privacy rule (no raw message text) are unchanged. This document replaces the **IRR-only** create-validation rule.

## `GET /v1/accounts` (unchanged shape, stronger client use)

Response is unchanged:

```json
{
  "accounts": [
    { "id": 3, "label": "Bank Mellat", "currency": "IRR", "type": "asset", "isDefault": false },
    { "id": 9, "label": "USD Wallet", "currency": "USD", "type": "asset", "isDefault": false }
  ]
}
```

- `currency` is the code the Android app copies onto captures and shows in the bind UI.
- An account with a missing/blank `currency` MUST NOT be bindable in the app. The bridge may still return it.
- No new query parameters.

## `POST /v1/transactions` (currency rule change)

Request body shape is unchanged from 004 (including optional `accountId` / `destinationAccountId` / `categoryId`).

**Was**: `currency` must be `"IRR"` or the handler returns `400 {"error":"validation","details":["currency must be IRR"]}`.

**Now**:

| Rule | Failure detail (400 `validation`) |
|---|---|
| `currency` present and non-empty after trim | `currency is required` |
| Length 1–16, letters/digits only (typical ISO-like codes) | `currency is invalid` |
| After mapping, Go Money account currency equals `txn.currency` | existing: `mapped Go Money account currency does not match transaction currency` |

Non-rial values (e.g. `"USD"`) are accepted when they match the mapped (or `accountId`) bank account. Counterpart FX conversion when the default expense/income account differs remains as in 001 `gomoney-integration.md`.

Android still applies the IRR amount divisor (`amount / 10`) only when sending `currency == "IRR"`; other currencies send `amountMinor` unchanged.

## `POST /v1/transactions/bulk`

Same currency rules per item as single create.

## `PUT /v1/transactions/assignment`

Unchanged. Does not modify currency.

## Compatibility

- Older app builds that only send IRR continue to work.
- Newer app builds may send other codes; older bridges that still require IRR will 400 — ship bridge and app together on this branch.
- No new endpoints or credentials.
