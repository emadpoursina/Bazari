# Feature Specification: Notification Scan Button

**Feature Branch**: `notification-events-button`

**Created**: 2026-10-05

**Status**: Draft

**Input**: User description: "Simple button in the android tracker, in the events tab that when pressed tracks current active notifications in the phone and tries to add them to events."

## Clarifications

### Session 2026-10-05

- Q: If a notification that has already produced an event is updated in place and still sits in the notification shade, should a later scan create a second event? → A: No — at most one event per notification, regardless of content changes.
- Q: Should events created by a scan be distinguishable from events captured in real time? → A: No — scan-created events are identical to real-time events.
- Q: Which counts must the scan summary include? → A: Examined, added, skipped (already-recorded counted inside skipped).
- Q: How should the completed scan's summary be shown in the Events tab? → A: Inline banner at the top of the Events list, cleared on the next scan or leaving the tab.
- Q: When the app lacks notification-read access, what should the guidance actually do? → A: A button that opens the system notification-access settings screen.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Capture events from notifications already on screen (Priority: P1)

A user opens the Events tab of the Android tracker and presses a scan button. The app reads the notifications that are currently visible in the phone's notification area and attempts to turn each one into an event using the same rules as notifications arriving in real time. Notifications that qualify and have not been recorded before appear as new events in the Events tab; ones that do not qualify or were already recorded are simply skipped.

**Why this priority**: This is the core value of the feature. Real-time capture can miss notifications (app restarted, service interrupted, notification posted while capture was unavailable), and this button gives users a manual way to recover those events without waiting for new notifications to arrive.

**Independent Test**: Can be fully tested by posting (or already having) qualifying notifications on the device, pressing the scan button, and verifying that the corresponding new events show up in the Events tab exactly once.

**Acceptance Scenarios**:

1. **Given** the user is on the Events tab and the app is permitted to read notifications, **When** the user presses the scan button while the phone shows notifications from recognized sources that have not been captured yet, **Then** those notifications are converted into events and appear in the Events list.
2. **Given** an event was already created from a given notification, **When** the user presses the scan button again, **Then** no duplicate event is created for that notification.
3. **Given** the phone shows only notifications that do not match any recognized source, **When** the user presses the scan button, **Then** no new events are created and the user is informed that nothing new was found.
4. **Given** a scan has been started, **When** it completes, **Then** the Events list reflects the results without the user needing to manually refresh in a way that loses the new entries.

---

### User Story 2 - Understand the result of a scan (Priority: P2)

After pressing the scan button, the user gets clear feedback about what happened: how many notifications were examined, how many new events were added, and how many were skipped or could not be processed — plus a clear message if the scan could not run at all.

**Why this priority**: Without feedback the user cannot tell whether the button worked, whether their notifications were excluded intentionally, or whether a permission problem stopped the scan. Feedback makes the P1 story trustworthy and debuggable by users.

**Independent Test**: Can be tested by running scans in each condition (new events found, nothing found, permission missing) and verifying the reported outcome matches what actually happened.

**Acceptance Scenarios**:

1. **Given** a scan finds 2 new qualifying notifications, **When** the scan finishes, **Then** the user is told that 2 new events were added.
2. **Given** the app does not have permission to read notifications, **When** the user presses the scan button, **Then** the user is shown a clear message and guidance to grant notification access, and no partial or confusing state is left behind.
3. **Given** a scan is already running, **When** the user presses the scan button again, **Then** the app does not start a conflicting second scan of the same data.

---

### User Story 3 - Recover from interrupted or partial capture (Priority: P3)

A user who missed events because the app was not running or capture was interrupted uses the scan button as a catch-up tool: pressing it once brings the Events list up to date with everything currently on the notification shade.

**Why this priority**: This is the main long-term use case, but it depends on P1 and P2 working correctly first; it is the same mechanism exercised over a larger backlog.

**Independent Test**: Can be tested by having several uncaptured qualifying notifications on the device, pressing the scan button once, and confirming the Events list then matches what a real-time capture would have produced for those notifications.

**Acceptance Scenarios**:

1. **Given** the app was unavailable while 3 qualifying notifications arrived, **When** the user presses the scan button after reopening the app, **Then** all 3 missing events are present in the Events list after the scan.
2. **Given** the phone's notification area contains a mix of qualifying and non-qualifying notifications, **When** the user presses the scan button, **Then** only the qualifying ones become events and each appears exactly once.

---

### Edge Cases

