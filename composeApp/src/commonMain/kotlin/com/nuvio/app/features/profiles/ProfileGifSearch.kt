package com.nuvio.app.features.profiles

import com.nuvio.app.features.addons.httpGetText
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * GIF search for the profile avatar editor.
 *
 * Ported from the NuvioMobile-Enhanced 0.2.19 build 94 implementation
 * (yesnt10/NuvioMobile-Enhanced @ 76d2e5df, ProfileGifSearchService.kt /
 * ProfileEditScreen.kt). The upstream client called the Tenor v1 API directly with a
 * key injected at build time. Google discontinued the Tenor API on 30 June 2026, and
 * shipping any provider key in the APK exposes it. So StreamBridge talks to its own
 * proxy instead.
 *
 * Proxy contract (defined by StreamBridge; the proxy holds the provider key):
 *
 *   GET {PROXY_URL}/gifs/search?q=<query>&limit=<1..50>
 *   200 application/json
 *   {
 *     "results": [
 *       { "id": "...", "title": "...", "preview_url": "https://...", "gif_url": "https://..." }
 *     ]
 *   }
 *
 * `preview_url` is a small still/animated image for the grid. `gif_url` is the image that
 * becomes the avatar. Both must be absolute http(s) URLs.
 */
internal data class ProfileGifSearchItem(
    val id: String,
    val title: String,
    val previewUrl: String,
    val gifUrl: String,
)

/** Thrown when no proxy base URL is configured for this build. */
internal class ProfileGifSearchNotConfiguredException : IllegalStateException()

/** Thrown when the proxy answers with a body that does not match the contract. */
internal class ProfileGifSearchResponseException(message: String) : IllegalStateException(message)

internal const val PROFILE_GIF_SEARCH_MAX_QUERY_LENGTH = 100
internal const val PROFILE_GIF_SEARCH_DEFAULT_LIMIT = 24

/**
 * Builds the proxy search URL, or null when the base URL or query is unusable.
 * Pure function so it can be unit tested without a network.
 */
internal fun buildProfileGifSearchUrl(
    proxyBaseUrl: String,
    query: String,
    limit: Int = PROFILE_GIF_SEARCH_DEFAULT_LIMIT,
): String? {
    val base = proxyBaseUrl.trim().trimEnd('/')
    val cleanQuery = query.trim().take(PROFILE_GIF_SEARCH_MAX_QUERY_LENGTH)
    if (cleanQuery.isEmpty()) return null
    if (!base.startsWith("https://") && !base.startsWith("http://")) return null
    if (base.length <= "https://".length) return null
    return buildString {
        append(base)
        append("/gifs/search?q=")
        append(cleanQuery.encodeURLParameter())
        append("&limit=")
        append(limit.coerceIn(1, 50))
    }
}

/**
 * Parses a proxy response into search items. Entries without a valid absolute http(s)
 * preview and GIF URL are dropped rather than shown broken. Malformed JSON throws
 * [ProfileGifSearchResponseException] so a failure is never shown as an empty result.
 */
internal fun parseProfileGifSearchResponse(body: String): List<ProfileGifSearchItem> {
    val response = try {
        profileGifSearchJson.decodeFromString<ProfileGifSearchResponseDto>(body)
    } catch (error: Exception) {
        throw ProfileGifSearchResponseException("GIF search response was not valid JSON.")
    }
    return response.results.mapNotNull { dto ->
        val preview = normalizedAvatarUrl(dto.previewUrl)
        val gif = normalizedAvatarUrl(dto.gifUrl)
        if (preview == null || gif == null) {
            null
        } else {
            ProfileGifSearchItem(
                id = dto.id.ifBlank { gif },
                title = dto.title?.trim()?.takeIf { it.isNotEmpty() } ?: "GIF",
                previewUrl = preview,
                gifUrl = gif,
            )
        }
    }
}

internal object ProfileGifSearchService {
    /** Runs a search through the configured proxy. Blank queries return an empty list. */
    suspend fun search(
        query: String,
        proxyBaseUrl: String = GifSearchConfig.PROXY_URL,
        limit: Int = PROFILE_GIF_SEARCH_DEFAULT_LIMIT,
    ): Result<List<ProfileGifSearchItem>> = withContext(Dispatchers.Default) {
        runCatching {
            if (query.isBlank()) return@runCatching emptyList()
            if (proxyBaseUrl.isBlank()) throw ProfileGifSearchNotConfiguredException()
            val url = buildProfileGifSearchUrl(proxyBaseUrl, query, limit)
                ?: throw ProfileGifSearchNotConfiguredException()
            parseProfileGifSearchResponse(httpGetText(url))
        }
    }
}

private val profileGifSearchJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

@Serializable
private data class ProfileGifSearchResponseDto(
    val results: List<ProfileGifSearchResultDto> = emptyList(),
)

@Serializable
private data class ProfileGifSearchResultDto(
    val id: String = "",
    val title: String? = null,
    @SerialName("preview_url") val previewUrl: String? = null,
    @SerialName("gif_url") val gifUrl: String? = null,
)
