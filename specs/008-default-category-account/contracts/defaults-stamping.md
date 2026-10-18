# Contract: Default Category/Account Stamping & Persistence

**Feature**: 008-default-category-account | **Date**: 2026-10-18
**Scope**: internal Android contract between `SettingsRepository` (persistence), `SettingsScreen` (management UI), and `CapturePipeline` (application). Server and bridge protocols are untouched.

## 1. Persistence (`SettingsRepository`)

```kotlin
data class DefaultPreferences(val defaultCategoryId: Int?, val defaultAccountId: Int?)

fun observeDefaults(): Flow<DefaultPreferences>          // key absent → null field
suspend fun currentDefaults(): DefaultPreferences        // one-shot read (capture path)
suspend fun setDefaultCategoryId(id: Int)
suspend fun setDefaultAccountId(id: Int)
suspend fun clearDefaultCategory()                      // remove(default_category_id)
suspend fun clearDefaultAccount()                       // remove(default_account_id)
```

- Keys: `default_category_id`, `default_account_id` (`intPreferencesKey`).
- Semantics: per-device, local-only, never sent to the server; persists across restarts.
- Clearing one default must not affect the other (FR-009).

## 2. Application (capture pipeline) — the stamping rule

**Trigger**: `CapturePipeline.processSource`, after `ParseResult.Success`, before `normalizedTransactionDao().insert(tx)`.

**Inputs**: parsed `NormalizedTransaction` (fields 4 & 5 null from parsers), `currentDefaults()`, cached catalogs via `serverCategoryDao().byId(id)` / `serverAccountDao().byId(id)`.

**Output (pure function over the tx):**

| Condition | Result |
|---|---|
| `tx.categoryId == null` AND default category ID resolves in catalog | `categoryId = defaultCategoryId` |
| `tx.destinationAccountId == null` AND default account ID resolves in catalog | `destinationAccountId = defaultAccountId` |
| default ID stored but not in cached catalog | field left `null` (FR-010); **stored default is not modified or deleted** |
| either field stamped | `assignmentSyncState = 'pending'` |
| field already non-null | left untouched (existing assignment wins) |
| no defaults / nothing stamped | `assignmentSyncState` stays `'synced'` |

**Invariants (must hold after stamping):**
- `currency`, `fingerprint`, `memoSyncState`, `userMemo`, delivery-state behavior are unchanged.
- Currency mismatch is never evaluated (FR-007b).
- The stamped row equals what `EventActions.saveAssignment(id, dest, cat)` would have produced → sync follows the existing path with **zero `sync/` changes**: queued captures enqueue the existing expedited sync (T031); held captures (blank currency) remain `pending` until they reach `sent` (005 T027/T028).
- The rule executes only at capture; set/change/clear of a default never rewrites existing rows (FR-008).

**Outcome contract (testable)**: `Outcome.QUEUED` / `Outcome.HELD` distribution is unaffected by stamping; stamping cannot turn a capture into a new `Outcome`.

## 3. Management UI (Settings tab) — visibility & notices

**Section**: "Default category & default account" card inside the existing `SettingsScreen`.

| Input | UI behavior |
|---|---|
| defaults flow + catalog lists | shows current selection label for each default (FR-011), or "None" when unset |
| stored default ID ∉ current catalog | shows "no longer available" warning; picking a new value replaces it; Clear removes the stored key (FR-010) |
| catalog empty / `Status.UNAVAILABLE` | pickers and Clear remain visible but selection is disabled with an explanatory hint; stored defaults keep applying (edge case: catalog unavailable) |
| user selects an item | corresponding setter called immediately (local-first, no save button) |
| user taps Clear (each independent) | corresponding clearer called → new captures revert to no pre-assignment |

**Out of scope**: changing/ clearing a default produces no writes to `normalized_transactions` and no sync traffic.

## 4. Non-goals

- No new REST/RPC endpoints, no payload changes to `sendWithFallback` or `PUT /v1/transactions/assignment`.
- No per-user server sync of defaults.
- `ServerAccount.isDefault` (server catalog flag) is unrelated and untouched.
