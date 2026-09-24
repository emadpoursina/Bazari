# Feature Specification: Go Money Android Transaction Capture

**Feature Branch**: `android-txn-capture`

**Created**: 2026-09-24

**Status**: Draft

**Input**: User description: "/Users/emad/Projects/playground/Bazari/scratch/androidTracker.md" (full PRD content used as feature description)

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Automatic transaction capture from bank notifications (Priority: P1)

A user carries their Android phone through a normal day. When they make a card purchase or withdrawal using a supported Iranian banking/payment app, the app posts a notification. The capture app detects that the notification belongs to a supported bank app, stores the raw notification, converts it into a normalized transaction, and queues it for delivery. The user never has to open the app or enter anything manually.

**Why this priority**: This is the core problem the feature exists to solve — eliminating manual transaction entry. Without automatic capture, nothing else in the feature has value.

**Independent Test**: Can be fully tested by making a transaction in one supported bank app and verifying that a corresponding transaction appears as captured (pending/sent) in the app's dashboard without any user interaction — delivering hands-free transaction recording.

**Acceptance Scenarios**:

1. **Given** notification capture is enabled and notification-listener permission is granted, **When** a supported bank app posts a transaction notification, **Then** the raw event is stored locally and a normalized transaction appears in the recent-events list within seconds.
2. **Given** a notification arrives from an app that is NOT in the supported/allowed list, **When** the notification is posted, **Then** it is ignored and no transaction is created.
3. **Given** the user has disabled notification capture in settings, **When** a bank notification arrives, **Then** it is not captured.
4. **Given** a bank notification contains an amount, account hint, and transaction type recognizable by that bank's parser, **When** it is parsed, **Then** the resulting normalized transaction contains the correct amount, currency (IRR), type (expense/income), and timestamp.

---

### User Story 2 - Reliable delivery to the local Go Money server (Priority: P1)

A transaction has been captured and normalized while the user's computer (running Go Money) is offline or the network is unavailable. The transaction stays safely queued on the phone. When the server becomes reachable again, the queued transaction is delivered automatically, without the user doing anything, and is marked as sent.

**Why this priority**: Capture without delivery is useless — the user still ends up entering transactions manually. Reliable offline-tolerant delivery is the second half of the core promise.

**Independent Test**: Can be fully tested by capturing a transaction with the server unreachable, then bringing the server online and verifying the transaction uploads automatically and is marked sent — delivering durable, loss-free delivery.

**Acceptance Scenarios**:

1. **Given** the Go Money server is unreachable, **When** a transaction is captured, **Then** it is stored locally in a queued state and the dashboard shows it as pending.
2. **Given** one or more transactions are queued locally, **When** the server becomes reachable, **Then** queued transactions are delivered automatically without user action and marked as sent.
3. **Given** a delivery attempt fails due to a network error, **When** the app retries later, **Then** the same transaction is retried (not re-captured) and local data survives app and phone restarts.
4. **Given** the server rejects a transaction, **When** the failure is recorded, **Then** the transaction is marked failed with a visible reason category and the user can manually retry it.

---

### User Story 3 - Duplicate prevention (Priority: P1)

The same real-world transaction reaches the system more than once — repeated bank notifications, the same transaction seen via both notification and SMS, or app/network retries. The system recognizes these as one transaction and only one transaction is ever recorded in Go Money.

**Why this priority**: Duplicates silently corrupt the user's financial records — worse than a missing transaction, because the user trusts Go Money as the source of truth.

**Independent Test**: Can be fully tested by delivering the same captured transaction event twice (identical and re-derived variants) and verifying Go Money records exactly one transaction — delivering trustworthy, corruption-free records.

**Acceptance Scenarios**:

1. **Given** a transaction was already successfully delivered, **When** an identical event (same bank, amount, timestamp, account) is captured again, **Then** no second Go Money transaction is created and the event is marked as a duplicate.
2. **Given** the same transaction was captured via both notification and SMS, **When** both are processed, **Then** they are recognized as one transaction and delivered once.
3. **Given** two genuinely distinct transactions with similar details occur close together, **When** both are processed, **Then** both are recorded (duplicates are detected without collapsing distinct transactions).

---

### User Story 4 - Visibility into capture status (Priority: P2)

The user opens the app and immediately sees whether it is healthy: connection status to the server, how many transactions were captured today, how many are pending, sent, or failed, and a short list of recent events with their state. Errors are shown in plain categories without exposing sensitive message contents.

**Why this priority**: Trust requires visibility, but the app is a background collector — the UI should confirm the system works, not become a finance dashboard.

