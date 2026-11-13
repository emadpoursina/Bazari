# Feature Specification: Quieter Capture Alerts, Deep-Link to Event, Visible Note Field

**Feature Branch**: `mobile-tx-notifications`

**Created**: 2026-11-12

**Status**: Draft

**Input**: User description: "There are some problems that I need you to fix on the mobile app: (1) On each transaction notification two new notif from tracking android app is generated: waiting to sync + what was this transaction for. only 1 is enough about a new transaction. at the end of the night just send a notification if either there is unsync transcation or notification without note. (2) When i click on the notification, it should open the application in that specific notification detail page so I can add note or edit it. (3) right now there is two transaction on the app, one income and one expense. when i click on the set category so I can add note, there is no note field."

## Clarifications

### Session 2026-11-12

- Q: Besides the one capture alert per new transaction, should the app still post daytime system notifications about sync status (waiting to sync, caught up, or action needed)? → A: Silence all daytime sync-status system notifications (waiting to sync, caught up, action needed). Keep per-event capture alerts and one nightly leftover reminder. In-app “waiting to sync” labels stay.
- Q: For the nightly leftover reminder, which captured events with a blank note should count as leftover? → A: Blank-note leftover = events captured since the previous nightly check (about “today”). Unsynced events of any age still count.
- Q: What local time should the app treat as the end of the night when it checks leftover work? → A: 21:00 in the device’s local timezone.
- Q: After the user saves a note for an event, should that event’s capture alert disappear on its own? → A: Auto-dismiss that event’s capture alert when a note is saved (in-app or inline reply).
- Q: Should the nightly leftover reminder say how many events are unsynced and how many are missing a note, or only that leftover work exists? → A: Include both counts (unsynced vs missing note) in the reminder text.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - One alert per newly captured transaction (Priority: P1)

When a bank or payment app posts a transaction and the capture app records it, the user receives **exactly one** capture-app alert about that new event — a prompt that a transaction was captured and they can add a note. During the day they receive **no** sync-status system notifications (waiting to sync, caught up, or action needed). In-app “waiting to sync” labels on rows and screens may still appear.

**Why this priority**: Duplicate alerts on every spend are the most frequent annoyance; fixing cadence is the first thing the user asked for.

**Independent Test**: Capture one new transaction (phone can be online or offline). Confirm the capture app posts a single system notification for that event, and that no waiting-to-sync, caught-up, or action-needed system notification is posted then or later during that daytime period.

**Acceptance Scenarios**:

1. **Given** capture is enabled and a new expense or income is recorded, **When** the capture app finishes storing that event, **Then** it posts exactly one system notification about that event.
2. **Given** that same capture, **When** the user inspects the notification shade immediately afterward, **Then** there is no second capture-app notification titled or worded as waiting-to-sync / queue-pending for that same event.
3. **Given** two distinct events captured in sequence (for example one income and one expense), **When** both are stored, **Then** the user sees at most one capture-app notification **per event**, not two per event.
4. **Given** notification permission is denied, **When** an event is captured, **Then** capture still succeeds and no crash occurs (alerts are simply omitted).
5. **Given** it is before 21:00 local, **When** events wait in the queue, finish syncing, or need attention, **Then** the capture app posts no waiting-to-sync, caught-up, or action-needed system notification.
6. **Given** a per-event capture notification is still showing, **When** the user saves a note for that event (editing screen or inline reply), **Then** that event’s capture notification is dismissed automatically.

---

### User Story 2 - Open the event’s editing screen from the capture alert (Priority: P1)

The user taps the single capture-app notification for a transaction. The app comes to the foreground on **that event’s detail / category-editing screen**, where they can add or edit a note without hunting through the Events list.

**Why this priority**: The remaining capture alert is only useful if it lands the user on the event they were just asked about.

**Independent Test**: With one capture notification visible, tap its body. Confirm the app opens the matching event’s editing screen (amount, category, and note) rather than a generic home or list with no selection.

