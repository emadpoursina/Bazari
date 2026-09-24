# Phase 0 Research: Go Money Android Transaction Capture

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md)

All NEEDS CLARIFICATION items from the plan template's Technical Context are resolved here. Decisions from the clarify stage (Q1/Q2/Q3) are treated as fixed inputs, not research topics.

## R1 — Android language & UI toolkit

**Decision**: Kotlin 2.x with Jetpack Compose (Material 3) for the small UI; Android SDK min 26.

**Rationale**: Kotlin is the Android-first language with the best support for coroutines/Flow (outbox streaming) and NotificationListenerService. Compose is the lightest path for a 3-screen UI (dashboard, events, settings) — much less boilerplate than XML Views for a tiny app. min 26 gives JobScheduler-based WorkManager and notification channels without legacy compat paths.

**Alternatives considered**: Java (more boilerplate, no coroutines); Flutter/React Native (third-party SDK weight conflicts with privacy/no-unnecessary-SDK rules FR-027; platform services like NotificationListenerService still need native bridges); XML Views (fine but slower to build; rejected for velocity, not correctness).

## R2 — Local storage & background delivery on Android

**Decision**: Room (SQLite) for raw events / normalized transactions / delivery records / dedup fingerprints; WorkManager with exponential backoff + network-constrained constraint for delivery; DataStore for settings.

**Rationale**: Room is the standard durable store — survives app/phone restarts (FR-016). WorkManager persists work across reboots and re-enqueues on `NetworkType.CONNECTED` becoming available, which is exactly FR-012 / SC-004 ("deliver automatically within 2 minutes of reachability"). A foreground-service pusher is unnecessary; WorkManager's periodic + expedited retry suffices for a single-user LAN app.

**Alternatives considered**: Plain SQLite (loses compile-time query/type safety); plain `AlarmManager` loop (killed on reboot, manual bookkeeping); Firebase JobDispatcher (deprecated); sending from the NotificationListenerService directly (dies with the listener lifecycle, no retry durability).

## R3 — Bridge HTTP surface: plain REST vs ConnectRPC from Android

**Decision**: Plain JSON REST (`POST /v1/transactions`, `POST /v1/ping`, `POST /v1/events/ack-duplicate` — see contracts/) served by the bridge with Go stdlib `net/http`; the bridge internally calls Go Money's ConnectRPC API.

**Rationale**: The app must not depend on Go Money's protobuf module (PRD: "keep the Android application independent from Go Money's internal database... integration should be replaceable"). A tiny JSON REST contract with the static bearer token (clarified Q1=B) keeps the Android side dependency-free and the adapter boundary (FR-013) real. HTTP/JSON is trivially consumed by OkHttp.

**Alternatives considered**: Android consumes ConnectRPC/JSON directly against Go Money (breaks the replaceable-bridge requirement and pushes dedup mapping into the phone); gRPC from Android (heavier deps, no benefit on a trusted LAN). Skipped using the generated buf Android SDK despite availability — deliberately, for decoupling.

## R4 — Bridge-side dedup persistence location

**Decision**: Bridge keeps its own SQLite store (single file, e.g. `~/.gomoney-android-bridge/dedup.db`) mapping dedup fingerprint → Go Money transaction id + recorded-at. Stores are keyed by fingerprint; lookup before create; insert in the same flow as record-aftersend.

**Rationale**: The bridge must enforce FR-015 even for overlapping deliveries from the app (retry races). Using Go Money's own database directly would couple the bridge to Go Money's schema — the bridge is meant to stay a thin adapter, and Go Money's schema is user-managed. A tiny local SQLite table is self-contained, survives bridge restarts, and can be wiped without touching financial data. Final truth remains Go Money (spec assumption), but the registry prevents the "two retries arrive concurrently" duplicate case.

**Alternatives considered**: In-memory map (loses dedup across bridge restart — rejected, restarts are common on a dev Mac); querying `ListTransactions` by title/memo each time (fragile matching, slow, no uniqueness guarantee); Go Money-side unique constraint on a tag (requires schema knowledge/policy coupling — rejected).

## R5 — Fingerprint algorithm (implements FR-009)

**Decision**: `fingerprint = sha256(bank || accountHint || type || amountMinor || round(ts) || normalizedDescription)`, computed at parse time, stored on every NormalizedTransaction. For ±2 min tolerance (clarified Q3=B), a second "bucket key" is derived by rounding the transaction timestamp down to a 2-minute epoch bucket; primary lookup is by exact fingerprint, fallback match is fingerprint-without-ts within the same (bank, account, amount, type) bucket window. Winner: first delivery wins; later matches are acknowledged as duplicates.

**Rationale**: Exact fingerprint handles identical copies (repeated notifications, retries) deterministically. The bucket-window fallback handles notification+SMS timestamps differing by up to 2 minutes without collapsing genuinely distinct transactions (two different purchases seconds apart fall into the same bucket only if they also match bank+account+amount+type — the rare same-amount-second-apart edge case is handled by requiring the description window match or by keeping both entries distinct when descriptions differ; edge case registered in data-model.md).

