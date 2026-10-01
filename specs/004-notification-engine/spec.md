# Feature Specification: Notification Source & Template Engine

**Feature Branch**: `notification-engine`

**Created**: 2026-09-30

**Status**: Draft

**Input**: User description: "## notification engin
I need a to be able to add any sms/notification template myself from inside the app, add a seperated screen for defining transaction source.
I should be able to add or remove new sources
each source will get a package id for the allow list and set that it should get it from notification or sms
then it will get a template so it knows how to extract the numbers from message, the template should have 2 variable: 1. income or expense 2. amount of the transaction
Also I should be able to set that specific source is. for what account on the server from a drop down list that gets updated from server

## other changes
- I should be able to set Destination Account of a transaction and category of it from inside the app. it should be a drop down list that get update from server"

## Clarifications

### Session 2026-09-30

- Q: How should a user express a source's template so the app knows how to pull the direction and amount out of a message? → A: Fill-in-the-blank message pattern — the user pastes a real message and marks the two values with placeholders (`{direction}`, `{amount}`).
- Q: How does the system decide whether a matched message is an income or an expense? → A: Per-source keyword lists — the user enters the words that mean income and the words that mean expense for that source, and the matched word decides the direction.
- Q: When a user tries to add a source whose identifier and channel are already used by another source, what should the app do? → A: Block the save with a duplicate error and require the user to edit or remove the existing source first.
- Q: Should the user be able to test a template against a sample message before saving the source? → A: Optional preview — a "test" button is offered but saving without testing is allowed.
- Q: How should messages that a defined source fails to parse be surfaced to the user, and how long should they be kept? → A: A review list on the sources screen, kept locally with a cap of the last 200 messages or 30 days (whichever comes first), each dismissible and shown with the failure reason.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Define a transaction source and capture its messages with a template (Priority: P1)

A user wants to capture transactions from a bank or payment app that the capture app does not already understand. Instead of waiting for someone to write code, the user opens a dedicated "sources" screen inside the app, adds a new source, gives it the app's package identifier (for notifications) or sender identifier (for SMS), states whether messages arrive as notifications or SMS, and builds a template by pasting a real message and marking the direction and amount with placeholders. They also enter the words that mean income and the words that mean expense for that source. From then on, matching messages are turned into transactions automatically.

**Why this priority**: This is the core of the feature — it lets the user extend capture to any sender themselves, which is the whole reason the notification engine exists. Without it, nothing else in the feature delivers value.

**Independent Test**: Add one source with a package identifier, a notification channel, and a template; trigger a message that matches the template and verify a transaction with the correct direction and amount appears; trigger a message from an undefined identifier and verify no transaction is created.

**Acceptance Scenarios**:

1. **Given** the user is on the source-definition screen, **When** they add a source with a name, an originating identifier, a channel (notification or SMS), a fill-in-the-blank template built from a pasted sample message with the direction and amount marked, and income/expense keyword lists, **Then** the source is saved and shown in the list of sources.
2. **Given** an enabled source exists for an identifier and channel, **When** a message from that identifier arrives on that channel, **Then** the template extracts the direction (income or expense) and the amount, and a transaction is created with those values.
3. **Given** a message arrives from an identifier that is not defined as any enabled source, **When** it is processed, **Then** no transaction is created from it.
4. **Given** a message from a defined source does not match that source's template (for example the amount cannot be found), **When** it is processed, **Then** the message is retained and flagged as a parse error and no transaction is created.
5. **Given** a message from a defined source contains an amount written with locale-specific digits or separators, **When** it is processed, **Then** the amount is read as a valid numeric value and used on the transaction.
6. **Given** the user is defining a source, **When** they press "test" with a sample message, **Then** the app shows the extracted direction and amount or a clear reason the message could not be parsed, and the user can still save the source without testing.
7. **Given** an identifier and channel are already used by another source, **When** the user tries to save a new source with the same identifier and channel, **Then** the save is blocked with a duplicate error and the user is told to edit or remove the existing source.

---

### User Story 2 - Bind a source to an account on the server (Priority: P2)

For each source, the user chooses which account on the server the captured transactions belong to, using a drop-down list whose values come from the server. Transactions captured from that source are recorded against that account.

**Why this priority**: Transactions that land in the wrong account force manual correction, which undermines the value of automatic capture. It builds directly on source definition but is secondary to capture itself.

