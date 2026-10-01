# Feature Specification: Account Currency & Source-Only Capture

**Feature Branch**: `notification-engine`

**Created**: 2026-10-01

**Status**: Draft

**Input**: User description: "1. Currency of the transaction should not be hardcoded to IRR, instead it should be set from it's source account. so when choose what account on server associate with the android app source, it should detect currency automatically 2. remove the bank allowlist from settings, since i now do the same thing using sources"

## Clarifications

### Session 2026-10-01

- Q: When a previously unbound source is later bound to a server account, what happens to still-held captures from that source that never received a currency? → A: Stamp the newly bound account’s currency onto those still-held captures, then deliver them. Do not rewrite records that already had a currency (including already delivered).
- Q: After sources become the only admission gate, do app-shipped built-in bank parsers still run? → A: Shipped parsers no longer run. Capture requires an enabled source with a user template. Former built-in banks are turned into sources/templates in the app so capture still works without a shipped-parser fallback.
- Q: What should happen to identifiers that existed only on the Settings allow-list? → A: Ignore or delete the Settings allow-list. Those identifiers capture only after sources exist. No migration UX (personal app, one user).
- Q: Can the user change a captured transaction’s currency? → A: Currency is editable on the transaction screen only while the transaction is still local and not yet delivered.
- Q: What happens to undelivered transactions that were already stored with hardcoded Iranian Rial? → A: There are no undelivered transactions. Do not specify a special first-delivery rewrite or review-hold for leftover hardcoded IRR. Already-delivered history stays unchanged. Empty/nonexistent undelivered queue is out of scope.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Inherit currency from the bound server account (Priority: P1)

A user binds a capture source to an account that lives on the server. The app reads that account's currency automatically and shows it next to the chosen account. From then on, every transaction captured from that source is recorded in that currency — not assumed to be Iranian Rial.

**Why this priority**: Wrong currency makes amounts unusable in the ledger (a dollar amount stored as rials, or the reverse). This is the user's primary request and unblocks capturing non-rial accounts.

**Independent Test**: Bind a source to a server account whose currency is not Iranian Rial, capture a matching message, and verify the resulting transaction uses that account's currency; bind a different source to a rial account and verify that source still produces rial transactions.

**Acceptance Scenarios**:

1. **Given** the user is adding or editing a source and the server account list is available, **When** they select an account, **Then** the app shows that account's currency automatically and the user does not enter or pick a currency separately.
2. **Given** a source is bound to a server account, **When** a matching message is captured, **Then** the transaction is recorded in that account's currency.
3. **Given** two sources bound to accounts with different currencies, **When** a matching message is captured from each, **Then** each transaction uses the currency of its own bound account.
4. **Given** a source is bound to an Iranian Rial account, **When** a matching message is captured, **Then** the transaction is still in Iranian Rial — because that is the account's currency, not because rial is a global default.
5. **Given** amounts are shown on a captured transaction or in a template test for a bound source, **When** the user looks at the amount, **Then** the displayed currency matches the bound account, not a fixed rial label.

---

### User Story 2 - Capture only from defined sources; drop the settings allow-list (Priority: P1)

A user no longer maintains a separate list of allowed bank apps in Settings. Defining, enabling, and disabling sources is the only way to say which apps and senders are captured. Settings still covers server connection, capture toggles, debug mode, and permissions — not a package/sender allow-list.

**Why this priority**: The allow-list now duplicates sources and confuses configuration (a sender can be on one list and not the other). Removing it is the user's second explicit request and is independently valuable even before multi-currency capture is used.

**Independent Test**: Confirm Settings has no bank allow-list and no conversion wizard for leftover identifiers; add an enabled source with a user template and capture a matching message; send a message from an identifier that is not an enabled source and confirm nothing is captured, including when that identifier would previously have been allow-listed or handled by a shipped parser.

**Acceptance Scenarios**:

