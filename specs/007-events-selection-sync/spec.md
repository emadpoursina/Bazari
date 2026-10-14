# Feature Specification: Events Tab Selection, Selective Sync/Delete, Fitting Top Bar

**Feature Branch**: `feature/events-selection-sync`

**Created**: 2026-10-14

**Status**: Draft

**Input**: User description: "skip events tab gets per-event checkbox selection with select all and clear all, sync and delete applied only to selected events, plus a redesigned top bar that fits on screen."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Select events per row (Priority: P1)

A user opens the Events tab and sees a checkbox on each event row. Tapping a checkbox selects or deselects that event. A counter shows how many events are selected out of the total.

**Why this priority**: Selection is the foundation for selective sync and delete; without it the rest has no target.

**Independent Test**: Can be fully tested by opening the Events tab with 3 events, tapping checkboxes, and verifying the selected count updates and survives list refresh.

**Acceptance Scenarios**:

1. **Given** the Events tab shows 3 events and none are selected, **When** the user taps the checkbox on 2 events, **Then** the counter shows 2 selected and those 2 rows appear checked.
2. **Given** 2 events are selected, **When** the user taps "Select all", **Then** every visible event becomes selected.
3. **Given** several events are selected, **When** the user taps "Clear" (clear selection), **Then** no event remains selected and the counter shows 0.

---

### User Story 2 - Sync and delete only selected events (Priority: P1)

With events selected, the user presses Sync or Delete. Only the selected events are synced (failed ones requeued, then pushed) or permanently deleted; unselected events are untouched.

**Why this priority**: Core value — today Clear processed / Clear all act on everything; users need scoped actions.

**Independent Test**: Can be fully tested by selecting 1 of 3 events, pressing Delete, and verifying only that event disappears; and by selecting failed events, pressing Sync, and verifying only selected failures move to queued/sent.

**Acceptance Scenarios**:

1. **Given** 3 events exist and 1 is selected, **When** the user confirms "Delete selected", **Then** only the selected event is removed and the other 2 remain.
2. **Given** 2 failed and 1 queued event with only the failed ones selected, **When** the user presses "Sync selected", **Then** the selected failures are requeued and pushed while the unselected queued event is not touched by this action.
3. **Given** no events are selected, **When** the user looks at the Sync/Delete buttons, **Then** both are disabled and pressing them does nothing.

---

### User Story 3 - Top bar fits on screen (Priority: P2)

The Events tab top action bar shows all actions without horizontal scrolling or clipping on a normal phone width: selection actions, selective actions, destructive clears, and scan.

**Why this priority**: The current bar scrolls horizontally and clips; a fitting bar makes selection usable.

**Independent Test**: Can be fully tested by opening the Events tab on a 360dp-wide viewport and verifying every button is reachable without horizontal scrolling.

**Acceptance Scenarios**:

1. **Given** the user opens the Events tab, **When** the top bar renders, **Then** all buttons wrap into visible rows with no horizontal scroll or clipped labels.
2. **Given** the selection changes, **When** the counter updates, **Then** the Sync/Delete labels reflect the selected count.

---

### Edge Cases

- What happens when a selected event disappears (deleted elsewhere, list refresh)? The selection prunes IDs that no longer exist.
- What happens when Delete selected is pressed with an empty selection? Buttons are disabled; no dialog appears.
- What happens when Sync selected is pressed while offline? Failed requeues still happen locally; network push is best-effort and unselected rows are never requeued by this action.
- What happens when both parse-error rows and transaction rows are selected? Delete removes both (raw event cascade); sync only affects rows with a delivery record in a syncable state.
- How are destructive deletes confirmed? Delete selected and Clear all each show their own confirmation dialog.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The Events tab MUST show a checkbox on every event row reflecting that row's selection state.
- **FR-002**: The Events tab MUST provide "Select all" (select every listed event) and "Clear" (deselect all) controls.
- **FR-003**: The Events tab MUST show a selection counter (selected / total).
- **FR-004**: "Sync selected" MUST requeue only selected FAILED rows to QUEUED and push only selected rows; it MUST NOT requeue or push unselected rows.
- **FR-005**: "Delete selected" MUST permanently delete only the selected raw events (cascading to their transactions/delivery rows) after user confirmation, leaving unselected events intact.
- **FR-006**: Sync/Delete selective buttons MUST be disabled when nothing is selected.
- **FR-007**: The top action bar MUST lay out all controls without horizontal scrolling or clipping on a standard phone width (wrapping into multiple rows with compact sizing).
- **FR-008**: Existing "Clear processed", "Clear all" (with its confirmation), and "Scan notifications" actions MUST keep working unchanged alongside the new controls.
- **FR-009**: Selection MUST be keyed by stable event ID and pruned when rows disappear.

### Key Entities

- **Event selection**: The set of selected event IDs in the Events tab; transient UI state, not persisted.
- **Event**: An existing captured entry shown in the Events tab (transaction row or parse-error row), identified by its stable ID.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Users can select any subset of visible events and see the selected count update immediately.
- **SC-002**: Deleting a 1-of-3 selection removes exactly 1 event in 100% of trials.
- **SC-003**: Syncing a selection requeues only selected failures (0 unselected rows change state).
- **SC-004**: All top-bar buttons are visible without horizontal scrolling on a 360dp viewport.
- **SC-005**: Existing clear/scan actions still work after the change (no regression).

## Assumptions

- Selection is transient (cleared on tab leave / process death is acceptable); no persistence requirement.
- Selective sync runs as a foreground scoped drain of the selected IDs plus an expedited worker trigger; full-queue periodic sync still exists independently.
- Delete cascade relies on the existing raw_events foreign-key cascade.
