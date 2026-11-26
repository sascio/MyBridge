package com.nuvio.app.features.media.service

import com.nuvio.app.core.network.readBoundedResponseBody
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.Parameters
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

/** Tokens are Subsonic MD5(password + salt) credentials supplied by the server owner.
 * Password login is deliberately not emulated; token and salt stay in the secure vault. */
@Serializable
internal class NavidromeConnection(val server: String, val username: String, val token: String, val salt: String) {
    init {
        validateMediaServer(server)
        require(username.isNotBlank() && username.length <= 256)
        require(token.matches(Regex("[a-fA-F0-9]{32}")))
        require(salt.isNotBlank() && salt.length <= 256)
    }
    override fun toString(): String = "NavidromeConnection(redacted)"
}

internal fun validateMediaServer(server: String): Url {
    val url = try { Url(server) } catch (_: Exception) { throw MediaServiceException(MediaFailure.InvalidInput) }
    if (url.protocol != URLProtocol.HTTPS || url.host.isBlank() || !url.user.isNullOrEmpty() || !url.password.isNullOrEmpty() ||
        url.parameters.names().isNotEmpty() || url.fragment.isNotEmpty()) throw MediaServiceException(MediaFailure.InvalidInput)
    // A reverse-proxy path prefix is supported, but not encoded traversal or separator tricks.
    if (url.encodedPath.split('/').any { it == "." || it == ".." } || '%' in url.encodedPath || '\\' in server)
        throw MediaServiceException(MediaFailure.InvalidInput)
    return url
}

/** Native Ktor engines; POST form auth avoids credentials in request URLs/history.
 * Redirects must be disabled on the injected client so credentials never cross origins.
 * No private YouTube protocol, access-control bypass or external proxy is involved. */
internal class NavidromeService(private val client: HttpClient, private val connection: NavidromeConnection) : CatalogueService, MediaLibraryService, MediaLyricsService {
    private val json = Json { ignoreUnknownKeys = true }
    private val base = validateMediaServer(connection.server).toString().trimEnd('/')

    private suspend fun request(method: String, values: List<Pair<String, String>> = emptyList()): JsonObject {
        try {
            val response = client.submitForm("$base/rest/$method.view", Parameters.build {
                append("u", connection.username); append("t", connection.token); append("s", connection.salt)
                append("v", "1.16.1"); append("c", "StreamBridge"); append("f", "json")
                values.forEach { (key, value) -> append(key, value) }
            })
            when (response.status.value) {
                401 -> throw MediaServiceException(MediaFailure.Authentication)
                403 -> throw MediaServiceException(MediaFailure.Permission)
                429 -> throw MediaServiceException(MediaFailure.RateLimited)
                !in 200..299 -> throw MediaServiceException(MediaFailure.Unavailable)
            }
            val text = readBoundedResponseBody(response.bodyAsChannel(), response.headers["Content-Length"]?.toLongOrNull(), 2 * 1024 * 1024)
            val root = json.parseToJsonElement(text).jsonObject["subsonic-response"]?.jsonObject
                ?: throw MediaServiceException(MediaFailure.InvalidResponse)
            if (root.text("status") != "ok") {
                val code = root["error"]?.jsonObject?.text("code")?.toIntOrNull()
                throw MediaServiceException(when (code) { 40, 41, 42, 43, 44 -> MediaFailure.Authentication; 50 -> MediaFailure.Permission; 70 -> MediaFailure.Unavailable; else -> MediaFailure.InvalidResponse })
            }
            return root
        } catch (cancelled: CancellationException) { throw cancelled } catch (failure: MediaServiceException) { throw failure } catch (_: SerializationException) { throw MediaServiceException(MediaFailure.InvalidResponse) } catch (_: IllegalArgumentException) { throw MediaServiceException(MediaFailure.InvalidResponse) } catch (_: Exception) { throw MediaServiceException(MediaFailure.Network) }
    }

    suspend fun ping() { request("ping") }

    override suspend fun home(continuation: String?): MediaPage<MediaEntry> {
        val offset = offset(continuation)
        val albums = request("getAlbumList2", listOf("type" to "newest", "size" to "30", "offset" to offset.toString()))
            .requiredObject("albumList2").objects("album").map { album(it) }
        return page(albums, offset)
    }

    override suspend fun search(query: String, continuation: String?): MediaPage<MediaEntry> {
        require(query.isNotBlank() && query.length <= 500)
        val offset = offset(continuation)
        val result = request("search3", listOf("query" to query.trim(), "songCount" to "30", "songOffset" to "$offset", "albumCount" to "0", "artistCount" to "0"))
        return page(result.requiredObject("searchResult3").objects("song").map { song(it) }, offset)
    }

    override suspend fun browse(key: MediaKey, continuation: String?): MediaPage<MediaEntry> {
        requireKey(key)
        require(continuation == null) // Subsonic returns complete album/playlist details.
        return when {
            key.id.startsWith("album:") -> MediaPage(request("getAlbum", listOf("id" to key.id.removePrefix("album:"))).requiredObject("album").objects("song").map { song(it) })
            key.id.startsWith("playlist:") -> MediaPage(request("getPlaylist", listOf("id" to key.id.removePrefix("playlist:"))).requiredObject("playlist").objects("entry").map { song(it) })
            else -> throw MediaServiceException(MediaFailure.InvalidInput)
        }
    }

