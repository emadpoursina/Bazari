# Contract: Event detail (first-class)

**Feature**: 010-android-ui-refresh | **Date**: 2026-11-17

Redesigns `EventDetailScreen`. Note field rules from `specs/009-notification-note-fixes/contracts/note-field-ui.md` stay. Currency gating from 005 (`currencyEditable`) stays. Assignment save still `EventActions.saveAssignment`.

## 1. Scan order (top → bottom)

1. **Back** to events.
2. **Identity**: formatted amount, Income or Expense, source (`bank`), human-readable time, status chip. No parser name, confidence, or raw `Delivery state:` line.
3. **Send assignment**: Destination account picker, category picker, currency (dropdown if editable, read-only otherwise). Catalog unavailable: last-known list + Refresh. Stale saved category: existing warning. **One confirm** control for destination+category (`Save` / `Confirm`). Pending-sync hint when `assignmentSyncState == pending`.
4. **Note**: always visible for income and expense; cap 200; **Save note** / **Cancel** (009). Pending-sync hint unchanged.
5. **Send/retry**: if delivery `failed`, a primary **Retry** control.

Users must not guess among competing unlabeled save actions: assignment confirm vs note save are separately labeled.

## 2. Debug internals

When `debugModeEnabled` is false: no `parserName`, `confidence`, fingerprint, or raw event text.

When true: a clearly secondary **Debug** entry that shows `DebugEventScreen` (raw event + parse internals). Not in the identity header.

`MainActivity` must pass `config.debugModeEnabled` and raw event into that path (`DebugEventScreen` is currently unused).

## 3. Invariants

- Saving assignment does not change memo, currency, or delivery state except existing pending assignment sync.
- Saving note does not change assignment or currency (009).
- Saving currency does not change assignment (005).
- `tx == null`: no note/assignment editors (stale id handled by list + missing-event contract).
- Sanitized `errorDetail` may appear near Retry for failed sends; never raw bank text.
