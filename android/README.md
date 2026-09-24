# Go Money Android Transaction Capture

Two deployable artifacts implementing `specs/001-android-txn-capture/spec.md`:

1. **Android capture app** (`android/`, Kotlin/Compose, standalone Gradle project) — captures bank-app notifications (and optionally SMS), parses them with pluggable per-bank parsers, queues them in a durable Room outbox, and delivers them to the bridge over plain HTTP + static bearer token.
2. **Transaction bridge** (this Go module: `cmd/android-bridge` + `pkg/androidbridge`) — authenticates the app, validates payloads, resolves account hints, enforces final deduplication, and records transactions in Go Money via `TransactionsService/CreateTransaction` (ConnectRPC).

## Build & test

```bash
# Bridge (Go, from repo root)
go test ./pkg/androidbridge/...       # bridge contract + dedup tests
go run ./cmd/android-bridge ...       # see specs/001-android-txn-capture/bridge-runbook.md

# Android app (from android/)
gradle wrapper && ./gradlew test      # parser fixtures + Room/Redactor tests
```

## Key docs

- Spec: `specs/001-android-txn-capture/spec.md`
- Bridge setup/run: `specs/001-android-txn-capture/bridge-runbook.md`
- Add-a-bank workflow: `specs/001-android-txn-capture/parser-guide.md`
- API contracts: `specs/001-android-txn-capture/contracts/`

## Clarified decisions baked in

- **Q1=B**: plain HTTP + static bearer token (trusted LAN), no TLS in MVP.
- **Q2=A**: single round-trip, single terminal state `SENT` (duplicate acks are also terminal, tagged `duplicate`).
- **Q3=B**: ±2-minute dedup timestamp tolerance (bridge-side adjacent-bucket window scan).