**Independent Test**: Can be fully tested by opening the app after several capture/delivery events and verifying the dashboard counts and recent-events list match reality — delivering at-a-glance confidence that capture is working.

**Acceptance Scenarios**:

1. **Given** the app has captured transactions today, **When** the user opens the dashboard, **Then** they see captured-today, pending, sent, and failed counts and a connection status (connected/offline).
2. **Given** recent events exist, **When** the user views the recent-events list, **Then** each entry shows amount, bank, time, and delivery state (sent / pending / failed).
3. **Given** an event failed to parse or deliver, **When** the user views it, **Then** the failure category (parse, network, server, duplicate, etc.) is visible without exposing raw sensitive text by default.

---

### User Story 5 - Optional SMS capture (Priority: P3)

The user can enable SMS as a second capture source for bank transaction SMS messages. When enabled, transaction SMS from configured bank senders are parsed with the same pipeline and normalized model as notifications. When disabled, SMS is ignored entirely.

**Why this priority**: Redundant capture source that improves coverage for banks with unreliable notifications; valuable but secondary to the primary notification path.

**Independent Test**: Can be fully tested by enabling SMS capture, receiving a bank transaction SMS, and verifying a normalized transaction is created; then disabling SMS capture and verifying SMS events are ignored — delivering a configurable second capture channel.

**Acceptance Scenarios**:

1. **Given** SMS capture is enabled, **When** a transaction SMS from a configured bank sender arrives, **Then** it is captured, parsed by the same bank parser pipeline, and queued like a notification event.
2. **Given** SMS capture is disabled, **When** a transaction SMS arrives, **Then** it is not captured.
3. **Given** the same transaction is captured via both SMS and notification, **When** both are processed, **Then** deduplication collapses them into one delivered transaction.

---

### User Story 6 - Adding a new bank parser without touching the core (Priority: P2)

The developer wants to support an additional Iranian bank or payment app. They add a new parser for that bank (matching its notification/SMS text patterns) and register it in the supported-apps list. The capture pipeline, outbox, and delivery system remain untouched.

**Why this priority**: Extensibility is a stated core goal and protects the architecture from becoming bank-specific spaghetti, but it delivers value only after the core pipeline works.

**Independent Test**: Can be tested by adding a parser for a new bank driven purely by captured raw-event fixtures and verifying the new bank's events flow through capture → parse → queue → deliver with no core changes — delivering cheap bank onboarding.

**Acceptance Scenarios**:

1. **Given** a new bank parser exists for a bank app, **When** a notification from that app arrives, **Then** the correct parser is selected and produces a normalized transaction.
2. **Given** a notification matches no parser, **When** it is processed, **Then** it is recorded as a parse error (retained for later analysis) and never silently dropped.

---

### User Story 7 - Parser development mode (Priority: P3)

While developing parsers, the developer can enable a debug mode to inspect raw captured events alongside their parse results: source, originating app package, title, text, parsed type, amount, currency, account hint, which parser matched, and parse confidence.

**Why this priority**: Developer-facing tooling that accelerates parser work for real Iranian bank message formats; important for iteration speed but not user-facing value.

**Independent Test**: Can be fully tested by enabling debug mode, capturing an event, and verifying all raw and parsed fields are inspectable — delivering fast parser debugging.

**Acceptance Scenarios**:

1. **Given** debug mode is enabled, **When** an event is captured, **Then** the developer can view the raw event fields (source, package, title, text) and the parse result (type, amount, currency, account hint, parser name, confidence) together.
2. **Given** debug mode is disabled, **When** events are captured, **Then** raw message contents are not shown in the normal UI and are not written to production logs.

---

### Edge Cases

- What happens when notification-listener permission is revoked while capture is active? → The app detects the missing permission, stops capturing, and clearly surfaces the permission state in settings/dashboard; queued data is preserved.
- What happens when the phone is offline for days and many transactions queue up? → All are retained locally in order and delivered once the server is reachable; nothing is dropped or expired silently.
- What happens when the same transaction notification is posted twice by the bank app? → The second copy is recognized as a duplicate and does not create a second Go Money transaction.
- What happens when a notification text format changes after a bank app update? → The event is stored and flagged as a parse error (visible in the app) rather than being silently discarded or mis-parsed.
- What happens when two different transactions with the same amount occur within seconds on the same account? → They are treated as distinct transactions; deduplication must not collapse genuinely distinct events.
- What happens when the server is reachable but rejects a transaction as invalid? → It is marked failed with a server-error category, retained locally, and can be retried manually after correction.
- What happens when the device restarts mid-delivery? → Delivery state is durable; an interrupted send resumes/retries without duplication.
- What happens when the user clears local processed events? → Only already-sent/processed events are removed; pending and failed events are preserved.
- What happens when amounts are written in Persian digits or with Persian formatting? → Parsers must normalize Persian numerals and separators into canonical numeric amounts.