1. **Given** the user opens Settings, **When** they look for a bank or sender allow-list, **Then** that section is gone and there is no way to add or remove package or sender identifiers there.
2. **Given** an enabled source with a user template exists for an identifier and channel, **When** a matching message arrives on that channel, **Then** it is considered for capture using that source's template, even though no settings allow-list exists and no shipped parser runs.
3. **Given** a message arrives from an identifier that is not an enabled source, **When** it is processed, **Then** no captured transaction is created from it.
4. **Given** a leftover allow-list from before this change, **When** the user continues using the app, **Then** that leftover list is ignored or deleted, has no effect on what is captured, and is not converted into sources (no migration UX).
5. **Given** the user disables or removes a source, **When** a message from that identifier arrives, **Then** it is not captured, with no separate settings allow-list to re-enable it.
6. **Given** a former built-in bank that used to be understood by a shipped parser, **When** capture is needed for that identifier, **Then** it works only because that bank exists as a source with a user template in the app, not because a shipped parser still runs.

---

### User Story 3 - Unbound sources and later rebinding (Priority: P2)

A user can still save a source without choosing a server account. Capture from that source continues, but currency is not invented and those captures stay local. Once they bind an account, still-held captures that never had a currency receive that account's currency and can be delivered; new captures use it too. Changing the bound account later applies to new captures only. Records that already had a currency are not rewritten.

**Why this priority**: Source definition already allows saving without a binding; currency must have a defined behavior in that gap. It is secondary to the happy path of a bound account.

**Independent Test**: Capture from an unbound source and verify no rial default is applied and the transaction is not delivered; bind an account and verify the still-held capture receives that account's currency and becomes eligible for delivery; capture again and verify the new transaction uses the same currency; rebind to a different-currency account and verify already-currency-stamped records stay unchanged.

**Acceptance Scenarios**:

1. **Given** a source with no bound account, **When** a matching message is captured, **Then** the transaction is kept locally without assuming Iranian Rial, and it is not delivered to the server until the source has a bound account with a known currency.
2. **Given** a source that was unbound and has still-held captures with no currency, **When** the user later binds a server account, **Then** those still-held captures are stamped with that account's currency and become eligible for delivery, and newly captured transactions from that source also use that account's currency.
3. **Given** a source bound to account A, **When** the user rebinds it to account B with a different currency, **Then** later captures use account B's currency; transactions that already had a currency (including already delivered) keep the currency they were given — they are not rewritten.
4. **Given** the bound account later disappears or its identity on the server is no longer valid, **When** the account list is refreshed, **Then** the source is shown as unbound or stale (as in the existing source-binding behavior), and new captures are treated as unbound until the user chooses again.

---

### User Story 4 - Edit currency before delivery (Priority: P2)

A user can correct currency on a captured transaction while it is still on the device and not yet delivered. After it has been delivered to the server, currency is not editable on that screen.

**Why this priority**: Binding supplies the usual currency automatically; a last-look correction is only needed for local, not-yet-delivered records.

**Independent Test**: Open a local undelivered transaction and change its currency, then confirm the new currency is what would be delivered; open an already-delivered transaction and confirm currency cannot be changed there.

**Acceptance Scenarios**:

1. **Given** a captured transaction that is still local and not yet delivered, **When** the user opens the transaction screen, **Then** they can edit its currency.
2. **Given** a captured transaction that has already been delivered, **When** the user opens the transaction screen, **Then** they cannot edit its currency there.

---

### Edge Cases

- What happens when the selected server account has no currency? → The binding cannot be completed; the user is told the account cannot be used until it has a currency, and the previous binding is not silently replaced with an assumed rial value.
- What happens when the account list cannot be loaded? → Last-known accounts (and their last-known currencies) are shown, or the user is told the list is unavailable, consistent with existing source binding; an existing binding and its currency are not discarded.
- What happens when a bound account's currency on the server changes after the source was bound? → The source shows the latest known currency for that account after a successful refresh; new captures use the latest known currency; transactions that already had a currency keep it.
- What happens when the user tests a template on a source that is not yet bound? → The test still shows extracted direction and amount, and does not label the amount as Iranian Rial; currency is shown as unknown until an account is bound.
- What happens when notification or SMS capture is turned off in Settings? → Those master toggles still apply; sources do not capture on a channel whose capture toggle is off.
- What happens to built-in, app-shipped bank understanding after the allow-list is removed? → Shipped parsers no longer run. A message is considered for capture only when it matches an enabled source that has a user template. Former built-in banks exist in the app as sources with templates so those identifiers can still be captured without a parser fallback.
- What happens to leftover Settings allow-list identifiers? → The list is ignored or deleted. Those identifiers are not captured until a source exists for them. There is no migration UX that turns allow-list entries into sources.
- What happens when a previously unbound source is later bound? → Still-held captures from that source that have no currency yet are stamped with the newly bound account's currency and then delivered. Transactions that already had a currency (including already delivered) are not rewritten.
- What happens if the user wants to change currency on a transaction? → They can edit it on the transaction screen only while the transaction is still local and not yet delivered.
- What happens to transactions already captured as Iranian Rial before this change? → Already-delivered history keeps the currency it already has; this feature does not rewrite that history. There is no undelivered leftover queue to migrate; do not specify a special first-delivery rewrite or review-hold for hardcoded Iranian Rial.
- What happens when destination account or category is set on a captured transaction? → That existing assignment behavior is unchanged; it does not override the transaction's currency, which remains the currency of the source's bound account at capture time (or the later stamp on a still-held unbound capture, or a user edit before delivery).