- What happens when the app is not permitted to read notifications? The scan must not run partially; the user must see actionable guidance instead.
- What happens when there are zero active notifications? The scan completes and reports that nothing was found.
- What happens when the same notification is scanned repeatedly? Each notification yields at most one event across all scans and all real-time captures.
- How does the system handle a very large number of active notifications (e.g., dozens)? The scan processes all of them without freezing the Events tab and reports a final summary.
- What happens if the scan is interrupted (user leaves the app, process killed)? On the next run, no duplicate or half-created events exist; the user can simply scan again.
- What happens if only some notifications are processable (unsupported source, malformed content)? Those are skipped without failing the whole scan; the summary reflects skips.
- What happens if the user presses the button while a scan is running? A single scan runs at a time; the button does not trigger overlapping scans.
- What happens if a notification that already produced an event is updated in place while still active? At most one event exists for that notification; the changed content never yields a second event.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The Events tab MUST provide a clearly visible control that lets the user start a scan of the notifications currently active on the phone.
- **FR-002**: Pressing the control MUST cause every currently active notification to be evaluated against the same capture rules used for notifications arriving in real time (same source recognition, same content extraction, same persistence path).
- **FR-003**: A notification that has already produced an event MUST NOT produce a duplicate event, regardless of how many scans or real-time captures it goes through. This holds even if the notification is updated in place while still active: at most one event ever exists per notification, regardless of content changes.
- **FR-004**: Notifications that do not match a recognized source or contain no usable content MUST be skipped without interrupting the scan of the remaining notifications.
- **FR-005**: When the scan completes, the system MUST show the user a summary of the outcome containing the number of notifications examined, the number of new events added, and the number skipped (already-recorded notifications counted inside "skipped"), or an explicit "nothing new found" result. The summary MUST be presented as an inline banner at the top of the Events list, cleared when the next scan starts or the user leaves the tab.
- **FR-006**: If the app lacks permission to read notifications, pressing the control MUST NOT start a scan; instead the user MUST be shown a clear message that includes a button opening the system notification-access settings screen, so the required access can be granted.
- **FR-007**: Only one scan MAY run at a time; pressing the control during an active scan MUST NOT start a second, overlapping scan.
- **FR-008**: Scanning MUST NOT block interaction with the Events tab; the user can keep browsing the list while a scan is in progress, with the control indicating that a scan is running.
- **FR-009**: Newly added events MUST appear in the Events list after the scan without requiring an app restart.
- **FR-010**: The scan MUST NOT modify or remove existing events; it is additive only.

### Key Entities

- **Event**: A recorded transaction or notification-derived entry shown in the Events tab; produced either by real-time notification arrival or by a manual scan, and uniquely identified so the same source notification never yields two events. Events created by a scan are indistinguishable from real-time events — no capture-source flag or badge is stored or shown.
- **Active Notification**: A notification currently present in the phone's notification area at the time of the scan; the input set for a scan.
- **Scan Result**: The outcome summary of one scan run (added count, skipped count, or failure reason such as missing permission).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Given at least 3 uncaptured qualifying notifications, one press of the scan button results in all 3 events being present in the Events list, with zero duplicates.
- **SC-002**: Repeating the scan immediately after a successful scan adds 0 new events (100% duplicate suppression across repeated scans).
- **SC-003**: A scan over 50 active notifications completes and reports its summary in under 10 seconds, without the Events tab becoming unresponsive.
- **SC-004**: In 100% of presses made without notification-read permission, the user receives a message with a control that opens the system notification-access settings screen, instead of a silent failure or an empty result.
- **SC-005**: After any single scan, the user can determine the outcome (added, skipped, or blocked) without inspecting logs or technical details.
- **SC-006**: 100% of scans are idempotent — running a scan N times yields the same event set as running it once.

## Assumptions

- The app already has (or the user can grant) the platform permission to read notifications; the feature reuses that existing access rather than requesting a new kind of permission.
- "Active notifications" means what the phone's notification area shows at the moment the button is pressed — including notifications from before the app was last opened — not a stored history of dismissed notifications.
- Notifications that were dismissed/swiped away before the scan are out of scope; only notifications still present are scanned.
- The scan applies the app's existing source allow-list and content-extraction rules unchanged; this feature adds no new matching rules.
- The button lives in the existing Events tab alongside the current list and event actions; no new screen or navigation is introduced.
- The summary is transient (shown at/after the scan) rather than stored permanently as part of the event history.
- Scan results are local to the device and follow the same sync/persistence behavior as events created in real time.
- If the platform makes reading the active notification list impossible for any reason, the scan fails cleanly with user guidance rather than partially succeeding.
