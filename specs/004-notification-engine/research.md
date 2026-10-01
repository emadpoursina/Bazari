# Phase 0 Research: Notification Source & Template Engine

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md)

All `NEEDS CLARIFICATION` items from the plan's Technical Context are resolved here. The five clarify-stage decisions (fill-in-the-blank template, per-source keyword lists, duplicate blocking, optional test preview, capped parse-error review list) are treated as fixed inputs, not research topics. Findings are grounded in the existing capture feature (`specs/001-android-txn-capture/`) and the code under `android/` and `pkg/androidbridge/`.

## R1 — Hosting app and integration boundary

**Decision**: Build the feature in the existing Android capture app (`android/`) and extend the existing Android bridge (`pkg/androidbridge` + `cmd/android-bridge`). No new artifact, no server-side (Go Money) change.

**Rationale**: The spec's Assumptions already resolve the host: the phone app is where notifications/SMS are captured and where raw text must stay (FR-023), so template matching and source storage belong there. The bridge already owns the Go Money service token, the `(bank, accountHint) → accountId` mapping, and the dedup registry, so server-list retrieval and destination/category updates are a natural additive extension there rather than a second integration point. The Android app must not depend on Go Money's protobuf module (capture research R3), so routing catalog reads through the bridge preserves that decoupling.

