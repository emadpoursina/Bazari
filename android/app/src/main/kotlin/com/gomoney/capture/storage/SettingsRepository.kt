package com.gomoney.capture.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** ServerConfiguration (data-model.md §6). */
data class ServerConfiguration(
    val serverUrl: String = "",
    val bearerToken: String = "",
    val notificationCaptureEnabled: Boolean = true,
    val smsCaptureEnabled: Boolean = false,
    val enabledBankPackages: Set<String> = emptySet(),
    val debugModeEnabled: Boolean = false,
)

/**
 * Settings repository: toggles/URL/allow-list in Preferences DataStore, the
 * bearer token in EncryptedSharedPreferences (FR-006: no bank credentials
 * stored anywhere; only the user's own bridge token, encrypted at rest).
 */
class SettingsRepository(
    private val context: Context,
    private val prefsProvider: () -> SharedPreferences = { defaultEncryptedPrefs(context) },
) {
    private val prefs: SharedPreferences by lazy { prefsProvider() }

    private val dataStore: DataStore<Preferences> =
        SettingsRepository.dataStoreFor(context)

    private val serverUrlKey = stringPreferencesKey("server_url")
    private val notificationKey = booleanPreferencesKey("notification_capture_enabled")
    private val smsKey = booleanPreferencesKey("sms_capture_enabled")
    private val packagesKey = stringSetPreferencesKey("enabled_bank_packages")
    private val debugKey = booleanPreferencesKey("debug_mode_enabled")

    private val tokenKey = "bridge_bearer_token"

    fun observe(): Flow<ServerConfiguration> = dataStore.data.map { prefs ->
        ServerConfiguration(
            serverUrl = prefs[serverUrlKey] ?: "",
            bearerToken = this@SettingsRepository.prefs.getString(tokenKey, "") ?: "",
            notificationCaptureEnabled = prefs[notificationKey] ?: true,
            smsCaptureEnabled = prefs[smsKey] ?: false,
            enabledBankPackages = prefs[packagesKey] ?: emptySet(),
            debugModeEnabled = prefs[debugKey] ?: false,
        )
    }

    suspend fun current(): ServerConfiguration = observe().first()

    suspend fun setServerUrl(url: String) = edit { it[serverUrlKey] = url }

    suspend fun setBearerToken(token: String) {
        prefs.edit().putString(tokenKey, token).apply()
    }

    suspend fun setNotificationCaptureEnabled(enabled: Boolean) = edit { it[notificationKey] = enabled }

    suspend fun setSmsCaptureEnabled(enabled: Boolean) = edit { it[smsKey] = enabled }

    suspend fun setEnabledBankPackages(packages: Set<String>) = edit { it[packagesKey] = packages }

    suspend fun setDebugModeEnabled(enabled: Boolean) = edit { it[debugKey] = enabled }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        dataStore.edit { block(it) }
    }

    companion object {
        // One DataStore instance per backing file (process-wide): creating a
        // new DataStore for the same file from a second SettingsRepository
        // instance throws "multiple DataStores active for the same file".
        private val dataStores = mutableMapOf<String, DataStore<Preferences>>()

        @Synchronized
        fun dataStoreFor(context: Context): DataStore<Preferences> {
            val file = context.applicationContext.preferencesDataStoreFile("server_config")
            return dataStores.getOrPut(file.toString()) {
                PreferenceDataStoreFactory.create { file }
            }
        }

        fun defaultEncryptedPrefs(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                "gomoney_capture_secure",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }
}
