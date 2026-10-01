# Phase 0 Research: Account Currency & Source-Only Capture

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md)

All Technical Context items are resolved here. Findings are grounded in `android/` and `pkg/androidbridge/` as they exist on `notification-engine` after `specs/004-notification-engine`.

## R1 — Currency comes from the cached server account, not the message

**Decision**: On bind and on capture, read `ServerAccount.currency` for `TransactionSource.boundAccountId` from the existing `server_accounts` cache (filled by `GET /v1/accounts`). Copy that code onto `NormalizedTransaction.currency` at capture time. Do not add a currency placeholder to the template. Do not persist a separate currency column on `transaction_sources`; the source UI shows the latest known catalog currency after a successful refresh.

**Rationale**: FR-001/002/018 and the spec assumption that accounts already carry currency. Catalog cache already stores `currency` (`Entities.kt` `ServerAccount`, `CatalogRepository`, `BridgeClient.fetchAccounts`). Snapshotting currency onto the source would hide later server updates; the spec edge case wants new captures to use the latest known currency, while already-stamped transactions keep theirs (FR-008).

**Alternatives considered**: User-picked currency on the source (contradicts FR-001); parsing currency words from the message (explicitly out of scope); a new `GET /v1/currencies` list (unnecessary for bind; accounts already expose the code).

## R2 — Unknown currency is an empty string, not IRR

**Decision**: Keep `normalized_transactions.currency` TEXT NOT NULL. Use `""` for “not yet stamped”. Never write `"IRR"` unless that is the bound account’s currency or a user edit. UI treats blank as unknown (no rial label). Room v4 does not need a nullability migration.

**Rationale**: FR-003/010. Current `UserSourceParser` hardcodes `"IRR"`; `EventDetailScreen` / `EventsScreen` / `SourceEditorScreen` / `source_test_success` hardcode the IRR label. Empty string is the smallest schema-compatible “no currency” token.

**Alternatives considered**: Nullable column (honest, but an ALTER + null-handling in every query for one personal app); a sentinel like `"UNK"` (leaks into the bridge if a bug sends it).

## R3 — Unbound captures use delivery state `held`

**Decision**: Add `DeliveryState.HELD` (`held`). Capture from an enabled unbound source still persists `RawEvent` + `NormalizedTransaction` (currency `""`) but writes a delivery row in `held`, does not enqueue expedited sync. `TransactionSyncWorker` continues to select only `queued` (and existing failed retry). When the source later binds to an account with a currency: stamp `currency` onto that source’s still-held rows where `currency` is empty; leave rows that already have a currency unchanged; transition those rows `held → queued`; then enqueue expedited sync.

Legal additions: `held → queued`. No `held → sending`. Rebind of a source that already had an account does **not** rewrite stamped rows and does **not** move `sent` records.

**Rationale**: FR-010/011. Today `CapturePipeline.persist` always `createQueued` and fires `onCaptured`. A new outbox state is smaller than inventing a parallel table. Reusing `queued` and filtering in the worker by empty currency would still risk a race if a user edited currency while unbound (FR-010 says delivery waits for a bound account, not merely for a currency string).

**Alternatives considered**: Keep `queued` and skip empty-currency in the worker (fails FR-010 after a pre-delivery currency edit); fail the capture when unbound (contradicts FR-009/010); invent IRR until bind (forbidden).

## R4 — Bind validation: no currency, no bind

**Decision**: `SourceValidator` / editor save rejects a bind when the selected `ServerAccount.currency` is blank. Show why; do not replace the previous binding with IRR. Stale/missing account after refresh keeps existing 004 behavior (`CatalogRepository.isBindingStale`); new captures from a stale binding are treated as unbound (`held`, empty currency) until the user chooses again.

**Rationale**: Spec edge cases for missing currency and disappeared accounts. Catalog already supports last-known list + unavailable flag (004 R8).

**Alternatives considered**: Allow bind and hold forever (user would not understand why); auto-pick IRR (FR-003).

## R5 — Settings allow-list is deleted from the UX and unread at runtime

**Decision**: Remove the bank allow-list section from `SettingsScreen`. Stop reading `ServerConfiguration.enabledBankPackages` in `CapturePipeline`, `NotificationCaptureService`, and `SmsCaptureReceiver`. On settings load (or first launch after this change), clear the DataStore set so leftover identifiers have no effect. Do not convert those identifiers into sources (FR-015). Keep server URL/token, capture toggles, debug mode, and permission status (FR-016).

**Rationale**: User request and FR-012–017. The allow-list now duplicates sources. This is a personal one-user app; no migration wizard.

**Alternatives considered**: Keep the list as a hidden extra gate (user explicitly wants it gone); auto-create sources from leftover packages (forbidden).

## R6 — Shipped parsers do not run; former banks are seeded sources

