# Research: Events Selection + Selective Sync/Delete

**Date**: 2026-10-14 | **Feature**: 007-events-selection-sync

## R1: Current top bar layout

- `EventsScreen.kt` action row is a `Row` with `horizontalScroll` + 3 `OutlinedButton`s (Clear processed / Clear all / Scan notifications). FR-001 of 006 required fit-without-clipping via scroll; with 7 buttons scrolling becomes unusable → switch to wrapping layout.
- **Decision**: `FlowRow` (`androidx.compose.foundation.layout.FlowRow`, `ExperimentalLayoutApi`) with `horizontalArrangement = spacedBy(8.dp)`, compact `ButtonDefaults` sizing, small labels. No horizontal scroll modifier.

## R2: Selection state ownership

- `EventsScreen` is a stateless composable fed by `EventsViewModel.EventsState` + callbacks from `MainActivity`. Selection is transient UI state.
- **Decision**: `remember { mutableStateOf(setOf<String>()) }` inside `EventsScreen`, keyed by `txId`; prune each composition (`selectedIds intersect rows.map(txId)`). Select All sets all row IDs; Clear empties. Counter derived.

## R3: Delete-selected storage path

- `RawEvent` is parent of `NormalizedTransaction`/`DeliveryRecord`/`ParseErrorMessage` via `CASCADE` FKs. `deleteAll()` proves cascade works.
- **Decision**: `RawEventDao.deleteByIds(ids: List<String>)` → `DELETE FROM raw_events WHERE id IN (:ids)`; `MaintenanceRepository.deleteEvents(ids: Set<String>): Int` early-returns 0 on empty, else DAO call. Parse-error rows share the same raw-event ID space (delivery id == raw id), so one path covers both.

## R4: Sync-selected scope

- `SyncEngine.drain()` loops `pendingForDelivery()` (all queued/failed) — expedited worker always drains everything. A pure `enqueueExpedited` cannot be scoped.
- **Decision**: `SyncEngine.drainIds(db, configs, deliveryRepo, dedupRepo, ids: Set<String>)` — same per-item logic as `drain()` (auto-retry only within set, SENDING transition, single/bulk send, memo+assignment sync limited to selected txs). `EventActions.syncSelected(ids)` requeues selected FAILED → QUEUED, calls `drainIds` with current configs when non-empty, then `enqueueExpedited` as a follow-up for memos. Unselected rows are never read for requeue nor send.

## R5: Confirmation + disabled states

- Existing Clear-all uses `AlertDialog` + `showClearAllDialog` boolean.
- **Decision**: Mirror with `showDeleteSelectedDialog`; Sync/Delete-selected buttons `enabled = selectedIds.isNotEmpty()`. Labels carry counts: `Sync selected (N)`, `Delete selected (N)`.
