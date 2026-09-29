# Currency Rate Mode API Contract

## Contract location and coordination

The application consumes generated ConnectRPC/protobuf clients from the external `xskydev/go-money-pb` Buf module. The schema source is not present in this repository. Implementing this feature therefore requires adding the compatible schema fields/enumeration in that module, publishing generated Go and TypeScript clients, and updating both application dependencies. Append new protobuf field numbers; do not renumber or reuse existing fields.

## Schema additions

- Add `CurrencyRateMode` with `UNSPECIFIED`, `MANUAL`, and `AUTOMATIC` values. `UNSPECIFIED` represents no mode and is used for the configured base currency; it is not a persisted mode.
- Add `rate_mode` to the shared `Currency` response model so `GetCurrencies` exposes the current mode.
- Add `rate_mode` to `UpdateCurrencyRequest` so the main app can change a currency's mode. `UNSPECIFIED` means “leave the current mode unchanged,” preserving compatibility with clients that do not send the new field.
- No client-provided mode can assign a mode to the base currency. The service returns `UNSPECIFIED`, fixes its rate to `1`, and rejects/ignores attempts to change its mode according to the existing base-currency update policy.

## Update semantics

- A valid request that changes a non-base currency's rate saves the rate and sets Manual in the same database write, regardless of a simultaneously requested Automatic mode.
- If the rate is unchanged and `rate_mode` is Manual or Automatic, update the mode and retain the rate. Switching Manual to Automatic does not itself fetch a rate; the next valid feed refresh can do so.
- If the rate is unchanged and `rate_mode` is `UNSPECIFIED`, retain the current mode. This lets older clients continue unrelated updates without changing the rate policy.
- A manually created currency defaults to Manual. A previously unknown non-base currency created by a valid feed rate defaults to Automatic.
- `GetCurrencies` returns the current mode for every non-base currency and `UNSPECIFIED` for the base. API consumers that do not understand the new enum field may ignore it.

## Feed-write semantics

- Feed rates must parse as positive decimal values. Invalid or omitted rates leave the current rate and mode unchanged.
- Feed upsert inserts a new non-base currency as Automatic.
- On conflict, the database updates the rate only if the stored row is still Automatic at the time the conflict update executes. This write-time predicate is required so a user save that changes the mode to Manual while a refresh is in flight cannot be overwritten.
- The configured base currency is always rate `1` and mode `UNSPECIFIED`.

## UI contract

- The main currency list shows each non-base currency's mode.
- The existing currency edit screen can change Manual/Automatic for non-base currencies; saving a changed rate visibly results in Manual.
- The base currency is visibly fixed at rate `1` and has no editable mode control.
- The phone app UI is out of scope; older generated clients can ignore the new response field.
