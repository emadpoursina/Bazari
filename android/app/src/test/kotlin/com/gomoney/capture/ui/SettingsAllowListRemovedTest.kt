package com.gomoney.capture.ui

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.gomoney.capture.storage.ServerConfiguration
import com.gomoney.capture.storage.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 005 T024 / FR-012, SC-003: Settings has NO bank/package/sender allow-list —
 * no add/remove identifier affordance exists anywhere in the settings surface,
 * and the legacy DataStore set is deleted on settings load (FR-015).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsAllowListRemovedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `SettingsViewModel exposes no allow-list add or remove actions`() {
        val methodNames = SettingsViewModel::class.java.declaredMethods.map { it.name }

        assertFalse(methodNames.any { it.contains("addPackage", ignoreCase = true) })
        assertFalse(methodNames.any { it.contains("removePackage", ignoreCase = true) })
        assertFalse(methodNames.any { it.contains("BankPackage", ignoreCase = true) })
        assertFalse(methodNames.any { it.contains("AllowList", ignoreCase = true) })
    }

    @Test
    fun `ServerConfiguration has no allow-list field`() {
        val fields = ServerConfiguration::class.java.declaredFields.map { it.name }

        assertFalse(fields.any { it.contains("enabledBankPackages") })
        assertFalse(fields.any { it.contains("bankPackages") })
        assertFalse(fields.any { it.contains("allowList") })
    }

    /** FR-015: a leftover `enabled_bank_packages` DataStore set is deleted on settings load. */
    @Test
    fun `legacy allow-list DataStore value is cleared on settings load`() = runTest {
        val settings = SettingsRepository(context) {
            context.getSharedPreferences("test-allow-list", Context.MODE_PRIVATE)
        }

        // Plant a leftover allow-list the way an older build would have.
        SettingsRepository.dataStoreFor(context).edit { prefs ->
            prefs[stringSetPreferencesKey("enabled_bank_packages")] = setOf("com.samanpr.blu", "ir.mellat.mellatab")
        }

        val config = settings.current()

        // The legacy key is gone from the store...
        assertNull(SettingsRepository.dataStoreFor(context).data.first()[stringSetPreferencesKey("enabled_bank_packages")])
        // ...and there is no allow-list field to even surface it.
        assertFalse(
            ServerConfiguration::class.java.declaredFields.any { it.name == "enabledBankPackages" },
        )
        assertEquals("", config.serverUrl) // sanity: a normal load still works
    }
}
