# Implementation Plan: Go Money Android Transaction Capture

**Branch**: `android-txn-capture` | **Date**: 2026-09-24 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/001-android-txn-capture/spec.md` (derived from `/scratch/androidTracker.md` PRD)

## Summary

Build a two-part MVP that eliminates manual transaction entry in Go Money:

1. **Android capture app** (Kotlin) — a local-first background collector that listens to notifications (primary) and optionally SMS (secondary) from explicitly configured Iranian bank/payment apps, stores every raw event locally, parses it into a provider-independent `NormalizedTransaction` via a pluggable per-bank parser layer, queues it in a durable local outbox (Room/SQLite), and delivers it to a local bridge over plain HTTP with a static bearer token. Single terminal success state `SENT` (one HTTP round-trip; the bridge acknowledges only after Go Money records the transaction). Deterministic deduplication fingerprint with ±2-minute timestamp tolerance across capture sources.
2. **Transaction bridge** (Go, in this repo) — a small HTTP service that authenticates the Android client (static bearer token), validates payloads, maps normalized transactions to Go Money's `TransactionsService/CreateTransaction` (ConnectRPC), enforces final deduplication against a fingerprint registry, and returns success only after Go Money records the transaction.

Go Money remains the source of truth; the app never touches its database.

## Technical Context

**Language/Version**:
- Android app: Kotlin 2.x, Android SDK (min SDK 26, target latest stable)
- Bridge: Go 1.25 (matches repo `go.mod`)

**Primary Dependencies**:
- Android: Jetpack Room (outbox persistence), WorkManager (retry/refresh scheduling), Kotlin Coroutines + Flow, DataStore (settings), Material 3 (minimal UI), OkHttp/Retrofit (bridge HTTP client)
- Bridge: Go standard library `net/http` (small JSON REST surface), Go Money ConnectRPC client (`gomoneypb.transactions.v1`)

**Storage**:
- Android: local Room/SQLite database (raw events, normalized transactions, outbox/delivery records, dedup fingerprints, settings mirror)
- Bridge: in-repo persistence for the dedup fingerprint registry (SQLite file or Go Money-side marker — resolved in research.md R4)

**Testing**:
- Android: JUnit + fixture-based parser tests (`fixtures/<bank>/*.json`), Room in-memory tests, WorkManager test helpers
- Bridge: Go `testing` + httptest; integration test bridge → (mocked) Go Money

**Target Platform**:
- Android phone (notification listener + optional SMS permissions; trusted-LAN client)
- Bridge: runs on the user's machine inside the home network (macOS/Linux binary or `go run`), default listen `:8787`

**Project Type**: mobile-app + small backend service (two deployable artifacts in one repo)

**Performance Goals**: capture-to-queued < 2 s locally; capture-to-visible-in-app ≤ 10 s (SC-001); queued delivery drains within 2 min of server reachability (SC-004)

**Constraints**:
- Plain HTTP + static bearer token only (trusted LAN; clarified decision Q1=B) — no TLS in MVP
- No third-party analytics/ads SDKs; no unnecessary permissions (FR-026/027)
- No bank credentials stored (FR-006); no raw sensitive text in production logs (FR-028)
- Offline-tolerant: queue survives app restart, phone restart, network and server failure (FR-016)

**Scale/Scope**: single-user, single-phone, home LAN. 2–4 bank parsers for the MVP (per spec assumption). Outbox sized for days of offline queueing (hundreds of events), not millions.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is currently an **unfilled template** (placeholder principle names, no ratified rules for this project). Therefore no project-specific gates can be evaluated or violated.

| Gate | Status | Notes |
|------|--------|-------|
| Template constitution placeholders | N/A — no concrete principles defined | Recommend ratifying a real constitution before /speckit.tasks; treat PRD §12 privacy rules (FR-006/026/027/028) as de-facto gates, all satisfied by design (no credentials, local-only data, no analytics SDKs, redacted logs) |
| Post-Phase 1 re-check | PASS | No violations introduced; plain-HTTP decision is a *spec-level* clarification (Q1=B), not a constitution matter |

**Verdict**: PASS (no active gates). No Complexity Tracking entries required.

## Project Structure

### Documentation (this feature)

```text
specs/001-android-txn-capture/
├── plan.md              # This file
├── research.md          # Phase 0 output — decisions & rationale
├── data-model.md        # Phase 1 output — entities, states, fingerprint spec
├── quickstart.md        # Phase 1 output — end-to-end validation guide
├── contracts/
│   ├── bridge-http-api.md     # Android ⇄ bridge REST contract
│   ├── gomoney-integration.md # bridge ⇄ Go Money ConnectRPC contract
│   └── parser-interface.md    # in-app BankParser plugin contract
└── tasks.md             # Phase 2 output (/speckit.tasks — NOT created here)
```

### Source Code (repository root)

```text
android/                              # Android capture app (new, self-contained Gradle project)
├── app/src/main/kotlin/.../
│   ├── capture/                      # NotificationCaptureService, SmsCaptureReceiver
│   ├── parser/                       # BankParser, MellatParser, MelliParser, GenericParser, ParserRegistry
│   ├── model/                        # RawEvent, NormalizedTransaction, DeliveryRecord
│   ├── storage/                      # Room DB: raw events, outbox, dedup cache, settings
│   ├── sync/                         # TransactionSync worker (WorkManager), BridgeClient
│   └── ui/                           # Dashboard, Events, Settings, DebugMode screens
└── app/src/test/                     # unit tests + fixtures/<bank>/*.json

cmd/android-bridge/                   # bridge entrypoint (new Go cmd in this repo)
pkg/androidbridge/                    # bridge logic: http api, validation, gomoney client, dedup store
pkg/androidbridge/*_test.go
```

**Structure Decision**: The PRD's suggested layout (`android/` + `bridge/`) is adapted to this repo's conventions: the bridge lives in this Go module as `cmd/android-bridge` + `pkg/androidbridge` (same pattern as `cmd/server`, `pkg/importers`), so it can import Go Money's pb client directly and share build/tooling. The Android app is a standalone Gradle project under `android/` with zero Go dependency.

## Complexity Tracking

> No constitution violations — table intentionally empty.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| — | — | — |
