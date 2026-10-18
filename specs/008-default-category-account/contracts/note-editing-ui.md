# Contract: Note Editing Relocation (Events tab → Event detail screen)

**Feature**: 008-default-category-account | **Date**: 2026-10-18
**Scope**: Compose screen-level contract. Storage and sync behavior is intentionally identical to before (FR-003) and is *not* redefined here.

## 1. Events tab row (`EventsScreen`) — display only

**Removed**: the `editingMemo` inline text field, `Save note`/`Cancel` buttons, and the `Add note`/`Edit note` button, plus the `onSaveMemo` parameter of `EventsScreen`.

**Retained exactly as today (FR-001):**
- `Note: <text>` line when `userMemo` is non-blank;
- pending-sync labels (`"Saved on this phone · waiting to sync"` / `"Note removal saved on this phone · waiting to sync"`);
- everything else (checkbox, amount, state, category line, `Set category`, `Retry delivery`).

## 2. Event detail screen (`EventDetailScreen`) — the single editing surface

**New parameter**: `onSaveMemo: (txId: String, memo: String) -> Unit`, wired in `MainActivity` to the existing `EventActions.saveMemo` (no new save logic).

**Behavior:**

| Rule | Value |
|---|---|
| Prefill | `tx.userMemo.orEmpty()` keyed on `tx.id` |
| Input cap | `MAX_MEMO_LENGTH = 200` (shared constant; enforced live in `onValueChange`) |
| Save | `onSaveMemo(tx.id, draft)` → trim/blank→null/pending-sync handled by `EventActions.saveMemo` |
| Cancel | discards draft, reverts to display mode |
| Display after save | saved note shown on the screen; pending label shown when `memoSyncState == "pending"` |
| Missing tx (`tx == null`) | note section not rendered (screen already guards with `tx?.let`) |

**Placement**: below the category/destination assignment controls, above the failed-delivery retry block — the screen where "destination account and category" are edited (spec US1).

**Invariants**: saving a note from this screen never changes `categoryId`, `destinationAccountId`, `assignmentSyncState`, `currency`, or delivery state; the note limit and pre-fill match the pre-move row behavior exactly (FR-002, FR-004).

## 3. Cross-surface consistency

- Opening the detail screen from the Events tab after a row no longer offers editing must show the same note text the row displays (SC-002).
- Round-trip: detail-screen save → back to list → row shows `Note: …` + pending label → reopen detail → same text (US1 scenario 4).
