# Feature Specification: Android Capture App UI Refresh

**Feature Branch**: `010-android-ui-refresh`

**Created**: 2026-11-17

**Status**: Draft

**Input**: User description: "The android app Ui/Ux is awoful. check it's screens out and imporove it. You can search internet and see what other similar products did"

## Clarifications

### Session 2026-11-17

- Q: When someone opens the Android app, should Home feel like a capture-health check or like a spending overview? → A: Health and recent activity only (reachable/blocked, today’s count, waiting/failed, short recent list). Not a spending overview.
- Q: What language should on-screen labels and messages use in this refresh? → A: Keep English copy; make layouts safe if text gets longer or RTL is added later.
- Q: Which similar-product look should this refresh copy most closely? → A: Serve a capture companion that tracks transactions and sends them to Go Money. No stats, charts, totals dashboards, or spending-overview look. Prefer a clear transaction list and send/health status over consumer-finance dashboard styling.
- Q: For a new user (or someone with notification access off), how should setup work? → A: No separate wizard — Home (and Sources/Settings) show one clear next step and open system settings from there.
- Q: How far should this UI refresh go beyond making the current screens prettier? → A: Restyle and restructure (bottom tabs, day-grouped list, hide bulk actions until selection, quieter Settings groups, focused event detail) and specially improve each transaction/event detail screen as a first-class redesign, not a light restyle.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Open the app and know it is working (Priority: P1)

A person who uses this phone as a silent collector for bank and SMS transactions opens the app after a day of spending. In a few seconds they can tell whether capture is healthy, how many events need attention, and what recently landed — without reading developer language or hunting through tabs.

**Why this priority**: Trust is the product. If the home screen looks like an operations console, people will not believe capture is working and will fall back to manual entry.

**Independent Test**: Open the app with a mix of sent, pending, and failed events and confirm a first-time visitor can state health, today’s volume, and the next action without leaving home.

**Acceptance Scenarios**:

1. **Given** the app has captured events today, **When** the user opens the home destination, **Then** they see today’s capture count, items waiting to send, items that failed, and a plain-language connection status (reachable / not reachable / not set up yet).
2. **Given** there are recent events, **When** the user views home, **Then** they see a short recent list with amount, source, time, and status — tapping a row opens that event’s detail.
3. **Given** capture is blocked (permission missing or destination not configured), **When** the user opens home, **Then** they see a single clear next step with a control that starts that setup, not only a technical sentence.
4. **Given** everything is healthy, **When** the user opens home, **Then** the screen feels calm (no error styling) and does not shout sync jargon.
5. **Given** captured events exist, **When** the user opens home, **Then** they do not see period spend totals, charts, budget widgets, or a spending-overview dashboard — only health, queue counts, and a short recent list.

---

### User Story 2 - Review and complete captured events quickly (Priority: P1)

After a purchase, the user opens the events list to confirm the amount, add a note, pick a category or destination, retry a failed send, or scan leftover notifications. The list must scan as a capture inbox: grouped by day, large readable amounts, income vs expense distinguishable at a glance, send status obvious, and bulk tools hidden until they are needed.

**Why this priority**: This is the daily surface. Today the list is a wall of cards, always-on checkboxes, and a wrapping button bar, which makes the core job (review one event and send it) slower than it should be.

**Independent Test**: With a populated list, complete “find yesterday’s grocery charge, open it, save a note” in one continuous flow; separately enter selection mode and delete two events without exposing destructive actions to a casual scroller.

**Acceptance Scenarios**:

