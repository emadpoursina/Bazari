# Contract: Home (capture health)

**Feature**: 010-android-ui-refresh | **Date**: 2026-11-17

`DashboardScreen` is the Home destination. It MUST NOT show period spend totals, charts, budget widgets, or a spending-overview dashboard (FR-020, US1.5).

## 1. Always visible

- Connection in everyday language: **Reachable** | **Not reachable** | **Not set up yet** (blank URL). No endpoint URL in the headline; URLs stay in Settings.
- **Captured today** count.
- **Waiting to send** count (pending minus failed).
- **Couldn't send** count (failed).
- Short **recent** list (up to 5): formatted amount, source, time, status chip. Row tap opens that event’s detail (switch to Events tab + `selectedTxId`).

## 2. Healthy vs blocked

**Healthy** (listener granted, destination configured, connection reachable or at least configured and not blocked): calm surfaces, no error styling. “Sync now” is optional/secondary when the existing `SyncBanner` is not `ALL_CLEAR`; it is not the Home headline.

**Blocked** — exactly **one** primary next step:

| Condition (priority order) | Primary action |
|---|---|
| Notification access missing | Control that opens system notification-listener settings (same intent as Events scan-blocked) |
| No destination URL / not set up | Control that switches to Settings |
| Else queue needs attention | Control that switches to Events |

Do not show only a technical sentence without a control (FR-004).

## 3. Data

Reuse `DashboardViewModel` counts and `BridgePing` connection. Expose permission + recent rows; do not add spend aggregations.

## 4. Privacy

No raw notification/SMS body. Sanitized banner/error detail only if already shown today via `SyncBanner.message` — restyle that message to drop internal category enums where practical without changing halt semantics.
