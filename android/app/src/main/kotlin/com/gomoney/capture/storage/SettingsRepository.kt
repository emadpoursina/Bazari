package com.gomoney.capture.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/**
 * ServerConfiguration (data-model.md §6).
 *
 * 005 FR-015: there is no bank allow-list any more. The legacy
 * `enabled_bank_packages` DataStore value is deleted on settings load and
 * unread; capture identifiers are sources only.
 *
 * Multiple-endpoint fallback: [serverUrl]/[bearerToken] is the FIRST
 * reachable endpoint (lowest priority) when endpoints exist, else the
 * legacy single URL. Sync/ping callers that need fallback must use
 * [SettingsRepository.currentConfigs] + `BridgeClient.*WithFallback`
 * instead of this single value.
 */
data class ServerConfiguration(
    val serverUrl: String = "",
    val bearerToken: String = "",
    val notificationCaptureEnabled: Boolean = true,
    val smsCaptureEnabled: Boolean = false,
    val debugModeEnabled: Boolean = false,
)

/**
 * A single bridge connection endpoint (multiple-endpoint fallback).
 *
 * The phone tries enabled endpoints in [priority] order (lowest first —
 * e.g. Tailscale first, then LAN, then the rest) and uses the first
 * reachable one for all ping/sync calls in that run. Users CRUD these
 * themselves in Settings; there are no built-in presets.
 */
data class BridgeEndpoint(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "",
    val url: String = "",
    val bearerToken: String = "",
    val enabled: Boolean = true,
    val priority: Int = 0,
)

/**
 * 008 FR-005/FR-006 (data-model §1, contracts/defaults-stamping.md §1):
 * the user's default category / default destination account for NEW
 * captures. Per-device, local-only, never synced (spec Assumption);
 * a null field means "no default" (key absent).
 */
data class DefaultPreferences(
    val defaultCategoryId: Int?,
    val defaultAccountId: Int?,
)