**Acceptance Scenarios**:

1. **Given** a capture-app notification for a specific stored event, **When** the user taps the notification body, **Then** the app opens on that event’s detail / category-editing screen.
2. **Given** the app was not running in the foreground, **When** the user taps the notification, **Then** the app launches and still lands on that same event’s editing screen.
3. **Given** the app was already open on a different screen, **When** the user taps the notification, **Then** navigation switches to that event’s editing screen.
4. **Given** the event no longer exists (cleared or unknown id), **When** the user taps the notification, **Then** the app opens to the Events list with a brief explanation that the event is gone, and does not crash.

---

### User Story 3 - Note field on the Set category screen (Priority: P1)

The user has captured events (including both an income and an expense). They tap **Set category** on either row to add a note. The editing screen shows a **visible note field** they can type into immediately — not a screen that only has category/account controls with no place to write a memo.

**Why this priority**: Without a note field, the user cannot complete the job the capture alerts ask them to do, even after they open the right screen.

**Independent Test**: From the Events tab, tap Set category on an income event and on an expense event. On both screens, a note text field is visible without an extra “reveal editor” step, and saving a note persists when the screen is reopened.

**Acceptance Scenarios**:

1. **Given** an expense event on the Events tab, **When** the user taps Set category, **Then** the editing screen shows a note text field (empty if there is no note yet) in addition to category/account controls.
2. **Given** an income event on the Events tab, **When** the user taps Set category, **Then** the same note text field is visible and usable (behavior does not differ by income vs expense).
3. **Given** the note field is empty, **When** the user types a short note and saves, **Then** the note is stored, shown on the editing screen and on the Events row, and kept after leaving and returning.
4. **Given** the event already has a note, **When** the user opens Set category, **Then** the existing note is already in the field and can be edited and saved.
5. **Given** the user is adding a note, **When** they type past the existing length limit, **Then** input is capped at that limit (same cap as today’s notes) and they can still save.

---

### User Story 4 - One end-of-night reminder for leftover work (Priority: P2)

During the day the capture app stays quiet about sync status. At **21:00 local**, if anything still needs attention — **unsynced transactions of any age** and/or **events captured since the previous nightly check that still have no note** — the user gets **one** reminder whose text includes both counts. If nothing qualifies, they get nothing.

**Why this priority**: Replaces the noisy per-transaction sync alerts with a single daily nudge; valuable once per-event noise is gone.

**Independent Test**: Leave at least one unsynced event (any age) or one same-window event without a note until 21:00 local; confirm a single reminder appears and names both leftover counts. Clear both conditions before that time; confirm no reminder.

**Acceptance Scenarios**:

1. **Given** at least one event is still unsynced at 21:00 local (any capture age), **When** that time arrives, **Then** the user receives exactly one capture-app reminder that leftover work remains, including the unsynced count and the missing-note count.
2. **Given** every event is synced but at least one event captured since the previous nightly check still has no note at 21:00 local, **When** that time arrives, **Then** the user still receives that same single reminder (in-window missing notes alone are enough), including both counts.
3. **Given** both unsynced events and in-window events without notes exist, **When** 21:00 local arrives, **Then** the user still receives only one reminder covering both conditions, not one notification per leftover event, and the text includes both counts.
4. **Given** there are no unsynced events and no in-window captured events without notes, **When** 21:00 local arrives, **Then** no reminder is posted.
5. **Given** a reminder was already posted for that local calendar date, **When** the leftover conditions remain later the same night, **Then** a second nightly reminder is not posted that same date.
6. **Given** the user taps a nightly reminder that refers to exactly one leftover event, **When** the app opens, **Then** they land on that event’s editing screen.
7. **Given** the user taps a nightly reminder that refers to more than one leftover event, **When** the app opens, **Then** they land on the Events list so they can work through the leftovers.

---

### Edge Cases

