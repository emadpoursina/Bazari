# Data Model: Default Category/Account + Note Editing Relocation

**Date**: 2026-10-18 | **Feature**: 008-default-category-account

**No new tables, no schema migration.** All entities below already exist; this feature adds *values* (DataStore keys) and *stamping behavior* on existing columns.

## 1. Default Preferences (new — DataStore, not a Room entity)

Persisted via `SettingsRepository` DataStore<Preferences>:

| Field | Key | Type | Absent means | Set means |
|---|---|---|---|---|
| `defaultCategoryId` | `default_category_id` | `Int?` | no default category (current "Default" behavior) | server category ID to stamp on new captures |
| `defaultAccountId` | `default_account_id` | `Int?` | no default destination account | server account ID to stamp on new captures |

- **Validation**: an ID is honored only while it resolves in the cached catalog (`server_categories` / `server_accounts`); otherwise treated as unset (FR-010) but **kept stored** so the settings screen can still show/warn about it (SC-006).
- **Clear (FR-009)**: `preferences.remove(key)` per key — each default independent.
- **Scope**: per-device, local-only, never synced (spec Assumption). Survives restarts (FR-005/FR-006).
- **Relationships**: logically references `ServerCategory.id` / `ServerAccount.id` (soft reference — no FK across storage layers; validated at read/stamp time).
- **State transitions**: none (a stored value is either valid-in-catalog or stale; no lifecycle).

## 2. ServerCategory / ServerAccount (existing, read-only here)

- `server_categories(id: Int, label: String, refreshedAt: String)` — one may be the user's default category.
- `server_accounts(id: Int, label, currency, type, isDefault, refreshedAt)` — one may be the user's default destination account.
- **Disambiguation**: `ServerAccount.isDefault` is the *server-side* account flag from the catalog and is **not** the user's default-destination preference (R3/R6). No writes to either table from this feature.
- Catalog availability (`CatalogRepository.Status`) gates *picking* a new default (edge case: offline/refresh-failed → pickers disabled, existing defaults keep applying).

## 3. NormalizedTransaction (existing — stamping rules at capture)

Relevant existing columns: `categoryId: Int?`, `destinationAccountId: Int?`, `assignmentSyncState: 'pending' | 'synced'` (default `'synced'`), `userMemo: String?`, `memoSyncState`.

**Capture-time stamping rule (FR-007, contract `contracts/defaults-stamping.md`)** — applied in `CapturePipeline` after parse, before insert:

```
if (tx.categoryId == null && defaultCategoryId valid in server_categories)
    tx.categoryId = defaultCategoryId
if (tx.destinationAccountId == null && defaultAccountId valid in server_accounts)
    tx.destinationAccountId = defaultAccountId
if (either field was stamped) tx.assignmentSyncState = 'pending'   // FR-007a
```

- Fill-if-null only: any assignment already present wins (spec Assumption).
- Stamping never touches `currency`, `fingerprint`, `memoSyncState`, or delivery state.
- **No retroactivity (FR-008)**: the rule runs only during capture; existing rows are never rewritten by set/change/clear of a default.

### `assignmentSyncState` transitions for a stamped row

```
capture ──(stamped)──► pending ──(initial send CREATED/DUPLICATE, or
                                  PUT /v1/transactions/assignment ok)──► synced
```

- Queued capture: existing `onCaptured → SyncEngine.enqueueExpedited` (T031) is the expedited follow-up; initial payload already carries both IDs; `markAssignmentSynced(id, dest, cat)` clears pending only when values still match.
- Held capture (blank currency, 005 T027): stays `pending` until the row reaches `sent` after binding (T028) — no expedited work while held.
- User override later (`EventActions.saveAssignment`): `pending → synced` via the identical path; per-event change never writes back to the defaults (FR-007 last sentence, US2/US3 scenario 3/4).

## 4. UserMemo (unchanged — relocation only)

- Storage/sync semantics untouched (FR-003): `updateMemo` → `memoSyncState='pending'` → expedited sync; `pendingMemos()` unchanged.
- Only the *editing surface* moves: `EventDetailScreen` reads/writes `userMemo` via the existing `EventActions.saveMemo` (trim, cap 200, blank→null). Rows keep rendering `Note: …` + pending labels (FR-001).

## 5. Validation rules (from requirements)

| Rule | Source | Enforcement point |
|---|---|---|
| Note ≤ 200 chars | FR-002 (existing limit) | `EventDetailScreen` input cap + `EventActions.saveMemo(.take(200))` |
| Default applied only at capture, never retroactively | FR-007/FR-008 | stamping runs only in `CapturePipeline.processSource` |
| Default fills only empty assignments | spec Assumption | fill-if-null condition |
| Stale default ID ⇒ no pre-assignment + user-visible notice | FR-010 | catalog `byId` validation at stamp time + settings warning line |
| Currency mismatch never blocks destination default | FR-007b | no currency check anywhere on this path (R7) |
| Each default clearable independently | FR-009 | per-key `remove()` |
