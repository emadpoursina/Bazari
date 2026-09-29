# Implementation Plan: Per-Currency Exchange Rate Modes

**Branch**: `exchange-rate-sync` | **Date**: 2026-09-29 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/003-currency-rate-modes/spec.md`

## Summary

Persist a Manual or Automatic mode for every non-base currency, show and let users change that mode in the main Angular app, and make rate synchronization respect it. Existing non-base rates migrate to Manual; feed-created currencies start Automatic. A database-side conditional upsert rechecks the current mode at write time, so a concurrent user edit wins over an in-flight feed refresh.

## Technical Context

**Language/Version**: Go 1.25.5; TypeScript 5.9 / Angular 21

**Primary Dependencies**: GORM 1.31, Gormigrate, shopspring/decimal, ConnectRPC and generated protobuf clients; Angular reactive forms and PrimeNG

**Storage**: PostgreSQL `currencies` table; schema changes use `pkg/database/migrations.go`

**Testing**: Go package/integration tests (`AUTO_CREATE_CI_DB=true go test ./pkg/currency ./pkg/database`); Angular tests (`cd frontend && npm test -- --watch=false`), lint, and build

**Target Platform**: Go ConnectRPC API and main Angular web app; Android/phone UI is unchanged

**Project Type**: Full-stack web application with Go API and Angular frontend

**Performance Goals**: Preserve the current feed cadence and per-refresh behavior; the mode check is part of the existing currency upsert, with no additional per-currency read

**Constraints**: Currency rates use decimal precision; PostgreSQL must atomically guard conflict updates by the stored mode. The shared Connect/protobuf schema and generated Go/TypeScript packages are consumed from the external `xskydev/go-money-pb` module and must be updated compatibly.

**Scale/Scope**: One mode per currency row; currency API, sync logic, migration, and the main app currency list/edit screens. Feed schedule, rate source, and phone UI remain unchanged.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

**Pre-design gate: PASS.** `.specify/memory/constitution.md` currently contains only unfilled template placeholders and defines no ratified principles or project-specific gates. The design follows the existing Go/GORM/Connect and Angular patterns and adds regression tests for the new behavior. No active constitution violation is identified.

## Project Structure

### Documentation (this feature)

```text
specs/003-currency-rate-modes/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/currency-rate-mode.md
└── tasks.md                  # Produced by the tasks workflow
```

### Source Code (repository root)

```text
pkg/
├── currency/
│   ├── service.go             # API mapping and manual/mode updates
│   ├── sync.go                # validated, mode-guarded feed upserts
│   ├── service_test.go
│   └── sync_test.go
└── database/
    ├── struct.go              # persisted currency mode
    └── migrations.go          # rollout migration and legacy defaults

frontend/src/app/pages/currencies/
├── currencies-list.component.ts
├── currencies-list.component.html
├── currencies-upsert.component.ts
└── currencies-upsert.component.html
```

**Structure Decision**: Extend the existing currency service/sync and database migration packages, and the existing Angular currency list/upsert pages. The API schema is maintained in the shared external `xskydev/go-money-pb` module; this repository consumes its generated Go and TypeScript clients. Contract changes and compatibility semantics are documented in `contracts/currency-rate-mode.md`.

**Post-design gate: PASS.** No active constitution rules exist to violate. The design uses the existing currency record and transaction boundaries, and includes migration, API, concurrency, and UI validation.

## Local-First Implementation Boundary

The pinned generated Go and TypeScript clients do not contain `CurrencyRateMode` or `rate_mode`, and this repository has no local protobuf source. Per the implementation authorization, the local database, service, and sync behavior can proceed without publishing or modifying the external module. The service keeps one translation point that maps persisted Manual/Automatic modes to protobuf `UNSPECIFIED` (numeric zero) until compatible generated clients are available. API mode reporting/selection and the dependent main-app UI remain deferred; no phone UI changes are in scope.

## Complexity Tracking

No constitution violations require justification.
