# Implementation Plan: Notification Source & Template Engine

**Branch**: `notification-engine` | **Date**: 2026-09-30 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/004-notification-engine/spec.md`

## Summary

Let the user teach the Android capture app new transaction sources from inside the app, without a code change or new release. A new **Sources** screen provides in-app CRUD for `TransactionSource` records: display name, originating identifier (package id for notifications, sender id for SMS), channel, a fill-in-the-blank `MessageTemplate` built from a pasted sample message (exactly two placeholders: `{direction}` and `{amount}`), per-source income/expense keyword lists, an enable/disable toggle, and an optional binding to a server account. At capture time the pipeline resolves the event to a user-defined source first (identifier + matching channel), extracts direction and amount from the template, and feeds the result through the **existing** normalization, fingerprint, outbox, dedup, and delivery pipeline. Messages that match a defined source but fail to yield a recognizable direction or a valid positive amount are retained and surfaced in a parse-error review list capped at the last 200 messages or 30 days.

Server-provided account and category lists are fetched through the existing **Android bridge** (which already holds the Go Money service token) via two new read endpoints and are cached locally for offline use. The user can bind a source to a server account, and can set a captured transaction's destination account and category from server-updated drop-downs; those choices are saved locally first and pushed to the same Go Money transaction (never a second one) through a new assignment-update endpoint. Raw message text never leaves the phone and is never written to production logs.

## Technical Context

**Language/Version**:
- Android app: Kotlin 2.x, Android SDK (min SDK 26, target latest stable)
- Bridge: Go 1.25 (matches repo `go.mod`)

**Primary Dependencies**:
- Android: Jetpack Room (sources, parse errors, catalog caches, extended transactions), WorkManager (existing retry/scheduling), Kotlin Coroutines + Flow, DataStore (existing settings), Material 3 Compose (existing UI toolkit), OkHttp + `org.json` (existing bridge client)
- Bridge: Go standard library `net/http` (existing JSON REST surface), existing ConnectRPC generated clients (`transactions.v1`, `accounts.v1`) plus `categories.v1`

**Storage**:
- Android: Room/SQLite `gomoney-capture.db` (schema v3) — new `transaction_sources`, `parse_errors`, `server_accounts`, `server_categories` tables; extended `normalized_transactions`
- Bridge: existing dedup SQLite store; no new persistent store (accounts/categories are read-through to Go Money)
- Go Money PostgreSQL remains the source of truth for accounts and categories (read-only from this feature)

**Testing**:
- Android: JUnit — `TemplateMatcher` fixture tests, source-validation/duplicate tests, Room in-memory tests for the new DAOs, pipeline tests for user-source resolution, assignment-sync tests; `make android-gradle-test`
- Bridge: Go `testing` + `httptest` for `GET /v1/accounts`, `GET /v1/categories`, `PUT /v1/transactions/assignment`, and extended create payloads; `make android-bridge-test`

**Target Platform**: Android phone (notification listener + optional SMS; trusted-LAN client) and the local Android bridge service (existing `cmd/android-bridge`)

**Project Type**: mobile-app + small backend service (the existing two-artifact structure)

**Performance Goals**: capture-to-queued < 2 s locally (unchanged); source save and template test are instant and offline-capable; account/category selectors reflect the server on refresh (SC-006 ≥95% of reachable refreshes); no new delivery latency on the transaction path

**Constraints**:
- Raw message text MUST NOT leave the phone and MUST NOT be written to production logs (FR-023, SC-007) — the bridge contract stays text-free
- Offline-tolerant: source definitions, parse errors, catalog caches, and transaction assignments survive app/device restart and offline periods (FR-006, FR-019)
- Reuse the existing capture/normalization/queue/delivery/dedup pipeline unchanged in behavior (FR-022); user sources add configuration and extraction only
- One template per source; one direction/amount pair per message; amounts in IRR (spec assumptions)
- Plain HTTP + static bearer token to the bridge; existing service token to Go Money (unchanged)

**Scale/Scope**: single user, single phone, home LAN. A handful of user-defined sources; account/category catalogs in the tens; parse-error list bounded at 200 messages / 30 days; outbox scale unchanged from the capture feature

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is still an **unfilled template** (placeholder principle names and no ratified project rules). No project-specific gates can be evaluated or violated, consistent with the treatment recorded in `specs/001-android-txn-capture/plan.md` and `specs/003-currency-rate-modes/plan.md`.

| Gate | Status | Notes |
|------|--------|-------|
| Template constitution placeholders | N/A — no concrete principles defined | De-facto gates from the capture feature (privacy, no unnecessary SDKs, no credentials, offline durability) are all preserved by design |
| Privacy (no raw text to server/logs) | PASS | Template matching runs entirely on-device; bridge payloads and logs remain text-free (FR-023) |
| Reuse over new paths | PASS | User sources plug into the existing parser/pipeline/outbox/dedup; accounts/categories reuse the bridge's existing service-token client |
| Post-Phase 1 re-check | PASS | No violations introduced; the new bridge endpoints are additive `/v1/` read/update surfaces with no new credentials or dependencies |

**Verdict**: PASS (no active gates). No Complexity Tracking entries required.

## Project Structure

### Documentation (this feature)

```text
specs/004-notification-engine/
├── plan.md              # This file (/speckit.plan command output)
├── research.md          # Phase 0 output — decisions & rationale
├── data-model.md        # Phase 1 output — entities, validation, states
├── quickstart.md        # Phase 1 output — end-to-end validation guide
├── contracts/
│   ├── bridge-api.md         # Android ⇄ bridge additions (catalog + assignment)
│   └── source-template.md    # In-app source & template matching contract
└── tasks.md             # Phase 2 output (/speckit.tasks — NOT created here)
```

### Source Code (repository root)

```text
android/app/src/main/kotlin/com/gomoney/capture/
├── model/                       # Channel, Direction, AssignmentSyncState enums
├── source/                      # TransactionSourceRepository, SourceValidator,
│                                #   TemplateMatcher, UserSourceParser
├── storage/                     # Entities.kt (TransactionSource, ParseErrorMessage,
│                                #   ServerAccount, ServerCategory; extended
│                                #   NormalizedTransaction), Daos.kt,
│                                #   AppDatabase.kt (MIGRATION_2_3)
├── capture/                     # CapturePipeline (user-source resolution),
│                                #   CaptureGate (unchanged permission gate)
├── sync/                        # BridgeClient (catalog + assignment calls),
│                                #   CatalogRepository, SyncEngine (assignment sync)
└── ui/                          # SourcesScreen, SourceEditorScreen,
                                 #   ParseErrorReviewList, EventDetailScreen (dropdowns),
                                 #   MainActivity (new Sources tab)

