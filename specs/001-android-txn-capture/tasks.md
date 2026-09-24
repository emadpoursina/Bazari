---
description: "Task list for Go Money Android Transaction Capture feature implementation"
---

# Tasks: Go Money Android Transaction Capture

**Input**: Design documents from `specs/001-android-txn-capture/`

**Prerequisites**: plan.md ✅, spec.md ✅, research.md ✅, data-model.md ✅, contracts/ (bridge-http-api, gomoney-integration, parser-interface) ✅, quickstart.md ✅

**Tests**: Included. The spec mandates fixture-based parser validation (FR-029), end-to-end duplicate-free verification (FR-030), and quickstart.md defines `./gradlew test` + `go test ./pkg/androidbridge/...` as first-class validation steps. Test tasks are therefore required, not optional.

**Organization**: Tasks grouped by user story. Story phases follow spec priority: US1/US2/US3 (P1), US4/US6 (P2), US5/US7 (P3).

**Path conventions**:
- Android app (standalone Gradle project): `android/app/src/main/kotlin/com/gomoney/capture/...`, tests in `android/app/src/test/kotlin/com/gomoney/capture/...`, parser fixtures in `android/app/src/test/resources/fixtures/<bank>/*.json`
- Bridge (this Go module): `cmd/android-bridge/`, `pkg/androidbridge/`

**Clarified decisions baked in**: Q1=B (plain HTTP + static bearer token, trusted LAN) · Q2=A (single round-trip, single terminal state `SENT`) · Q3=B (±2-minute dedup timestamp tolerance)

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: User story label (US1–US7) — required in story phases, absent in Setup/Foundational/Polish

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Initialize the two deployable artifacts (Android Gradle project + Go bridge command) per plan.md project structure.

- [X] T001 Create Android Gradle project scaffold under `android/` — `settings.gradle.kts`, `android/app/build.gradle.kts`, `gradle/libs.versions.toml` version catalog, `AndroidManifest.xml` with min SDK 26 — declaring dependencies: Kotlin 2.x, Jetpack Compose (Material 3), Room, WorkManager, DataStore, Coroutines/Flow, OkHttp/Retrofit, and NO third-party analytics/ads SDKs (FR-027)
- [X] T002 [P] Create Go bridge entrypoint `cmd/android-bridge/main.go` — flag/env config (`--listen :8787`, `--gomoney-url`, `--gomoney-token`, dedup db path, mappings file path), wire to `pkg/androidbridge`, registered in repo `go.mod` tooling like sibling `cmd/` packages
- [X] T003 [P] Configure lint/format tooling — ktlint/detekt config for `android/`, `golangci-lint`/gofmt alignment for `pkg/androidbridge/`; add both test commands from quickstart.md (`./gradlew test`, `go test ./pkg/androidbridge/...`) to a feature README or Makefile target