**Alternatives considered**: Pure exact fingerprint (misses ±2 min variants — violates clarified Q3); fuzzy gets/decays (nondeterministic, hard to test); pure timestamps-snap (breaks distinct-nearby-transactions requirement).

## R6 — Bridge ⇄ Go Money API mapping

**Decision**: Bridge maps NormalizedTransaction → `gomoneypb.transactions.v1.TransactionsService/CreateTransaction` (`POST /gomoneypb.transactions.v1.TransactionsService/CreateTransaction`), authenticated with a Go Money **service token** (created via `ConfigurationService/CreateServiceToken`, per docs/api/endpoints.md), not user JWT login. `type: expense → TRANSACTION_TYPE_EXPENSE`, `income → TRANSACTION_TYPE_INCOME`; `accountHint` is resolved to a Go Money account id via a one-time bridge mapping table (bank account hint → Go Money source_account_id); currency IRR; `transaction_date_time` = normalized ISO-8601 with offset.

**Rationale**: Service tokens are the documented non-interactive auth path for server-to-server clients; CreateTransaction is the canonical single-transaction creation endpoint and returns after the transaction exists — satisfying Q2 (acknowledge only after Go Money records). Dependency injection of the Go Money client keeps the adapter replaceable (FR-013).

**Alternatives considered**: CreateTransactionsBulk (unnecessary for single-event delivery; useful later for batch drain — noted as future optimization); direct DB writes (forbidden by FR-013/PRD §9); JWT login with user credentials (would require storing passwords — forbidden by FR-006).

## R7 — Parser plugin architecture (implements FR-008)

**Decision**: In-app `interface BankParser { canParse(RawEvent): Boolean; parse(RawEvent): ParseResult }` with a `ParserRegistry` that iterates registered parsers in priority order; each parser declares its supported package names / SMS sender IDs and confidence. MVP parsers: Mellat, Melli, Saman, plus a conservative GenericParser that matches only high-confidence patterns and otherwise yields a parse error. Parsers are pure functions of RawEvent → fixture-tested.

**Rationale**: Adapter pattern isolates bank-specific text matching from capture/queue/delivery (FR-008, SC-005: "only parser + fixtures, zero core changes"). Fixture-driven JSON tests map 1:1 to PRD §18 layout (`fixtures/<bank>/*.json`).

**Alternatives considered**: Reflection/scanned plugin loading (over-engineering for ≤10 parsers); API-driven remote parser updates (contradicts local-only privacy posture).

## R8 — Permission & capture lifecycle handling

**Decision**: Notification capture via `NotificationListenerService` (user enables in system settings; app monitors `isNotificationListenerAccessGranted` and surfaces it on dashboard/settings per FR-024). SMS via `SmsRetriever`-style receiver with user-granted `RECEIVE_SMS` permission, independently toggleable (FR-004/017). No root, no accessibility service, no screenshots (FR-005). Revoked-permission edge case: detector runs on app open + WorkManager tick; capture gates check permission before processing; queued data is untouched.

**Rationale**: These are the only non-root mechanisms Android provides for passive event capture; both are explicitly in scope per PRD §5.1/§5.2 and are opt-in, matching the privacy posture.

**Alternatives considered**: Accessibility-service scraping (explicitly forbidden FR-005); screen OCR (explicitly out of scope).

## R9 — Error taxonomy (implements FR-023)

**Decision**: Enum of six categories exactly as PRD §16: `CAPTURE_ERROR`, `PARSE_ERROR`, `VALIDATION_ERROR`, `NETWORK_ERROR`, `SERVER_ERROR`, `DUPLICATE`. Every failed event carries exactly one category + optional safe detail string; raw text is never stored in the detail field (see data-model.md validation rules). Unmatched-notify events land in cature as `PARSE_ERROR` (retained, user-visible per spec acceptance 6.2).

**Rationale**: 1:1 with spec FR-023 categories and PRD §16; small, enumerable, testable.

**Alternatives considered**: Free-text error strings (unbounded, leaks sensitive content risk); HTTP-status-only mapping (conflates network vs server rejection).

## R10 — Persian numeral & format normalization (implements FR-010)

**Decision**: Single shared amount-normalization utility in the app/parser layer: map Persian digits (`۰-۹` U+06F0–06F9) and Arabic-Indic digits (`٠-٩` U+0660–0669) to ASCII, normalize Persian thousand separators (`٬` U+066C) and decimal separator (`٫` U+066B) to ASCII `,`/`.`, strip currency words (ریال/Rial/IRR/toman→IRR handling documented in data-model.md), parse into integer minor units (IRR = whole rial integer). Reused identically by SMS and notification paths via the shared parser pipeline.

**Rationale**: One canonical numeric representation avoids per-parser divergence and is trivially unit-testable against fixtures (spec edge case).

**Alternatives considered**: Per-parser custom digit handling (duplicated logic, drift risk); locale-default parsing (device locale is unreliable for Persian text).

## Open items → resolved summary

All previously identified unknowns and two integration patterns (bridge REST + Go Money service-token mapping) are resolved above. No remaining NEEDS CLARIFICATION entries. Constitution is an unfilled template — flagged in plan.md Constitution Check, not blocking Phase 0.
