# Quickstart: Go Money Android Transaction Capture — End-to-End Validation

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md) | Contracts: [bridge-http-api.md](./contracts/bridge-http-api.md), [gomoney-integration.md](./contracts/gomoney-integration.md), [parser-interface.md](./contracts/parser-interface.md)

Run these scenarios after implementation to prove the MVP acceptance criteria (spec §19). Each step maps to the story it validates.

## Prerequisites

- Android phone (SDK 26+) and the machine running the bridge on the same private LAN, or connected to the same Tailscale network. ADB + Android Studio for install and debug mode.
- Go Money instance reachable from the bridge, with a **service token** created (`ConfigurationService.CreateServiceToken`).
- Bridge config: `GOMONEY_URL`, `GOMONEY_SERVICE_TOKEN`, listen port `:8787`, mapping file for `(bank, accountHint) → accountId`.
- App config: server URL `http://<lan-ip>:8787` or the PC's Tailscale URL (for this setup, `http://100.91.5.122:8788`), bearer token, bank app allow-list checked, notification-listener permission granted.

## Build & Run

```bash
# Bridge (from repo root; see bridge-runbook.md for the env file setup)
make android-bridge-run

# Android app
cd android && ./gradlew installDebug
```

Unit/fixture tests (run before manual scenarios):

```bash
cd android && ./gradlew test                 # parser fixtures, dedup logic, outbox states
go test ./pkg/androidbridge/...              # bridge API, dedup registry, mapping
```

## Scenario 1 — Capture & parse (US1, SC-001)

1. Enable notification capture in the app; grant notification-listener access.
2. Make a real card purchase in a supported bank app (or post a matching debug notification).
3. **Expect**: within ~10 s the app's recent-events list shows the amount/bank/time without any interaction; dashboard "captured today" increments; device restart of the app page is not required.

## Scenario 2 — Offline queue & auto-delivery (US2, FR-012/016, SC-002/004)

1. Stop the bridge (or disconnect Wi-Fi to the LAN).
2. Capture a transaction (expect state `pending` on dashboard).
3. Restart the phone (proves durability across restarts) and bring the bridge/Go Money back up.
4. **Expect** within ~2 min: the pending item flips to `sent` with no user action; exactly one new Go Money transaction appears matching the capture.

## Scenario 3 — Duplicates (US3/US5, FR-015, SC-003)

Repeat delivery of the same logical transaction three ways; verify exactly one Go Money transaction each time:

1. **Identical resend**: re-send the same captured event (app retry or `POST /v1/transactions` with the same body via curl). Expect `{"status":"duplicate"}` → app marks sent, no new Go Money record.
2. **Notification + SMS**: enable SMS capture, capture the same purchase via both channels (timestamps ≤2 min apart). Expect one Go Money transaction; the later copy shows as duplicate.
3. **Distinct nearby transactions**: two genuinely different purchases, same amount, seconds apart → both recorded (dedup must NOT collapse them).

## Scenario 4 — Server rejection & manual retry (US2.4, FR-023)

1. Add an `(bank, accountHint)` combination that has NO mapping in the bridge.
2. Capture a transaction for it → expect app shows `failed` with category **validation** ("unmapped account"), no Go Money record, and the item is NOT auto-retried forever.
3. Add the mapping (`PUT /v1/mappings` or mappings file), tap retry → expect success (`sent`, one Go Money record).

## Scenario 5 — Parser add-on without core changes (US6, SC-005)

1. Add a fixture JSON for a new bank variant; run `./gradlew test` — parser tests pass without touching capture/queue/delivery code (git diff shows only the new parser + fixtures).
2. Enable the new bank package in the app allow-list; capture its notification → it flows through to Go Money.

## Scenario 6 — Permission & privacy checks (FR-005/006/024/028, SC-007)

1. Revoke notification-listener access in system settings → dashboard/settings surface the missing permission; capture stops; queued items intact.
2. Inspect app logs and bridge logs during a capture: no raw message text, no amounts in log lines — only fingerprint prefixes and outcomes.
3. Confirm app requests only INTERNET + POST_NOTIFICATIONS(+ notification-listener) and (if SMS enabled) RECEIVE_SMS — no accessibility services, no analytics/ads libraries in `build.gradle` dependencies.

## Scenario 7 — Debug mode (US7)

1. Enable debug mode in settings; capture an event.
2. **Expect**: event details screen shows source, package, title/text, parser name, confidence, parsed type/amount/currency/account hint.
3. Disable debug mode → raw text no longer visible in normal UI.

## Success mapping

| Scenario | Proves |
|---|---|
| 1 | FR-001..004, 007, SC-001 |
| 2 | US2, FR-011/012/016, SC-002/004 |
| 3 | FR-009/015, FR-030, SC-003 |
| 4 | FR-023, US2.4 |
| 5 | FR-008, SC-005 |
| 6 | FR-005/006/024/026/027/028, SC-007 |
| 7 | FR-025, US7 |