**Checkpoint**: Both artifacts build and run empty; `go run ./cmd/android-bridge` starts and `./gradlew test` passes with zero tests.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core models, storage, fingerprint spec, error taxonomy, and the parser plugin contract — every user story depends on these.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [X] T004 Implement Room database and entities in `android/app/src/main/kotlin/com/gomoney/capture/storage/` — `AppDatabase`, entities `RawEvent`, `NormalizedTransaction`, `DeliveryRecord`, `DedupCache` per data-model.md §1–§4 (field types/constraints/indexes incl. unique `fingerprint` index, `DeliveryRecord.state` index, FK `sourceEventId`), plus DAOs for each; all writes transactional
- [X] T005 [P] Implement error taxonomy enum `ErrorCategory` (capture_error, parse_error, validation_error, network_error, server_error, duplicate) and `ParseResult`/`Confidence` types in `android/app/src/main/kotlin/com/gomoney/capture/model/` per research.md R9 and parser-interface.md
- [X] T006 [P] Implement shared Persian/Arabic-Indic numeral & format normalizer in `android/app/src/main/kotlin/com/gomoney/capture/parser/AmountNormalizer.kt` per research.md R10 — map ۰-۹ (U+06F0–F9) and ٠-٩ (U+0660–69) to ASCII, Persian thousand/decimal separators (٬ U+066C, ٫ U+066B) to ASCII, strip currency words, output canonical integer minor units; unit test with Persian-formatted amounts in `android/app/src/test/kotlin/com/gomoney/capture/parser/AmountNormalizerTest.kt`
- [X] T007 Implement deterministic fingerprint + bucket-key computation in `android/app/src/main/kotlin/com/gomoney/capture/parser/Fingerprint.kt` per data-model.md §Fingerprint spec (FR-009, Q3=B): `fingerprint = sha256(bank|accountHint|type|amountMinor|round(ts)|normalizedDescription)`, `bucketKey = bank|accountHint|type|amountMinor|floor(unix(ts)/120)`; unit tests in `android/app/src/test/kotlin/com/gomoney/capture/parser/FingerprintTest.kt` covering identical-source match, ±2 min cross-source match, and distinct-transactions-same-amount non-collapse
- [X] T008 [P] Define `BankParser` interface + `ParserRegistry` skeleton in `android/app/src/main/kotlin/com/gomoney/capture/parser/BankParser.kt` and `ParserRegistry.kt` per contracts/parser-interface.md — ordered registry, first `canParse=true` wins, no allow-list re-check inside registry, purity rules documented
- [X] T009 [P] Implement `ServerConfiguration` DataStore repository in `android/app/src/main/kotlin/com/gomoney/capture/storage/SettingsRepository.kt` per data-model.md §6 — serverUrl, bearerToken (stored encrypted), notificationCaptureEnabled (default true), smsCaptureEnabled (default false), enabledBankPackages, debugModeEnabled (default false)
- [X] T010 [P] Implement bridge dedup SQLite store `pkg/androidbridge/dedup.go` per data-model.md §7 — `FingerprintRegistry` with `fingerprint` PK, indexed `bucketKey`, `gomoneyTxnId`, `recordedAt`; `Lookup(fingerprint)` exact + bucket-window (±2 min) scan API; unit tests in `pkg/androidbridge/dedup_test.go` (temp file db, exact hit, bucket hit, miss)
- [X] T011 [P] Define bridge HTTP types + `GoMoneyClient` interface in `pkg/androidbridge/types.go` and `pkg/androidbridge/gomoney.go` per contracts/gomoney-integration.md — request/response structs mirroring contracts/bridge-http-api.md payloads, error categories 1:1 with app taxonomy, `GoMoneyClient` interface injection point (FR-013 replaceability)

**Checkpoint**: Foundation ready — storage, fingerprint algorithm, parser contract, settings, bridge dedup store, and adapter interfaces exist and unit-test green. User stories can now proceed.

---

## Phase 3: User Story 1 — Automatic transaction capture from bank notifications (Priority: P1) 🎯 MVP

**Goal**: A bank-app notification is captured, stored raw, parsed to a normalized transaction, and queued — with zero user interaction (SC-001).

**Independent Test**: Make a transaction in one supported bank app → a corresponding captured (queued) transaction appears in the app's recent-events list within seconds, without opening the capture app.

### Tests for User Story 1 ⚠️

> Write fixture tests FIRST (FR-029); they must FAIL until parsers T013–T016 are implemented.

- [X] T012 [P] [US1] Create raw-event fixture sets `android/app/src/test/resources/fixtures/{mellat,melli,saman,generic}/*.json` per PRD §18 layout — each fixture: raw event in (source, package, title, text with Persian digits) → expected NormalizedTransaction out (amount, currency IRR, type, timestamp, account hint); cover purchase, withdrawal, deposit, transfer types per bank per FR-029
- [X] T013 [P] [US1] Write fixture-driven parser tests `android/app/src/test/kotlin/com/gomoney/capture/parser/ParserFixtureTest.kt` — parameterized over all fixtures, asserting parser selection via `ParserRegistry`, correct normalization (Persian digits via AmountNormalizer), and failure cases yielding sanitized `Failure(reason, confidence)`; verify tests FAIL before parser implementations land

