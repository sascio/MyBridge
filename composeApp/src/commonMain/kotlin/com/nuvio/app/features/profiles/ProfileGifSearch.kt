package com.nuvio.app.features.profiles

import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.core.network.isPublicServerHost
import io.ktor.http.Url
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
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
 * shipping any provider key in the APK exposes it. A configured StreamBridge proxy
 * remains supported; without one, user-initiated searches use the later Enhanced
 * DuckDuckGo image-search implementation without embedding a provider API key.
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

/** Thrown when an explicitly configured proxy URL is unusable. */
internal class ProfileGifSearchNotConfiguredException : IllegalStateException()

/** Thrown when the search provider cannot supply a usable response. */
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
        val preview = gifSearchResultUrl(dto.previewUrl)
        val gif = gifSearchResultUrl(dto.gifUrl)
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
    /** A configured proxy takes precedence; otherwise use the Enhanced search provider. */
    suspend fun search(
        query: String,
        proxyBaseUrl: String = GifSearchConfig.PROXY_URL,
        limit: Int = PROFILE_GIF_SEARCH_DEFAULT_LIMIT,
        fetch: suspend (String, Map<String, String>) -> String = ::fetchGifSearchText,
    ): Result<List<ProfileGifSearchItem>> = withContext(Dispatchers.Default) {
        try {
            val cleanQuery = query.trim().take(PROFILE_GIF_SEARCH_MAX_QUERY_LENGTH)
            val results = when {
                cleanQuery.isEmpty() -> emptyList()
                proxyBaseUrl.isNotBlank() -> {
                    val url = buildProfileGifSearchUrl(proxyBaseUrl, cleanQuery, limit)
                        ?: throw ProfileGifSearchNotConfiguredException()
                    parseProfileGifSearchResponse(fetch(url, emptyMap()))
                }
                else -> searchDuckDuckGoGifs(cleanQuery, limit, fetch)
            }
            Result.success(results)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }
}

private suspend fun fetchGifSearchText(url: String, headers: Map<String, String>): String {
    val response = httpRequestRaw("GET", url, headers, "", maxResponseBodyBytes = 2 * 1024 * 1024)
    if (response.status !in 200..299) throw ProfileGifSearchResponseException("GIF search service returned HTTP ${response.status}.")
    return response.body
}

/** Two-stage image search adapted from Enhanced's 0.4.13 GIF provider. */
internal suspend fun searchDuckDuckGoGifs(
    query: String,
    limit: Int,
    fetch: suspend (String, Map<String, String>) -> String,
): List<ProfileGifSearchItem> {
    val searchQuery = if (query.contains("gif", ignoreCase = true)) query else "$query gif"
    val encoded = searchQuery.encodeURLParameter()
    val pageUrl = "https://duckduckgo.com/?q=$encoded&iax=images&ia=images"
    val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15",
        "Accept" to "application/json,text/html;q=0.9,*/*;q=0.8",
        "Referer" to "https://duckduckgo.com/",
    )
    val page = fetch(pageUrl, headers)
    val token = extractGifSearchToken(page)
        ?: throw ProfileGifSearchResponseException("The GIF search service did not return a search token.")
    val url = "https://duckduckgo.com/i.js?l=wt-wt&o=json&q=$encoded&vqd=${token.encodeURLParameter()}&f=type%3Agif&p=1"
    return parseDuckDuckGoGifResponse(fetch(url, headers + ("Referer" to pageUrl)), limit)
}

internal fun extractGifSearchToken(page: String): String? {
    val patterns = listOf(Regex("""vqd=['"]([^'"]+)['"]"""), Regex("""vqd=([^&"'\\]+)"""))
    return patterns.firstNotNullOfOrNull { it.find(page)?.groupValues?.getOrNull(1) }
        ?.takeIf { it.length in 1..128 && it.all { c -> c.isLetterOrDigit() || c == '-' || c == '_' } }
}

internal fun parseDuckDuckGoGifResponse(body: String, limit: Int): List<ProfileGifSearchItem> {
    val response = try {
        profileGifSearchJson.decodeFromString<DuckDuckGoGifResponse>(body)
    } catch (error: Exception) {
        throw ProfileGifSearchResponseException("GIF search response was not valid JSON.")
    }
    return response.results.asSequence().mapNotNull { item ->
        val gif = sequenceOf(item.image, item.url).mapNotNull(::gifSearchResultUrl)
            .firstOrNull { it.substringBefore('?').substringBefore('#').endsWith(".gif", ignoreCase = true) }
            ?: return@mapNotNull null
        val preview = gifSearchResultUrl(item.thumbnail) ?: gif
        ProfileGifSearchItem(
            id = item.imageToken?.takeIf { it.isNotBlank() } ?: gif,
            title = item.title?.trim()?.takeIf { it.isNotEmpty() } ?: "GIF",
            previewUrl = preview,
            gifUrl = gif,
        )
    }.distinctBy { it.gifUrl }.take(limit.coerceIn(1, 50)).toList()
}

/** Never automatically request credential-bearing or obvious local-network image URLs. */
private fun gifSearchResultUrl(raw: String?): String? {
    val value = normalizedAvatarUrl(raw) ?: return null
    val authority = value.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
    if (authority.isBlank() || '@' in authority || value.any { it.code < 32 || it.code == 127 }) return null
    val parsed = runCatching { Url(value) }.getOrNull() ?: return null
    return value.takeIf { parsed.host.isNotBlank() && isPublicServerHost(value) }
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

@Serializable
private data class DuckDuckGoGifResponse(val results: List<DuckDuckGoGifItem>)

@Serializable
private data class DuckDuckGoGifItem(
    val title: String? = null,
    val image: String? = null,
    val thumbnail: String? = null,
    val url: String? = null,
    @SerialName("image_token") val imageToken: String? = null,
)
