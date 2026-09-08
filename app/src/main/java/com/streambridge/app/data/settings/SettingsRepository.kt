package com.streambridge.app.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "stream_bridge_settings")

/**
 * App settings. Everything optional (TMDB, MDBList) is OFF by default and
 * no API key is ever shipped with the app: keys live only on the user's
 * device inside DataStore.
 */
data class SettingsState(
    val accent: String = "gold",
    val pureBlack: Boolean = false,
    val autoplayNext: Boolean = true,
    val watchedThresholdPercent: Int = 95,
    val tmdbEnabled: Boolean = false,
    val tmdbApiKey: String = "",
    val mdblistEnabled: Boolean = false,
    val mdblistApiKey: String = "",
    val serverEnabled: Boolean = false,
    val serverPort: Int = 0 // 0 = automatic (ephemeral port)
) {
    val tmdbActive: Boolean get() = tmdbEnabled && tmdbApiKey.isNotBlank()
    val mdblistActive: Boolean get() = mdblistEnabled && mdblistApiKey.isNotBlank()
}

class SettingsRepository(private val context: Context) {

    private object Keys {
        val ACCENT = stringPreferencesKey("accent")
        val PURE_BLACK = booleanPreferencesKey("pure_black")
        val AUTOPLAY_NEXT = booleanPreferencesKey("autoplay_next")
        val WATCHED_THRESHOLD = intPreferencesKey("watched_threshold_percent")
        val TMDB_ENABLED = booleanPreferencesKey("tmdb_enabled")
        val TMDB_API_KEY = stringPreferencesKey("tmdb_api_key")
        val MDBLIST_ENABLED = booleanPreferencesKey("mdblist_enabled")
        val MDBLIST_API_KEY = stringPreferencesKey("mdblist_api_key")
        val SERVER_ENABLED = booleanPreferencesKey("server_enabled")
        val SERVER_PORT = intPreferencesKey("server_port")
    }

    private val defaults = SettingsState()

    val state: Flow<SettingsState> = context.settingsDataStore.data.map { prefs ->
        SettingsState(
            accent = prefs[Keys.ACCENT] ?: defaults.accent,
            pureBlack = prefs[Keys.PURE_BLACK] ?: defaults.pureBlack,
            autoplayNext = prefs[Keys.AUTOPLAY_NEXT] ?: defaults.autoplayNext,
            watchedThresholdPercent = (prefs[Keys.WATCHED_THRESHOLD] ?: defaults.watchedThresholdPercent)
                .coerceIn(50, 99),
            tmdbEnabled = prefs[Keys.TMDB_ENABLED] ?: defaults.tmdbEnabled,
            tmdbApiKey = prefs[Keys.TMDB_API_KEY] ?: "",
            mdblistEnabled = prefs[Keys.MDBLIST_ENABLED] ?: defaults.mdblistEnabled,
            mdblistApiKey = prefs[Keys.MDBLIST_API_KEY] ?: "",
            serverEnabled = prefs[Keys.SERVER_ENABLED] ?: defaults.serverEnabled,
            serverPort = (prefs[Keys.SERVER_PORT] ?: defaults.serverPort).coerceIn(0, 65535)
        )
    }

    suspend fun setAccent(value: String) = edit { it[Keys.ACCENT] = value }
    suspend fun setPureBlack(value: Boolean) = edit { it[Keys.PURE_BLACK] = value }
    suspend fun setAutoplayNext(value: Boolean) = edit { it[Keys.AUTOPLAY_NEXT] = value }
    suspend fun setWatchedThresholdPercent(value: Int) =
        edit { it[Keys.WATCHED_THRESHOLD] = value.coerceIn(50, 99) }

    suspend fun setTmdbEnabled(value: Boolean) = edit { it[Keys.TMDB_ENABLED] = value }
    suspend fun setTmdbApiKey(value: String) = edit { it[Keys.TMDB_API_KEY] = value.trim() }

    suspend fun setMdblistEnabled(value: Boolean) = edit { it[Keys.MDBLIST_ENABLED] = value }
    suspend fun setMdblistApiKey(value: String) = edit { it[Keys.MDBLIST_API_KEY] = value.trim() }

    suspend fun setServerEnabled(value: Boolean) = edit { it[Keys.SERVER_ENABLED] = value }
    suspend fun setServerPort(value: Int) = edit { it[Keys.SERVER_PORT] = value.coerceIn(0, 65535) }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsDataStore.edit(block)
    }
}