### Implementation for User Story 1

- [X] T014 [P] [US1] Implement `MellatParser` in `android/app/src/main/kotlin/com/gomoney/capture/parser/MellatParser.kt` — bank slug `mellat`, purchase/withdrawal/deposit patterns, using AmountNormalizer (no hand-rolled digit handling)
- [X] T015 [P] [US1] Implement `MelliParser` in `android/app/src/main/kotlin/com/gomoney/capture/parser/MelliParser.kt` — bank slug `melli`, purchase/transfer patterns
- [X] T016 [P] [US1] Implement `SamanParser` in `android/app/src/main/kotlin/com/gomoney/capture/parser/SamanParser.kt` — bank slug `saman`, purchase/withdrawal patterns
- [X] T017 [P] [US1] Implement `GenericParser` in `android/app/src/main/kotlin/com/gomoney/capture/parser/GenericParser.kt` — bank slug `generic`, conservative high-confidence patterns only; unmatched → `Failure` → parse_error (parser-interface.md MVP table)
- [X] T018 [US1] Register parsers in `ParserRegistry` with priority order (Mellat, Melli, Saman, Generic last) — depends on T008, T014–T017
- [X] T019 [US1] Implement `NotificationCaptureService` in `android/app/src/main/kotlin/com/gomoney/capture/capture/NotificationCaptureService.kt` — `NotificationListenerService` that filters by `ServerConfiguration.enabledBankPackages` allow-list (FR-001), ignores all other apps, writes a `RawEvent` to Room BEFORE any processing (FR-002/003)
- [X] T020 [US1] Implement capture pipeline in `android/app/src/main/kotlin/com/gomoney/capture/capture/CapturePipeline.kt` — RawEvent → allow-list gate → ParserRegistry selection → parse → fingerprint (T007) → persist NormalizedTransaction + DeliveryRecord(state=queued) transactionally; no parser match → retain RawEvent flagged `parse_error` (US6 scenario 2, never silently dropped); honors notificationCaptureEnabled toggle (spec scenario 1.3)
- [X] T021 [US1] Implement minimal recent-events list screen in `android/app/src/main/kotlin/com/gomoney/capture/ui/EventsScreen.kt` — Compose list showing amount, bank, time, delivery state per row, backed by a Room Flow query; sufficient to verify US1 independent test (full dashboard is US4)
- [X] T022 [US1] Add notification capture gating + permission check wiring in `android/app/src/main/kotlin/com/gomoney/capture/capture/CaptureGate.kt` — check `isNotificationListenerAccessGranted` before processing, stop capture when revoked, preserve queued data (edge case); unit test in `android/app/src/test/kotlin/com/gomoney/capture/capture/CapturePipelineTest.kt` covering scenarios 1.1–1.4 with in-memory Room

**Checkpoint**: User Story 1 independently testable — a supported bank notification produces a queued normalized transaction visible in the recent-events list; non-allow-listed notifications are ignored; disabled capture captures nothing; parser fixtures pass (`./gradlew test`).

---

## Phase 4: User Story 2 — Reliable delivery to the local Go Money server (Priority: P1)

**Goal**: Captured transactions survive offline periods and are auto-delivered to Go Money via the bridge, reaching the single terminal `SENT` state (Q2=A, SC-002/004).

**Independent Test**: Capture a transaction with the server unreachable → bring the server online → transaction uploads automatically and is marked sent, exactly once.

### Tests for User Story 2 ⚠️

- [X] T023 [P] [US2] Write bridge API contract tests in `pkg/androidbridge/server_test.go` — httptest-based coverage of `POST /v1/ping` (ok / gomoney unreachable variants), `POST /v1/transactions` (201 created, 200 duplicate, 400 validation, 401 unauthorized, 502 gomoney_unreachable, 500 gomoney_error) and `POST /v1/transactions/bulk` per-item results per contracts/bridge-http-api.md, using a mocked `GoMoneyClient`; verify tests FAIL before handler implementation
- [X] T024 [P] [US2] Write Android `BridgeClient` + sync worker tests in `android/app/src/test/kotlin/com/gomoney/capture/sync/BridgeClientTest.kt` and `TransactionSyncWorkerTest.kt` — OkHttp MockWebServer per response mapping (201→SENT, 200+duplicate→SENT with errorCategory=duplicate, 400→validation_error no auto-retry, 502→network_error retry, 500→server_error retry); verify tests FAIL before implementation

