# Contract: Navigation chrome

**Feature**: 010-android-ui-refresh | **Date**: 2026-11-17

Replaces the text-only `TabRow` in `MainActivity` (`listOf("Dashboard", "Events", "Sources", "Settings")`). Nested routing flags stay.

## 1. Bottom destinations

On every top-level screen (and while a nested screen is open), a Material 3 `NavigationBar` shows four items, **icon + short English label**:

| Index | Label | Opens |
|---|---|---|
| 0 | Home | `DashboardScreen` (Home copy) |
| 1 | Events | `EventsScreen` or `EventDetailScreen` if `selectedTxId != null` |
| 2 | Sources | `SourcesScreen` or `SourceEditorScreen` if editing |
| 3 | Settings | `SettingsScreen` |

The selected item is visually obvious (`selected` on `NavigationBarItem`).

## 2. Nested screens

- Event detail and source editor show a **Back** control that clears the nested flag and returns to the parent list.
- Switching the bottom destination away from Events sets `selectedTxId = null` and `missingEventMessage = null` (existing `LaunchedEffect(selectedTab)`).
- Switching away from Sources sets `editingSource = null`.
- Notification-open routing outcomes unchanged (`NotificationOpenRouting`): detail vs Events list + dismissible missing-event message. Only the banner chrome may restyle.

## 3. Invariants

- No fifth destination. No onboarding wizard route.
- Settings stays in the bar (spec override of Material “settings is not a tab” guidance).
- ScanCoordinator `eventsTabVisible` remains `selectedTab == 1`.
