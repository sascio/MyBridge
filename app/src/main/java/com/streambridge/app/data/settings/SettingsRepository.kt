package com.streambridge.app.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
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
    val serverPort: Int = 0, // 0 = automatic (ephemeral port)
    val recentQueries: List<String> = emptyList(),
    // General
    val startupTab: String = "home",
    // Appearance
    val posterSize: String = "medium", // small | medium | large
    // Home
    val homeShowContinueWatching: Boolean = true,
    val homeShowRecommendations: Boolean = true,
    val homeShowRecentlyAdded: Boolean = true,
    // Detail
    val preferredMetadataAddon: String = "", // "" = automatic
    // Playback
    val preferredSubtitleLanguage: String = "",
    val preferredAudioLanguage: String = "",
    val subtitleScale: Float = 1f,
    val defaultPlaybackSpeed: Float = 1f
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
        val RECENT_QUERIES = stringSetPreferencesKey("recent_queries")
        val STARTUP_TAB = stringPreferencesKey("startup_tab")
        val POSTER_SIZE = stringPreferencesKey("poster_size")
        val HOME_SHOW_CONTINUE = booleanPreferencesKey("home_show_continue")
        val HOME_SHOW_RECOMMENDATIONS = booleanPreferencesKey("home_show_recommendations")
        val HOME_SHOW_RECENTLY_ADDED = booleanPreferencesKey("home_show_recently_added")
        val PREFERRED_METADATA_ADDON = stringPreferencesKey("preferred_metadata_addon")
        val PREFERRED_SUBTITLE_LANGUAGE = stringPreferencesKey("preferred_subtitle_language")
        val PREFERRED_AUDIO_LANGUAGE = stringPreferencesKey("preferred_audio_language")
        val SUBTITLE_SCALE = floatPreferencesKey("subtitle_scale")
        val DEFAULT_PLAYBACK_SPEED = floatPreferencesKey("default_playback_speed")
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
            serverPort = (prefs[Keys.SERVER_PORT] ?: defaults.serverPort).coerceIn(0, 65535),
            startupTab = prefs[Keys.STARTUP_TAB] ?: defaults.startupTab,
            posterSize = prefs[Keys.POSTER_SIZE] ?: defaults.posterSize,
            homeShowContinueWatching = prefs[Keys.HOME_SHOW_CONTINUE] ?: defaults.homeShowContinueWatching,
            homeShowRecommendations = prefs[Keys.HOME_SHOW_RECOMMENDATIONS] ?: defaults.homeShowRecommendations,
            homeShowRecentlyAdded = prefs[Keys.HOME_SHOW_RECENTLY_ADDED] ?: defaults.homeShowRecentlyAdded,
            preferredMetadataAddon = prefs[Keys.PREFERRED_METADATA_ADDON] ?: "",
            preferredSubtitleLanguage = prefs[Keys.PREFERRED_SUBTITLE_LANGUAGE] ?: "",
            preferredAudioLanguage = prefs[Keys.PREFERRED_AUDIO_LANGUAGE] ?: "",
            subtitleScale = (prefs[Keys.SUBTITLE_SCALE] ?: defaults.subtitleScale).coerceIn(0.6f, 1.8f),
            defaultPlaybackSpeed = (prefs[Keys.DEFAULT_PLAYBACK_SPEED] ?: defaults.defaultPlaybackSpeed)
                .coerceIn(0.25f, 4f),
            recentQueries = prefs[Keys.RECENT_QUERIES]
                ?.mapNotNull { entry ->
                    val separator = entry.indexOf(" # ")
                    if (separator <= 0) return@mapNotNull null
                    val timestamp = entry.substring(0, separator).toLongOrNull() ?: return@mapNotNull null
                    val query = entry.substring(separator + 3)
                    if (query.isBlank()) null else timestamp to query
                }
                ?.sortedByDescending { it.first }
                ?.map { it.second }
                ?: emptyList()
        )
    }

    /** Remembers a search query (most recent first, capped at 10). */
    suspend fun rememberQuery(query: String) {
        val trimmed = query.trim()
        if (trimmed.length < 2) return
        val now = System.currentTimeMillis()
        edit { prefs ->
            val existing = prefs[Keys.RECENT_QUERIES] ?: emptySet()
            val kept = existing.mapNotNull { entry ->
                val separator = entry.indexOf(" # ")
                if (separator <= 0) return@mapNotNull null
                val timestamp = entry.substring(0, separator).toLongOrNull() ?: return@mapNotNull null
                val text = entry.substring(separator + 3)
                if (text.isBlank() || text == trimmed) null else timestamp to text
            }
            val capped = (kept + (now to trimmed))
                .sortedByDescending { it.first }
                .take(10)
            prefs[Keys.RECENT_QUERIES] = capped.map { (timestamp, text) ->
                "$timestamp # $text"
            }.toSet()
        }
    }

    suspend fun forgetQuery(query: String) {
        edit { prefs ->
            val existing = prefs[Keys.RECENT_QUERIES] ?: return@edit
            prefs[Keys.RECENT_QUERIES] = existing.filterNot {
                it.substringAfter(" # ", it) == query
            }.toSet()
        }
    }

    suspend fun clearRecentQueries() {
        edit { prefs -> prefs.remove(Keys.RECENT_QUERIES) }
    }

    suspend fun setStartupTab(value: String) = edit { it[Keys.STARTUP_TAB] = value }
    suspend fun setPosterSize(value: String) = edit { it[Keys.POSTER_SIZE] = value }
    suspend fun setHomeShowContinueWatching(value: Boolean) =
        edit { it[Keys.HOME_SHOW_CONTINUE] = value }

    suspend fun setHomeShowRecommendations(value: Boolean) =
        edit { it[Keys.HOME_SHOW_RECOMMENDATIONS] = value }

    suspend fun setHomeShowRecentlyAdded(value: Boolean) =
        edit { it[Keys.HOME_SHOW_RECENTLY_ADDED] = value }

    suspend fun setPreferredMetadataAddon(value: String) =
        edit { it[Keys.PREFERRED_METADATA_ADDON] = value }

    suspend fun setPreferredSubtitleLanguage(value: String) =
        edit { it[Keys.PREFERRED_SUBTITLE_LANGUAGE] = value.trim() }

    suspend fun setPreferredAudioLanguage(value: String) =
        edit { it[Keys.PREFERRED_AUDIO_LANGUAGE] = value.trim() }

    suspend fun setSubtitleScale(value: Float) =
        edit { it[Keys.SUBTITLE_SCALE] = value.coerceIn(0.6f, 1.8f) }

    suspend fun setDefaultPlaybackSpeed(value: Float) =
        edit { it[Keys.DEFAULT_PLAYBACK_SPEED] = value.coerceIn(0.25f, 4f) }

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
