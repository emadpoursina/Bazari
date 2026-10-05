# Quickstart: Notification Scan Button

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md) | **Contracts**: [scan-coordination.md](./contracts/scan-coordination.md), [notification-identity.md](./contracts/notification-identity.md)

Validation guide only. Implementation lives in `tasks.md`. See the [005 quickstart](../005-account-currency-sources/quickstart.md) for building the app and running the bridge (no bridge changes are needed for this feature).

## Prerequisites

- Branch `notification-events-button` with this feature implemented.
- Android capture app built and installed from `android/`.
- Notification access **granted** for the app (Settings → Apps → Special access → Notification access) unless a step says otherwise.
- At least one enabled `TransactionSource` with a matching template (Sources tab) so qualifying notifications exist.
- Capture toggle **Notification capture** on (Settings) unless a step says otherwise.

## Automated checks

```bash
make android-gradle-test
```

Expect green tests covering: identity claim/commit/release + stale takeover, already-recorded rejection, outcome→count mapping (`added + skipped == examined`), single-flight scan guard, banner lifecycle (cleared on new scan and on leaving the tab), Room v4→v5 migration creating `notification_capture_records`.

## Manual walkthrough

### 1. Button and permission guidance (FR-001, FR-006, SC-004, Q5)

Revoke notification access. Open **Events**. The scan control is visible next to *Clear processed* / *Clear all* — including when the list is empty. Press it: no scan starts, the inline banner explains that notification-read access is missing, and its button opens the system **Notification access** settings screen.

### 2. Basic scan (FR-002, SC-001, acceptance 1)

Grant access. With ≥ 3 uncaptured qualifying notifications in the shade (e.g. restart the app after posting them, or force-stop it first), press the scan button. The banner shows *Scanning…* then `Examined X · Added 3 · Skipped …`; the 3 events appear at the top of the list without restarting the app (FR-009).

### 3. Idempotence (FR-003, SC-002, SC-006, Q1)

Press scan again immediately: `Added 0`, existing events unchanged, zero duplicates — even for notifications that were updated in place with different content after their first capture. (Real-time arrivals of the same notifications are equally deduplicated.)

### 4. Nothing qualifies (acceptance 3, edge cases)

Clear the shade down to only unrecognized/unrelated notifications (or disable the source): scan → `No new events found · Examined X · Skipped X`, no new rows, no errors. With an empty shade: `No active notifications.`

### 5. Single scan + non-blocking UI (FR-007, FR-008)

Press the button repeatedly while a scan runs: the button stays disabled with an indicator, only one scan executes, and the list remains scrollable the whole time. With ~50 notifications the summary completes in well under 10 s (SC-003).

### 6. Summary lifecycle (FR-005, Q4)

Leave the Events tab and return → banner gone. Run a scan, leave before it finishes, return → no stale banner; the new events are still in the list. Start a new scan → previous banner replaced by *Scanning…*.

### 7. Additive only + clear semantics (FR-010, research R8)

Run a scan over a list that already contains those events → existing rows (states, notes, delivery) untouched. After **Clear all**, a scan re-creates events for notifications still in the shade; after **Clear processed**, it does not resurrect cleared terminal rows.

### 8. Toggle off (same capture rules, FR-002)

Turn **Notification capture** off and scan: `Added 0` (qualifying notifications counted as skipped); turning it back on and scanning again captures them — proving the scan uses the exact real-time rules rather than its own.

## Done when

Success criteria SC-001–SC-006 in [spec.md](./spec.md) hold for the walkthrough and the automated tests above.
