# Quickstart: Account Currency & Source-Only Capture

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md) | **Contracts**: [bridge-api.md](./contracts/bridge-api.md), [source-admission.md](./contracts/source-admission.md)

Validation guide only. Implementation lives in `tasks.md`. Amounts and currency codes below assume a reachable bridge whose `GET /v1/accounts` includes at least one non-IRR account and one IRR account (see [004 quickstart](../004-notification-engine/quickstart.md) for bridge/app boot).

## Prerequisites

- Branch `notification-engine` with this feature implemented (not required to run this file as docs-only).
- Android capture app built from `android/`.
- Bridge (`cmd/android-bridge`) deployed with the relaxed currency validation.
- Go Money accounts: one `IRR`, one other (e.g. `USD`).
- Notification listener (and SMS if testing SMS sources) granted; capture toggles on.

## Automated checks

```bash
make android-gradle-test
go test -p 1 -timeout 60s ./pkg/androidbridge/
```

Expect: pipeline tests cover bound non-IRR capture, unbound hold, stamp-on-bind, no `ParserRegistry` fallback, leftover allow-list ignored; bridge tests accept `USD` create when the mapped account is `USD` and still 400 on blank currency or mapped mismatch.

## Manual walkthrough

### 1. Settings has no allow-list (SC-003)

Open Settings. Confirm server URL, token, notification/SMS toggles, debug, permissions. Confirm **no** bank/package/sender list and no add/remove identifier field.

### 2. Bind shows currency automatically (SC-002)

Open Sources → add/edit. Pick the USD account. The UI shows that account’s currency without a separate currency picker. Pick the IRR account on another source; that one shows IRR because the account uses it.

Refuse: an account with blank currency cannot be saved as the binding.

### 3. Capture follows the bound account (SC-001, SC-007)

Enable a source with a user template bound to USD. Send a matching notification. Event list/detail amount uses **USD**, not a hardcoded rial label. Repeat with the IRR-bound source; that capture is IRR.

### 4. Unbound hold, then stamp (SC-005, SC-008)

Save a source with **no** account. Capture a matching message. Transaction is local, currency unknown, **not** delivered. Bind the USD account. The held row becomes USD and is eligible for delivery. New captures from that source are USD.

### 5. Rebind does not rewrite stamped rows (SC-006)

After a USD capture exists, rebind the source to IRR. New captures are IRR. The earlier USD row stays USD (including if it already delivered).

### 6. Edit currency only before delivery (SC-009)

Open a `queued` or `held` event: currency dropdown works; changing it is what would be sent (once eligible). Open a `sent` event: currency is not editable.

### 7. No source → no capture; parsers off (SC-004, SC-010)

Send a notification from a package that is not an enabled source (including something that used to be only on the Settings allow-list or only handled by a shipped parser). Zero new transactions. Capture of a former built-in bank works only if that seeded (or user) source exists, is enabled, and its template matches.

### 8. Privacy (unchanged)

Bridge logs and `/v1/transactions` bodies contain no raw notification/SMS text.

## Done when

Success criteria SC-001–SC-010 in [spec.md](./spec.md) hold for the walkthrough and automated tests above.
