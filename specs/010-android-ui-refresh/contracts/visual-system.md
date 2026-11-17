# Contract: Visual system

**Feature**: 010-android-ui-refresh | **Date**: 2026-11-17

## 1. Theme

- `CaptureTheme` wraps all `setContent` UI.
- Light/dark follow the **system** setting (`isSystemInDarkTheme()` + DayNight application theme). No in-app theme picker.
- Body text and amount figures remain readable in both appearances (SC-006).

## 2. Type and space

- Screen title: `titleLarge`. Amounts on detail: `headlineMedium` or larger. List amounts: `titleLarge`. Meta: `bodyMedium` / `labelMedium`.
- 8 dp spacing grid; 16 dp screen padding; list rows ≥ 48 dp tall for the primary tap target.
- Layout uses `start`/`end`, not physical left/right. Primary actions remain fully visible without horizontal scrolling at **360 dp** width (SC-005).

## 3. Color meaning (always with text or sign)

| Meaning | Color role | Non-color cue |
|---|---|---|
| Expense | error/tertiary expense token | minus sign or “Expense” |
| Income | tertiary/positive token | plus sign or “Income” |
| Sent / healthy | primary or explicit success | chip text “Sent” |
| Waiting | secondary/warning | chip text “Waiting” |
| Failed / parse problem | error | chip text “Couldn't send” / “Parse problem” |

Do not rely on color alone (FR-017).

## 4. Shared components

- Status chip: one composable used on Home recent rows, Events rows, and detail header ([data-model.md](../data-model.md) §3).
- Amount string: one formatter ([research.md](../research.md) R4) used on list, detail, Home recent, and source test success (replace raw `amountMinor` in user-visible strings).
- Cards: same corner/elevation language on Home counts, event rows, source rows, settings groups.
- Destructive confirms: keep existing `AlertDialog` copy and buttons for clear-all, delete-selected, remove-source.

## 5. Copy

English only this pass. Prefer “Waiting to send”, “Couldn't send”, “Reachable”, “Not set up yet”, “Grant notification access” over “Bridge”, “QUEUED”, “FAILED”, raw package names as headlines.
