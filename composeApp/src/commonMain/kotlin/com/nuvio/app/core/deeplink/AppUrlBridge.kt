package com.nuvio.app.core.deeplink

import com.nuvio.app.core.tracking.ensureTrackingProvidersRegistered
import com.nuvio.app.features.party.PARTY_CODE_LENGTH
import com.nuvio.app.features.party.STREAMBRIDGE_URL_SCHEME
import com.nuvio.app.features.party.normalizePartyCode
import com.nuvio.app.features.player.infusePlaybackCallbacks
import com.nuvio.app.features.tracking.TrackingProviderRegistry
import io.ktor.http.Url
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal sealed interface AppDeepLink {
    data class Meta(
        val type: String,
        val id: String,
    ) : AppDeepLink

    data class AddonInstall(
        val manifestUrl: String,
    ) : AppDeepLink

    data object Downloads : AppDeepLink

    data class Party(
        val code: String,
    ) : AppDeepLink
}

internal object AppDeepLinkRepository {
    private val _pendingDeepLink = MutableStateFlow<AppDeepLink?>(null)
    val pendingDeepLink: StateFlow<AppDeepLink?> = _pendingDeepLink.asStateFlow()

    fun handleUrl(url: String) {
        parseAppDeepLink(url)?.let { deepLink ->
            _pendingDeepLink.value = deepLink
        }
    }

    fun markConsumed(deepLink: AppDeepLink) {
        if (_pendingDeepLink.value == deepLink) {
            _pendingDeepLink.value = null
        }
    }
}

fun handleAppUrl(url: String) {
    val normalizedUrl = url.trim()
    if (normalizedUrl.isBlank()) return
    if (infusePlaybackCallbacks.handleUrl(normalizedUrl)) return

    ensureTrackingProvidersRegistered()
    TrackingProviderRegistry.handleAuthCallback(normalizedUrl)
    AppDeepLinkRepository.handleUrl(normalizedUrl)
}

fun buildMetaDeepLinkUrl(
    type: String,
    id: String,
): String = buildString {
    append("nuvio://meta?type=")
    append(type.trim().encodeURLParameter())
    append("&id=")
    append(id.trim().encodeURLParameter())
}

fun buildDownloadsDeepLinkUrl(): String = "nuvio://downloads"

internal fun parseAppDeepLink(url: String): AppDeepLink? {
    val parsedUrl = runCatching { Url(url) }.getOrNull() ?: return null
    val scheme = parsedUrl.protocol.name.lowercase()
    if (scheme == "stremio") {
        return if (looksLikeAddonHost(parsedUrl.host.lowercase())) {
            customSchemeToHttpsUrl(url, scheme)?.let(AppDeepLink::AddonInstall)
        } else {
            null
        }
    }
    val host = parsedUrl.host.lowercase()
    val pathSegments = parsedUrl.pathSegments.map(String::trim).filter(String::isNotBlank)
    if (scheme == "https") {
        return when {
            // Legacy invites shared while StreamBridge still advertised Nuvio's
            // website. Kept so older messages keep working; StreamBridge no longer
            // generates links on this host.
            host == LEGACY_PARTY_HOST && pathSegments.firstOrNull() == "party" ->
                partyDeepLink(pathSegments.getOrNull(1))

            // StreamBridge's own invite redirect page (see
            // docs/WATCH-PARTY-LINKS.md): accepts both /party/<code> and
            // /party/?code=<code>, in any letter case for the path segments.
            host == PARTY_REDIRECT_HOST -> partyRedirectDeepLink(pathSegments, parsedUrl)

            else -> null
        }
    }
    // The StreamBridge scheme and the legacy Nuvio scheme are parsed identically.
    if (scheme != "nuvio" && scheme != STREAMBRIDGE_URL_SCHEME) return null

    return when (host) {
        "meta" -> {
            parseMetaFromParameters(parsedUrl)
                ?: parseMetaFromPath(pathSegments)
        }

        "detail", "details", "open", "watch" -> parseMetaFromPath(pathSegments)

        "movie", "movies", "series", "show", "shows", "tv" -> {
            val type = normalizeDeepLinkMediaType(host)
            val id = pathSegments.firstOrNull()?.let(::normalizeDeepLinkId).orEmpty()
            if (type.isBlank() || id.isBlank()) null else AppDeepLink.Meta(type = type, id = id)
        }

        "imdb", "tmdb" -> parseProviderMetaDeepLink(host, pathSegments, parsedUrl)

        "downloads" -> AppDeepLink.Downloads

        "party" -> partyDeepLink(pathSegments.firstOrNull() ?: firstParameter(parsedUrl, "code"))

        "auth" -> null

        else -> {
            if (looksLikeAddonHost(host)) {
                customSchemeToHttpsUrl(url, scheme)?.let(AppDeepLink::AddonInstall)
            } else {
                null
            }
        }
    }
}