    override suspend fun playlists(): List<MediaEntry> = request("getPlaylists").requiredObject("playlists").objects("playlist").map {
        MediaEntry(MediaKey(MediaServiceId.Navidrome, "playlist:${it.requiredId()}"), MediaKind.Playlist, it.text("name") ?: "Untitled playlist", it.text("owner").orEmpty())
    }
    override suspend fun liked(): List<MediaEntry> = request("getStarred2").requiredObject("starred2").objects("song").map { song(it) }
    override suspend fun setLiked(key: MediaKey, liked: Boolean) {
        requireKey(key)
        val parameter = when { key.id.startsWith("song:") -> "id"; key.id.startsWith("album:") -> "albumId"; else -> throw MediaServiceException(MediaFailure.InvalidInput) }
        request(if (liked) "star" else "unstar", listOf(parameter to key.id.substringAfter(':')))
    }
    override suspend fun createPlaylist(title: String): MediaKey {
        require(title.isNotBlank() && title.length <= 200)
        val result = request("createPlaylist", listOf("name" to title.trim())).requiredObject("playlist")
        return MediaKey(MediaServiceId.Navidrome, "playlist:${result.requiredId()}")
    }
    override suspend fun addToPlaylist(playlist: MediaKey, entries: List<MediaKey>) {
        requireKey(playlist); require(playlist.id.startsWith("playlist:")); require(entries.isNotEmpty() && entries.size <= 200)
        entries.forEach { requireKey(it); require(it.id.startsWith("song:")) }
        request("updatePlaylist", listOf("playlistId" to playlist.id.removePrefix("playlist:")) + entries.map { "songIdToAdd" to it.id.removePrefix("song:") })
    }
    override suspend fun deletePlaylist(playlist: MediaKey) {
        requireKey(playlist); require(playlist.id.startsWith("playlist:"))
        request("deletePlaylist", listOf("id" to playlist.id.removePrefix("playlist:")))
    }
    suspend fun radio(key: MediaKey): List<MediaEntry> {
        requireKey(key); require(key.id.startsWith("song:"))
        return request("getSimilarSongs2", listOf("id" to key.id.removePrefix("song:"), "count" to "30"))
            .requiredObject("similarSongs2").objects("song").map { song(it) }
    }
    suspend fun randomTracks(): List<MediaEntry> = request("getRandomSongs", listOf("size" to "30"))
        .requiredObject("randomSongs").objects("song").map { song(it) }

    override suspend fun lyrics(entry: MediaEntry): MediaLyrics? {
        requireKey(entry.key)
        val response = request("getLyrics", listOf("artist" to entry.subtitle, "title" to entry.title))
        val lyrics = response["lyrics"] as? JsonObject ?: return null
        val text = lyrics.text("value")?.takeIf { it.isNotBlank() } ?: return null
        val timed = parseSyncedLyrics(text)
        return if (timed.isNotEmpty()) MediaLyrics("Navidrome", timed, true)
        else MediaLyrics("Navidrome", text.lineSequence().take(5000).map { LyricLine(0, it) }.toList(), false)
    }
    private fun requireKey(key: MediaKey) { require(key.service == MediaServiceId.Navidrome && key.id.substringAfter(':').isNotBlank()) }
    private fun offset(value: String?): Int = if (value == null) 0 else value.toIntOrNull()?.takeIf { it in 0..100_000 } ?: throw MediaServiceException(MediaFailure.InvalidInput)
    private fun page(entries: List<MediaEntry>, offset: Int) = MediaPage(entries, if (entries.size == 30 && offset < 100_000) (offset + 30).toString() else null)
    private fun album(value: JsonObject) = MediaEntry(MediaKey(MediaServiceId.Navidrome, "album:${value.requiredId()}"), MediaKind.Album, value.text("name") ?: "Untitled album", value.text("artist").orEmpty())
    private fun song(value: JsonObject) = MediaEntry(
        MediaKey(MediaServiceId.Navidrome, "song:${value.requiredId()}"), MediaKind.Track,
        value.text("title") ?: "Untitled track", value.text("artist").orEmpty(),
        durationMs = value.text("duration")?.toLongOrNull()?.takeIf { it in 0..604_800 }?.times(1_000), liked = !value.text("starred").isNullOrBlank(),
    )
    private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.requiredId(): String = text("id")?.takeIf { it.isNotBlank() } ?: throw MediaServiceException(MediaFailure.InvalidResponse)
    private fun JsonObject.requiredObject(name: String): JsonObject = this[name] as? JsonObject ?: throw MediaServiceException(MediaFailure.InvalidResponse)
    private fun JsonObject.objects(name: String): List<JsonObject> = when (val value = this[name]) {
        null -> emptyList()
        is JsonArray -> value.map { it as? JsonObject ?: throw MediaServiceException(MediaFailure.InvalidResponse) }
        else -> throw MediaServiceException(MediaFailure.InvalidResponse)
    }
}