1. **Given** multiple events across days, **When** the user opens the events destination, **Then** rows are grouped by calendar day, each showing a human-readable amount with currency, source name, short description or note preview, time, and a status chip (sent, waiting, failed, parse problem).
2. **Given** the list is shown, **When** the user is not selecting, **Then** they do not see select-all / sync-selected / delete-selected / clear-all as a permanent toolbar; those actions appear only after they enter selection mode (or an overflow of rare maintenance actions).
3. **Given** an event row, **When** the user taps the row (not a tiny secondary button), **Then** they open event detail. A failed event still offers retry without forcing a trip through detail.
4. **Given** no events yet, **When** the user opens the list, **Then** they see an empty state with a short explanation and one primary action (scan notifications or go to sources), while top-level navigation remains visible.
5. **Given** a scan is running or just finished, **When** the user is on the events destination, **Then** progress or result appears as a dismissible banner above the list, not as a competing row of buttons.
6. **Given** the user opened the app from a reminder about a leftover event, **When** that event still exists, **Then** they land on its detail; if it does not, they land on the list with a dismissible explanation.

---

### User Story 3 - Finish an event on a focused detail screen (Priority: P1)

The user opens one captured event to confirm amount and source, choose destination account and category, set currency when it is still local, write a short “what was this for” note, and retry a failed send. This screen is a first-class destination in the refresh: it reads as one transaction ready to send to Go Money, not as a lightly restyled stack of unlabeled forms and internal parser fields.

**Why this priority**: Detail is where people complete capture and send. Stakeholders called out this screen specifically. Competing save actions, raw parser confidence, and unformatted amounts make it feel unfinished.

**Independent Test**: Open a queued expense, change category and note, save, go back, and confirm the list reflects both; open an already-sent event and confirm currency is not editable; confirm a first-time tester can describe amount, send status, and the next edit without seeing parser internals.

**Acceptance Scenarios**:

1. **Given** a captured event, **When** the user opens detail, **Then** the header shows formatted amount, income/expense, source, time, and status before any editing controls.
2. **Given** destination and category lists are available, **When** the user changes them and confirms, **Then** both persist and a pending-sync hint appears until they reach Go Money — without requiring a separate “assignment” mental model if a single confirm control can cover it.
3. **Given** a note field, **When** the user types and confirms (or cancels), **Then** the note saves or reverts as today; the field stays visible for income and expense; character limit remains.
4. **Given** the event has not started delivery, **When** currency is editable, **Then** the user picks from known currencies; once sending or sent, currency is read-only.
5. **Given** a failed delivery, **When** the user is on detail, **Then** retry is an obvious primary action.
6. **Given** catalog lists are unavailable, **When** the user opens pickers, **Then** they see a last-known list plus a refresh control, and a missing saved category still warns them to pick again.
7. **Given** debug mode is off, **When** the user views detail, **Then** they do not see parser name, confidence, or other internals.
8. **Given** detail is open, **When** the user scans from top to bottom, **Then** they see one transaction story in order: identity (formatted amount, income/expense, source, time, status), then send assignment (destination account, category, currency when editable), then note, then send/retry — not an unlabeled form stack.
9. **Given** destination/category and note can both change, **When** the user finishes, **Then** assignment has one obvious confirm path and the note keeps explicit save/cancel; they are not asked to guess among competing unlabeled save actions.

---

### User Story 4 - Set up sources and permissions without feeling lost (Priority: P2)

A new user (or someone adding a bank app) goes to Sources to add a sender, bind an account, test a sample message, and review unparsed messages. Missing notification access is a first-class in-place next step on Home, Events scan, and Settings — not a footnote and not a separate first-launch wizard.

**Why this priority**: Capture quality depends on sources. The current list is usable but visually generic; parse errors and stale account bindings are easy to miss.

**Independent Test**: From an empty source list, add a source through a titled editor, bind an account, run a test, save, and return to a list that shows enabled state and bound account; dismiss one parse error.

**Acceptance Scenarios**:

1. **Given** no sources, **When** the user opens Sources, **Then** they see why sources matter and a primary “Add source” action.
2. **Given** sources exist, **When** the user views the list, **Then** each row shows name, channel, identifier, bound account (or none), enabled state, and a clear stale-binding warning when the bound account disappeared from the server list.
3. **Given** the add/edit editor, **When** the user fills name, identifier, channel, template, keywords, optional account, and sample test, **Then** validation errors sit next to the field they belong to; test success shows a formatted amount and direction.
4. **Given** unparsed messages exist, **When** the user is on Sources, **Then** they appear in a distinct review section (not mixed into the source cards) with sanitized reasons and dismiss.
5. **Given** notification access is missing, **When** the user is on home, events scan, or settings, **Then** they get the same plain explanation and a control that opens the system notification-access screen.