**Alternatives considered**: Putting sources on the Go Money server (contradicts the spec assumption that definitions are local in this version and would require new server schema/proto); letting the Android app call Go Money directly (breaks the bridge's replaceable-adapter boundary and would require storing another credential on the phone); a new third service (needless operational surface for a single-user LAN app).

## R2 — Local persistence for sources, parse errors, and catalog caches

**Decision**: Add four Room entities to `gomoney-capture.db` and bump the schema to v3 via `MIGRATION_2_3`: `transaction_sources`, `parse_errors`, `server_accounts`, `server_categories`. Extend `normalized_transactions` with `sourceId`, `accountId`, `destinationAccountId`, `categoryId`, and `assignmentSyncState`.

**Rationale**: Room already stores raw events, normalized transactions, delivery records, and dedup entries and is proven to survive app/device restarts (FR-006). Sources are relational (a source has a unique identifier+channel and a template), so a Room table gives a free uniqueness constraint and reactive `Flow` lists for the UI. The catalog caches must also survive restarts so selectors can show the last known list offline (FR-016). Extending `normalized_transactions` keeps destination/category state next to the transaction it describes and lets the existing memo-sync pattern be mirrored exactly.

**Alternatives considered**: DataStore for sources (poor fit for a list with uniqueness and relational updates; the existing settings DataStore is for scalar config); a separate database (more migration surface, cross-database joins impossible); storing catalog lists only in memory (fails the offline/restart requirements).

## R3 — Source resolution inside the capture pipeline

**Decision**: Add a `SourceRepository` (Room) and a `UserSourceResolver`. In `CapturePipeline.process`, after the existing permission/toggle gate, resolve a user-defined enabled source by `(event.sourcePackage == source.identifier)` **and** `(event.source == source.channel)`. If one matches, persist the raw event and run the user-source path. If none matches, fall back to the existing built-in allow-list (`enabledBankPackages`) + `ParserRegistry` path, unchanged. Events matching neither are still ignored without persistence.

**Rationale**: FR-004 says capture only from identifiers configured as enabled sources on the matching channel; the user-source lookup is itself that gate. User sources are checked first because they are the most specific, user-authored intent for that identifier, and because a message must never be parsed by more than one source (FR-005). The raw event must be persisted on the user-source path **before** matching so a parse failure can be retained and shown (FR-010), unlike the built-in path where non-allow-listed events are dropped before persistence. The built-in parsers and their fixtures are untouched (SC-005 preserved).

**Alternatives considered**: Adding a synthetic `BankParser` per user source into `ParserRegistry` (the registry is constructed once with pure, stateless parsers and does not re-read the DB; injecting mutable DB-backed parsers would break its purity contract); checking built-ins first (would let a shipped parser shadow an explicit user source and makes the "parsed once" guarantee harder to reason about); requiring user sources to also be added to the allow-list (duplicate configuration and confusing UX).

## R4 — Template representation and matching algorithm

**Decision**: Store the template as the user's literal sample text with exactly two placeholder tokens, `{direction}` and `{amount}`. Compile it into an ordered list of literal anchors and placeholder slots (a "segment" model). Match by **anchor-and-scan**: normalize the message (Persian/Arabic digits and separators → ASCII, whitespace collapsed), then require the literal anchors to occur in order; the text between consecutive anchors is the candidate segment for the placeholder. Anchors are matched case-insensitively and tolerantly (surrounding punctuation trimmed). Extra text before, after, or between anchors is allowed, so dates/reference numbers that differ between messages do not break matching (spec assumption).

**Rationale**: A template built from one real sample must keep matching later messages of the same shape (spec assumption). A strict literal/regex equality would fail as soon as a date or reference number changes; a full regex with wildcards is the same idea but harder for the user to reason about and easier to make unsafe. The anchor-and-scan model captures the user's intent ("these fixed words surround the two values") while ignoring the parts that vary. It is a pure function, trivially fixture-testable, and needs no new dependency.

**Alternatives considered**: Exact literal match (rejected: fails on any variable field); converting the template to a regex with `.*?` between literals (works but opaque and prone to catastrophic backtracking on arbitrary user input); ML/NER extraction (massive over-engineering for a single-user app); requiring the user to write a regex (contradicts the fill-in-the-blank decision).

## R5 — Direction resolution via per-source keyword lists

**Decision**: Resolve direction by scanning the normalized message for the source's income and expense keyword lists (case-insensitive, whole-token match, digits already normalized). Exactly one list matching → that direction. Neither list matching, or both matching → **unrecognizable direction** → parse error (FR-026, spec edge case). The `{direction}` placeholder is still required for template validity (FR-007/FR-012), and the segment it captures is used as a first-pass hint, but the final decision comes from the keyword lists per FR-026.

**Rationale**: FR-026 explicitly makes the keyword lists the source of truth for direction, and the clarify answer chose keyword lists over positional/word-shape inference. A deterministic "exactly one list wins" rule is easy to test and avoids guessing. Ambiguity (both lists) is surfaced as a parse error rather than silently picking one, matching the spec's "no transaction is created" edge case.

**Alternatives considered**: Deciding direction only from the `{direction}` segment (fails when the wording varies positionally); first-match-wins on the message scan (non-deterministic for overlapping words); stemming/fuzzy keyword matching (unnecessary complexity for user-curated words).

## R6 — Amount extraction and normalization

**Decision**: Reuse the existing `AmountNormalizer` (Persian/Arabic-Indic digit mapping, separator normalization, currency-word handling, whole-IRR minor units). Extract the numeric token from the `{amount}` segment, then validate it is a positive integer; missing/zero/non-numeric → parse error. The resulting `amountMinor` follows exactly the same convention as the built-in parsers so the shared fingerprint and bridge scaling remain uniform.

**Rationale**: FR-011 requires locale-specific digits/separators to become a canonical number, and the capture feature already centralized this (capture research R10, FR-010). Reusing it guarantees user-source transactions are indistinguishable from built-in ones downstream and avoids a second normalization implementation drifting.

**Alternatives considered**: A new user-source amount parser (duplication and drift risk); `toDouble`/locale-default parsing (unreliable for Persian text and floating-point unsafe for currency).

## R7 — Binding a source to a server account

**Decision**: The bound account is sent on the transaction payload as an explicit `accountId` (the server account id), and the bridge uses it as the account the captured transaction is recorded against, replacing the static `(bank, accountHint) → accountId` mapping for that transaction. When no `accountId` is present the bridge falls back to the existing mappings lookup (built-in path, unchanged). The bound account's label is cached locally for display and for detecting a stale/removed binding.

**Rationale**: The bridge currently requires a mapped account before it will create a transaction; user sources have no bank/account-hint pair, so they must supply the account directly. Sending the id explicitly is unambiguous, survives account-list changes, and lets the bridge keep its existing validation and dedup logic. This implements FR-013/FR-015 and US2.3 ("the transaction is recorded against that account"). The spec's Assumptions sentence calling this the "destination account" is read as the user's mental model for "the account the transaction belongs to"; the Go Money destination account is the separate field set by R9, and the two do not conflict.

**Alternatives considered**: Extending the bridge's mappings file with user-source entries (the mapping file is operator-managed, not app-managed, and couples sources to a server-side file); resolving by source name on the bridge (names are not stable/unique); reusing `accountHint` to carry the id (overloads a fingerprint field and loses the human-readable hint).

## R8 — Retrieving server accounts and categories through the bridge

**Decision**: Add two read endpoints to the bridge — `GET /v1/accounts` and `GET /v1/categories` — that call Go Money's existing `AccountsService/ListAccounts` and `CategoriesService/ListCategories` with the existing service token and return a minimal `{id, label, …}` shape. The Android app refreshes them through the existing `BridgeClient` into the `server_accounts` / `server_categories` caches; selectors read the cache, and a failed refresh shows the cached list with an "unavailable" indicator instead of losing an existing binding (FR-016).

**Rationale**: Go Money's `ListAccounts` and `ListCategories` already exist and already authenticate with the service token the bridge holds (confirmed in `cmd/server/internal/handlers/accounts.go` and `categories.go`, both gated on a resolved user). Routing through the bridge keeps the Android app free of Go Money protobuf/credential dependencies and reuses the existing auth and error-classification code. The minimal projection mirrors the bridge's existing privacy stance of discarding unrelated account data (see `ListAccounts` in `gomoney.go`). Local caching satisfies the offline/restart and "clearly unavailable" requirements (FR-016, US2.4, US3.5).

**Alternatives considered**: The Android app calling Go Money directly (new credential and protobuf dependency on the phone); returning the raw protobuf messages (leaks unrelated user data to the phone and to bridge logs); no caching (selectors break offline, violating FR-016).

## R9 — Destination account and category on a captured transaction

**Decision**: Add `destinationAccountId` and `categoryId` to `normalized_transactions`, plus an `assignmentSyncState` (`pending` | `synced`) mirroring `memoSyncState`. Saving a choice writes locally first and marks it pending, then enqueues the existing expedited sync. If the transaction is **not yet delivered**, the values ride along in the create payload (extended `POST /v1/transactions`). If it is **already delivered**, the app calls a new `PUT /v1/transactions/assignment` endpoint that looks the transaction up in the dedup registry, reuses the current Go Money fields, sets only the destination account/category, and calls `UpdateTransaction` — updating the same transaction and never creating a second one (FR-019/FR-020, SC-005). Offline, the state stays `pending` and syncs when reachable.

**Rationale**: This is exactly the established memo-update pattern (`handleUpdateMemo`, `MemoUpdateRequest`, `SyncEngine.syncPendingMemos`) applied to two more fields; `createRequestFromExisting` already copies `CategoryId` and `DestinationAccountId`, so the bridge change is small and low-risk. Local-first + pending state is required by FR-019 (survive restart/offline) and the "no duplicate transaction" rule by FR-020. Using the dedup registry for lookup means a duplicate-acked capture updates the same Go Money transaction.

**Alternatives considered**: A new transaction with the assignment (forbidden — would duplicate); server-side-only update without local persistence (loses choices offline and on restart); editing via the main Go Money app (the spec explicitly wants it inside the capture app).

## R10 — Parse-error review list and retention cap

**Decision**: Persist user-source parse failures in a `parse_errors` table keyed by raw-event reference, with the failure reason (sanitized), the originating source id, and the occurrence time. The Sources screen shows them newest-first with the reason and a dismiss action that deletes the row. Retention is enforced on insert: delete entries older than 30 days, then, if more than 200 remain, delete the oldest beyond 200 (FR-028, SC-010). Raw text is shown only from the local raw event, never sent to the bridge.

**Rationale**: FR-010 requires the message to be retained and flagged; FR-028 adds the 200/30-day cap and dismissibility. Keying to the raw event reuses the existing `RawEvent` store (which already holds text locally and is never transmitted) and avoids a second copy. Pruning on insert is simple and deterministic and keeps the table bounded without a background job. This is separate from the built-in parsers' `DeliveryRecord` parse errors, which keep their existing behavior.

**Alternatives considered**: Reusing `DeliveryRecord` parse errors for the list (mixes built-in and user-source failures and lacks the reason/source presentation the spec wants); an unbounded list (violates SC-010); a scheduled pruning worker (extra machinery for a trivial cap).

## R11 — Duplicate identifier + channel enforcement

**Decision**: Enforce a unique index on `(identifier, channel)` in `transaction_sources` and pre-validate in `SourceValidator` so the save is blocked with a clear duplicate error naming the existing source (FR-005, SC-009). The unique index is the backstop against a race; the pre-check produces the user-facing message.

**Rationale**: The clarify answer requires blocking the save and telling the user to edit/remove the existing source. A database unique index is the only reliable guarantee that a single message is never parsed by two sources, and the pre-check keeps the UX clear. Identifier comparison is trimmed and case-sensitive for package ids (case-sensitive by nature) with the same normalization used for matching.

**Alternatives considered**: Application-level check only (a race could insert two rows); allowing duplicates and picking a winner at capture (violates FR-005 and SC-009).

## R12 — Sources UI and navigation

**Decision**: Add a fourth **Sources** tab to the existing `MainActivity` tab host. The Sources screen lists sources (name, identifier, channel, enabled toggle, bound account) and hosts the parse-error review list. Add/edit opens a `SourceEditorScreen` with name, identifier, channel selector, a template field built from a pasted sample (insert `{direction}`/`{amount}` markers), income/expense keyword fields, the server-account drop-down, and a **Test** button that shows the extracted direction/amount or the failure reason (testing optional, FR-027). `EventDetailScreen` gains destination-account and category drop-downs populated from the cached catalog.

**Rationale**: The app is a single-activity Compose host with tab navigation and hoisted view models; adding one tab and two screens follows the existing pattern and keeps the feature discoverable. The Test button is a pure call into `TemplateMatcher`, so it works offline and before saving (FR-027, SC-011).

**Alternatives considered**: A settings sub-page (sources are a primary workflow, not a setting); a separate activity/navigation graph (unnecessary for three screens); building the template by editing a regex field (contradicts the clarify decision).

## R13 — Privacy, logging, and permissions

**Decision**: Keep all raw text on-device. Template matching, keyword resolution, and amount extraction run locally; bridge payloads carry only normalized fields plus account/category ids. Bridge logs keep the existing rules (fingerprint prefix, endpoint, outcome, latency — never text, description, or account hint). Reuse the existing notification-listener and SMS permission handling unchanged (FR-024, SC-007).

**Rationale**: FR-023/SC-007 are hard constraints inherited from the capture feature, and the new surfaces (catalog reads, assignment updates) carry no message text at all. Reusing `CaptureGate` and the existing permission UI satisfies the "permission not granted → capture stops, sources still definable" edge case without new work.

**Alternatives considered**: Sending a redacted sample for server-side matching (violates FR-023 and the local-first posture); new permission flows (the spec says permission handling is reused).

## Existing project evidence

- `android/app/src/main/kotlin/com/gomoney/capture/capture/CapturePipeline.kt` gates on `enabledBankPackages`, persists the raw event before processing, selects a `BankParser` from `ParserRegistry`, computes the fingerprint, and persists through `DeliveryRepository`/`DedupRepository`; parse failures become `DeliveryRecord` rows.
- `android/app/src/main/kotlin/com/gomoney/capture/parser/AmountNormalizer.kt` already normalizes Persian/Arabic digits, separators, and currency words and produces whole-IRR minor units.
- `android/app/src/main/kotlin/com/gomoney/capture/storage/Entities.kt` / `Daos.kt` / `AppDatabase.kt` show the Room schema (currently v2, with `MIGRATION_1_2` as the pattern for the new `MIGRATION_2_3`).
- `android/app/src/main/kotlin/com/gomoney/capture/sync/TransactionSyncWorker.kt` shows the memo-sync pattern (`pendingMemos`, `syncPendingMemos`) that the assignment sync mirrors, plus the expedited/periodic work and retry classification.
- `pkg/androidbridge/handlers.go` implements `deliver` (validate → mappings resolve → dedup → Go Money create), `handleUpdateMemo` (registry lookup → `createRequestFromExisting` → `UpdateTransaction`), and `buildCreateRequest`; `gomoney.go` already holds `ListAccounts`, `CreateTransaction`, `GetTransactionByID`, `UpdateTransaction`, and the service-token transport.
- `cmd/server/internal/handlers/accounts.go` and `categories.go` expose `ListAccounts` and `ListCategories` gated on a resolved user; the bridge's service token satisfies that gate today.
- The bridge HTTP contract is `specs/001-android-txn-capture/contracts/bridge-http-api.md`; the parser contract is `specs/001-android-txn-capture/contracts/parser-interface.md`.
- Test entry points: `make android-bridge-test` (`go test ./pkg/androidbridge/...`), `make android-gradle-test`, `make test`.

## Resolved clarifications

All plan-level unknowns are resolved above. No `NEEDS CLARIFICATION` entries remain. The constitution is an unfilled template (flagged in plan.md), so it contributes no gates. The only interpretation recorded rather than asked is R7 (the spec's Assumptions wording "destination account" for the source binding); it is called out explicitly and reconciled with the separate per-transaction destination account so implementation can proceed.
