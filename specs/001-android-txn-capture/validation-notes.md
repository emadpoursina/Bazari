# Validation Notes (T050 / T051)

**Environment of record (pass 1):** macOS dev machine — Go toolchain available; no Android SDK/Gradle installed.

## What was executed here

| Check | Command | Result |
|---|---|---|
| Bridge build | `go build ./pkg/androidbridge/ ./cmd/android-bridge/` | PASS |
| Bridge vet | `go vet ./pkg/androidbridge/ ./cmd/android-bridge/` | PASS |
| Bridge unit + contract + dedup + concurrency tests | `go test ./pkg/androidbridge/ -race` | PASS (dedup exact/window/boundary, validation, mappings, bulk, concurrent exactly-one-201) |
| Bridge smoke | `go run ./cmd/android-bridge` + curl `/v1/ping`, `/v1/mappings`, bad token | PASS (401 + ping reachable/unreachable variants) |
| Full repo build | `go build ./...` | PASS |

Test coverage mapping (FR-029/FR-030):
- Parser fixtures: 14 fixtures across mellat/melli/saman/generic/samplebank (+SMS fixtures) executed by `ParserFixtureTest` (Android-side; requires `./gradlew test`).
- Dedup end-to-end: `pkg/androidbridge/server_test.go` + `handlers_dedup_test.go` (exact hit, ±2-min bucket window incl. boundary, distinct-same-amount non-collapse, concurrent racers → exactly one 201).
- Android-side dedup/delivery state machine: `DeliveryRepositoryTest`, `DedupRepositoryTest`, `TransactionSyncWorkerTest`, `CrossSourceDedupTest` (requires Gradle/Robolectric run).

## What requires a device (T050/T051 device-dependent items)

The following quickstart scenarios need a physical Android device and a running Go Money instance; they were NOT executable in this pass:

1. **Capture-to-queued < 2 s (SC-001)** — measure with the debug screen timestamps after installing the app on a device.
2. **~200-item queue drain within 2 min (SC-004)** — seed the outbox via repeated captures and observe the bridge dashboard/log counters.
3. **Reboot mid-queue retains items in order (FR-016)** — WorkManager-persisted periodic work + Room outbox by construction; verify after reboot.
4. **Quickstart scenarios 1–7 end-to-end** — follow `quickstart.md` with a real bank app (or the fixture texts replayed through the notification listener).

## Environment setup for the device pass

```bash
cd android && gradle wrapper && ./gradlew test   # unit tests incl. fixtures
go run ./cmd/android-bridge --bearer-token <tok> --gomoney-token <tok> --listen :8787
```

## Permission & dependency audit (T048, FR-005/006/026/027)

- `AndroidManifest.xml`: only `INTERNET`, `RECEIVE_SMS` (opt-in source), plus the `BIND_NOTIFICATION_LISTENER_SERVICE` service declaration. No accessibility services, no location, no contacts.
- Dependencies (gradle/libs.versions.toml): AndroidX, Compose, Room, WorkManager, DataStore, OkHttp, coroutines — **no analytics/ads/tracking SDKs** (FR-027).
- Credentials: only the user's own bridge bearer token, stored in EncryptedSharedPreferences (FR-006). No bank credentials anywhere.
- Raw message text never leaves the phone (`NormalizedTransaction` has no rawText; bridge logs redacted — verified by `TestLoggingRedactsSensitiveFields` and `BridgeClientTest#request body has no rawText field`).