---

### User Story 5 - Configure the companion without a settings dump (Priority: P2)

The user opens Settings to manage where events are sent, capture toggles, default category/account, and debug. Related items are grouped, destructive or rare actions are visually quieter, and permission status has a direct action.

**Why this priority**: Settings is currently one long undifferentiated scroll. Comparable finance apps isolate “connections,” “defaults,” and “privacy/debug.”

**Independent Test**: Change default category, add an endpoint, toggle notification capture, and confirm each group is visually distinct and still behaves as today.

**Acceptance Scenarios**:

1. **Given** Settings is open, **When** the user scrolls, **Then** they see labeled groups: connection destinations, capture switches, defaults, permissions, diagnostics.
2. **Given** endpoints exist, **When** the user reorders, tests, edits, or deletes, **Then** those actions remain available but sit inside each destination card rather than competing with capture toggles.
3. **Given** defaults, **When** the user picks or clears category or destination account, **Then** the change still applies only to newly captured events; stale IDs still warn; pickers still disable when the server list is unavailable.
4. **Given** a permission is missing, **When** the user views the permission group, **Then** they can open the matching system screen from the app.

---

### User Story 6 - The whole app feels like one product (Priority: P2)

Across home, events, sources, settings, and detail, spacing, type, color, and controls feel consistent. The app follows the phone’s light/dark preference. Navigation uses a persistent bottom bar with icons and short labels. Destructive actions require confirmation. Touch targets are comfortable. Income and expense, success and failure, use consistent color meaning.

**Why this priority**: Users described the UI as awful because it looks like stacked defaults, not because a feature is missing. The bar is a clear capture companion: bottom bar, readable amounts, send/health status, and a rebuilt transaction detail — not a consumer-finance dashboard.

**Independent Test**: Walk every destination in light and dark appearance and confirm shared chrome (app bar or screen title, bottom destinations, cards, status chips) and that detail/editor screens keep a back affordance without losing the destination model.

**Acceptance Scenarios**:

1. **Given** any top-level destination, **When** the user looks at the bottom of the screen, **Then** they see four destinations — Home, Events, Sources, Settings — with icon plus label, and the current one is obvious.
2. **Given** a nested screen (event detail, source editor), **When** it is open, **Then** a back control returns to the parent list; leaving the Events or Sources destination still closes nested screens as today.
3. **Given** the system appearance is dark or light, **When** the user opens any screen, **Then** surfaces, text, and controls remain readable (contrast sufficient for body text and amounts).
4. **Given** income vs expense on lists and detail, **When** the user scans, **Then** type is distinguishable without relying on color alone (sign or label plus color).
5. **Given** a destructive action (clear all, delete selected, remove source), **When** the user invokes it, **Then** a confirmation dialog still appears.

---

### Edge Cases