## Requirements *(mandatory)*

### Functional Requirements

**Capture**

- **FR-001**: System MUST capture notifications only from explicitly configured/supported bank or payment apps, and MUST ignore all other notifications.
- **FR-002**: System MUST store every captured event locally as a raw event (source, originating app, title, text, event timestamp, capture timestamp) before any processing.
- **FR-003**: System MUST retain raw events locally until they have been successfully processed.
- **FR-004**: System MUST support SMS as an optional, independently configurable capture source using the same normalized transaction model as notifications.
- **FR-005**: System MUST NOT read other apps' private storage, require root access, use accessibility/screen scraping, or take automatic screenshots/OCR as capture mechanisms.
- **FR-006**: System MUST NOT store bank credentials, passwords, or PINs.

**Parsing & Normalization**

- **FR-007**: System MUST convert each captured raw event into a normalized transaction containing: unique id, source event reference, source type, bank identifier, account hint, transaction type (e.g., expense/income), amount, currency, transaction timestamp, description, and the original raw text reference.
- **FR-008**: System MUST select the parser per bank app via a pluggable parser architecture, such that adding support for a new bank does not require modifying the core capture, queue, or delivery pipeline.
- **FR-009**: System MUST produce a deterministic deduplication fingerprint for every normalized transaction, derived from stable attributes (bank, account, type, amount, timestamp, normalized description) so that the same real-world transaction yields the same fingerprint regardless of source or delivery attempt. Duplicate matching MUST tolerate timestamp variation of up to ±2 minutes between copies of the same transaction from different capture sources (e.g., notification vs. SMS).
- **FR-010**: System MUST normalize Persian-digit and locale-specific amount formatting into canonical numeric values.

**Delivery & Outbox**

- **FR-011**: System MUST queue every parsed transaction locally before any delivery attempt, with a delivery lifecycle at minimum covering: captured, parsed, queued, sending, sent, and failed/retry states. Delivery acknowledgement MUST be a single HTTP round-trip: the bridge returns success only after the transaction is recorded in Go Money, so "sent" is the single terminal success state (no separate confirmed state exists).
- **FR-012**: System MUST automatically retry delivery of queued transactions when the Go Money server becomes reachable, without user intervention.
- **FR-013**: System MUST deliver transactions to the Go Money backend through a replaceable bridge/adapter layer (authentication, validation, mapping, deduplication, error handling) rather than coupling the app to Go Money's internal database.
- **FR-014**: System MUST communicate with the local Go Money bridge using a static bearer/API token for authentication over plain HTTP; this is acceptable because communication is confined to the user's trusted local network (encrypted transport such as HTTPS is not required for the MVP).
- **FR-015**: System MUST NOT create a second Go Money transaction for an event whose deduplication fingerprint was already successfully processed.
- **FR-016**: System MUST preserve queued transactions across app restarts, phone restarts, network failures, and server failures.

**User Interface & Control**

- **FR-017**: Users MUST be able to enable/disable notification capture and SMS capture independently.
- **FR-018**: Users MUST be able to select which installed bank/payment apps are supported for capture.
- **FR-019**: Users MUST be able to configure the server address and authentication credential, and test the connection.
- **FR-020**: Users MUST be able to view a dashboard showing connection status, captured-today count, pending count, sent count, and failed count.
- **FR-021**: Users MUST be able to view recent captured transactions with amount, bank, time, and delivery state.
- **FR-022**: Users MUST be able to manually retry failed deliveries and to clear locally stored processed events.
- **FR-023**: System MUST surface errors in defined categories (capture error, parse error, validation error, network error, server error, duplicate) without exposing unnecessary sensitive raw content by default.
- **FR-024**: System MUST display the current notification-listener (and, when enabled, SMS) permission status and guide the user to grant missing permissions.
- **FR-025**: System MUST provide a developer/debug mode for inspecting raw events and parse results (source, package, title, text, parsed type, amount, currency, account hint, parser name, confidence).

**Privacy & Security**

- **FR-026**: System MUST keep all captured data on the user's devices and trusted home network; no third-party cloud processing.
- **FR-027**: System MUST NOT include third-party analytics, advertising SDKs, or unnecessary network access.
- **FR-028**: System MUST NOT log sensitive raw transaction data in production logs.

**Testing Ground Rules (product-level)**