- What happens if several transactions are captured in a short burst? Each event may have its own single capture notification; they must not be paired with a second sync-status notification per event. Burst capture must not flood the shade with sync banners.
- What happens if the phone is already in sync (server reachable, queue empty) at capture time, or later becomes caught up or needs action, all before 21:00 local? Still exactly one capture notification about the new event; no daytime “caught up”, “waiting to sync”, or “action needed” system notification.
- What happens if the user already added a note from the Events screen before a delayed capture notification is posted? Do not post (or immediately dismiss) that event’s capture alert; the editing screen still shows the saved note if they open the event another way.
- What happens at nightly time while the device is off or in Do Not Disturb? The reminder is delivered when the device can next show scheduled reminders (standard OS behavior); it must still be at most one for that local date once it fires.
- What happens if leftover events exist only from previous days? Unsynced events of any age still count. Events with a blank note count only if they were captured since the previous nightly check (about “today”); older blank-note events do not trigger the reminder by themselves.
- What happens if the user saves a note while that event’s capture alert is still in the shade? The alert for that event is dismissed automatically (same whether the note was saved on the editing screen or via inline reply). Other events’ capture alerts stay.
- What happens if the user syncs or adds notes after the reminder was posted? The reminder may remain until dismissed; tapping it must still open a valid screen (detail if one leftover remains, Events list if several, Events list if all leftovers are gone).
- What happens if notification permission is later revoked? Capture, Set category, and note saving continue to work; alerts simply stop.
- What happens if income and expense both exist and one of them previously had no visible note field? Both event types MUST show the same always-visible note field on the Set category screen.

## Requirements *(mandatory)*

### Functional Requirements

**Capture-time alerts**

- **FR-001**: When a new transaction event is captured, the capture app MUST post at most one system notification about that event.
- **FR-002**: That single notification MUST be about the new transaction (so the user can add or edit a note), not a separate sync-queue status notice.
- **FR-003**: Until the nightly leftover check, the capture app MUST NOT post system notifications whose purpose is sync status, including waiting to sync, caught up, and action needed. This is not limited to the moment of capture.
- **FR-004**: Existing in-app labels on event rows and the editing screen that mention waiting to sync MAY remain; FR-003 applies to **system notifications**, not on-screen status text.
- **FR-017**: When a note is saved for an event (editing screen or inline notification reply), the app MUST dismiss that event’s per-event capture notification if it is still showing. If a note already exists when the capture notification would be posted, the app MUST NOT post that capture notification.

**Notification tap → event editing screen**

- **FR-005**: Tapping the body of a per-event capture notification MUST open the capture app on that event’s detail / category-editing screen (the same screen reached from **Set category**).
- **FR-006**: The opened screen MUST let the user add or edit the note for that event without first searching the Events list.
- **FR-007**: If the target event cannot be found, the app MUST open the Events list and MUST NOT crash.

**Note field on Set category / event editing**

- **FR-008**: The event editing screen opened via **Set category** MUST show a note text field as soon as the screen appears (empty placeholder when there is no note; filled when there is one). The field MUST NOT be hidden behind a second control the user has to discover.
- **FR-009**: The note field MUST appear for both income and expense events.
- **FR-010**: Saving a note from that field MUST use the existing note save/sync behavior (stored on the phone, queued to Go Money, same length limit as today). Category, destination account, and delivery state MUST NOT be altered by saving a note.
- **FR-011**: Notes already stored on events MUST remain readable and editable in that field after this change.

**Nightly leftover reminder**

- **FR-012**: At **21:00 in the device’s local timezone**, once per local calendar date, the capture app MUST evaluate leftover work. The user is not asked to pick a time in this change.
- **FR-013**: Leftover work is true if **either** (a) at least one captured event is unsynced, of any capture age, **or** (b) at least one event captured since the previous nightly check (or since first install if none has run) has a blank note. Unsynced includes events not yet delivered to Go Money and events whose note or category assignment is still waiting to sync. Blank-note events captured before the previous nightly check MUST NOT count as leftover by themselves.
- **FR-014**: If leftover work is true, the app MUST post exactly one nightly reminder for that local calendar date. If leftover work is false, it MUST post none.
- **FR-015**: The nightly reminder text MUST include both leftover counts (number unsynced, number of in-window events missing a note) and MUST NOT include raw bank message text. A zero on one side is still shown when the other side is non-zero.
- **FR-016**: Tapping the nightly reminder MUST open the single leftover event’s editing screen when exactly one leftover exists, and the Events list when more than one leftover exists (or when leftovers have since been cleared).