## Requirements *(mandatory)*

### Functional Requirements

**Currency from the bound account**

- **FR-001**: When a user binds a source to a server account, the system MUST take that account's currency automatically; the user MUST NOT be asked to type or choose a currency.
- **FR-002**: When a transaction is captured from a source that is bound to a server account, the system MUST record the transaction in that account's currency.
- **FR-003**: The system MUST NOT treat Iranian Rial as a global default currency for newly captured transactions from user-defined sources.
- **FR-004**: Amounts shown for a captured transaction or a bound-source template test MUST use the bound account's currency in the label the user sees.
- **FR-005**: Different sources MAY produce transactions in different currencies in the same app, each following its own bound account.
- **FR-006**: A server account that has no currency MUST NOT be bindable; the user MUST be told why.
- **FR-007**: Delivery to the server MUST send the transaction with the currency stored on it at delivery time (bound-account currency at capture, a later stamp on a still-held unbound capture, or a user edit before delivery), and MUST accept non-rial currencies that the bound account uses.
- **FR-008**: Changing a source's bound account MUST apply that account's currency to captures after the change, and MUST NOT rewrite the currency on transactions that already had a currency (including already delivered).

**Unbound sources**

- **FR-009**: Users MAY still save a source without a bound account.
- **FR-010**: A transaction captured while its source is unbound MUST NOT be assigned Iranian Rial (or any other invented currency) and MUST NOT be delivered to the server until the source is bound to an account that has a currency.
- **FR-011**: When a previously unbound source is later bound, the system MUST stamp the newly bound account's currency onto still-held captures from that source that have no currency yet, and MUST then make those captures eligible for delivery. The system MUST NOT rewrite currency on transactions that already had a currency (including already delivered).

**Sources replace the settings allow-list**

- **FR-012**: Settings MUST NOT present a bank, package, or sender allow-list, and users MUST NOT be able to add or remove capture identifiers there.
- **FR-013**: The system MUST consider a message for capture only when its originating identifier and channel match an enabled source that has a user template.
- **FR-014**: Messages from identifiers that are not an enabled source MUST NOT create captured transactions, even if they were previously listed in a settings allow-list or handled by a shipped parser.
- **FR-015**: Any previously stored settings allow-list MUST be ignored or deleted after this change, MUST have no effect on capture, and MUST NOT be converted into sources. There is no migration UX.
- **FR-016**: Existing Settings controls that are not the allow-list (server connection, notification/SMS capture toggles, debug mode, permission status) MUST remain.
- **FR-017**: Disabling or removing a source MUST be sufficient to stop capture from that identifier; no parallel settings list may re-admit it.

**Consistency**

- **FR-018**: Currency is not extracted from the message text in this version; the template still marks only direction and amount. Currency comes from the bound account (at capture or as a later stamp on a still-held unbound capture), unless the user edits it before delivery.
- **FR-019**: Destination account and category assignment on a captured transaction remain as already specified; they MUST NOT change the transaction's currency.
- **FR-020**: Duplicate prevention, local queueing, offline retry, and the rule that raw message text never leaves the device MUST continue to apply.
- **FR-021**: App-shipped built-in bank parsers MUST NOT run. Former built-in banks MUST exist in the app as sources with user templates so those identifiers can still be captured without a shipped-parser fallback.
- **FR-022**: The user MAY edit a captured transaction's currency on the transaction screen only while that transaction is still local and not yet delivered. After delivery, currency MUST NOT be editable on that screen.

### Key Entities

