# Go Money Android Transaction Capture

Two deployable artifacts implementing `specs/001-android-txn-capture/spec.md`:

1. **Android capture app** (`android/`, Kotlin/Compose, standalone Gradle project) — captures bank-app notifications (and optionally SMS), parses them with pluggable per-bank parsers, queues them in a durable Room outbox, and delivers them to the bridge over plain HTTP + static bearer token.
2. **Transaction bridge** (this Go module: `cmd/android-bridge` + `pkg/androidbridge`) — authenticates the app, validates payloads, resolves account hints, enforces final deduplication, and records transactions in Go Money via `TransactionsService/CreateTransaction` (ConnectRPC).

## Blue Bank (بلو, package `com.samanpr.blu`) — on-device E2E notes

The `BlueParser` (`parser/BlueParser.kt`, bank slug `blu`) handles Blue Bank
notifications so the app can be exercised end-to-end on a real device.

### Allow-list entry flow (FR-018)

1. Open the app → **Settings** tab.
2. In "Bank app allow-list", type the Blue app package ID **`com.samanpr.blu`**
   into the "Package ID" field (the field requires a dotted package-like ID;
   installed apps are **never** auto-allowed) and tap **Add**.
3. Confirm it appears in the allow-list rows. Use **Remove** to drop a package.
   The empty default allow-list is unchanged: nothing is captured until a
   package is explicitly added.

### Synthetic account hint / bridge mapping key

Blue notifications carry **no masked card/account suffix**, so the parser emits
the deterministic, non-PII synthetic hint **`blue-default`**
(`BlueParser.SYNTHETIC_ACCOUNT_HINT`). Add the mapping on the bridge side
(`mappings.json` or `PUT /v1/mappings`):

```json
{ "blu|blue-default": <your Go Money source account id> }
```

Without it the bridge returns 400 `validation` ("unmapped account:") and the
capture stays `FAILED`/retry-on-mapping (manual retry from Events after fixing
the mapping).

The bridge uses this ID as the bank-side account and resolves Go Money's
default counterpart account (`Default Expense` for withdrawals) from the
Accounts API. The mapped bank account must be `IRR` for these captures. The
default expense/income account can use another currency: the bridge reads the
active Go Money currency rates and converts the counterpart amount at delivery
time. For example, an IRR Blue withdrawal can be recorded from the mapped IRR
asset account to a USD default expense account. Configure the IRR rate in Go
Money; the USD amount is rounded to the configured USD precision and then stored
on the transaction.

### What the parser guarantees

- The parser anchors on `برداشت` (withdrawal); the `مبلغ` label is **not**
  required (it never appears in real Blue bodies). Text is truncated before
  the trailing `موجودی` (balance) marker **first**, so the balance figure is
  structurally unreachable; the **first amount in the truncated body** is the
  transaction amount (e.g. `<name>، ۱۰٬۰۰۰٬۰۰۰ ريال` → `amountMinor =
  10,000,000`).
- Persian/Arabic digits and `٬` separators are normalized through
  `AmountNormalizer` (FR-010).
- The event timestamp is the Android notification `postedAt`
  (`sbn.postTime`), not capture time.
- The **description is a fixed, safe type-appropriate constant** — `Blue
  withdrawal`, `Blue purchase`, or `Blue deposit` — so a recognized income or
  purchase message is never mislabeled as a withdrawal. The **customer name
  and balance are never copied into the description** (structurally excluded,
  not string-matched).
- Notification text is taken from the longer of `EXTRA_TEXT` /
  `EXTRA_BIG_TEXT` so the full BigText variant (label + amount) is not lost.
  Raw message text is never logged (FR-028).
- Unrecognizable Blue messages produce a **sanitized parse failure**
  (retained as `parse_error`, visible in the Events tab) — never raw text.

### Setup / permissions notes

- Grant **Notification access** to Go Money Capture in system settings
  (Settings tab shows the current state).
- Enter the bridge URL (plain `http://host:port`) and bearer token in
  Settings; **Test connection** on Dashboard or Settings runs
  `POST /v1/ping` and shows connected / offline / error status.
- Plain HTTP is the specified bridge contract (Q1=B, trusted LAN); the
  manifest sets `android:usesCleartextTraffic="true"` because targetSdk 35
  blocks cleartext by default. TLS is added when the bridge contract gains
  TLS support.

### Limitation: one undifferentiated Blue account hint

Every parsed Blue capture uses the single `blue-default` hint. The bridge
therefore cannot distinguish between multiple Blue accounts (e.g. checking
vs. saving) — all map to the same Go Money source account. If Blue ever
exposes an account/card suffix in its notifications, the parser should
prefer the real masked hint over the synthetic one.

## Build & test

```bash
# Bridge (Go, from repo root)
go test ./pkg/androidbridge/...       # bridge contract + dedup tests
go run ./cmd/android-bridge ...       # see specs/001-android-txn-capture/bridge-runbook.md

# Android app (from android/)
gradle wrapper && ./gradlew test      # parser fixtures + Room/Redactor tests
```

## Key docs

- Spec: `specs/001-android-txn-capture/spec.md`
- Bridge setup/run: `specs/001-android-txn-capture/bridge-runbook.md`
- Add-a-bank workflow: `specs/001-android-txn-capture/parser-guide.md`
- API contracts: `specs/001-android-txn-capture/contracts/`

## Clarified decisions baked in

- **Q1=B**: plain HTTP + static bearer token (trusted LAN), no TLS in MVP.
- **Q2=A**: single round-trip, single terminal state `SENT` (duplicate acks are also terminal, tagged `duplicate`).
- **Q3=B**: ±2-minute dedup timestamp tolerance (bridge-side adjacent-bucket window scan).