### Key Entities

- **Captured event**: A transaction recorded by the capture app (income or expense). Relevant attributes: amount/direction, note, delivery/sync state, category assignment.
- **Per-event capture notification**: The one system notification posted when a new event is captured; identifies that event so a tap can open its editing screen. Auto-dismissed when a note is saved for that event.
- **Nightly leftover reminder**: At most one system notification per local calendar date, posted at 21:00 local only if unsynced events of any age remain and/or events captured since the previous nightly check still have no note. Text includes both leftover counts.
- **Event editing screen**: The screen opened from **Set category** (and from a per-event notification tap), where category, destination account, and note are edited.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: After a single new capture, 100% of test runs show exactly one capture-app system notification for that event, and 0 daytime waiting-to-sync, caught-up, or action-needed system notifications.
- **SC-007**: After a note is saved for an event that still had a capture alert, 100% of test runs show that event’s capture alert is gone from the shade.
- **SC-002**: In 100% of taps on a per-event capture notification for a still-existing event, the user lands on that event’s editing screen within 3 seconds of the app coming to the foreground.
- **SC-003**: After opening Set category for an income event and for an expense event, 100% of those screens show a usable note text field without any extra tap to reveal it, and a first note can be typed and saved in under 30 seconds.
- **SC-004**: 100% of notes that existed before this change remain visible in the note field on the editing screen (no data loss).
- **SC-005**: On nights with leftover work at 21:00 local, the user receives exactly 1 leftover reminder for that local date whose text includes both leftover counts; on nights with no leftover work, they receive 0.
- **SC-006**: A user who ignores alerts all day can still clear leftover work from the nightly reminder in one tap to the right screen (detail if one leftover, list if several).

## Assumptions

- Scope is the existing Android capture / tracking app only. Go Money server, web app, and parsers are unchanged except that they continue to receive notes the same way they already do.
- “Only 1 is enough about a new transaction” means keep a single **per-event** capture/note prompt and **silence all daytime sync-status system notifications** (waiting to sync, caught up, action needed). In-app “waiting to sync” labels on rows and the editing screen stay.
- “End of the night” is **21:00 in the device’s local timezone**, evaluated once per local calendar date. The user is not asked to pick a time in this change; a later settings control is out of scope.
- “Unsync transaction” means the event (or its pending note/category assignment) has not finished reaching Go Money (any age). “Notification without note” means a captured event whose note is blank **and** that was captured since the previous nightly check.
- Saving a note (in-app or inline reply) auto-dismisses that event’s capture alert. The nightly leftover reminder text includes both leftover counts and no raw bank text.
- Events that failed to parse into a transaction are not part of the missing-note leftover set; only stored captured events are.
- A nightly reminder is suppressed when leftover work is empty, including when the user has no captured events at all.
- The note field is an always-visible text box on the editing screen (pre-filled when a note exists). The previous “Add note” / “Edit note” two-step that hid the box until tapped is treated as the bug the user reported.
- The two events currently on the device (one income, one expense) are the acceptance pair for FR-008/FR-009; this feature does not create, delete, or merge those events.
- Notification permission, channels, and privacy rules already in the app continue to apply: no raw bank notification body in capture-app alerts.
- A physical Android phone connected to the Mac over USB is available for later verification of capture alerts, tap-through, and the note field; this spec does not require that verification to occur during specify.
- Parent pipeline will use converge as the validation gate (critic/tester skipped). The spec is still written complete enough to implement and to converge against.