- **FR-029**: Every bank parser MUST be validated against stored raw-event fixtures covering that bank's common transaction types (e.g., purchase, withdrawal, deposit, transfer).
- **FR-030**: System MUST be verifiable end-to-end: a captured event results in exactly one Go Money transaction, and repeated/parallel captures of the same event result in exactly one.

### Key Entities *(include if feature involves data)*

- **RawEvent**: A captured, unmodified event (notification or SMS) — source type, originating app package, bank identifier, title, text, event timestamp, capture timestamp; retained until successfully processed.
- **NormalizedTransaction**: The provider-independent result of parsing a RawEvent — unique id, reference to its source event, source type, bank, account hint, transaction type, amount, currency, transaction timestamp, description, raw text reference, and deduplication fingerprint.
- **DeliveryRecord**: The outbox state of a normalized transaction — lifecycle state (captured/parsed/queued/sending/sent/failed), retry history, error category, and last attempt time.
- **BankParser (capability, not stored data)**: A per-bank matching-and-parsing rule set that declares which raw events it can handle and how they become normalized transactions.
- **ServerConfiguration**: User-configured bridge/server address, authentication credential, and per-source capture toggles and enabled bank apps.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A real transaction in a supported bank app is captured and visible in the app within 10 seconds of the notification appearing, with no user interaction.
- **SC-002**: 100% of captured, successfully parsed transactions are eventually delivered to Go Money — no transaction is lost due to offline periods, app restarts, or phone restarts.
- **SC-003**: Zero duplicate Go Money transactions are created when the same event is presented multiple times (identical duplicates, notification+SMS duplicates, and retry duplicates) in a test scenario set.
- **SC-004**: After the server becomes reachable, all queued transactions are delivered automatically within 2 minutes without user action.
- **SC-005**: A developer can add support for a new bank by creating only a new parser plus fixtures, with zero changes to the core capture/queue/delivery pipeline.
- **SC-006**: A user can determine the system's health (connection, pending/sent/failed counts) within 5 seconds of opening the app.
- **SC-007**: No sensitive raw transaction content appears in production logs or in any data leaving the user's trusted network; network communication with the bridge is authenticated (static token over plain HTTP within the trusted LAN).
- **SC-008**: The end-to-end journey (transaction in bank app → transaction recorded in Go Money) completes without the user opening the capture app at all.

## Assumptions

- The Go Money server runs on the user's own machine inside their home/trusted network (LAN); the Android app never requires a publicly reachable server.
- Go Money (or its bridge) exposes an HTTP-based integration point that the Android app can deliver normalized transactions to; exact mapping to Go Money's data model is the bridge's responsibility, not the app's.
- The MVP only needs parsers for the bank/payment apps the developer actually uses; other Iranian banks can be added later via the parser architecture.
- The user's phone runs a recent Android version that supports notification listener access and the optional SMS capture permissions.
- SMS capture may be constrained by platform permission policies; where it is not technically/legally appropriate, notification capture alone remains fully functional (SMS is secondary and optional).
- Amounts are recorded in Iranian Rial (IRR); currency handling for other currencies is out of scope for the MVP.
- Local storage retention is indefinite for pending/failed events and user-clearable for processed events (industry-practice default; no automatic expiry in MVP).
- Duplicate detection relies on the deterministic fingerprint of event attributes; near-identical but genuinely distinct transactions (same amount, seconds apart) are assumed rare enough that a timestamp-inclusive fingerprint is an acceptable default.
- The bridge, not the Android app, is responsible for final deduplication enforcement against Go Money's actual records; the app's fingerprint enables this but Go Money remains the source of truth.
- Bridge authentication uses a single user-configured static bearer/API token sent with each request; the bridge endpoint runs on plain HTTP because it is reachable only inside the user's trusted home network (no HTTPS certificate/mTLS management in the MVP).
- OCR-based capture, merchant recognition, automatic categorization, and transfer detection are explicitly out of scope for the MVP.

## Clarifications

### Session 2026-09-24

- Q: What authentication mechanism should the app use with the local Go Money bridge? → A: Static bearer/API token over plain HTTP, relying on trusted-LAN-only access (matches the PRD example URL http://192.168.1.10:8787); no HTTPS/mTLS in the MVP.
- Q: Does the bridge acknowledge transactions back to the app, and does that acknowledgement distinguish "accepted" from "recorded in Go Money"? → A: One HTTP round-trip — the bridge returns success only after Go Money records the transaction; the app has a single "sent" terminal state and no separate CONFIRMED state.
- Q: What time tolerance should duplicate matching allow across capture sources? → A: ±2 minutes tolerance window on the transaction timestamp.
