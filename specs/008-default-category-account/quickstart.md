# Quickstart: Validation Guide — 008-default-category-account

Validation for the plan's two outcomes: note editing relocated (US1) and default category/account stamped at capture (US2/US3). Behavioral details live in [spec.md](./spec.md), [data-model.md](./data-model.md), and [contracts/](./contracts/) — not duplicated here.

## Prerequisites

- JDK 17+, Android SDK; `cd android && test -f gradlew || gradle wrapper` (first run only).
- Device/emulator (API 26+) with notification access granted; a configured bridge endpoint + catalog sync so `server_categories`/`server_accounts` are populated (Settings tab → Test connection, then trigger a catalog refresh by opening an event's detail screen).
- Build/lint gate: `make android-gradle-test` (runs `cd android && ./gradlew test`) must pass — includes the new tests (`capture/DefaultStampingTest.kt`, `storage/DefaultPreferencesTest.kt`).

## A. Note relocation (US1 / FR-001–FR-004 / SC-001, SC-002)

1. Open Events tab on an event **without** a note → confirm the row shows **no** Add/Edit note control (SC-001: 0 rows with a note button), but keeps `Event category:` and amount lines.
2. Tap `Set category` → on the editing screen tap the add-note control → type ≤200 chars → Save → note is displayed on the editing screen.
3. Verify input stops at `200/200` (existing limit) and Cancel discards the draft.
4. Back to Events tab → row shows `Note: …` (and `Saved on this phone · waiting to sync` while pending). Reopen the event → same note shown and editable (SC-002, US1 scenario 4).
5. Pre-existing note (seeded before the change) is readable/editable from the editing screen — no data loss.
6. With the bridge online, run a sync → pending label clears on both the row and detail screen (unchanged sync path).

## B. Default category + default account (US2/US3 / FR-005–FR-011 / SC-003–SC-006)

1. Settings tab → find the **"Default category & default account"** card: current values read `None` initially.
2. Pick a default category and a default destination account → each shows its label immediately; kill and relaunch the app → selections persist (FR-005/FR-006, SC-005).
3. Capture a new transaction (notification/SMS) → open its detail screen → category and destination pre-filled with the defaults, and (while undelivered) the row/detail shows the pending-assignment indicator until synced (SC-003). Zero taps needed at capture.
4. On that event, change the destination → saved for the event only; Settings still shows the original default; other new captures keep using the default (US2/US3 scenario 3).
5. Capture before/after changing a default → previously captured transactions unchanged (SC-004 / FR-008).
6. Currency mismatch (FR-007b): pick a default account in a different currency from a new transaction → capture succeeds, destination stamped, **no** warning shown.
7. Clear one default (e.g. category) → new captures get `Default` for category but the account default still applies (FR-009).
8. Stale default (FR-010/SC-006): with a default set, remove that category/account from the server catalog (or simulate via test) → next capture is **not** pre-assigned for that field, app does not crash, Settings shows "no longer available" while the stored value remains visible.
9. Offline catalog: airplane mode + failed refresh → pickers disabled with hint; stored defaults still stamp on new captures.

## C. Regression sweep

- Events tab: selection checkboxes, Sync/Delete selected (007), Scan (006), Clear processed/Clear all, currency edit gating on detail screen (005) all unchanged.
- `make android-gradle-test` green (existing `CapturePipelineTest`, `AssignmentSyncTest`, `AssignmentPersistenceTest` must still pass — stamping only activates when defaults are set).

## Expected outcome summary

| Check | Pass criterion |
|---|---|
| SC-001 | 0 note buttons on Events rows; add-note flow <30s |
| SC-002 | 100% pre-existing notes readable/editable from detail screen |
| SC-003 | 100% of new captures pre-assigned both defaults |
| SC-004 | 0 old transactions affected by default changes |
| SC-005 | set/change/clear <1 min; defaults visible at a glance |
| SC-006 | stale default → no crash, warning shown, capture continues |
