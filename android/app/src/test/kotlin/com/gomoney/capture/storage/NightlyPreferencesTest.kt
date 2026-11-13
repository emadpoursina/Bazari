package com.gomoney.capture.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NightlyPreferencesTest {

    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        settings = SettingsRepository(context) {
            context.getSharedPreferences("test-nightly-prefs", Context.MODE_PRIVATE)
        }
        runBlocking {
            settings.clearNightlyPreferences()
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            settings.clearNightlyPreferences()
        }
    }

    @Test
    fun `keys absent yield null nightly preference fields`() = runTest {
        val current = settings.currentNightlyPreferences()
        assertNull(current.lastCheckAt)
        assertNull(current.lastEvaluatedLocalDate)
        assertNull(settings.getNightlyLastCheckAt())
        assertNull(settings.getNightlyLastEvaluatedLocalDate())
        assertEquals(current, settings.observeNightlyPreferences().first())
    }

    @Test
    fun `set nightly evaluation round-trips through observe and current`() = runTest {
        val instant = "2026-11-12T21:00:00Z"
        val date = "2026-11-12"

        settings.setNightlyEvaluation(instant, date)

        val current = settings.currentNightlyPreferences()
        assertEquals(instant, current.lastCheckAt)
        assertEquals(date, current.lastEvaluatedLocalDate)
        assertEquals(instant, settings.getNightlyLastCheckAt())
        assertEquals(date, settings.getNightlyLastEvaluatedLocalDate())
        assertEquals(
            NightlyPreferences(lastCheckAt = instant, lastEvaluatedLocalDate = date),
            settings.observeNightlyPreferences().first(),
        )
    }

    @Test
    fun `setting individual nightly keys works independently`() = runTest {
        val instant = "2026-11-12T21:00:00Z"
        val date = "2026-11-12"

        settings.setNightlyLastCheckAt(instant)
        assertEquals(instant, settings.getNightlyLastCheckAt())
        assertNull(settings.getNightlyLastEvaluatedLocalDate())

        settings.setNightlyLastEvaluatedLocalDate(date)
        assertEquals(instant, settings.getNightlyLastCheckAt())
        assertEquals(date, settings.getNightlyLastEvaluatedLocalDate())
    }

    @Test
    fun `clear nightly preferences restores absent state`() = runTest {
        settings.setNightlyEvaluation("2026-11-12T21:00:00Z", "2026-11-12")
        settings.clearNightlyPreferences()

        val current = settings.currentNightlyPreferences()
        assertNull(current.lastCheckAt)
        assertNull(current.lastEvaluatedLocalDate)
    }

    @Test
    fun `nightly preferences persist across repository instances`() = runTest {
        val instant = "2026-11-12T21:00:00Z"
        val date = "2026-11-12"
        settings.setNightlyEvaluation(instant, date)

        val context = ApplicationProvider.getApplicationContext<Context>()
        val reopened = SettingsRepository(context) {
            context.getSharedPreferences("test-nightly-prefs", Context.MODE_PRIVATE)
        }
        assertEquals(
            NightlyPreferences(lastCheckAt = instant, lastEvaluatedLocalDate = date),
            reopened.currentNightlyPreferences(),
        )
    }
}
