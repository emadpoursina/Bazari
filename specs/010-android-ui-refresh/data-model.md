# Data Model: Android Capture App UI Refresh

**Date**: 2026-11-17 | **Feature**: 010-android-ui-refresh

No Room schema version bump. This feature adds **presentation types** only. Persistence stays as in 001/005/007/008/009.

## 1. Destination (UI)

Not stored. Top-level places in `MainActivity`:

| Index | Label | Nested |
|---|---|---|
| 0 | Home | none |
| 1 | Events | Event detail (`selectedTxId`) |
| 2 | Sources | Source editor (`editingSource`) |
| 3 | Settings | none (endpoint forms inline) |

**Validation**: Bottom bar always shows these four on top-level screens (and remains visible on nested screens so switching destinations still closes nested state). Leaving Events clears `selectedTxId`, missing-event message, and Events selection. Leaving Sources clears the editor.

## 2. Captured event (existing Room + display)

Source of truth: `normalized_transactions` + `delivery_records` (+ parse-error deliveries without a normalized row).

| Field | Location | Display use |
|---|---|---|
| `id` | both | navigation, selection keys |
| `amountMinor` | normalized | grouped number; never rewritten |
| `currency` | normalized | suffix when non-blank |
| `type` | normalized | Income / Expense + color/sign |
| `bank` | normalized | source name on list/detail |
| `txAt` | normalized | time + day group key |
| `description` / `userMemo` | normalized | list preview; detail note |
| `destinationAccountId` / `categoryId` | normalized | assignment pickers |
| `assignmentSyncState` / `memoSyncState` | normalized | pending-sync hints |
| `parserName` / `confidence` | normalized | **debug only** |
| `state` | delivery | maps to status chip |
| `errorCategory` / `errorDetail` | delivery | sanitized; parse-problem chip |

**Validation (unchanged)**: memo max 200; currency edit only `held`/`queued`/`failed`; assignment save independent of currency.

## 3. Status chip (derived)

Not stored. Shared mapper `StatusPresentation`.

| Chip label | Tone | When |
|---|---|---|
| Sent | success | `state == sent` |
| Waiting | warning | `queued`, `sending`, `held`, `captured` |
| Couldn't send | error | `failed` |
| Parse problem | error | delivery without normalized tx, or parse `errorCategory` |

Default screens must not show raw enum names.

## 4. Health state (derived, Home)

Not stored. Built in `DashboardViewModel` from existing flows + permission.

| Field | Source |
|---|---|
| connection: reachable / not reachable / not set up | `ConnectionStatus` + blank `serverUrl` |
| captureBlocked | notification listener not granted (and notification capture intended) |
| capturedToday | existing count |
| waiting | pending deliveries that are not failed |
| failed | existing failed count |
| recent | first 5 of observed recent events as list rows |
| nextStep | exactly one of: grant notification access, configure destination, open Events, or none (healthy) |

**State**: `blocked → one CTA` | `healthy → calm summary`. No spend totals.

## 5. Source (existing)

`transaction_sources` unchanged. List display: name, channel, identifier, bound account or “No account bound”, enabled, stale-binding warning (`staleBindingIds`). Unparsed messages remain `ParseErrorMessage` rows in a **separate** section.

## 6. Connection destination (existing)

`BridgeEndpoint` list in DataStore/settings unchanged: order, test, edit, delete, secret handling. Settings UI only regroups cards.

## 7. Selection session (ephemeral)

Compose `selectedIds: Set<String>` on Events. Not persisted.

**Transitions**: empty → selecting (long-press or overflow Select) → actions enabled → leave Events or Clear selection → empty. Prune IDs not in the live list (already implemented). Destructive actions still go through existing dialogs.

## 8. Theme preference

No new DataStore key. Follow system `uiMode` night bit via `isSystemInDarkTheme()` and DayNight application theme.

## Relationships

```text
Destination (Home | Events | Sources | Settings)
  Home → HealthState → recent Captured events
  Events → [day] → EventRow → Status chip
       ↳ Event detail (assignment + note) ↳ DebugEventScreen if debug
  Sources → TransactionSource
         ↳ ParseErrorMessage (review section)
  Settings → BridgeEndpoint + defaults + permissions + debug
```

## Validation rules (UI-only)

- Empty Events/Sources: headline + one sentence + one primary action; bottom bar still visible.
- Long amounts/names: wrap or ellipsize; no overflow off the row.
- Unknown currency: number only.
- RTL-capable: no `Left`/`Right` layout that would clip on 360 dp.