- **Bound-account currency**: The currency belonging to the server account a source is bound to. Copied onto each new capture from that source at capture time, and onto still-held no-currency captures from that source when the source is later bound. Shown automatically when the user selects the account.
- **Captured transaction currency**: The currency stored on a captured transaction. Set from the source's bound account at capture time, or stamped later when a still-held unbound capture gets a binding. Not a global rial constant. Unchanged if the source is rebound later or if the record already had a currency. Editable on the transaction screen only while still local and not yet delivered.
- **Transaction source**: Unchanged from the notification-source feature, except that its bound account now also supplies currency, it is the only admission control for which identifiers are captured, and capture uses its user template (shipped parsers do not run).
- **Settings allow-list (removed)**: The former list of bank app packages / SMS senders in Settings. No longer a user-facing control, no longer a capture gate, ignored or deleted, and not migrated into sources.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: In 100% of tested captures from a source bound to a non-rial account, the transaction is recorded in that account's currency and never as Iranian Rial unless that account actually uses rial.
- **SC-002**: When a user binds a source to a server account, they see the account's currency without a separate currency control, in 100% of tested bindings where the account has a currency.
- **SC-003**: Settings contains no bank/package/sender allow-list in a walkthrough of the settings screen; leftover allow-list identifiers are not auto-converted to sources; 100% of testers who previously used that list can instead start or stop capture by adding, enabling, disabling, or removing a source within 2 minutes.
- **SC-004**: 100% of tested messages from identifiers that are not an enabled source produce zero captured transactions, including identifiers that existed only on the old allow-list or were previously handled only by a shipped parser.
- **SC-005**: 100% of tested unbound-source captures are kept locally without an invented rial currency and are not delivered until the source is bound.
- **SC-006**: After rebinding a source to an account with a different currency, 100% of new tested captures use the new currency and 100% of tested transactions that already had a currency keep their original currency.
- **SC-007**: A mixed test set with at least two currencies (rial and one other) records each capture against the correct bound-account currency with zero cross-source currency mix-ups.
- **SC-008**: After binding a previously unbound source, 100% of tested still-held captures from that source that had no currency receive the bound account's currency and become eligible for delivery.
- **SC-009**: In 100% of tested local undelivered transactions, currency can be edited on the transaction screen; in 100% of tested already-delivered transactions, currency cannot be edited there.
- **SC-010**: With shipped parsers not running, 100% of tested captures from former built-in bank identifiers succeed only via an enabled source with a user template.

## Assumptions

- This feature extends the existing phone capture app and the notification-source feature (`specs/004-notification-engine`). Source definition, templates, direction keywords, parse-error review, destination account, and category assignment stay as already specified except where this spec changes currency and admission control.
- Server accounts already include a currency as part of the account list the app retrieves; the user does not maintain a separate currency catalog in the capture app.
- Currency is not a third template placeholder. Message text is not parsed for currency words or codes in this version.
- Iranian Rial remains a valid currency when the bound account actually uses it.
- Amounts continue to be extracted as a positive numeric value from the message; how that number is scaled for a given currency on the way to the ledger is a planning concern, as long as the user-visible and recorded currency match the bound account.
- Unbound captures are held (not delivered) until a bound account supplies a currency, rather than failing the capture or inventing a default. When that source is later bound, still-held captures with no currency are stamped with the bound account's currency and then delivered.
- Transactions that already had a currency (including already delivered) are never retroactively rewritten when a source is bound, rebound, or when a leftover allow-list is dropped.
- The notification and SMS master capture toggles in Settings remain; they are not the allow-list.
- App-shipped built-in bank parsers do not run. Capture requires an enabled source with a user template. Former built-in banks are represented in the app as sources with templates so those identifiers can still be captured without a parser fallback.
- Leftover Settings allow-list entries are ignored or deleted and are not converted into sources. This is a personal app with one user; no allow-list migration UX is required.
- Currency may be edited on the transaction screen only while the transaction is still local and not yet delivered.
- There is no undelivered queue of leftover hardcoded-Iranian-Rial transactions. Do not specify a special first-delivery rewrite or review-hold for that case. Already-delivered history stays unchanged. An empty or nonexistent undelivered leftover queue is out of scope.
- Creating or editing accounts and their currencies remains in the main finance app; this feature only reads them.
- The existing authenticated connection to the server is reused.
- Constitution is still an unfilled template; no extra governance constraints apply beyond this spec.