### Implementation for User Story 2 — Bridge side

- [X] T025 [P] [US2] Implement bridge HTTP server + bearer-token auth middleware in `pkg/androidbridge/server.go` and `pkg/androidbridge/auth.go` — routes `/v1/ping`, `/v1/transactions`, `/v1/transactions/bulk` per contracts/bridge-http-api.md; constant-time token compare; 401 `{"error":"unauthorized"}` on mismatch; JSON error model 1:1 with app categories
- [X] T026 [US2] Implement transaction handler + validation in `pkg/androidbridge/handlers.go` — validate payload (amount > 0, currency IRR, fingerprint 64-hex, enums) → 400 validation without touching Go Money; enforce FR-013 flow: dedup lookup (T010) → miss: create via `GoMoneyClient` → update registry → 201 created; hit: → 200 duplicate; Go Money failure → rollback fingerprint row → 502/500; synchronous single round-trip only, no internal queueing
- [X] T027 [US2] Implement Go Money adapter in `pkg/androidbridge/gomoney_client.go` per contracts/gomoney-integration.md — ConnectRPC `POST /gomoneypb.transactions.v1.TransactionsService/CreateTransaction` with service token (env `GOMONEY_SERVICE_TOKEN`/flags), mapping: type→TRANSACTION_TYPE_EXPENSE/INCOME, title `<description> [<bank>/<accountHint>]`, destination_amount decimal string, IRR, txAt ISO-8601; implements `GoMoneyClient` interface
- [X] T028 [P] [US2] Implement account-hint mapping store + management endpoints in `pkg/androidbridge/mappings.go` — `mappings.json` `{"mellat|****1234": 1}` atomic read/write, exact `(bank, accountHint) → source_account_id` resolution, unmatched → 400 validation `unmapped account` WITHOUT calling Go Money; `GET /v1/mappings` + `PUT /v1/mappings` bearer-protected endpoints
- [X] T029 [P] [US2] Implement bridge logging rules in `pkg/androidbridge/logging.go` per contracts/bridge-http-api.md — log timestamp, endpoint, fingerprint prefix (8 hex), outcome, latency only; never full payloads/description/accountHint (FR-028); wire into handlers

### Implementation for User Story 2 — Android side

- [X] T030 [US2] Implement `BridgeClient` in `android/app/src/main/kotlin/com/gomoney/capture/sync/BridgeClient.kt` — OkHttp client, base URL + static bearer token from SettingsRepository, `POST /v1/ping`, `POST /v1/transactions`, `POST /v1/transactions/bulk` (used when ≥5 items queued, max 50), response → status/errorCategory mapping (FR-014)
- [X] T031 [US2] Implement `TransactionSyncWorker` in `android/app/src/main/kotlin/com/gomoney/capture/sync/TransactionSyncWorker.kt` — WorkManager unique periodic + expedited on-demand work, `NetworkType.CONNECTED` constraint, exponential backoff, drains QUEUED/FAILED rows oldest-first, transitions SENDING→SENT/FAILED, auto-retry on reachability without user action (FR-011/012), enqueued at pipeline completion (T020) and on app start so queue survives app/phone restarts (FR-016)
- [X] T032 [US2] Implement delivery state machine helpers in `android/app/src/main/kotlin/com/gomoney/capture/storage/DeliveryRepository.kt` — legal transitions per data-model.md §3 (CAPTURED→PARSED→QUEUED→SENDING→SENT; SENDING→FAILED→QUEUED on retry; SENT terminal immutable; duplicate ack → state=sent + errorCategory=duplicate; FAILED requires non-null category; attempt counter + lastAttemptAt/nextRetryAt bookkeeping); Room in-memory tests for illegal-transition rejection in `android/app/src/test/kotlin/com/gomoney/capture/storage/DeliveryRepositoryTest.kt`

