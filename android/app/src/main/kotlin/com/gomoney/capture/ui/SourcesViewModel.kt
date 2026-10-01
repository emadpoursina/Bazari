package com.gomoney.capture.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gomoney.capture.source.MatchResult
import com.gomoney.capture.source.ParseErrorRepository
import com.gomoney.capture.source.SaveResult
import com.gomoney.capture.source.TemplateMatcher
import com.gomoney.capture.source.TransactionSourceRepository
import com.gomoney.capture.source.ValidationError
import com.gomoney.capture.storage.AppDatabase
import com.gomoney.capture.storage.ParseErrorMessage
import com.gomoney.capture.storage.ServerAccount
import com.gomoney.capture.storage.ServerConfiguration
import com.gomoney.capture.storage.SettingsRepository
import com.gomoney.capture.storage.TransactionSource
import com.gomoney.capture.storage.keywordList
import com.gomoney.capture.sync.CatalogRepository
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Backs the Sources screen (US1/US4): the source list, the parse-error review
 * list, and enable/remove actions.
 */
class SourcesViewModel(
    db: AppDatabase,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val sourceRepository = TransactionSourceRepository(db.transactionSourceDao(), db.parseErrorDao())
    private val parseErrorRepository = ParseErrorRepository(db.parseErrorDao())
    private val catalog = CatalogRepository(db.serverAccountDao(), db.serverCategoryDao())

    data class SourcesState(
        val sources: List<TransactionSource> = emptyList(),
        val parseErrors: List<ParseErrorMessage> = emptyList(),
        /** Source ids whose bound account is no longer in the server list. */
        val staleBindingIds: Set<String> = emptySet(),
    )

    val state: StateFlow<SourcesState> = combine(
        sourceRepository.observeAll(),
        parseErrorRepository.observeAll(),
        catalog.observeAccounts(),
    ) { sources, errors, accounts ->
        SourcesState(
            sources = sources,
            parseErrors = errors,
            staleBindingIds = sources
                .filter { CatalogRepository.isBindingStale(it.boundAccountId, accounts) }
                .map { it.id }
                .toSet(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SourcesState())

    suspend fun setEnabled(id: String, enabled: Boolean): Boolean = sourceRepository.setEnabled(id, enabled)

    suspend fun remove(id: String): Boolean = sourceRepository.remove(id)

    suspend fun dismissParseError(id: String): Boolean = parseErrorRepository.dismiss(id)

    suspend fun currentConfig(): ServerConfiguration = settings.current()
}

/**
 * Backs the source editor (US1/US2): draft validation, the optional offline
 * Test preview (FR-027), and the server-account selector backed by the catalog
 * cache (FR-013/014/016).
 */
class SourceEditorViewModel(
    db: AppDatabase,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val sourceRepository = TransactionSourceRepository(db.transactionSourceDao(), db.parseErrorDao())
    private val catalog = CatalogRepository(db.serverAccountDao(), db.serverCategoryDao())

    private val _accounts = MutableStateFlow<List<ServerAccount>>(emptyList())
    val accounts: StateFlow<List<ServerAccount>> = _accounts.asStateFlow()

    private val _errors = MutableStateFlow<List<ValidationError>>(emptyList())
    val errors: StateFlow<List<ValidationError>> = _errors.asStateFlow()

    private val _testResult = MutableStateFlow<MatchResult?>(null)
    val testResult: StateFlow<MatchResult?> = _testResult.asStateFlow()

    val catalogState: StateFlow<CatalogRepository.CatalogState> = catalog.state

    /** Load the last known account list so the selector works offline (FR-016). */
    fun loadCachedAccounts() {
        viewModelScope.launch { _accounts.value = catalog.accounts() }
    }

    /** Refresh the account list from the server; a failure keeps the cache. */
    fun refreshAccounts() {
        viewModelScope.launch {
            catalog.refreshAccounts(settings.current())
            _accounts.value = catalog.accounts()
        }
    }

    fun newSource(): TransactionSource = TransactionSource(
        id = UUID.randomUUID().toString(),
        name = "",
        identifier = "",
        channel = "notification",
        enabled = true,
        template = "",
        incomeKeywords = "",
        expenseKeywords = "",
        createdAt = OffsetDateTime.now().toString(),
        updatedAt = OffsetDateTime.now().toString(),
    )

    suspend fun load(id: String): TransactionSource? = sourceRepository.byId(id)

    /** Optional offline template preview; saving without testing is allowed (FR-027). */
    fun test(template: String, message: String, incomeKeywords: String, expenseKeywords: String) {
        _testResult.value = TemplateMatcher.preview(
            template = template,
            message = message,
            incomeKeywords = incomeKeywords.keywordList(),
            expenseKeywords = expenseKeywords.keywordList(),
        )
    }

    fun clearTest() {
        _testResult.value = null
    }

    suspend fun save(source: TransactionSource): SaveResult {
        val result = sourceRepository.save(source)
        _errors.value = (result as? SaveResult.Invalid)?.errors ?: emptyList()
        return result
    }

    suspend fun config(): ServerConfiguration = settings.current()
}
