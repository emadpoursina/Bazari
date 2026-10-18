# Feature Specification: Move Add-Note into Category Editing Screen & Default Category/Account for Transactions

**Feature Branch**: `default-category-account`

**Created**: 2026-10-18

**Status**: Draft

**Input**: User description: "in the android app, in the events tab, move the add note button inside the screen that is for editing categories. also add feature for setting default category and account for transactions."

## Clarifications

### Session 2026-10-18

- Q: Do Events-tab rows keep displaying the note text and its sync status when the add-note button moves to the editing screen? → A: Yes — rows keep displaying "Note: …" and the "waiting to sync" status; only the Add note/Edit note button moves to the event editing screen (option A).
- Q: Do defaults stamp onto a transaction at capture time, or follow it dynamically? → A: Stamp at capture — the capture pipeline sets the transaction's category ID and destination account ID to the default's IDs; later default changes never affect that transaction (option A).
- Q: How does a default-pre-assigned category/account get synchronized? → A: Exactly like a user assignment — mark assignmentSyncState pending and enqueue the existing expedited-sync follow-up (option A).
- Q: What happens when the default destination account's currency differs from a new transaction's? → A: The default is applied regardless of currency mismatch; the user can override it per event (option A).
- Q: Where are the default category and default account set and managed? → A: A new "Default category & default account" section in the existing Settings tab, with clear buttons for each default (option A).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Add/Edit a note from the category-editing screen (Priority: P1)

A user opens an event from the Events tab to edit its destination account and category. On that same editing screen the user can also add or edit the note (memo) attached to the transaction. The note button no longer appears on the Events tab list rows themselves; only the add/edit note *button* moves — each row keeps displaying its note text ("Note: …") and any "waiting to sync" status exactly as today. The list stays clean and note editing happens only inside the editing screen.

**Why this priority**: This is the directly requested UI reorganization and is a prerequisite for a coherent editing flow — all per-event edits (category, account, note) happen in one place.

**Independent Test**: Can be fully tested by opening an event from the Events tab, adding a note on the editing screen, saving, and verifying the note is stored and shown; and by verifying the Events tab list rows no longer offer an add/edit note button.

**Acceptance Scenarios**:

1. **Given** an event with no note, **When** the user opens its editing screen from the Events tab and taps the add-note control, **Then** the user can type and save a note, and the saved note is displayed on the editing screen.
2. **Given** an event that already has a note, **When** the user opens its editing screen, **Then** the existing note is shown and can be edited and re-saved.
3. **Given** the Events tab list, **When** the user looks at an event row, **Then** there is no add/edit note button on the row itself, while any existing note text ("Note: …") and its "waiting to sync" status are still displayed on the row.
4. **Given** a note saved from the editing screen, **When** the user returns to the Events tab and reopens the event, **Then** the note (and any pending-sync indicator, if applicable) is still shown on the editing screen.

---

### User Story 2 - Set a default category for new transactions (Priority: P2)

A user chooses one of the server categories as their default. Newly captured transactions are pre-assigned that category automatically, while still allowing the user to override it per event.

**Why this priority**: High recurring value — most users categorize transactions the same way and should not have to pick per event. Independent of User Story 1.

**Independent Test**: Can be fully tested by setting a default category, capturing a new transaction, and verifying the transaction is pre-assigned the default category while existing transactions are untouched.

**Acceptance Scenarios**:

1. **Given** no default category is set, **When** the user opens the default-selection setting, **Then** the user can pick a category from the server category list and save it.
2. **Given** a default category is set, **When** a new transaction is captured, **Then** that transaction is pre-assigned the default category.
3. **Given** a default category is set and a new transaction was pre-assigned it, **When** the user opens the event's editing screen, **Then** the pre-assigned category is shown and the user can change it; the change is saved for that event only.
4. **Given** a default category was set before some transactions existed, **When** the default is changed or set, **Then** previously captured transactions are not retroactively re-categorized.

---

### User Story 3 - Set a default destination account for new transactions (Priority: P2)

A user chooses one of the server accounts as their default destination. Newly captured transactions are pre-assigned that destination account automatically, with per-event override still available.

**Why this priority**: Symmetric to the default category and equally valuable; can ship independently if needed.

**Independent Test**: Can be fully tested by setting a default destination account, capturing a new transaction, and verifying the transaction is pre-assigned that destination account while existing transactions are untouched.

**Acceptance Scenarios**:

1. **Given** no default destination account is set, **When** the user opens the default-selection setting, **Then** the user can pick an account from the server account list and save it.
2. **Given** a default destination account is set, **When** a new transaction is captured, **Then** that transaction is pre-assigned the default destination account.
3. **Given** a default destination account is set and a new transaction was pre-assigned it, **When** the user changes the destination on the event's editing screen, **Then** the change applies to that event only and the default remains unchanged.
4. **Given** a default destination account was set before some transactions existed, **When** the default is changed or set, **Then** previously captured transactions keep their existing destination assignment.

---

### Edge Cases

- What happens when the chosen default category or default account no longer exists on the server (removed or catalog refresh removed it)? The app must not crash: the stored default becomes invalid, new transactions fall back to the current behavior (no pre-assignment / "Default"), and the settings screen indicates the saved default is no longer available so the user can pick a new one.
- What happens when the category/account catalog is unavailable (offline, refresh failed)? The user cannot pick a new default; existing defaults continue to apply to new transactions.
- What happens when the user clears a default? The setting is removed and new transactions go back to having no pre-assigned category/destination ("Default").
- What happens when a user saves a note longer than the allowed limit? Input is capped at the existing note length limit (same limit as today).
- What happens to events captured before a default was set? They are unaffected (no retroactive application).
- What happens when the category catalog refresh removes a category that was pre-assigned via the default to an unsynced event? Existing behavior for a missing category (flag it on the editing screen and ask the user to pick a new one) still applies.
- What happens when the default destination account's currency differs from the currency of a newly captured transaction? The default is applied regardless of the mismatch (no warning blocks it); the user can override the destination for that event.