**Checkpoint**: User Stories 1+2 independently testable — offline capture queues durably; server back → auto-delivery within 2 min; bridge maps to Go Money and acks only after recording; rejection → failed with visible category; bridge contract tests green (`go test ./pkg/androidbridge/...`).

---

## Phase 5: User Story 3 — Duplicate prevention (Priority: P1)

**Goal**: The same real-world transaction never creates two Go Money transactions — identical resends, notification+SMS pairs (±2 min), and retry races all collapse to one (SC-003, FR-015, FR-030).

**Independent Test**: Deliver the same captured transaction twice (identical and re-derived variants) → Go Money records exactly one transaction.

### Tests for User Story 3 ⚠️

- [X] T033 [P] [US3] Write bridge dedup-enforcement tests in `pkg/androidbridge/dedup_test.go` + `handlers_dedup_test.go` — exact fingerprint → duplicate; ±2 min bucket-window match (different timestamps, same bank/account/type/amount) → duplicate; distinct transactions seconds apart, differing descriptions → both created; concurrent duplicate deliveries → exactly one 201 (mutex/transaction safety); verify tests FAIL before enforcement lands in handlers
- [X] T034 [P] [US3] Write app-side dedup tests in `android/app/src/test/kotlin/com/gomoney/capture/storage/DedupRepositoryTest.kt` — local DedupCache short-circuit after restart, outcome sent|duplicate recorded, fingerprint reuse across sources via shared Fingerprint.kt

### Implementation for User Story 3

- [X] T035 [US3] Enforce dedup in bridge delivery flow in `pkg/androidbridge/handlers.go` (extend T026) — exact fingerprint lookup → 200 duplicate; else bucketKey window scan (±2 min, bank+account+type+amount match, per data-model.md §7) → duplicate; else insert row → CreateTransaction → update gomoneyTxnId, rollback on failure (contracts/gomoney-integration.md §Dedup)
- [X] T036 [US3] Implement app-side `DedupRepository` usage in `android/app/src/main/kotlin/com/gomoney/capture/storage/DedupRepository.kt` — before sending, check DedupCache; on terminal ack write (fingerprint, outcome) so restarts don't re-send known duplicates; wire into TransactionSyncWorker (T031) and CapturePipeline (T020) so a re-captured identical event is short-circuited locally

**Checkpoint**: User Story 3 testable independently — quickstart Scenario 3 passes: identical resend → duplicate; notification+SMS ≤2 min apart → one record; distinct same-amount nearby transactions → both recorded.

---

## Phase 6: User Story 4 — Visibility into capture status (Priority: P2)

**Goal**: Dashboard shows health at a glance: connection status, captured-today/pending/sent/failed counts, recent events with delivery state and error categories — no sensitive raw text exposed (SC-006).

**Independent Test**: Open the app after several capture/delivery events → dashboard counts and recent-events list match reality within 5 seconds.

### Implementation for User Story 4

- [X] T037 [US4] Implement `DashboardViewModel` + dashboard screen in `android/app/src/main/kotlin/com/gomoney/capture/ui/DashboardScreen.kt` and `DashboardViewModel.kt` — captured-today, pending, sent, failed counts and connection status (derived from last /v1/ping or sync outcome) via Room Flow queries (FR-020)
- [X] T038 [US4] Extend `EventsScreen` (T021) with event detail in `android/app/src/main/kotlin/com/gomoney/capture/ui/EventDetailScreen.kt` — amount, bank, time, delivery state, error category (FR-023 taxonomy, sanitized detail only; raw text hidden unless debug mode — delegated to US7)
- [X] T039 [P] [US4] Implement settings UI in `android/app/src/main/kotlin/com/gomoney/capture/ui/SettingsScreen.kt` — server URL + bearer token editors, "Test connection" button hitting `/v1/ping` (FR-019), independent notification/SMS capture toggles (FR-017), bank app allow-list picker of installed packages (FR-018), notification-listener (and SMS) permission status display + system-settings deep link guidance (FR-024)
- [X] T040 [US4] Implement manual retry + clear-processed actions in `android/app/src/main/kotlin/com/gomoney/capture/ui/EventActions.kt` and `storage/MaintenanceRepository.kt` — retry button requeues FAILED → QUEUED and triggers expedited sync (US2.4, FR-022); clear removes ONLY sent/terminal rows, preserving pending/failed (spec edge case); integration test in `android/app/src/test/kotlin/com/gomoney/capture/storage/MaintenanceRepositoryTest.kt`

