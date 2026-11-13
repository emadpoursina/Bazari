# Contract: Always-visible note field on event editing screen

**Feature**: 009-notification-note-fixes | **Date**: 2026-11-12

Supersedes the two-step reveal in `specs/008-default-category-account/contracts/note-editing-ui.md` §2 (`editingMemo` + Add/Edit note button). Events-tab **row** display rules in 008 §1 stay.

## 1. `EventDetailScreen` when `tx != null`

On first paint, without an extra tap:

- Label `Note`
- `OutlinedTextField` prefilled with `tx.userMemo.orEmpty()`
- Placeholder when empty (existing “What was this for?” / grocery examples)
- Live cap `EventActions.MAX_MEMO_LENGTH` (200)
- `Save note` → `onSaveMemo(tx.id, draft)` → existing trim / blank→null / `memoSyncState='pending'` / expedited sync
- `Cancel` discards the draft back to `tx.userMemo.orEmpty()`; **field remains visible**
- Pending labels unchanged (“Saved on this phone · waiting to sync” / removal variant)

Income and expense use this same block (no `type` branch).

When `tx == null` (unknown id after a stale notification): note section not rendered; list + missing-event copy is owned by [notification-tap-navigation.md](./notification-tap-navigation.md).

## 2. Invariants

- Saving a note does not change category, destination account, assignment sync state, currency, or delivery state.
- Notes that existed before this change remain in the field (SC-004).
- After save, `MemoPromptNotifier.cancel` runs ([notification-cadence.md](./notification-cadence.md)).
- Events list still has **no** add/edit note button; rows still show `Note: …` and waiting-to-sync text.
