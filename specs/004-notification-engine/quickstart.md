# Quickstart: Notification Source & Template Engine — End-to-End Validation

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md) | Data model: [data-model.md](./data-model.md) | Contracts: [bridge-api.md](./contracts/bridge-api.md), [source-template.md](./contracts/source-template.md)

Run these scenarios after implementation to prove the feature's acceptance criteria. Each step maps to the user story and requirements it validates. Details of entities, matching rules, and payloads live in the linked artifacts and are not duplicated here.

## Prerequisites

- The capture app and bridge from `specs/001-android-txn-capture/` are installed and working (phone on the same LAN/Tailscale as the bridge, bridge configured with `GOMONEY_URL` and a Go Money service token, app configured with the bridge URL + bearer token, notification-listener permission granted).
- Go Money is reachable from the bridge and has at least two accounts and two categories.
- Build/run:
  ```bash
  # Bridge (repo root; see bridge-runbook.md for the env file)
  make android-bridge-run

  # Android app
  cd android && ./gradlew installDebug
  ```
- Automated tests to run first:
  ```bash
  go test ./pkg/androidbridge/...     # or: make android-bridge-test
  cd android && ./gradlew test        # or: make android-gradle-test
  ```

## Scenario 1 — Define a source and capture with a template (US1, P1; FR-001/002/007/009, SC-001/002)

1. Open the new **Sources** tab → **Add source**.
2. Enter a name, the bank app's package identifier, channel = Notification, paste a real sample message, and mark the amount and direction with `{amount}` and `{direction}`. Enter at least one income and one expense keyword.
3. Press **Test** → expect the extracted direction and amount (or a clear failure reason). Save without testing in a second pass to confirm that is allowed (FR-027, SC-011).
4. **Expect**: the source appears in the list, enabled.
5. Post a matching notification (real purchase or debug) → within ~10 s a transaction with the correct direction and amount appears in the events list and reaches Go Money.
6. Post a message from an identifier with no enabled source → **no** transaction is created (FR-004, SC-003).

## Scenario 2 — Template tolerance, direction keywords, and parse errors (US1; FR-010/011/026, SC-002/010)

1. Post a matching message whose date/reference number differs from the sample → still parses (anchor tolerance).
2. Post a matching-shape message with an amount written in Persian digits/separators → the amount is read correctly (FR-011).
3. Post a message from the defined source whose amount is missing/zero, or whose wording contains neither keyword → **expect** no transaction and an entry in the parse-error review list with a reason (FR-010).
4. Post a message containing both an income and an expense keyword → **expect** a parse error (ambiguous direction).
5. Dismiss one parse-error entry → it disappears immediately. Add entries beyond 200 or age some past 30 days → the list never exceeds the newest 200 / 30 days (FR-028, SC-010).

## Scenario 3 — Duplicate identifier + channel is blocked (US1.7; FR-005, SC-009)

1. Try to add a second source with the same identifier and channel as an existing one.
2. **Expect**: the save is blocked with a duplicate error naming the existing source; no message is ever parsed by two sources.

## Scenario 4 — Bind a source to a server account (US2, P2; FR-013/014/015/016, SC-004/006)

1. Edit a source and open the account drop-down → it lists Go Money's accounts.
2. Bind an account and save. Capture a matching message → **expect** the resulting Go Money transaction recorded against that account.
3. In Go Money add/rename an account, refresh the selector → **expect** it reflects the server (SC-006). Delete/rename the bound account → **expect** the source shows as unbound/stale and prompts a re-selection; capture is not lost.
4. Stop Go Money (or the bridge) and open the selector → **expect** the last known list (or a clear "unavailable" message) and that the existing binding is not silently discarded (FR-016).

## Scenario 5 — Set destination account and category on a captured transaction (US3, P2; FR-017..021, SC-005)

1. Open a captured transaction → **expect** destination-account and category drop-downs populated from the server.
2. Choose both and save → **expect** they persist across an app/device restart (FR-019).
3. **Already-delivered case**: with the transaction already in Go Money, change the destination account/category → **expect** the *same* Go Money transaction is updated and no second transaction is created (FR-020).
4. **Offline case**: disable the network, make a choice → it is stored locally; re-enable the network → it reaches the same server transaction automatically with no duplicate (SC-005).
5. Change categories/accounts in Go Money, reopen the drop-downs → they reflect the server's current values (FR-021).

## Scenario 6 — Enable/disable/remove a source (US4, P3; FR-003, SC-008)

1. Disable a source → its messages are no longer captured; other sources keep working.
2. Re-enable it → capture resumes.
3. Remove it → it disappears from the list, its messages are ignored, and previously captured transactions are retained.

## Scenario 7 — Privacy and permissions (FR-022/023/024, SC-007)

1. Inspect app and bridge logs during scenarios 1–5 → no raw message text, and no raw text in any bridge request/response.
2. Revoke notification-listener access → capture stops, queued data is intact, and sources can still be defined.
3. Configure a source for SMS and post a notification with the same identifier → it is ignored (channel must match).

## Success mapping

| Scenario | Proves |
|---|---|
| 1 | FR-001/002/004/007/009/012/027, SC-001/002/003/011 |
| 2 | FR-009/010/011/026/028, SC-002/010 |
| 3 | FR-005, SC-009 |
| 4 | FR-013/014/015/016, SC-004/006 |
| 5 | FR-017/018/019/020/021, SC-005/006 |
| 6 | FR-003, SC-008 |
| 7 | FR-022/023/024, SC-007 |