**Checkpoint**: User Story 4 independently testable — dashboard counts/list match Room state; failures show category without raw text; manual retry and clear-processed work per edge cases.

---

## Phase 7: User Story 6 — Adding a new bank parser without touching the core (Priority: P2)

**Goal**: New bank support = one new parser class + fixtures; zero changes to capture/queue/delivery (SC-005, FR-008).

**Independent Test**: Add a parser + fixture for a new bank → its events flow capture → parse → queue → deliver with no core changes (quickstart Scenario 5).

### Implementation for User Story 6

- [X] T041 [P] [US6] Document the add-a-bank workflow in `specs/001-android-txn-capture/parser-guide.md` — step-by-step: create parser implementing `BankParser`, add fixtures, register in ParserRegistry (the ONLY core line touched), enable package in allow-list; reference contracts/parser-interface.md rules
- [X] T042 [US6] Verify extensibility with a sample fourth-bank parser in `android/app/src/main/kotlin/com/gomoney/capture/parser/SampleBankParser.kt` + fixtures `android/app/src/test/resources/fixtures/samplebank/*.json` — demonstrate that `git diff` shows only new parser + fixtures + one registry line; parse-error retention path (no parser match → flagged, not dropped) asserted in `ParserFixtureTest`

**Checkpoint**: User Story 6 independently testable — new bank onboarded with parser+fixtures only; unmatched events surface as parse errors.

---

## Phase 8: User Story 5 — Optional SMS capture (Priority: P3)

**Goal**: SMS as an independently toggleable second capture source, parsed by the same pipeline and deduped against notifications (FR-004).

**Independent Test**: Enable SMS capture → bank transaction SMS creates a normalized transaction; disable → SMS ignored; same transaction via both channels dedupes to one.

### Implementation for User Story 5

- [X] T043 [P] [US5] Implement `SmsCaptureReceiver` in `android/app/src/main/kotlin/com/gomoney/capture/capture/SmsCaptureReceiver.kt` — RECEIVE_SMS permission, bank SMS sender allow-list (from enabledBankPackages / sender config), writes RawEvent(source=sms) via same pipeline; gated by smsCaptureEnabled (FR-017), ignored entirely when off (US5 scenario 2)
- [X] T044 [P] [US5] Add SMS sender fixtures `android/app/src/test/resources/fixtures/{mellat,melli,saman}/*sms*.json` — bank transaction SMS bodies (Persian) → expected normalized output, proving cross-source same-pipeline parsing (FR-029)
- [X] T045 [US5] Verify cross-source dedup end-to-end in `android/app/src/test/kotlin/com/gomoney/capture/capture/CrossSourceDedupTest.kt` — notification+SMS of the same transaction ≤2 min apart → single delivery (Fingerprint bucket window, T007/T036); extend quickstart Scenario 3 step 2 validation

**Checkpoint**: User Story 5 independently testable — SMS toggled on/off behaves per scenarios; notification+SMS duplicates collapse via the shared fingerprint.

---

## Phase 9: User Story 7 — Parser development mode (Priority: P3)

**Goal**: Debug mode reveals raw event fields + parse result details for fast parser iteration; raw text never shown in normal UI or production logs (FR-025, US7 scenario 2).

**Independent Test**: Enable debug mode → captured event shows source, package, title, text, parsed type/amount/currency/account hint, parser name, confidence.

### Implementation for User Story 7

