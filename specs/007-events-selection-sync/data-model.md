# Data Model: 007-events-selection-sync

No schema change. No version bump.

## Selection (transient UI, not stored)

- `selectedIds: Set<String>` — stable event IDs (`EventRow.txId` == `raw_events.id` == `delivery_records.id`).
- Derived: `selectedCount = selectedIds.size`, `totalCount = rows.size`.
- Invariant: `selectedIds ⊆ rows.map(txId)` (pruned on each recomposition).

## Storage delta

- `RawEventDao.deleteByIds(ids: List<String>): Int` — `DELETE FROM raw_events WHERE id IN (:ids)`; cascade removes child rows.
- `MaintenanceRepository.deleteEvents(ids: Set<String>): Int` — 0 when empty, else `rawEventDao.deleteByIds(ids.toList())`.

## Sync delta

- `SyncEngine.drainIds(db, configs, deliveryRepository, dedupRepository, ids: Set<String>)` — drains only `pendingForDelivery().filter { it.id in ids }`; auto-retry, SENDING, bulk/single send, memo + assignment sync all filtered to `ids`. Empty set → no-op.