android/app/src/test/kotlin/com/gomoney/capture/
├── source/TemplateMatcherTest.kt
├── source/SourceValidatorTest.kt
├── capture/UserSourcePipelineTest.kt
├── storage/SourceRepositoryTest.kt
└── sync/AssignmentSyncTest.kt

pkg/androidbridge/
├── handlers.go      # GET /v1/accounts, GET /v1/categories,
│                    #   PUT /v1/transactions/assignment; extended create/deliver
├── gomoney.go       # ListCategories + account/category label mapping
├── types.go         # new request/response types, GoMoneyCategory, payload fields
├── server.go        # new routes
└── *_test.go        # endpoint + extended-payload tests
```

**Structure Decision**: The feature is split exactly along the existing capture-feature boundary. All configuration, template matching, extraction, and local persistence live in the Android app under `android/app/src/main/kotlin/com/gomoney/capture/`, reusing the existing `CapturePipeline`/`ParserRegistry`/outbox design rather than introducing a parallel path. Everything that must talk to Go Money (server account/category lists, destination-account/category updates) goes through the existing bridge in `pkg/androidbridge`, which already owns the Go Money service token and the `(bank, accountHint)` mapping and dedup registry. No new deployable artifact is introduced.

## Complexity Tracking

> No constitution violations — table intentionally empty.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| — | — | — |