- [X] T046 [P] [US7] Implement debug event detail view in `android/app/src/main/kotlin/com/gomoney/capture/ui/DebugEventScreen.kt` — gated by `debugModeEnabled`; renders RawEvent (source, package, title, text) alongside parse result (type, amount, currency, account hint, parserName, confidence) from Room
- [X] T047 [US7] Implement log redaction + debug gating in `android/app/src/main/kotlin/com/gomoney/capture/logging/Redactor.kt` — production logs never contain raw message text or amounts (FR-028, SC-007); debug mode OFF → raw text hidden in all UI paths (T038 detail view); unit test in `android/app/src/test/kotlin/com/gomoney/capture/logging/RedactorTest.kt`

**Checkpoint**: User Story 7 independently testable — quickstart Scenario 7 passes: debug on reveals all fields, off hides raw text everywhere.

---

## Phase 10: Polish & Cross-Cutting Concerns

**Purpose**: Privacy/permission audit, durability/performance validation, and end-to-end acceptance across all stories.

- [X] T048 [P] Audit permissions & dependencies against FR-005/006/026/027 in `android/app/build.gradle.kts` and `android/app/src/main/AndroidManifest.xml` — only INTERNET, POST_NOTIFICATIONS(+listener), RECEIVE_SMS (if SMS enabled); no accessibility services, no analytics/ads libraries; verify no credentials stored anywhere (EncryptedSharedPreferences for token only)
- [X] T049 [P] Document bridge setup & run instructions in `specs/001-android-txn-capture/bridge-runbook.md` — service-token creation via `ConfigurationService/CreateServiceToken`, env/flags, mappings.json format, LAN firewall notes; update repo-level feature README
- [ ] T050 Performance & durability validation — capture-to-queued < 2 s (SC-001) measured on device; drain of a ~200-item queue completes within 2 min of reachability (SC-004); device reboot mid-queue retains all items in order (FR-016); record results in `specs/001-android-txn-capture/validation-notes.md`
- [ ] T051 Run full quickstart.md validation — execute Scenarios 1–7 end-to-end (capture, offline queue + restart, three-way dedup, rejection + manual retry, new-bank add-on, permission/privacy audit, debug mode) and confirm the success-mapping table; fix or file follow-ups for any failure (FR-030, SC-008)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: no dependencies — start immediately. T002/T003 parallel with T001.
- **Foundational (Phase 2)**: depends on Phase 1 (T004–T011 need project scaffolds). BLOCKS all user stories. Within Phase 2: T004 first (Room schema); T005/T006/T008/T009/T010/T011 fully parallel; T007 depends on T005 (types) + T006 (normalizer) only for tests.
- **US1 (Phase 3)**: depends on Phase 2. Fixtures/tests (T012–T013) before parsers (T014–T017); registry (T018) after parsers; capture service + pipeline (T019–T022) after registry.
- **US2 (Phase 4)**: depends on Phase 2 (and benefits from US1's pipeline for end-to-end runs). Bridge tests (T023) and Android tests (T024) first; bridge implementation T025–T029 (T026 after T025; T027 after T011; T028/T029 parallel); Android implementation T030–T032 (T031/T032 after T030).
- **US3 (Phase 5)**: depends on Phase 2 + Phase 4 (dedup enforcement extends the delivery flow). Tests T033–T034 parallel; implementation T035–T036.
- **US4 (Phase 6)**: depends on US1 (events exist) + US2 (states/errors exist). T037→T038 sequential-ish (same screen family); T039 parallel; T040 after T031 (sync worker for retry trigger).
- **US6 (Phase 7)**: depends on US1 (parser registry + fixtures machinery). T041 parallel with T042.
- **US5 (Phase 8)**: depends on US1 (pipeline) + US3 (cross-source dedup). T043/T044 parallel; T045 last.
- **US7 (Phase 9)**: depends on US1 (events) + US4 (detail screen). T046 parallel with T047.
- **Polish (Phase 10)**: depends on all desired user stories. T048–T049 parallel; T050–T051 last.

### User Story Dependencies

- **US1 (P1)**: after Foundational — no cross-story dependency
- **US2 (P1)**: after Foundational — independently testable (bridge + worker), integrates with US1 pipeline for full journey
- **US3 (P1)**: after Foundational — enforcement point is US2's flow but logic is self-contained and independently testable via mocked client
- **US4 (P2)**: after US1+US2 (consumes their data) — but its UI code touches only distinct files
- **US6 (P2)**: after US1 — proves extensibility of the US1 registry
- **US5 (P3)**: after US1+US3 — reuses pipeline and dedup
- **US7 (P3)**: after US1 (+US4 for shared detail scaffolding)

### Within Each User Story

- Tests written and FAILING before implementation (FR-029/FR-030 mandate)
- Models/storage before services, services before UI, core before integration
- Story checkpoint = independently demonstrable increment

### Parallel Opportunities

- Phase 2: T005, T006, T008, T009, T010, T011 all parallel (distinct files/modules)
- Phase 3: T012–T016 parallel (fixtures + four parser files)
- Phase 4: T023/T024 parallel; bridge tasks parallel with Android tasks (T025–T029 vs T030–T032 once interfaces fixed)
- Phase 6/7/8/9: marked [P] tasks within each story
- Bridge (Go) and Android (Kotlin) workstreams are parallelizable across ALL stories by different developers after Phase 2

---

## Parallel Example: Phase 2 (Foundational)

```bash
# Launch all independent foundational tasks together:
Task: "Error taxonomy + ParseResult types — android/.../model/"
Task: "Persian amount normalizer + tests — android/.../parser/AmountNormalizer.kt"
Task: "ServerConfiguration DataStore — android/.../storage/SettingsRepository.kt"
Task: "Bridge dedup SQLite store + tests — pkg/androidbridge/dedup.go"
Task: "Bridge types + GoMoneyClient interface — pkg/androidbridge/types.go, gomoney.go"
```

## Parallel Example: User Story 1

```bash
# All parser implementations + fixtures together:
Task: "MellatParser — android/.../parser/MellatParser.kt"
Task: "MelliParser — android/.../parser/MelliParser.kt"
Task: "SamanParser — android/.../parser/SamanParser.kt"
Task: "GenericParser — android/.../parser/GenericParser.kt"
```

---

## Implementation Strategy

### MVP First (User Stories 1–3 Only)

1. Complete Phase 1: Setup
2. Complete Phase 2: Foundational (CRITICAL — blocks all stories)
3. Complete Phase 3 (US1) → validate: capture appears in recent-events list
4. Complete Phase 4 (US2) → validate: offline queue drains to Go Money
5. Complete Phase 5 (US3) → validate: quickstart Scenario 3 zero-duplicate
6. **STOP and VALIDATE**: the core promise (capture → one recorded transaction, hands-free) is delivered — SC-001/002/003/008 all demonstrable

### Incremental Delivery

- After MVP: add US4 (visibility → user trust), US6 (cheap bank onboarding), then US5 + US7 (optional channels/tooling)
- Each story adds value without breaking previous stories; every story checkpoint is independently testable

### Parallel Team Strategy

- Developer A (Go): bridge tasks T002, T010–T011, T023, T025–T029, T033, T035, T049
- Developer B (Kotlin): everything under `android/`
- Join points: Phase 2 interface definitions (T008, T011), Phase 4 contract tests (T023/T024), Phase 5 dedup semantics (T033–T036)

---

## Notes

- [P] tasks = different files, no dependencies on incomplete tasks
- [Story] labels map tasks to spec user stories for traceability (US-numbering follows spec.md, hence no US-label phase reorders priorities: P1 → US1,US2,US3; P2 → US4,US6; P3 → US5,US7)
- Clarified decisions are fixed: plain HTTP + bearer token (Q1=B), single-SENT ack semantics (Q2=A), ±2 min dedup tolerance (Q3=B)
- Every parser change must go through fixtures (FR-029); every dedup change must re-run Scenario 3 (FR-030)
- Raw message text never leaves the phone, never enters logs, never enters errorDetail (FR-023/028)
- Commit after each task or logical group; stop at any checkpoint to validate the story independently