## Requirements *(mandatory)*

### Functional Requirements

**Note relocation (Events tab)**

- **FR-001**: The Events tab list rows MUST NOT offer an add/edit note control; note editing is removed from the list view. Rows MUST continue to display an event's note text ("Note: …") and its "waiting to sync" status exactly as they do today — only the button moves, not the display.
- **FR-002**: The per-event editing screen (the screen opened from the Events tab where destination account and category are edited) MUST provide an add/edit note capability with the same behavior as before: pre-fill any existing note, enforce the existing note length limit, save on confirmation, and show the saved note.
- **FR-003**: Moving the note control MUST NOT change how notes are stored or synchronized; saved notes keep the existing save/pending-sync behavior and the existing row display of note text and pending-sync status.
- **FR-004**: Notes on existing events MUST remain intact and readable from the editing screen after the change.

**Default category and default account**

- **FR-005**: The app MUST let the user choose a default category from the cached server category list and save it; the choice persists across app restarts.
- **FR-006**: The app MUST let the user choose a default destination account from the cached server account list and save it; the choice persists across app restarts.
- **FR-007**: Newly captured transactions MUST be pre-assigned the default category and default destination account at capture time, while remaining fully editable per event afterwards. The pre-assignment stamps the default's category ID and destination account ID onto the transaction during capture; subsequent changes to the defaults MUST NOT dynamically affect that transaction.
- **FR-007a**: A default-pre-assigned category/destination MUST be treated exactly like a user assignment for synchronization: its assignmentSyncState is marked pending and the existing expedited-sync follow-up is enqueued.
- **FR-007b**: The default destination account MUST be applied to a new transaction regardless of any currency mismatch with that transaction; no warning blocks the assignment, and the user can override it per event.
- **FR-008**: Setting, changing, or clearing a default MUST NOT modify any previously captured transactions.
- **FR-009**: The user MUST be able to clear each default independently, returning new transactions to the current no-pre-assignment ("Default") behavior.
- **FR-010**: If a saved default no longer exists in the server catalog, the app MUST treat new transactions as having no default for that field, and MUST surface to the user (in the settings/default-selection area) that the saved default is no longer available.
- **FR-011**: The current default category and default destination account MUST be visible to the user where the defaults are managed, i.e., shown as the current selection in the "Default category & default account" section of the Settings tab.

### Key Entities

- **Transaction (event)**: A captured transaction; relevant attributes: note (memo), category assignment, destination account assignment. Each may be explicitly assigned or fall back to the system default.
- **Server Category**: A category offered by the server; one may be designated as the user's default category.
- **Server Account**: An account offered by the server; one may be designated as the user's default destination account.
- **Default Preferences**: The user's saved choices of default category and default destination account; independent of any individual transaction.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: After the change, 0 of the Events tab list rows show an add/edit note control, and a user can complete "open event → add note → save" in under 30 seconds.
- **SC-002**: 100% of pre-existing notes remain readable and editable from the editing screen after the change (no data loss).
- **SC-003**: With a default category and default destination account set, 100% of newly captured transactions arrive pre-assigned with both, requiring zero taps from the user when the defaults are what they wanted.
- **SC-004**: Changing a default affects 0 previously captured transactions in all test scenarios.
- **SC-005**: A user can set, change, and clear each default in under 1 minute, and the current defaults are visible at a glance in the settings area.
- **SC-006**: When a saved default has been removed from the server catalog, the app continues to work normally (no crashes, no blocked flows) and 100% of affected users are shown that their default is no longer available.

## Assumptions

- "The screen that is for editing categories" is interpreted as the per-event editing screen opened from the Events tab (the screen where the destination account and category drop-downs live). There is currently no separate category-management screen; if one is intended, that is a scope question for a later stage.
- The add-note control on the editing screen behaves exactly like today's list-row control (same length limit, same pre-fill, same save/pending-sync semantics); only its location changes.
- The add/edit note control is removed entirely from the Events tab rows (moved, not duplicated); rows still display the note text and its pending-sync status.
- "Default category/account" applies only to newly captured transactions, at capture time; it is never applied retroactively and never overrides an explicit per-event choice. Defaults are stamped onto the transaction's IDs during capture — they do not follow later default changes dynamically.
- A default-pre-assigned category/destination syncs exactly like a user assignment (assignmentSyncState pending + the existing expedited-sync follow-up); no separate sync path exists.
- The default destination account is applied even when its currency does not match the new transaction's currency; there is no currency-mismatch warning, and per-event override remains available.
- The default selections are chosen from the same cached server category/account lists already used for per-event editing; no new server-side feature is required.
- The default *preference* (the chosen default IDs) is stored locally on the device and is per-user-on-device (not synced to the server) unless a later stage specifies otherwise; this is distinct from a stamped transaction assignment, which syncs like any user assignment (see FR-007a).
- The defaults live in a new "Default category & default account" section within the app's existing Settings tab — where catalog refresh and other app-level preferences already live — with separate clear buttons for each default.
- If a transaction's category/destination is already set by other means (e.g., an existing assignment or pipeline behavior), that existing assignment wins over the default; the default fills in only when no assignment exists.
- Scope is limited to the Android app's UI/behavior for these two changes; server, sync protocol, and other clients are untouched.
