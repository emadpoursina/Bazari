# Research: Per-Currency Exchange Rate Modes

## Findings and decisions

### Persist the mode on the existing currency record

- **Decision**: Add a nullable `rate_mode` column to `currencies`, represented by the domain values `manual` and `automatic`. `NULL` means the configured base currency, which has no mode. Keep a database default of `manual`; the feed explicitly marks newly inserted non-base currencies `automatic`.
- **Rationale**: Currency identity, current rate, and active/precision settings already live in `database.Currency`. A nullable mode expresses the base-currency exception without inventing a third mode. A safe manual default protects currencies created outside the feed.
- **Alternatives considered**: A separate mode table would require additional joins and transactional coordination for one field. A boolean would obscure the two user-facing states and make the no-mode base case less clear. Defaulting all new database rows to Automatic would make administrative currency creation unexpectedly feed-controlled.

### Roll out existing data safely

- **Decision**: In a new Gormigrate migration, preserve existing rates, set every existing non-base currency to Manual, set the configured base currency to rate `1` and mode `NULL`, and retain `manual` as the default for non-feed inserts.
- **Rationale**: The specification requires a non-disruptive transition for existing rates and the base-rate invariant. Migrations are currently declared as ordered SQL migrations in `pkg/database/migrations.go`.
- **Alternatives considered**: Backfilling existing currencies as Automatic could overwrite rates users previously edited and contradicts the explicit rollout requirement. Inferring a user's intent from `updated_at` is unreliable and unnecessary.

### Make the feed write race-safe at the database boundary

- **Decision**: Validate incoming rates as positive decimals, then use the existing PostgreSQL `INSERT ... ON CONFLICT` path. New feed-created rows are Automatic. On conflict, update the rate only when the stored row is still Automatic, by adding a predicate to the `DO UPDATE` clause. Do not fetch mode before the upsert and rely on that stale value.
- **Rationale**: The guarded upsert rechecks the persisted mode at write time. If a manual save commits first, the refresh's conditional update does not replace it; if the refresh commits first, the later manual save writes both the user rate and Manual mode. This directly implements the resolved clarification without an extra per-currency query.
- **Alternatives considered**: Checking mode in Go before writing has a time-of-check/time-of-use race. Updating all conflicts would continue to overwrite Manual rates. Skipping only currencies that were Manual when the refresh began would still lose a concurrent user edit.

### Keep updates atomic and backward-compatible

- **Decision**: Save a changed user rate and Manual mode together. Expose an explicit mode update through the shared currency Connect/protobuf contract; `UNSPECIFIED` means no mode change, while a non-base response reports its current mode. If a request changes both rate and mode, the changed rate forces Manual. The base is reported as `UNSPECIFIED` and cannot be assigned a mode. Update the external generated Go and TypeScript client packages compatibly.
- **Rationale**: The service's current `UpdateCurrency` endpoint always receives the rate along with other editable fields, so it must compare the parsed rate to the stored decimal to distinguish a changed rate from an unrelated edit by an older client. The frontend currently uses generated clients from `xskydev/go-money-pb`; a shared contract avoids adding a one-off HTTP protocol.
- **Alternatives considered**: A UI-only setting would not survive refresh/restart and could not be enforced by the sync process. A new ad hoc endpoint would diverge from the existing Connect service. Treating every update request as a manual rate edit would unintentionally change mode when a user only edits another field.

### Validate independently at the sync and presentation boundaries

- **Decision**: Ignore zero/negative or otherwise unparsable feed rates; omitted currencies and failed refreshes leave stored rate and mode untouched. Keep the base at exactly `1`. Display the mode in the main currency list and provide a Manual/Automatic control on the existing edit screen, hidden or disabled for the base currency.
- **Rationale**: This preserves the established refresh path and meets the spec's invalid/incomplete-feed, visibility, and base-currency behavior. The existing UI already has list and upsert screens for currency records.
- **Alternatives considered**: Removing currencies absent from a feed risks data loss and is not needed for rates. Allowing users to set the base mode would create a meaningless state and threaten the fixed-rate invariant.

## Existing project evidence

- `pkg/currency/sync.go` performs a single transaction and PostgreSQL conflict upsert per feed rate; its existing upsert currently always updates `rate`.
- `pkg/database/struct.go` defines the GORM currency row; `pkg/database/migrations.go` uses ordered Gormigrate migrations and raw SQL.
- `pkg/currency/service.go` implements `GetCurrencies`/`UpdateCurrency` and maps the row to generated protobuf types. `UpdateCurrency` already forces the configured base rate to `1`.
- The frontend's `currencies-list.component.*` and `currencies-upsert.component.*` are the existing main-app currency views. Go and TypeScript protobuf/Connect code is generated from a separately consumed Buf module; no `.proto` source is present in this repository.
- The project test entry point is `make test`, which sets `AUTO_CREATE_CI_DB=true` before running Go tests. The frontend package exposes `npm test`, `npm run lint`, and `npm run build`.

## Clarifications

The feature specification's only concurrency clarification is resolved: a manual edit wins over an in-flight refresh, and the refresh rechecks mode at the database write. No additional product behavior remains unresolved. The external shared-protobuf change is an implementation coordination dependency, not an open behavior question.

## Local-first implementation note (2026-09-29)

The checked-in clients are pinned to a revision without `CurrencyRateMode` or `rate_mode`; no protobuf source exists in this repository, and the external module cannot be published from this workspace. T001/T002 are therefore deferred, not replaced with generated-client edits. Local persistence and sync use the database enum; changed rates become Manual, new feed rows become Automatic, and the base remains rate `1` with a null local mode. The service's single local-to-protobuf mapping point currently returns `UNSPECIFIED` for either non-base mode and the base. Responses cannot expose the value until the shared response field exists, so mode reporting/selection and the main-app UI tasks remain open. Phone UI remains out of scope. Automated application-restart coverage is not available in the current test harness; quickstart manual step 7 remains the persistence check.
