# Implementation Plan: Account Currency & Source-Only Capture

**Branch**: `notification-engine` | **Date**: 2026-10-01 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/005-account-currency-sources/spec.md`

**Note**: This template is filled in by the `/speckit.plan` command; its definition describes the execution workflow. Git branch remains `notification-engine` (shared with `specs/004-notification-engine`). Feature directory is `specs/005-account-currency-sources`.

## Summary

Stop treating Iranian Rial as a global capture default, and stop using Settings as a second admission gate. When a source is bound to a Go Money account, that account’s currency (already returned by `GET /v1/accounts`) is shown next to the account and copied onto new captures. Unbound captures are held locally with no invented currency; binding later stamps still-held empty-currency rows and queues them. Currency is editable on the transaction screen only while the record is local and not yet delivered. Capture runs only through an enabled `TransactionSource` with a user template: the Settings bank allow-list is ignored/deleted, and shipped `ParserRegistry` parsers no longer run. Former built-in banks are seeded as editable sources. The bridge accepts the stored currency (not IRR-only) and still requires it to match the mapped bank account.

## Technical Context

**Language/Version**: Kotlin 2.x (Android capture app, min SDK 26) and Go 1.25 (Android bridge, matches `go.mod`)

**Primary Dependencies**: Existing stack only — Jetpack Room, WorkManager, DataStore, Material 3 Compose, OkHttp/`org.json` on Android; Go `net/http` + existing ConnectRPC clients (`accounts.v1`, `transactions.v1`, `currency.v1`) on the bridge. No new libraries.

**Storage**: Android Room `gomoney-capture.db` (schema v3 today → v4). `normalized_transactions.currency` remains NOT NULL; empty string means “unknown / not yet stamped”. New delivery state `held`. Settings DataStore `enabledBankPackages` is unread and cleared. Bridge dedup SQLite unchanged. Go Money remains the account/currency source of truth.

**Testing**: Android JUnit (pipeline, bind/stamp, hold/queue, currency edit, Settings allow-list gone, seeded sources, hardcoded-IRR UI gone); `make android-gradle-test`. Bridge `go test ./pkg/androidbridge` for non-IRR create validation and mapped-account currency match. Existing parser fixture tests that assume a live `ParserRegistry` in the capture path are retired or pointed at seeded-source templates.

**Target Platform**: Android phone (notification listener + optional SMS) and the existing local Android bridge (`cmd/android-bridge`)

**Project Type**: mobile-app + small LAN backend (same two artifacts as 001/004)

**Performance Goals**: Capture-to-local persist remains < 2 s. Bind-then-stamp of held rows is a single local transaction, then the existing expedited sync. No extra network round-trip at capture time (currency comes from the already-cached `server_accounts` row).

**Constraints**:
- Raw message text never leaves the device and is never logged (unchanged from 001/004).
- Iranian Rial is valid only when the bound account (or a pre-delivery user edit) actually uses it — never as a global default (FR-003).
- Delivery must not send a transaction until it has a non-empty currency **and** the source is bound to an account with a currency (FR-010).
- Amount extraction stays on `AmountNormalizer`; IRR `/ 10` scaling stays at the bridge client boundary only.
- Constitution is an unfilled template; de-facto gates are privacy, reuse, and no new credentials.

**Scale/Scope**: Single user, single phone. A handful of sources (user-defined + a small seeded former-bank set). Account catalog in the tens. Mixed currencies (at least IRR + one other) on the same device.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is still an **unfilled template**. No project-specific gates can be evaluated or violated, consistent with `specs/001-android-txn-capture/plan.md` and `specs/004-notification-engine/plan.md`.

| Gate | Status | Notes |
|------|--------|-------|
| Template constitution placeholders | N/A | No ratified principles |
| Privacy (no raw text to server/logs) | PASS | Currency is a catalog/code field; templates still run on-device |
| Reuse over new paths | PASS | Currency from existing `GET /v1/accounts` / `server_accounts`; hold uses the existing outbox; no new bridge resource besides relaxing create validation |
| No new credentials / protobuf on phone | PASS | Catalog and delivery still go through the bridge |
| Post-Phase 1 re-check | PASS | See re-evaluation below |

**Verdict**: PASS (no active gates). No Complexity Tracking entries required.

### Post-Phase 1 re-check

Design does not add a third persistence store, a currencies REST endpoint, or a parser-to-regex compiler. Seeded former banks are ordinary `TransactionSource` rows. Bridge change is validation + existing mapped-account currency match (already implemented for create). Held state is one extra outbox value with a legal transition to `queued`. Privacy and reuse gates still pass.

## Project Structure

### Documentation (this feature)

```text
specs/005-account-currency-sources/
├── plan.md              # This file
├── research.md          # Phase 0
├── data-model.md        # Phase 1
├── quickstart.md        # Phase 1
├── contracts/
│   ├── bridge-api.md         # Currency on POST /v1/transactions
│   └── source-admission.md   # Source-only capture + currency copy/stamp/edit
└── tasks.md             # Phase 2 (/speckit.tasks — NOT created here)
```

### Source Code (repository root)

```text
android/app/src/main/kotlin/com/gomoney/capture/
├── capture/                 # CapturePipeline admission; receivers drop allow-list
├── source/                  # UserSourceParser currency; seed former banks; bind stamp
├── storage/                 # Room v4, DeliveryRepository held→queued
├── sync/                    # Sync skips held; BridgeClient already non-IRR-aware
├── ui/                      # Settings, source editor, events/detail currency
└── model/                   # DeliveryState.HELD

android/app/src/test/kotlin/com/gomoney/capture/
├── capture/                 # Bound/unbound/hold/stamp/no-parser-fallback
├── source/                  # Seed + bind validation
├── storage/                 # Currency empty, held transitions
├── sync/                    # Non-IRR create payload; held not delivered
└── ui/                      # Allow-list gone; currency edit gating

pkg/androidbridge/
├── handlers.go              # validateTransaction: currency non-empty, not IRR-only
├── handlers.go / gomoney.go # existing mapped-account currency match (keep)
└── *_test.go                # USD (and other) create; empty/IRR-mismatch 400

cmd/android-bridge/          # unchanged entrypoint
```

**Structure Decision**: Same two-artifact layout as 004. All capture/admission/currency-copy work is in `android/`. The only server-side change is relaxing `POST /v1/transactions` currency validation in `pkg/androidbridge` so non-rial bound accounts can be delivered. Do not add Go Money proto fields or a new `/v1/currencies` endpoint.

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

None.