**Independent Test**: Bind a source to a server account, capture a matching message, and verify the resulting transaction is recorded against that account; change the account list on the server, refresh, and verify the selector reflects the change.

**Acceptance Scenarios**:

1. **Given** the user is adding or editing a source, **When** they open the account selector, **Then** it lists the accounts provided by the server.
2. **Given** the accounts on the server change, **When** the app refreshes the account list, **Then** the selector reflects the server's current accounts.
3. **Given** a source is bound to a server account, **When** a transaction is captured from that source, **Then** the transaction is recorded against that account.
4. **Given** the server cannot be reached, **When** the user opens the account selector, **Then** the last known account list is shown or the user is clearly told the list is unavailable, and an existing binding is not silently lost.

---

### User Story 3 - Set the destination account and category of a captured transaction (Priority: P2)

For a captured transaction, the user can set its destination account and its category from drop-down lists whose values come from the server. The choices are saved immediately and reach the server even if the transaction was already sent or the connection is currently unavailable.

**Why this priority**: This is the user's explicitly requested "other change". It makes captured records accurate without editing them elsewhere, and it is independently useful even before all sources are configured.

**Independent Test**: Open a captured transaction, choose a destination account and a category from server-provided lists, verify the choices persist across a restart, and verify they appear on the same server transaction without creating a second transaction.

**Acceptance Scenarios**:

1. **Given** a captured transaction, **When** the user opens it, **Then** they can choose a destination account and a category from drop-down lists populated from the server.
2. **Given** the user selects a destination account and category, **When** they save, **Then** the choices are stored locally immediately and remain after the app or device restarts.
3. **Given** the transaction was already delivered to the server, **When** the destination account or category is set or changed, **Then** the existing server transaction is updated and no second transaction is created.
4. **Given** the app is offline, **When** the user sets the destination account or category, **Then** the choices are kept locally and applied automatically once the server is reachable again.
5. **Given** the categories or accounts on the server change, **When** the user opens the drop-down lists, **Then** they reflect the server's current values.

---

### User Story 4 - Remove or disable a source (Priority: P3)

The user can remove a source they no longer want, or temporarily disable one without deleting it, for example while the bank's message format is changing.

**Why this priority**: Housekeeping for the sources created in User Story 1; valuable for keeping the list correct but not required for the first working capture.

**Independent Test**: Disable a source and verify its messages are no longer captured while other sources keep working; re-enable it and verify capture resumes; remove it and verify it disappears and its messages are ignored.

**Acceptance Scenarios**:

1. **Given** a source, **When** the user disables it, **Then** messages from that source are no longer captured and other sources are unaffected.
2. **Given** a disabled source, **When** the user re-enables it, **Then** matching messages are captured again.
3. **Given** a source, **When** the user removes it, **Then** it no longer appears in the list and its messages are no longer captured, while transactions already captured from it are retained.

---

### Edge Cases

- What happens when two sources are defined for the same identifier and channel? → The duplicate is blocked: the second source cannot be saved, the user is shown a duplicate error, and the existing source must be edited or removed first, so a message is never parsed twice.
- What happens when a message from a defined source matches the template but the direction is not recognizable as income or expense? → No transaction is created; the message is retained and flagged as a parse error.
- What happens when the amount is missing, zero, or otherwise not a valid positive number? → No transaction is created; the message is retained and flagged as a parse error.
- What happens when a template does not contain both required values (direction and amount)? → The source cannot be saved and the user is told which value is missing.
- What happens when a message arrives on the notification channel but the source is configured for SMS (or the reverse)? → The message is ignored for that source.
- What happens when the server has no accounts, or the account list cannot be loaded? → The user can still define and capture sources; the account selector shows that no accounts are available or that the list is unavailable, and the binding can be set later.
- What happens when an account that a source was bound to is deleted or renamed on the server? → The source is shown as unbound (or with a stale binding clearly indicated) and the user is prompted to choose again; capture is not lost.
- What happens when the server rejects a chosen destination account or category? → The change is marked failed with a visible reason and can be retried after correction; the transaction itself is not duplicated.
- What happens when notification-listener or SMS permission is not granted? → Sources can still be defined, but capture from the affected channel does not occur until permission is granted, consistent with the existing permission guidance.
- What happens when the same real-world transaction is seen more than once (repeated message, or both notification and SMS)? → Existing duplicate prevention still applies; only one transaction is recorded.
- What happens when the parse-error review list reaches its cap? → The oldest entries are removed first, so the list holds at most the last 200 messages or 30 days of messages, whichever comes first; dismissing an entry removes it immediately.