- Empty lists (no events, no sources, no endpoints) keep navigation chrome and show a useful empty state, not a blank page.
- Very large amounts and long source names wrap or truncate with a readable fallback; they never overflow the row.
- Very long notes truncate on the list and remain fully editable on detail.
- Many events: the list remains scrollable; grouping headers stay sticky enough to know which day the user is in.
- Selection mode: if selected rows disappear (sync/delete elsewhere), the selection shrinks to remaining IDs; leaving the Events destination clears selection.
- Catalog unavailable or empty: pickers explain the state; stored defaults and last-known lists still display.
- Notification-open target missing: list + dismissible message, never a blank detail.
- Scan blocked or unavailable: same recovery path as today (open system access, try again).
- Debug mode on: internals may appear on a dedicated debug view, not on the default detail header.
- Amounts with unknown currency: show a formatted number without inventing a currency name.
- RTL-capable layouts: screens do not clip essential actions on a narrow phone (~360 dp wide).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The app MUST present four top-level destinations — Home, Events, Sources, and Settings — in a persistent bottom navigation bar with icon and text on every top-level screen.
- **FR-002**: Nested screens (event detail, source editor) MUST provide a back action to their parent list and MUST close when the user leaves that top-level destination.
- **FR-003**: Home MUST show capture health in everyday language: destination reachable or not, whether capture is blocked, today’s capture count, waiting count, failed count, and a short recent-event preview.
- **FR-004**: Home MUST offer one primary recovery action when blocked (configure destination, grant notification access, or open Events) instead of only status text.
- **FR-005**: The events list MUST group items by day and MUST display formatted amounts (grouped digits plus the event’s currency when known), source, time, status, and note/description preview.
- **FR-006**: Event rows MUST be tappable to open detail. Retry for failed delivery MUST remain available from the list without requiring detail.
- **FR-007**: Bulk maintenance (select all, sync selected, delete selected, clear processed, clear all) MUST NOT occupy a permanent wrapping button bar. Selection-related actions MUST appear only in selection mode; rare clears MAY live in an overflow or similarly secondary place.
- **FR-008**: Scan notifications MUST remain available from Events even when the list is empty, with running/result/blocked/unavailable feedback as a banner that does not replace the list chrome.
- **FR-009**: Empty Events MUST explain that nothing is captured yet and point to scan or adding a source.
- **FR-010**: Event detail MUST lead with amount, type, source, time, and status; parser internals MUST be hidden unless debug mode is on.
- **FR-011**: Event detail MUST keep destination account, category, currency gating, note field (always visible, same for income and expense), save/cancel for the note, pending-sync hints, catalog-unavailable refresh, and retry-on-failure. Confirming destination/category MUST remain possible without surprising the user with unsaved picker state.
- **FR-012**: Sources list MUST show enable/disable, edit, remove (with confirm), bound account, stale-binding warning, and a distinct unparsed-message review section with dismiss.
- **FR-013**: Source editor MUST keep name, identifier, channel, template, keywords, account binding rules (no bind without currency), test-with-sample, and field-level validation messages.
- **FR-014**: Settings MUST group connection destinations, capture toggles, default category/account, permissions, and debug. Existing endpoint order, test, secret handling, and default-stamping rules MUST be preserved.
- **FR-015**: Permission status MUST include a control that opens the relevant system settings when access is missing.
- **FR-016**: The visual system MUST follow the device light/dark preference, use consistent type scale and spacing, and mark status with chips (sent / waiting / failed / parse problem) whose meaning is understandable without reading raw internal state names.
- **FR-017**: Income and expense MUST be distinguishable by both wording (or sign) and color; status success vs failure MUST use consistent color meaning across Home, Events, and detail.
- **FR-018**: Destructive actions already confirmed today MUST remain confirmed. Accidental selection of many events MUST be harder than today (selection is opt-in).
- **FR-019**: Notification-open routing (detail vs list + missing-event message) MUST keep the same outcomes; only presentation of the missing-event message may be restyled.
- **FR-020**: This feature MUST NOT add budgeting, charts, shared wallets, or a full ledger that competes with Go Money. It MUST NOT change capture, parse, dedup, or delivery rules except where display formatting requires reading existing fields.
- **FR-021**: Sensitive raw notification/SMS text MUST stay hidden on default screens (sanitized copy only), matching existing privacy behavior.
- **FR-022**: All primary tap targets MUST be large enough for one-handed use on a typical phone; list actions MUST remain fully visible without horizontal scrolling on a 360 dp-wide screen.

### Key Entities

