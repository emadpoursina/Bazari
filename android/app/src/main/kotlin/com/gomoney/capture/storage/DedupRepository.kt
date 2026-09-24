package com.gomoney.capture.storage

import com.gomoney.capture.storage.DedupCache
import com.gomoney.capture.storage.DedupCacheDao
import java.time.OffsetDateTime

/**
 * Local dedup short-circuit (US3, FR-015): before sending, check the cache;
 * after a terminal ack, record (fingerprint, outcome) so restarts don't
 * re-send known duplicates.
 */
class DedupRepository(
    private val dedupCacheDao: DedupCacheDao,
    private val clock: () -> OffsetDateTime = { OffsetDateTime.now() },
) {

    /**
     * Returns the recorded outcome when this fingerprint is already known
     * (terminal), so the sender can short-circuit locally.
     */
    suspend fun lookup(fingerprint: String): DedupCache? = dedupCacheDao.byFingerprint(fingerprint)

    /** Record a terminal outcome: sent | duplicate. */
    suspend fun record(fingerprint: String, outcome: String) {
        require(outcome == "sent" || outcome == "duplicate") { "outcome must be sent|duplicate" }
        dedupCacheDao.insert(DedupCache(fingerprint, clock().toString(), outcome))
    }
}