## Requirements *(mandatory)*

### Functional Requirements

**Source management**

- **FR-001**: Users MUST be able to add a new transaction source from inside the app on a dedicated sources screen.
- **FR-002**: Each source MUST define a display name, an originating identifier (an app package identifier for notifications, or a sender identifier for SMS), a channel (notification or SMS), a message template, and the words that indicate income and the words that indicate expense for that source.
- **FR-003**: Users MUST be able to remove a source and to enable or disable a source without removing it.
- **FR-004**: System MUST capture messages only from identifiers configured as enabled sources on the matching channel, and MUST ignore all other messages.
- **FR-005**: System MUST reject a new or edited source whose identifier and channel are already used by another source, showing a duplicate error, so that a single message is never parsed by more than one source.
- **FR-006**: Source definitions MUST persist across app and device restarts.

**Templates and extraction**

- **FR-007**: Each source MUST have a fill-in-the-blank template built from a sample message, in which the user marks exactly two values: the transaction direction (income or expense) and the transaction amount.
- **FR-008**: Users MUST be able to define and edit a template inside the app without any change to the app's code or a new app release.
- **FR-009**: System MUST create a transaction only when a matching message yields both a recognizable direction and a valid positive amount.
- **FR-010**: When a message from a defined source does not match its template, or yields an unrecognizable direction or an invalid amount, the system MUST retain the message and flag it as a parse error, and MUST NOT create a transaction from it.
- **FR-011**: System MUST normalize amounts written with locale-specific digits or separators (for example Persian numerals) into canonical numeric values before producing the transaction amount.
- **FR-012**: A source MUST be savable only when its template marks both required values and both direction keyword lists are provided; otherwise the user MUST be told what is missing.

**Account binding and server-provided lists**

- **FR-013**: Users MUST be able to bind a source to an account selected from a list of accounts obtained from the server.
- **FR-014**: System MUST refresh the account list from the server and present the server's current accounts in the selector.
- **FR-015**: When a transaction is captured from a source that is bound to an account, the system MUST record the transaction against that account.
- **FR-016**: When the account list cannot be retrieved, the system MUST fall back to the last known list or clearly indicate that the list is unavailable, and MUST NOT silently discard an existing binding.

**Destination account and category on a captured transaction**

- **FR-017**: Users MUST be able to set the destination account of a captured transaction from a list of accounts obtained from the server.
- **FR-018**: Users MUST be able to set the category of a captured transaction from a list of categories obtained from the server.
- **FR-019**: Destination account and category selections MUST be saved locally before any delivery attempt and MUST survive app restarts and offline periods.
- **FR-020**: Setting or changing the destination account or category of an already-delivered transaction MUST update that same transaction on the server and MUST NOT create a second transaction.
- **FR-021**: The account and category lists used for these selections MUST be refreshed from the server and reflect the server's current values.

**Consistency, privacy, and reuse**

- **FR-022**: Transactions produced by user-defined sources MUST flow through the existing capture, normalization, queue, and delivery pipeline and MUST remain subject to existing duplicate prevention.
- **FR-023**: Raw message text MUST NOT be sent to the server and MUST NOT be written to production logs.
- **FR-024**: Messages MUST be captured only from the channel configured on the matching source (notification or SMS).
- **FR-025**: Creating or editing accounts and categories themselves remains outside this feature; the app only selects from values provided by the server.

**Direction keywords, template testing, and parse-error review**

- **FR-026**: System MUST resolve the transaction direction by matching the message against the source's income and expense keyword lists; when no income or expense keyword is found, the direction is unrecognizable and the message is handled as a parse error.
- **FR-027**: Users MUST be able to test a template against a pasted sample message before saving a source, and the app MUST show the extracted direction and amount or the reason the message could not be parsed; testing is optional and saving without testing is allowed.
- **FR-028**: System MUST retain parse-error messages in a review list on the sources screen, capped at the last 200 messages or 30 days (whichever comes first), showing each entry's failure reason and allowing the user to dismiss it.

### Key Entities *(include if feature involves data)*