/**
 * Settings repository: toggles/URL/allow-list in Preferences DataStore, the
 * bearer token in EncryptedSharedPreferences (FR-006: no bank credentials
 * stored anywhere; only the user's own bridge token, encrypted at rest).
 *
 * Multiple endpoints: metadata (id/name/url/enabled/priority) lives as JSON
 * in DataStore under [endpointsKey]; each endpoint's token lives in the
 * encrypted prefs under `bridge_token_<id>` so tokens stay encrypted at
 * rest. The legacy single `server_url` + `bridge_bearer_token` is migrated
 * to one "Default" endpoint on first load and kept in sync for rollback.
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

    /**
     * 005 FR-015: the former allow-list key. Kept only so [clearLegacyAllowList]
     * can delete it; it is never read and [setEnabledBankPackages] is gone.
     */
    private val legacyPackagesKey = stringSetPreferencesKey("enabled_bank_packages")
    private val debugKey = booleanPreferencesKey("debug_mode_enabled")
    private val endpointsKey = stringPreferencesKey("bridge_endpoints_json")
    // 008 FR-005/006: default category/account for new captures (R3).
    private val defaultCategoryIdKey = intPreferencesKey("default_category_id")
    private val defaultAccountIdKey = intPreferencesKey("default_account_id")

    private val tokenKey = "bridge_bearer_token"

    private fun tokenKeyFor(id: String) = "bridge_token_$id"

    fun observe(): Flow<ServerConfiguration> = dataStore.data.map { stored ->
        val endpoints = readEndpointsLocked(stored)
        val first = endpoints.filter { it.enabled }.minByOrNull { it.priority }
        ServerConfiguration(
            serverUrl = first?.url ?: (stored[serverUrlKey] ?: ""),
            bearerToken = first?.bearerToken
                ?: (this@SettingsRepository.prefs.getString(tokenKey, "") ?: ""),
            notificationCaptureEnabled = stored[notificationKey] ?: true,
            smsCaptureEnabled = stored[smsKey] ?: false,
            debugModeEnabled = stored[debugKey] ?: false,
        )
    }

    /** All endpoints sorted by priority (lowest first), for the Settings CRUD list. */
    fun observeEndpoints(): Flow<List<BridgeEndpoint>> = dataStore.data.map { stored ->
        readEndpointsLocked(stored).sortedBy { it.priority }
    }

    /** Enabled endpoints sorted by priority — the order ping/sync tries them. */
    fun observeEnabledEndpoints(): Flow<List<BridgeEndpoint>> = observeEndpoints().map { list ->
        list.filter { it.enabled }
    }

    /**
     * Settings load (005 FR-015/T019): delete the leftover
     * `enabled_bank_packages` set so it has no effect on capture and is not
     * migrated into sources. Never read again.
     */
    suspend fun clearLegacyAllowList() {
        runCatching {
            dataStore.edit { prefs ->
                if (prefs[legacyPackagesKey] != null) prefs.remove(legacyPackagesKey)
            }
        }
    }

    suspend fun current(): ServerConfiguration {
        clearLegacyAllowList()
        migrateLegacyIfNeeded()
        return observe().first()
    }

    /** Enabled endpoints in try-order; migrates the legacy single URL first. */
    suspend fun currentEndpoints(): List<BridgeEndpoint> {
        clearLegacyAllowList()
        migrateLegacyIfNeeded()
        return observeEnabledEndpoints().first()
    }

    /** All endpoints in try-order (including disabled), for Settings UI. */
    suspend fun allEndpoints(): List<BridgeEndpoint> {
        migrateLegacyIfNeeded()
        return observeEndpoints().first()
    }

    /**
     * Enabled endpoints mapped to [ServerConfiguration] (shared toggles +
     * per-endpoint url/token) in try-order. Pass to
     * `BridgeClient.*WithFallback` for ping/sync.
     */
    suspend fun currentConfigs(): List<ServerConfiguration> {
        val base = current()
        return currentEndpoints().map { endpoint ->
            base.copy(serverUrl = endpoint.url, bearerToken = endpoint.bearerToken)
        }
    }

    // --- CRUD ---

    /** Add a user-defined endpoint; appended last (lowest precedence). */
    suspend fun addEndpoint(name: String, url: String, token: String): BridgeEndpoint {
        val existing = allEndpoints()
        val endpoint = BridgeEndpoint(
            id = java.util.UUID.randomUUID().toString(),
            name = name.trim(),
            url = url.trim().trimEnd('/'),
            bearerToken = token,
            enabled = true,
            priority = (existing.maxOfOrNull { it.priority } ?: -1) + 1,
        )
        persistEndpoints(existing + endpoint)
        return endpoint
    }

    /** Update name/url/token/enabled; null token leaves the stored token untouched. */
    suspend fun updateEndpoint(
        id: String,
        name: String,
        url: String,
        token: String?,
        enabled: Boolean,
    ): BridgeEndpoint? {
        val existing = allEndpoints()
        val current = existing.firstOrNull { it.id == id } ?: return null
        val updated = current.copy(
            name = name.trim(),
            url = url.trim().trimEnd('/'),
            bearerToken = token ?: current.bearerToken,
            enabled = enabled,
        )
        persistEndpoints(existing.map { if (it.id == id) updated else it })
        return updated
    }

    suspend fun deleteEndpoint(id: String): Boolean {
        val existing = allEndpoints()
        if (existing.none { it.id == id }) return false
        persistEndpoints(existing.filterNot { it.id == id })
        runCatching { prefs.edit().remove(tokenKeyFor(id)).apply() }
        return true
    }

    suspend fun setEndpointEnabled(id: String, enabled: Boolean): Boolean {
        val existing = allEndpoints()
        val current = existing.firstOrNull { it.id == id } ?: return false
        if (current.enabled == enabled) return true
        persistEndpoints(existing.map { if (it.id == id) it.copy(enabled = enabled) else it })
        return true
    }

    /** Reorder by id list (index 0 = tried first). Unknown ids are ignored. */
    suspend fun reorderEndpoints(idsInOrder: List<String>) {
        val existing = allEndpoints()
        val byId = existing.associateBy { it.id }
        val ordered = idsInOrder.mapNotNull { byId[it] }
        if (ordered.isEmpty()) return
        val orderedIds = ordered.map { it.id }.toSet()
        val rest = existing.filterNot { it.id in orderedIds }
        persistEndpoints((ordered + rest).mapIndexed { index, endpoint -> endpoint.copy(priority = index) })
    }

    /** Move one step up (tried earlier) or down (tried later). Returns false when unmoved. */
    suspend fun moveEndpoint(id: String, up: Boolean): Boolean {
        val sorted = allEndpoints().sortedBy { it.priority }
        val index = sorted.indexOfFirst { it.id == id }
        if (index < 0) return false
        val swapWith = if (up) index - 1 else index + 1
        if (swapWith !in sorted.indices) return false
        val reordered = sorted.toMutableList()
        val tmp = reordered[index]
        reordered[index] = reordered[swapWith]
        reordered[swapWith] = tmp
        reorderEndpoints(reordered.map { it.id })
        return true
    }

    // --- legacy single-URL compat (writes go to the first endpoint when present) ---

    suspend fun setServerUrl(url: String) {
        val normalized = url.trim().trimEnd('/')
        val existing = allEndpoints()
        if (existing.isEmpty()) {
            edit { it[serverUrlKey] = normalized }
        } else {
            val sorted = existing.sortedBy { it.priority }
            val first = sorted.first()
            persistEndpoints(sorted.map { if (it.id == first.id) it.copy(url = normalized) else it })
            edit { it[serverUrlKey] = normalized }
        }
    }

    suspend fun setBearerToken(token: String) {
        val existing = allEndpoints()
        if (existing.isEmpty()) {
            prefs.edit().putString(tokenKey, token).apply()
        } else {
            val first = existing.sortedBy { it.priority }.first()
            val updated = existing.map { if (it.id == first.id) it.copy(bearerToken = token) else it }
            // persistEndpoints writes each endpoint's token + metadata (and
            // re-emits observeEndpoints); update the list first so the new
            // token is not overwritten by the stale copy.
            persistEndpoints(updated)
            prefs.edit().putString(tokenKey, token).apply()
        }
    }

    suspend fun setNotificationCaptureEnabled(enabled: Boolean) = edit { it[notificationKey] = enabled }

    suspend fun setSmsCaptureEnabled(enabled: Boolean) = edit { it[smsKey] = enabled }

    suspend fun setDebugModeEnabled(enabled: Boolean) = edit { it[debugKey] = enabled }

    // --- 008: default category / default account (FR-005–FR-006, FR-009) ---

    /** Emits both defaults; key absent → null field (data-model §1). */
    fun observeDefaults(): Flow<DefaultPreferences> = dataStore.data.map { stored ->
        DefaultPreferences(
            defaultCategoryId = stored[defaultCategoryIdKey],
            defaultAccountId = stored[defaultAccountIdKey],
        )
    }

    /** One-shot read for the capture path (R4: stamp-at-capture). */
    suspend fun currentDefaults(): DefaultPreferences = observeDefaults().first()

    suspend fun setDefaultCategoryId(id: Int) = edit { it[defaultCategoryIdKey] = id }

    suspend fun setDefaultAccountId(id: Int) = edit { it[defaultAccountIdKey] = id }

    /** FR-009: per-key remove() — clearing one default leaves the other intact. */
    suspend fun clearDefaultCategory() = edit { it.remove(defaultCategoryIdKey) }

    suspend fun clearDefaultAccount() = edit { it.remove(defaultAccountIdKey) }

    // --- internals ---

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        dataStore.edit { block(it) }
    }

    private fun readEndpointsLocked(stored: Preferences): List<BridgeEndpoint> {
        val raw = stored[endpointsKey] ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                val id = obj.optString("id").ifBlank { return@mapNotNull null }
                BridgeEndpoint(
                    id = id,
                    name = obj.optString("name", ""),
                    url = obj.optString("url", "").trimEnd('/'),
                    bearerToken = prefs.getString(tokenKeyFor(id), "") ?: "",
                    enabled = obj.optBoolean("enabled", true),
                    priority = obj.optInt("priority", i),
                )
            }
        }.getOrDefault(emptyList())
    }

    private suspend fun persistEndpoints(endpoints: List<BridgeEndpoint>) {
        val sorted = endpoints.sortedBy { it.priority }
        for (endpoint in sorted) {
            prefs.edit().putString(tokenKeyFor(endpoint.id), endpoint.bearerToken).apply()
        }
        val array = JSONArray()
        sorted.forEach { endpoint ->
            array.put(
                JSONObject()
                    .put("id", endpoint.id)
                    .put("name", endpoint.name)
                    .put("url", endpoint.url)
                    .put("enabled", endpoint.enabled)
                    .put("priority", endpoint.priority),
            )
        }
        dataStore.edit { it[endpointsKey] = array.toString() }
    }

    /**
     * One-time migration: legacy single `server_url` becomes a "Default"
     * endpoint so old installs keep working with the fallback chain.
     */
    private suspend fun migrateLegacyIfNeeded() {
        val stored = dataStore.data.first()
        if (!stored[endpointsKey].isNullOrBlank()) return
        val legacyUrl = (stored[serverUrlKey] ?: "").trim().trimEnd('/')
        if (legacyUrl.isBlank()) return
        val legacyToken = prefs.getString(tokenKey, "") ?: ""
        val endpoint = BridgeEndpoint(
            id = java.util.UUID.randomUUID().toString(),
            name = "Default",
            url = legacyUrl,
            bearerToken = legacyToken,
            enabled = true,
            priority = 0,
        )
        persistEndpoints(listOf(endpoint))
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
