package com.gomoney.capture.source

import com.gomoney.capture.storage.ParseErrorDao
import com.gomoney.capture.storage.ParseErrorMessage
import java.time.OffsetDateTime

/**
 * Local retention for user-source parse failures (data-model.md §3, FR-028,
 * SC-010): newest-first review list, dismissible, pruned on insert to the last
 * 200 entries or 30 days, whichever comes first.
 */
class ParseErrorRepository(
    private val dao: ParseErrorDao,
    private val clock: () -> OffsetDateTime = { OffsetDateTime.now() },
) {

    fun observeAll() = dao.observeAll()

    suspend fun all(): List<ParseErrorMessage> = dao.all()

    suspend fun byId(id: String): ParseErrorMessage? = dao.byId(id)

    /** Insert and immediately enforce the retention cap (FR-028). */
    suspend fun insert(error: ParseErrorMessage) {
        dao.insert(error)
        prune()
    }

    /** Dismiss removes the entry immediately (SC-010). */
    suspend fun dismiss(id: String): Boolean = dao.dismiss(id) > 0

    /** Delete rows older than [RETENTION_DAYS], then everything beyond [MAX_ENTRIES]. */
    suspend fun prune(): Int {
        val cutoff = clock().minusDays(RETENTION_DAYS.toLong()).toString()
        val byAge = dao.deleteOlderThan(cutoff)
        val byCount = dao.deleteBeyond(MAX_ENTRIES)
        return byAge + byCount
    }

    companion object {
        const val MAX_ENTRIES = 200
        const val RETENTION_DAYS = 30
    }
}