- **TransactionSource**: A user-defined capture source — display name, originating identifier (package identifier or SMS sender), channel (notification or SMS), enabled flag, associated template, the income and expense keyword lists used to resolve direction, and an optional bound account.
- **MessageTemplate**: The fill-in-the-blank pattern attached to a source, derived from a sample message, in which exactly two values are marked — the transaction direction (income or expense) and the transaction amount. Direction is resolved against the source's income and expense keyword lists.
- **AccountBinding**: The association between a source and a server account, expressed as a reference to a server-provided account.
- **ServerAccount**: An account offered by the server for selection (an identifier plus a human-readable label) — used both for source binding and for a transaction's destination account.
- **ServerCategory**: A category offered by the server for selection, used to categorize a captured transaction.
- **Captured transaction assignment**: The destination account and category a user sets on a captured transaction, plus the state of whether those changes have reached the server.
- **ParseErrorMessage**: A message from a defined source that could not be parsed, retained locally with its failure reason and shown in the sources-screen review list; capped at the last 200 messages or 30 days and dismissible by the user.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A user can add a new working source (identifier, channel, and template) and capture a matching message within 5 minutes, with no code change or new app release.
- **SC-002**: 100% of tested messages from enabled sources that contain a recognizable direction and a valid amount produce exactly one transaction with the correct direction and amount; messages that do not are retained as parse errors and never silently dropped.
- **SC-003**: Zero transactions are created from identifiers that are not defined as an enabled source in the test scenario set.
- **SC-004**: 100% of tested sources bound to a server account record their captured transactions against that account.
- **SC-005**: 100% of destination-account and category selections made while offline are eventually reflected on the same server transaction, with zero duplicate transactions created.
- **SC-006**: In at least 95% of refreshes with a reachable server, the account and category selectors reflect the server's current values.
- **SC-007**: No raw message text leaves the device or appears in production logs in any tested scenario.
- **SC-008**: A user can remove or disable a source and confirm that its messages are no longer captured, while other sources continue to work, within 1 minute.
- **SC-009**: 100% of attempts to save a source whose identifier and channel duplicate an existing source are blocked with a visible duplicate error, and no message in the test set is ever parsed by more than one source.
- **SC-010**: 100% of parse-error messages appear in the sources-screen review list with a failure reason; the list never exceeds the last 200 messages or 30 days, and dismissed entries are removed immediately.
- **SC-011**: A user can paste a sample message into the optional test and see the extracted direction and amount (or the parse failure reason) before saving a source, in 100% of tested cases.

## Assumptions

- **The app hosting the new screens is the phone (Android) capture app** described by the existing Android Transaction Capture feature; the Go Money server remains the source of truth for accounts and categories. This reading is chosen because the feature's source identifier is an Android package identifier for an allow-list, because the existing capture pipeline parses messages on the device (raw text must not leave the phone), and because the main Go Money app already allows setting a transaction's destination account and category. The requested "other change" therefore applies to captured transactions in the phone app.
- Source definitions and templates are stored locally on the phone app in this version; storing or sharing them on the server is out of scope unless later requested.
- The server exposes accounts and categories that the app can retrieve over its existing authenticated connection; the exact retrieval mechanism is a planning concern, not specified here.
- A template is understood as a fill-in-the-blank pattern built by pasting a real sample message and marking the direction and the amount with two placeholders. The unmarked wording around the placeholders identifies a message from that source, and matching tolerates the parts that differ between messages (such as dates and reference numbers) so that a template built from one sample also matches later messages of the same shape. Direction is resolved by matching the message against the source's user-entered income and expense keyword lists. One template per source, and one direction/amount pair per message, is sufficient for this version.
- Parse-error messages are kept only so the user can review them on the sources screen; they are never sent to the server, are capped at the last 200 messages or 30 days (whichever comes first), and can be dismissed by the user.
- Each source has at most one bound account. The account binding acts as the destination account for transactions from that source, and a user may still set or change the destination account on an individual transaction.
- Destination account and category are optional per transaction; when they are not set, the existing defaults and server behavior apply.
- The existing capture, queue, delivery, retry, and duplicate-prevention behavior is reused unchanged; this feature adds configuration and extraction, not a new delivery path.
- The existing permission handling for notification-listener and SMS access is reused; this feature does not change how permissions are requested.
- The existing authentication to the server (a configured token) is reused for retrieving accounts and categories and for updating a transaction's destination account and category.
- Amounts are in Iranian Rial (IRR), consistent with the existing capture feature; other currencies are out of scope.
- Accounts and categories are managed in the main Go Money app; this feature only selects from them.
