package com.gomoney.capture.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 008 T003 / FR-005/006/009 (contracts/defaults-stamping.md §1): the two
 * default-preference DataStore keys — key absent = null field, set/clear/
 * observe round-trip, and clearing one default leaves the other intact.
 *
 * Per-device, local-only, never synced (spec Assumption).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultPreferencesTest {

    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Plain SharedPreferences token store — avoids EncryptedSharedPreferences
        // on the JVM (same pattern as CapturePipelineTest).
        settings = SettingsRepository(context) {
            context.getSharedPreferences("test-defaults", Context.MODE_PRIVATE)
        }
        // The DataStore file is process-wide; start every test from "no defaults".
        kotlinx.coroutines.runBlocking {
            settings.clearDefaultCategory()
            settings.clearDefaultAccount()
        }
    }

    @After
    fun tearDown() {
        // Never leak defaults into other suites sharing the same DataStore file.
        kotlinx.coroutines.runBlocking {
            settings.clearDefaultCategory()
            settings.clearDefaultAccount()
        }
    }

    /** Key absent → both fields null (data-model §1 "Absent means no default"). */
    @Test
    fun `key absent yields null default fields`() = runTest {
        val current = settings.currentDefaults()
        assertNull(current.defaultCategoryId)
        assertNull(current.defaultAccountId)
        assertEquals(current, settings.observeDefaults().first())
    }

    /** Set → observe → one-shot read round-trips both keys (FR-005/FR-006). */
    @Test
    fun `set defaults round-trips through observe and current`() = runTest {
        settings.setDefaultCategoryId(42)
        settings.setDefaultAccountId(9)

        assertEquals(42, settings.currentDefaults().defaultCategoryId)
        assertEquals(9, settings.currentDefaults().defaultAccountId)
        assertEquals(
            DefaultPreferences(defaultCategoryId = 42, defaultAccountId = 9),
            settings.observeDefaults().first(),
        )
    }

    /** Setting one default leaves the other key untouched. */
    @Test
    fun `setting one default does not disturb the other`() = runTest {
        settings.setDefaultAccountId(9)
        settings.setDefaultCategoryId(42)

        assertEquals(42, settings.currentDefaults().defaultCategoryId)
        assertEquals(9, settings.currentDefaults().defaultAccountId)

        settings.setDefaultCategoryId(7)
        assertEquals(7, settings.currentDefaults().defaultCategoryId)
        assertEquals(9, settings.currentDefaults().defaultAccountId)
    }

    /** FR-009: per-key remove() — clearing one default leaves the other intact. */
    @Test
    fun `clearing one default leaves the other intact`() = runTest {
        settings.setDefaultCategoryId(42)
        settings.setDefaultAccountId(9)

        settings.clearDefaultCategory()
        val afterCategoryClear = settings.currentDefaults()
        assertNull(afterCategoryClear.defaultCategoryId)
        assertEquals(9, afterCategoryClear.defaultAccountId)

        settings.setDefaultCategoryId(42)
        settings.clearDefaultAccount()
        val afterAccountClear = settings.currentDefaults()
        assertEquals(42, afterAccountClear.defaultCategoryId)
        assertNull(afterAccountClear.defaultAccountId)
    }

    /** Clear both → back to the key-absent state. */
    @Test
    fun `clearing both defaults restores the key-absent state`() = runTest {
        settings.setDefaultCategoryId(42)
        settings.setDefaultAccountId(9)

        settings.clearDefaultCategory()
        settings.clearDefaultAccount()

        assertEquals(DefaultPreferences(null, null), settings.currentDefaults())
        assertEquals(DefaultPreferences(null, null), settings.observeDefaults().first())
    }

    /** Persists across a repository re-read (survives restarts — FR-005/006). */
    @Test
    fun `defaults persist across a new repository instance`() = runTest {
        settings.setDefaultCategoryId(42)
        settings.setDefaultAccountId(9)

        val context = ApplicationProvider.getApplicationContext<Context>()
        val reopened = SettingsRepository(context) {
            context.getSharedPreferences("test-defaults", Context.MODE_PRIVATE)
        }
        assertEquals(
            DefaultPreferences(defaultCategoryId = 42, defaultAccountId = 9),
            reopened.currentDefaults(),
        )
    }
}