private const val LEGACY_PARTY_HOST = "nuvio.tv"
private const val PARTY_REDIRECT_HOST = "sascio.github.io"
private val PARTY_REDIRECT_PATH_PREFIX = listOf("MyBridge", "party")

/**
 * Resolves `https://sascio.github.io/MyBridge/party/<code>` and
 * `https://sascio.github.io/MyBridge/party/?code=<code>`.
 *
 * Only the exact StreamBridge Pages path is accepted, so a link that merely
 * happens to be hosted on the same Pages host cannot open a party.
 */
private fun partyRedirectDeepLink(
    pathSegments: List<String>,
    parsedUrl: Url,
): AppDeepLink.Party? {
    if (pathSegments.size < PARTY_REDIRECT_PATH_PREFIX.size) return null
    PARTY_REDIRECT_PATH_PREFIX.forEachIndexed { index, expected ->
        if (!pathSegments[index].equals(expected, ignoreCase = true)) return null
    }
    if (pathSegments.size > PARTY_REDIRECT_PATH_PREFIX.size + 1) return null
    val fromPath = pathSegments.getOrNull(PARTY_REDIRECT_PATH_PREFIX.size)
    return partyDeepLink(fromPath ?: firstParameter(parsedUrl, "code"))
}

private fun partyDeepLink(code: String?): AppDeepLink.Party? =
    code?.let(::normalizePartyCode)?.takeIf { it.length == PARTY_CODE_LENGTH }?.let(AppDeepLink::Party)

private fun parseMetaFromParameters(parsedUrl: Url): AppDeepLink.Meta? {
    val type = firstParameter(parsedUrl, "type", "mediaType", "media_type")
        ?.let(::normalizeDeepLinkMediaType)
        .orEmpty()
    val id = firstParameter(parsedUrl, "id", "imdb", "imdbId", "imdb_id")
        ?.let(::normalizeDeepLinkId)
        ?: firstParameter(parsedUrl, "tmdb", "tmdbId", "tmdb_id")
            ?.let { "tmdb:${it.removePrefix("tmdb:").trim()}" }
        ?: ""
    return if (type.isBlank() || id.isBlank()) null else AppDeepLink.Meta(type = type, id = id)
}

private fun parseMetaFromPath(pathSegments: List<String>): AppDeepLink.Meta? {
    if (pathSegments.size < 2) return null
    val type = normalizeDeepLinkMediaType(pathSegments[0])
    val id = normalizeDeepLinkId(pathSegments[1])
    return if (type.isBlank() || id.isBlank()) null else AppDeepLink.Meta(type = type, id = id)
}

private fun parseProviderMetaDeepLink(
    provider: String,
    pathSegments: List<String>,
    parsedUrl: Url,
): AppDeepLink.Meta? {
    val first = pathSegments.firstOrNull().orEmpty()
    val second = pathSegments.getOrNull(1).orEmpty()
    val firstAsType = normalizeDeepLinkMediaType(first)
    val queryType = firstParameter(parsedUrl, "type", "mediaType", "media_type")
        ?.let(::normalizeDeepLinkMediaType)
        .orEmpty()
    val type = firstAsType.ifBlank { queryType }
    val rawId = if (firstAsType.isNotBlank()) second else first
    val id = when (provider) {
        "tmdb" -> rawId.removePrefix("tmdb:").trim().takeIf(String::isNotBlank)?.let { "tmdb:$it" }
        else -> normalizeDeepLinkId(rawId).takeIf(String::isNotBlank)
    }.orEmpty()
    return if (type.isBlank() || id.isBlank()) null else AppDeepLink.Meta(type = type, id = id)
}

private fun firstParameter(parsedUrl: Url, vararg keys: String): String? =
    keys.firstNotNullOfOrNull { key ->
        parsedUrl.parameters[key]?.trim()?.takeIf(String::isNotBlank)
    }

private fun normalizeDeepLinkMediaType(value: String): String =
    when (value.trim().lowercase()) {
        "movie", "movies", "film", "films" -> "movie"
        "series", "show", "shows", "tv", "tvshow", "tvshows" -> "series"
        else -> ""
    }

private fun normalizeDeepLinkId(value: String): String =
    value.trim()
        .removePrefix("imdb:")
        .takeIf(String::isNotBlank)
        .orEmpty()

private fun looksLikeAddonHost(host: String): Boolean =
    host.contains('.') ||
        host.equals("localhost", ignoreCase = true) ||
        host.any(Char::isDigit)

private fun customSchemeToHttpsUrl(url: String, scheme: String): String? {
    val prefix = "$scheme://"
    val rest = url.trim()
        .takeIf { it.startsWith(prefix, ignoreCase = true) }
        ?.substring(prefix.length)
        ?.takeIf { it.isNotBlank() && !it.startsWith("/") }
        ?: return null
    return "https://$rest"
}