- **Destination**: A top-level place in the app (Home, Events, Sources, Settings).
- **Captured event**: A normalized transaction or parse-failure row the user reviews: amount, currency, type, source, time, status, optional note, optional category/account assignment.
- **Source**: A sender the app listens to (notification package or SMS sender) with template, keywords, optional bound account.
- **Connection destination**: An ordered remote endpoint used to deliver events to Go Money.
- **Status chip**: User-facing delivery or parse state (sent, waiting, failed, parse problem), mapped from existing internal states without exposing raw names by default.
- **Health state**: Home summary of connectivity, permissions, and queue (waiting/failed/today).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A new user can, within 15 seconds of opening the app, correctly say whether capture is healthy and what to do next (grant access, add a destination, or do nothing).
- **SC-002**: From a list of at least 20 events spanning two days, a user can locate a named event and open its detail in under 10 seconds (day grouping + readable amounts).
- **SC-003**: Completing “add a note and a category to one event and return to the list” takes at most 5 taps after the list is visible, and 90% of first-time testers succeed on the first try.
- **SC-004**: Casual review does not expose bulk delete: 100% of delete-selected / clear-all invocations in tests occur only after an explicit selection-mode or overflow step plus confirmation.
- **SC-005**: On a 360 dp-wide screen, no primary control on Home, Events (including selection mode), Sources, Settings, detail, or source editor is clipped or requires horizontal scrolling.
- **SC-006**: In both light and dark appearance, body text and amount figures meet a readable contrast standard (people with typical vision can read amounts at arm’s length).
- **SC-007**: Empty Events and empty Sources each show a headline, one-sentence explanation, and a single primary action; testers do not ask “is the app broken?”
- **SC-008**: Existing capture/delivery behaviors remain intact: queued events still send, notes still sync, defaults still stamp only new captures, scan still respects notification access. UI refresh introduces no new missed or duplicated events in a regression pass of the current event flows.

## Assumptions

- Git work is already on branch `android-ui-ux`; this spec lives in `specs/010-android-ui-refresh` and does not rename the branch.
- Scope is the Android companion app in this repository only. Go Money on the computer, the bridge protocol, parsers, and notification capture rules stay as they are unless a label or layout must change.
- The app remains a **capture companion**, not a Spendee/YNAB clone. Comparable products (Spendee, Wallet by BudgetBakers, Money Manager, Monefy) inform **visual and interaction patterns** — bottom navigation with icons, large formatted amounts, day-grouped lists, color-coded type/status, empty states with one action, guided notification-access setup — not new product surfaces (budgets, pie charts, shared wallets, widgets). Widgets and home-screen tiles are out of scope for this pass.
- Four destinations stay in the bottom bar, including Settings, because this companion is setup-heavy (endpoints, permissions, sources). Material guidance that Settings is often not a bottom destination is overridden for that reason.
- Home is a **health + recent activity** screen, not a spending-overview dashboard. Counts already on the current dashboard remain, restyled and explained in plain language (“Waiting to send” instead of raw pending; “Couldn’t send” instead of raw failed). “Bridge” and endpoint URLs stay in Settings, not as the Home headline.
- Amounts today appear as raw minor units. This refresh **formats** them for humans (grouping separators; currency code already on the event). It does not change stored minor-unit values or invent a currency when the field is blank.
- Default language stays English for this pass (current UI copy is English). Layouts should not break if labels grow or if a later pass adds Persian; no translation project is in scope.
- Light and dark follow the system setting; no separate in-app theme picker in this pass.
- Selection mode is the reasonable default for bulk actions (opt-in checkboxes), matching how mail and files apps avoid accidental mass delete. Scan stays a primary Events action because it is part of the capture job, not maintenance.
- Event detail may use one confirm control for destination/category if that reduces “unsaved dropdown” confusion, as long as pending-sync semantics stay identical. Note save/cancel stay explicit because notes are free text.
- Parser name and confidence remain available only when debug mode is on (existing debug destination or equivalent), so everyday detail matches consumer finance apps.
- No extra stakeholder clarification is required to start planning: where the request was vague (“make it less awful”), the above defaults apply.
