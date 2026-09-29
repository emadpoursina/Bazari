# Quickstart: Validate Currency Rate Modes

## Prerequisites

- Go 1.25.5 and the repository's configured test database support.
- Node.js/npm and frontend dependencies installed under `frontend/`.
- For manual UI validation, run against a disposable development database and a controllable exchange-rate feed. Do not use a production database to force rates or modes.

## Automated validation

From the repository root, run the currency service and database tests with the CI database bootstrap enabled:

```sh
AUTO_CREATE_CI_DB=true go test ./pkg/currency ./pkg/database
```

The sync tests should cover a legacy/manual currency retaining its rate, an Automatic currency accepting a valid rate, a new feed currency defaulting to Automatic, invalid or omitted rates retaining the last valid value, base rate remaining `1`, and a manual save during a blocked/in-flight feed request winning when the refresh reaches its write.

Run the Angular tests, lint, and production build:

```sh
cd frontend
npm test -- --watch=false
npm run lint
npm run build
```

If validating the complete Go repository, use the existing project target from the root:

```sh
make test
```

## Manual main-app scenario

1. Start the app against a development database after applying migrations. Confirm pre-existing non-base currencies keep their rates and show **Manual**; confirm the base rate is `1` and has no mode control.
2. Edit a non-base rate and save. Confirm the list shows **Manual** and the saved rate remains after reload.
3. Run a successful feed refresh that reports another valid rate for that currency. Confirm the rate remains unchanged.
4. Switch the currency to **Automatic** without changing its rate. Confirm the current rate remains until the next successful refresh, then confirm a valid feed rate is applied.
5. While a refresh is paused after fetch but before its upsert, save a different rate for that currency. Let the refresh continue; confirm the saved rate remains and the mode is Manual.
6. Add a previously unknown currency through a valid feed rate and confirm it appears as Automatic. Omit another currency or provide a zero/invalid rate and confirm its last valid rate and mode remain unchanged.
7. Restart the app and repeat a refresh. Confirm mode and rate persist, and confirm the base rate stays exactly `1`.

Use the existing feed refresh path in the development environment; use the currency sync tests and a controlled HTTP fixture for deterministic invalid-feed and in-flight-write scenarios.
