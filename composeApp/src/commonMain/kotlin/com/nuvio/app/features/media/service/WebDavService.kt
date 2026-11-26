package com.nuvio.app.features.media.service

import com.nuvio.app.core.network.readBoundedResponseBody
import io.ktor.client.HttpClient
import io.ktor.client.request.request
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64
import kotlinx.io.IOException

@Serializable
internal class WebDavConnection(val server: String, val username: String, val password: String) {
    init { validateMediaServer(server); require(username.isNotBlank() && ':' !in username && password.isNotBlank()) }
    override fun toString() = "WebDavConnection(redacted)"
}

/** Read-only, user-owned HTTPS library. Credentials are request headers, never URLs.
 * The shared API client disables redirects. The resolver cannot escape the chosen collection. */
internal class WebDavService(private val client: HttpClient, private val connection: WebDavConnection) : CatalogueService, MediaResolver {
    private val root = Url(connection.server.trimEnd('/') + "/")
    private val authorization = "Basic " + Base64.encode("${connection.username}:${connection.password}".encodeToByteArray())
    private fun checked(value: String): Url {
        val url = try { Url(value) } catch (_: Exception) { throw MediaServiceException(MediaFailure.InvalidInput) }
        if (url.protocol != root.protocol || url.host != root.host || url.port != root.port || !url.user.isNullOrEmpty() || !url.password.isNullOrEmpty() ||
            url.parameters.names().isNotEmpty() || url.fragment.isNotEmpty() || !url.encodedPath.startsWith(root.encodedPath) ||
            Regex("(?i)%2e|%2f|%5c").containsMatchIn(url.encodedPath) || url.encodedPath.split('/').any { it == "." || it == ".." } || '\\' in value)
            throw MediaServiceException(MediaFailure.Permission)
        return url
    }
    override suspend fun home(continuation: String?): MediaPage<MediaEntry> { require(continuation == null); return list(root) }
    override suspend fun search(query: String, continuation: String?): MediaPage<MediaEntry> {
        require(query.isNotBlank() && continuation == null)
        // Explicit collection-local filtering; never imply recursive or server-wide search.
        return MediaPage(list(root).items.filter { it.title.contains(query, ignoreCase = true) })
    }
    override suspend fun browse(key: MediaKey, continuation: String?): MediaPage<MediaEntry> {
        require(key.service == MediaServiceId.WebDav && continuation == null)
        return list(checked(key.id))
    }
    override suspend fun resolve(key: MediaKey): ResolvedMedia {
        require(key.service == MediaServiceId.WebDav)
        val url = checked(key.id)
        if (url.encodedPath.endsWith('/')) throw MediaServiceException(MediaFailure.InvalidInput)
        return ResolvedMedia(url.toString(), mapOf(HttpHeaders.Authorization to authorization))
    }
    private suspend fun list(collection: Url): MediaPage<MediaEntry> {
        try {
            val response = client.request(collection) {
                method = HttpMethod("PROPFIND")
                header(HttpHeaders.Authorization, authorization); header("Depth", "1")
                contentType(ContentType.Application.Xml)
                setBody("""<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:displayname/><d:resourcetype/><d:getcontenttype/></d:prop></d:propfind>""")
            }
            when (response.status.value) {
                207 -> Unit
                401 -> throw MediaServiceException(MediaFailure.Authentication)
                403 -> throw MediaServiceException(MediaFailure.Permission)
                else -> throw MediaServiceException(MediaFailure.Unavailable)
            }
            val xml = readBoundedResponseBody(response.bodyAsChannel(), response.headers[HttpHeaders.ContentLength]?.toLongOrNull(), 2 * 1024 * 1024)
            val items = withContext(Dispatchers.Default) { parseWebDavListing(xml) }.mapNotNull { item ->
                val href = item.href
                val url = when {
                    href.startsWith("https://") -> Url(href)
                    href.startsWith("/") -> URLBuilder(root).apply { encodedPath = href }.build()
                    else -> Url(collection.toString().trimEnd('/') + "/" + href)
                }
                // Ignore any server response outside the configured origin/collection.
                val safe = try { checked(url.toString()) } catch (_: Exception) { return@mapNotNull null }
                if (safe.encodedPath.trimEnd('/') == collection.encodedPath.trimEnd('/')) return@mapNotNull null
                val title = item.name.ifBlank { safe.encodedPath.trimEnd('/').substringAfterLast('/').decodeURLPart() }
                if (!item.directory && safe.encodedPath.substringAfterLast('.').lowercase() !in setOf("mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "m4b", "mp4", "webm")) return@mapNotNull null
                MediaEntry(MediaKey(MediaServiceId.WebDav, safe.toString()), if (item.directory) MediaKind.Album else MediaKind.Track, title)
            }.distinctBy { it.key }.sortedWith(compareBy<MediaEntry> { it.kind != MediaKind.Album }.thenBy { it.title.lowercase() })
            return MediaPage(items)
        } catch (cancelled: CancellationException) { throw cancelled } catch (error: MediaServiceException) { throw error } catch (_: IOException) { throw MediaServiceException(MediaFailure.Network) } catch (_: Exception) { throw MediaServiceException(MediaFailure.InvalidResponse) }
    }
}
