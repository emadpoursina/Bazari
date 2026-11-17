# Contract: Events inbox

**Feature**: 010-android-ui-refresh | **Date**: 2026-11-17

Updates `EventsScreen`. Scan banner contract from 006 (`ScanCoordinator` first list item) stays; chrome around it restyles.

## 1. Default (not selecting)

- Rows grouped by **local calendar day** of `txAt`, newest day first; sticky day headers.
- Each row: formatted amount + currency if known, source name, note/description preview (truncated), time, status chip. Income vs expense via sign/label + color.
- **Whole row tap** opens detail (`onOpenDetail`).
- Failed delivery: **Retry** on the row without opening detail (`onRetry`).
- **No** permanent Select all / Sync selected / Delete selected / Clear processed / Clear all bar.
- **No** always-on checkboxes.
- **Scan notifications** remains a labeled primary control, enabled unless `ScanUiState.Running`, including when the list is empty.

## 2. Selection mode

Enter: long-press a row, or overflow **Select**. Then:

- Checkboxes appear; selection count shown.
- Actions: Select all, Clear selection, Sync selected, Delete selected (delete still uses the existing confirm dialog).
- Leave selection: overflow Done / Clear, or leaving the Events destination (composition dispose).

**Clear processed** and **Clear all** live in an overflow menu, not the default bar. Clear all keeps the existing confirm dialog.

## 3. Scan banner

If `scanState !is Idle`, the banner is the first `LazyColumn` item (progress / result / blocked / unavailable). Blocked and unavailable keep **Open notification access settings**. The banner does not replace the list chrome or bottom navigation.

## 4. Empty list

Headline, one-sentence explanation (“Nothing captured yet”), one primary action (**Scan notifications**). Bottom bar remains. Do not show a wrapping maintenance button row.

## 5. Missing-event message

Notification-open miss: dismissible banner above the list (existing `missingEventMessage`). Restyle to match other banners; outcomes unchanged.

## 6. Amounts and overflow

Unknown/zero parse-error amount may show “—” as today. Very large amounts and long source names wrap or ellipsize; no horizontal scroll of the row.
