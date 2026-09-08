package com.streambridge.app.data.integrations

import com.streambridge.app.addon.SbHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody

/**
 * Client for the opensubtitles.com API (v3), powering the built-in
 * "Open Subtitles V3" addon.
 *
 * Requires the user's own API key and account credentials (free at
 * opensubtitles.com) — nothing is bundled with the app. The JWT login
 * token is cached in memory until shortly before its 24 h expiry.
 *
 * A subtitle search returns one [Result] per downloadable file; each
 * result already carries its resolved download link and the headers the
 * link needs (User-Agent), so playback needs no extra auth.
 */
class OpenSubtitlesClient(
    httpClient: SbHttpClient,
    private val json: Json
) {

    private val http: OkHttpClient = httpClient.client.newBuilder().build()

    data class Result(
        val fileId: Long,
        val fileName: String,
        val language: String,
        val url: String,
        val headers: Map<String, String>
    ) {
        val label: String get() = language.ifBlank { fileName }
    }

    private var cachedToken: String? = null
    private var cachedTokenAt: Long = 0L
    private val tokenMutex = Mutex()

    /** True when all three credentials needed by the API are present. */
    fun isConfigured(apiKey: String, username: String, password: String): Boolean =
        apiKey.isNotBlank() && username.isNotBlank() && password.isNotBlank()

    /**
     * Searches subtitles for a movie or episode by IMDb id.
     * Returns results with resolved download links (POST /download).
     */
    suspend fun search(
        apiKey: String,
        username: String,
        password: String,
        imdbId: String?,
        season: Int?,
        episode: Int?
    ): List<Result> = withContext(Dispatchers.IO) {
        val cleanImdb = imdbId?.trim()?.takeIf { it.startsWith("tt") } ?: return@withContext emptyList()
        if (!isConfigured(apiKey, username, password)) return@withContext emptyList()
        val token = login(apiKey, username, password) ?: return@withContext emptyList()

        val url = buildString {
            append("$BASE/subtitles?imdb_id=$cleanImdb")
            if (season != null && season > 0) append("&season_number=$season")
            if (episode != null && episode > 0) append("&episode_number=$episode")
        }

        val searchBody = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            http.newCall(
                Request.Builder()
                    .url(url)
                    .header("Api-Key", apiKey)
                    .header("Authorization", "Bearer $token")
                    .header("User-Agent", USER_AGENT)
                    .build()
            ).execute().use { response ->
                if (!response.isSuccessful) return@withTimeoutOrNull null
                response.body?.string()
            }
        } ?: return@withContext emptyList()

        val files = parseFiles(searchBody).take(MAX_RESULTS)

        // Resolve a download link per file (the link is what the player
        // needs; search results alone do not contain playable URLs).
        files.mapNotNull { file ->
            try {
                resolveDownloadLink(apiKey, token, file.fileId)?.let { link ->
                    Result(
                        fileId = file.fileId,
                        fileName = file.fileName,
                        language = file.language,
                        url = link,
                        headers = mapOf("User-Agent" to USER_AGENT)
                    )
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    private suspend fun resolveDownloadLink(apiKey: String, token: String, fileId: Long): String? {
        return withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            http.newCall(
                Request.Builder()
                    .url("$BASE/download")
                    .header("Api-Key", apiKey)
                    .header("Authorization", "Bearer $token")
                    .header("User-Agent", USER_AGENT)
                    .post(
                        RequestBody.create(
                            MediaType.parse("application/json"),
                            """{"file_id":$fileId}"""
                        )
                    )
                    .build()
            ).execute().use { response ->
                if (!response.isSuccessful) return@withTimeoutOrNull null
                val body = json.parseToJsonElement(response.body?.string() ?: return@withTimeoutOrNull null)
                (body as? JsonObject)?.get("link")?.jsonPrimitive?.contentOrNull
            }
        }
    }

    /** Logs in and caches the JWT for ~24 h (the API's validity window). */
    private suspend fun login(apiKey: String, username: String, password: String): String? =
        tokenMutex.withLock {
            val now = System.currentTimeMillis()
            cachedToken?.let { token ->
                if (now - cachedTokenAt < TOKEN_TTL_MS) return token
            }
            val body = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                http.newCall(
                    Request.Builder()
                        .url("$BASE/login")
                        .header("Api-Key", apiKey)
                        .header("User-Agent", USER_AGENT)
                        .post(
                            RequestBody.create(
                                MediaType.parse("application/json"),
                                """{"username":"${escape(username)}","password":"${escape(password)}"}"""
                            )
                        )
                        .build()
                ).execute().use { response ->
                    if (!response.isSuccessful) null
                    else response.body?.string()
                }
            } ?: return null
            val token = try {
                json.parseToJsonElement(body).jsonObject["token"]?.jsonPrimitive?.contentOrNull
            } catch (_: Exception) {
                null
            }
            if (token.isNullOrBlank()) return null
            cachedToken = token
            cachedTokenAt = now
            token
        }

    private data class SubtitleFile(val fileId: Long, val fileName: String, val language: String)

    private fun parseFiles(body: String): List<SubtitleFile> = try {
        val root = json.parseToJsonElement(body).jsonObject
        val data = root["data"]?.jsonArray ?: emptyList()
        data.flatMap { entry ->
            try {
                val attributes = entry.jsonObject["attributes"]?.jsonObject
                    ?: return@flatMap emptyList()
                val language = attributes["language"]?.jsonPrimitive?.contentOrNull ?: ""
                val files = attributes["files"]?.jsonArray ?: return@flatMap emptyList()
                files.mapNotNull { file ->
                    val fileObject = file.jsonObject
                    val fileId = runCatching { fileObject["file_id"]?.jsonPrimitive?.content }
                        .getOrNull()?.toLongOrNull() ?: return@mapNotNull null
                    SubtitleFile(
                        fileId = fileId,
                        fileName = fileObject["file_name"]?.jsonPrimitive?.contentOrNull ?: "",
                        language = language
                    )
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    companion object {
        private const val BASE = "https://api.opensubtitles.com/api/v1"
        private const val USER_AGENT = "StreamBridge v1.0"
        private const val REQUEST_TIMEOUT_MS = 15_000L
        private const val TOKEN_TTL_MS = 23 * 60 * 60 * 1000L
        private const val MAX_RESULTS = 15
    }
}