**Decision**: Production `CapturePipeline` has one capture path: resolve an **enabled** `TransactionSource` for `(identifier, channel)` with a user template → `UserSourceParser`. If none, ignore without persisting (`IGNORED_NOT_ALLOW_LISTED` can be renamed to `IGNORED_NO_SOURCE`). Do not call `ParserRegistry.select`. Wire `TransactionSourceRepository` + `UserSourceParser` in `NotificationCaptureService` and `SmsCaptureReceiver` (they currently construct a pipeline without those deps, so user sources would never run in the real app).

Seed ordinary `TransactionSource` rows for former built-in banks (Mellat, Melli, Saman, Blue) — one row per `(package or SMS sender, channel)` that those parsers claimed — using fill-in-the-blank templates and keyword lists derived from existing fixtures/parser keywords. Insert only when that `(identifier, channel)` is absent so user edits are not overwritten. Do **not** seed `GenericParser` (it is a catch-all) or `SampleBankParser` (test-only). Parser classes may remain in the tree for historical unit tests; they must not be on the production capture path.

**Rationale**: FR-013/014/021, SC-004/010. Templates cannot reproduce every parser heuristic (card-hint extraction, generic dual-signal). Fingerprints for user sources already use `bank=user` and `accountHint=source.identifier` (004). Seeding gives the user a starting source they can edit; it is not a promise of parser-parity.

**Alternatives considered**: Keep `ParserRegistry` as fallback (forbidden by clarify); compile parsers into regex at runtime (new complexity, still a shipped parser); require the user to recreate Mellat from scratch (worse UX, fails “exist in the app as sources”).

## R7 — Amount scaling stays IRR-only at the bridge client

**Decision**: Keep storing the integer from `AmountNormalizer` / `{amount}` as `amountMinor`. `BridgeClient.bridgeAmount` already divides by 10 only when `currency == "IRR"` and passes other currencies through. Do not change `AmountNormalizer` in this feature (it still strips decimals for whole-rial capture). The bridge continues to convert counterpart accounts via existing `ListCurrencies` when the default expense/income account differs (already in `handlers.go`).

**Rationale**: Spec: scaling is a planning concern as long as the recorded/displayed currency matches the bound account. Changing amount semantics for USD cents would be a separate feature. Known ceiling: messages whose amount includes a decimal fraction for non-IRR currencies still lose the fraction in `AmountNormalizer`. Upgrade later if we need ISO minor units.

**Alternatives considered**: Per-currency precision from Go Money at capture time (needs a currencies endpoint and online capture); store decimal strings (breaks fingerprint/int columns).

## R8 — Pre-delivery currency edit uses catalog codes

**Decision**: On `EventDetailScreen`, if delivery state is `held`, `queued`, or `failed` (local, not delivered), show a currency dropdown of distinct non-blank codes from cached `server_accounts`. Saving updates `NormalizedTransaction.currency` only. It does **not** by itself move `held → queued` (source must still be bound — FR-010). After delivery (`sent` / `sending`), show currency as text, not editable (FR-022). Destination/category assignment stays independent and does not change currency (FR-019).

**Rationale**: Spec says currencies are maintained in the main app; the capture app only reads them. Distinct codes from the account cache avoid a new API. `sending` is treated as in-flight delivery, not editable, to avoid racing the worker.

**Alternatives considered**: Free-text ISO code (easy to typo and fail mapped-account match); `GET /v1/currencies` (extra contract for little gain).

## R9 — Bridge create validation accepts any non-empty currency that matches the mapped account

**Decision**: In `validateTransaction`, replace `currency must be IRR` with: currency required, non-empty, reasonable length/charset (existing account codes, typically 3-letter). Keep the later mapped-account check: `mappedAccount.Currency != txn.Currency` → 400. `GET /v1/accounts` already returns `currency`; no payload shape change. Update `specs/001` consumers via this feature’s [contracts/bridge-api.md](./contracts/bridge-api.md) rather than rewriting 001 history.

**Rationale**: FR-007. Create path already converts FX for the counterpart when currencies differ; the IRR-only gate is the blocker. Android `BridgeClient.txJson` already sends `tx.currency`.

**Alternatives considered**: Bridge overwrites currency from the mapped account (hides phone-side edits); keep IRR-only (feature cannot ship).

## R10 — Receivers pass every event to the pipeline; the pipeline is the only gate

**Decision**: Notification and SMS receivers keep permission/toggle checks, then call `pipeline.process` without an allow-list membership test. The pipeline ignores non-sources without persisting. Master Settings toggles still short-circuit first (FR-016 / spec edge case).

**Rationale**: Smallest change that cannot drift from a second copy of the allow-list. Volume of unrelated notifications is fine for a single-user phone; ignore is cheap if it does not write Room.

**Alternatives considered**: Duplicate source lookup in the receiver (two gates to keep in sync).
