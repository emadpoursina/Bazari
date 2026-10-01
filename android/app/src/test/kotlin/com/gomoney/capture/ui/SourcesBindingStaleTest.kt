package com.gomoney.capture.ui

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gomoney.capture.R
import com.gomoney.capture.source.TransactionSourceRepository
import com.gomoney.capture.storage.AppDatabase
import com.gomoney.capture.storage.ServerAccount
import com.gomoney.capture.storage.SettingsRepository
import com.gomoney.capture.storage.TransactionSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stale source-account binding tests (T063; spec edge case "bound account
 * deleted/renamed", data-model §1): the Sources screen flags a source whose
 * `boundAccountId` is no longer present in the cached server list so the user is
 * prompted to choose again, and the prompt string resource is wired.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SourcesBindingStaleTest {

    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var sources: TransactionSourceRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        settings = SettingsRepository(context) { context.getSharedPreferences("test-secure", Context.MODE_PRIVATE) }
        sources = TransactionSourceRepository(db.transactionSourceDao(), db.parseErrorDao())
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun accountRow(id: Int) = ServerAccount(
        id = id,
        label = "Account $id",
        currency = "IRR",
        type = "expense",
        isDefault = false,
        refreshedAt = "2026-09-30T10:00:00+03:30",
    )

    private suspend fun addSource(id: String, boundAccountId: Int?) {
        sources.save(
            TransactionSource(
                id = id,
                name = "Source $id",
                identifier = "com.example.$id",
                channel = "notification",
                enabled = true,
                template = "خرید مبلغ {amount} ریال {direction}",
                incomeKeywords = "واریز",
                expenseKeywords = "خرید",
                boundAccountId = boundAccountId,
                boundAccountLabel = boundAccountId?.let { "Account $it" },
                createdAt = "2026-09-30T09:00:00+03:30",
                updatedAt = "2026-09-30T09:00:00+03:30",
            ),
        )
    }

    @Test
    fun `sources bound to an absent account are flagged stale, healthy and unbound are not`() = runTest {
        db.serverAccountDao().insertAll(listOf(accountRow(1), accountRow(2)))
        addSource("bound-ok", boundAccountId = 1)
        addSource("bound-gone", boundAccountId = 999)
        addSource("unbound", boundAccountId = null)

        val viewModel = SourcesViewModel(db, settings)
        val state = viewModel.state.first { it.sources.size == 3 }

        assertEquals(setOf("bound-gone"), state.staleBindingIds)
    }

    @Test
    fun `removing the bound account from the cached list flips the source to stale`() = runTest {
        db.serverAccountDao().insertAll(listOf(accountRow(7)))
        addSource("bound", boundAccountId = 7)

        val viewModel = SourcesViewModel(db, settings)

        val healthy = viewModel.state.first { it.sources.size == 1 }
        assertTrue(healthy.staleBindingIds.isEmpty())

        // Simulate a refresh that no longer returns account 7 (deleted/renamed).
        db.serverAccountDao().replaceAll(emptyList())

        val stale = viewModel.state.first { it.staleBindingIds.isNotEmpty() }
        assertEquals(setOf("bound"), stale.staleBindingIds)
    }

    @Test
    fun `the stale re-choose prompt resource is the wired non-blank message`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prompt = context.getString(R.string.sources_binding_stale)
        assertTrue(prompt.isNotBlank())
        assertTrue(prompt.contains("choose again", ignoreCase = true))
    }
}
