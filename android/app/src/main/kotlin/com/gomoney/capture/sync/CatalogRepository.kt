package com.gomoney.capture.sync

import com.gomoney.capture.storage.ServerAccount
import com.gomoney.capture.storage.ServerAccountDao
import com.gomoney.capture.storage.ServerCategory
import com.gomoney.capture.storage.ServerCategoryDao
import com.gomoney.capture.storage.ServerConfiguration
import java.time.OffsetDateTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Offline-tolerant cache of server accounts/categories (research R8, FR-014/016/
 * 021). Refresh replaces the cache atomically; a failed refresh keeps the last
 * known list and flags it unavailable so an existing binding is never silently
 * lost.
 */
class CatalogRepository(
    private val accountDao: ServerAccountDao,
    private val categoryDao: ServerCategoryDao,
    private val bridge: BridgeClient = BridgeClient(),
    private val clock: () -> OffsetDateTime = { OffsetDateTime.now() },
) {

    enum class Status { UNKNOWN, AVAILABLE, UNAVAILABLE }

    data class CatalogState(
        val accounts: Status = Status.UNKNOWN,
        val categories: Status = Status.UNKNOWN,
    )

    private val _state = MutableStateFlow(CatalogState())
    val state: StateFlow<CatalogState> = _state.asStateFlow()

    fun observeAccounts(): Flow<List<ServerAccount>> = accountDao.observeAll()

    fun observeCategories(): Flow<List<ServerCategory>> = categoryDao.observeAll()

    suspend fun accounts(): List<ServerAccount> = accountDao.all()

    suspend fun categories(): List<ServerCategory> = categoryDao.all()

    /** Refresh the account cache; returns false (and flags unavailable) on failure. */
    suspend fun refreshAccounts(config: ServerConfiguration): Boolean {
        val result = bridge.fetchAccounts(config)
        if (!result.ok) {
            _state.update { it.copy(accounts = Status.UNAVAILABLE) }
            return false
        }
        val now = clock().toString()
        accountDao.replaceAll(
            result.items.map {
                ServerAccount(
                    id = it.id,
                    label = it.label,
                    currency = it.currency,
                    type = it.type,
                    isDefault = it.isDefault,
                    refreshedAt = now,
                )
            },
        )
        _state.update { it.copy(accounts = Status.AVAILABLE) }
        return true
    }

    /** Refresh the category cache; returns false (and flags unavailable) on failure. */
    suspend fun refreshCategories(config: ServerConfiguration): Boolean {
        val result = bridge.fetchCategories(config)
        if (!result.ok) {
            _state.update { it.copy(categories = Status.UNAVAILABLE) }
            return false
        }
        val now = clock().toString()
        categoryDao.replaceAll(
            result.items.map { ServerCategory(id = it.id, label = it.label, refreshedAt = now) },
        )
        _state.update { it.copy(categories = Status.AVAILABLE) }
        return true
    }

    /** Instance convenience mirroring [Companion.isBindingStale]. */
    fun isBindingStale(boundAccountId: Int?, accounts: List<ServerAccount>): Boolean =
        Companion.isBindingStale(boundAccountId, accounts)

    companion object {
        /**
         * True when a source's bound account is no longer present in the
         * server list (deleted/renamed). A null binding is never stale
         * (spec edge case; data-model §1).
         */
        fun isBindingStale(boundAccountId: Int?, accounts: List<ServerAccount>): Boolean =
            boundAccountId != null && accounts.none { it.id == boundAccountId }
    }
}
