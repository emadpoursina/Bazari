# Feature Specification: Per-Currency Exchange Rate Modes

**Feature Branch**: `exchange-rate-sync`

**Created**: 2026-09-29

**Status**: Draft

**Input**: User description: "Apply it." Context: prevent exchange-rate sync from overwriting manually edited currency rates by assigning each currency a Manual or Automatic rate mode.

## Clarifications

### Session 2026-09-29

- Q: If a feed refresh is already running when a user saves a rate for the same currency, which update should win? → A: The saved manual edit wins; the refresh rechecks the mode when writing.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Keep My Edited Rate (Priority: P1)

As a user of the main app, I want a rate I edit to remain under my control so that a later exchange-rate refresh does not silently replace it.

**Why this priority**: Preventing sync from undoing a user's explicit rate change is the central problem this feature solves.

**Independent Test**: Edit a non-base currency rate, run a successful feed refresh that reports a different value, and verify the edited value remains in effect and the currency is shown as Manual.

**Acceptance Scenarios**:

1. **Given** a non-base currency with an editable rate, **When** the user saves a changed rate in the main app, **Then** the saved rate is retained and the currency's mode becomes Manual.
2. **Given** a currency in Manual mode, **When** a successful feed refresh reports a different rate, **Then** the saved rate is unchanged.
3. **Given** a manually edited rate, **When** the user returns to the main app after a refresh or restart, **Then** the saved rate and Manual mode are still in effect.
4. **Given** a feed refresh is in progress for a currency in Automatic mode, **When** the user saves a changed rate before that refresh applies its fetched rate, **Then** the saved rate remains in effect, the currency becomes Manual, and the refresh does not overwrite it.

---

### User Story 2 - Choose Which Rates Follow the Feed (Priority: P2)

As a user, I want to switch a non-base currency between Manual and Automatic so that I can choose which rates follow the exchange-rate feed.

**Why this priority**: Users need a direct way to resume automatic updates or take control of a rate after its initial setup.

**Independent Test**: Switch a currency to Automatic, complete a successful refresh with a different valid feed rate, and verify that rate is applied; then switch it to Manual and verify a later refresh leaves it unchanged.

**Acceptance Scenarios**:

1. **Given** a non-base currency in Manual mode, **When** the user switches it to Automatic, **Then** its existing rate remains in effect until the next successful feed refresh.
2. **Given** a currency in Automatic mode, **When** the user saves a changed rate, **Then** the saved value is retained and its mode becomes Manual without requiring a separate mode change.
3. **Given** a currency in Automatic mode, **When** the next successful refresh provides a valid rate, **Then** the currency uses that feed rate.

---

### User Story 3 - Start Safely and Keep the Base Rate Fixed (Priority: P3)

As a user, I want existing rates preserved when the new modes take effect, newly available feed currencies to update automatically, and the base currency to remain fixed at 1.

**Why this priority**: Safe defaults avoid changing current behavior or values unexpectedly while keeping newly supplied rates current and maintaining a stable base.

**Independent Test**: Verify that existing non-base currencies keep their current values and become Manual, a newly introduced feed currency becomes Automatic, and the base currency remains 1 after edits and refreshes.

**Acceptance Scenarios**:

1. **Given** a currency already present when rate modes take effect, **When** the change is introduced, **Then** its current rate is preserved and its mode is Manual.
2. **Given** a currency newly introduced by the exchange-rate feed, **When** a valid rate is received, **Then** the currency defaults to Automatic and uses that rate.
3. **Given** the base currency, **When** a user attempts to edit its rate or a feed refresh runs, **Then** its rate remains 1 and it cannot be assigned another mode.

### Edge Cases

- What happens when a feed refresh fails or omits a currency that is in Automatic mode? The currency keeps its last valid rate and remains Automatic until a later successful update.
- What happens when the feed supplies a missing, zero, or otherwise invalid rate? The invalid value is not applied; the currency keeps its last valid rate.
- What happens if a user edits a rate while it is Automatic? Saving the edit immediately changes that currency to Manual, so a subsequent refresh cannot replace it.
- What happens if a feed refresh is already running when a user saves a rate for the same currency? The saved manual edit wins; before applying its fetched rate, the refresh rechecks that the currency is still Automatic and leaves the manual rate unchanged.
- What happens if the user changes a currency from Manual to Automatic? Its current rate remains until a successful feed refresh supplies a valid rate.
- Can the base currency be changed to Manual or Automatic? No; its rate is fixed at 1.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The main app MUST identify each non-base currency's rate mode as Manual or Automatic.
- **FR-002**: Users MUST be able to change the mode of any non-base currency between Manual and Automatic.
- **FR-003**: When a user saves a changed rate in the main app, the system MUST save the rate and set that currency's mode to Manual.
- **FR-004**: A successful feed refresh MUST update only currencies in Automatic mode; it MUST NOT change the rate of any currency in Manual mode.
- **FR-005**: When rate modes take effect, every existing non-base currency MUST retain its current rate and be assigned Manual mode.
- **FR-006**: A currency newly introduced by the exchange-rate feed MUST default to Automatic and use a valid rate supplied by the feed.
- **FR-007**: When a user changes a currency from Manual to Automatic, its current rate MUST remain in effect until the next successful feed refresh supplies a valid rate.
- **FR-008**: The base currency MUST always have a rate of 1 and MUST NOT be editable or assigned a rate mode.
- **FR-009**: If a feed refresh fails, omits a currency, or provides an invalid rate, the system MUST retain that currency's last valid rate and its current mode.
- **FR-010**: Each currency's rate and mode MUST remain in effect across app restarts and subsequent feed refreshes unless changed by an explicit user edit or a valid update for an Automatic currency.
- **FR-011**: Rate-mode controls and manual-rate behavior MUST apply to the main app; changes to the phone app are out of scope.
- **FR-012**: Before applying each fetched rate, the system MUST recheck that the currency is still in Automatic mode; a refresh MUST NOT overwrite a rate after the user has saved a manual edit for that currency.

### Key Entities *(include if feature involves data)*

- **Currency**: A supported currency with a code, a base/non-base designation, a current exchange rate, and (for non-base currencies) a Manual or Automatic mode.
- **Exchange-rate feed update**: A set of rates made available for currencies; valid rates can update currencies in Automatic mode.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: In all tested successful refreshes, 100% of Manual currencies retain their last user-saved rate when refresh processing completes, including when that rate was saved during an in-progress refresh.
- **SC-002**: In all tested successful refreshes with valid feed data, 100% of Automatic currencies use the latest valid rate supplied for them.
- **SC-003**: At introduction of rate modes, 100% of existing non-base currencies retain their prior rate and are identified as Manual.
- **SC-004**: 100% of newly introduced feed currencies with valid rates are identified as Automatic and use the supplied rate.
- **SC-005**: In a usability check, at least 90% of users can identify a currency's active mode and whether a refresh may change its rate without assistance.
- **SC-006**: Across all tested user edits and feed refreshes, the base currency rate remains exactly 1.

## Assumptions

- Existing users retain the same permissions for editing exchange rates as they have today.
- The current exchange-rate feed and refresh behavior remain in use; this feature does not introduce a new feed or refresh schedule.
- Switching from Manual to Automatic does not immediately change the displayed rate; the next successful refresh with a valid rate does so.
- Failed or incomplete feed updates do not erase or replace a last valid rate.
- Rates and modes are shared within the main app's existing currency settings; the separate phone app is out of scope.
