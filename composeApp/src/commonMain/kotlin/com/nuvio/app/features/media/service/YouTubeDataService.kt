package com.nuvio.app.features.media.service

import com.nuvio.app.core.network.readBoundedResponseBody
import io.ktor.client.HttpClient
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.http.HttpMethod
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsChannel
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

/** Public catalogue through the documented Data API v3. Requires the user's enabled API
 * project; never borrows another application's OAuth registration. This is NOT a stream
 * resolver, signed-in YouTube session, or claim of upstream engine parity. */
internal class YouTubeDataService(
    private val client: HttpClient, private val apiKey: String = "",
    private val accessToken: (suspend () -> String)? = null,
    private val musicOnly: Boolean = false,
) : CatalogueService, MediaCommentsService, MediaLibraryService {
    private val identity = if (musicOnly) MediaServiceId.YouTubeMusic else MediaServiceId.YouTube
    val signedIn: Boolean get() = accessToken != null
    init { require((apiKey.isNotBlank() || accessToken != null) && apiKey.length <= 256 && apiKey.none { it.isWhitespace() }) }
    private val json = Json { ignoreUnknownKeys = true }
    private suspend fun request(path: String, values: Map<String, String>, method: HttpMethod = HttpMethod.Get, payload: JsonObject? = null): JsonObject {
        try {
            val bearer = accessToken?.invoke()
            val response = client.request("https://www.googleapis.com/youtube/v3/$path") {
                this.method = method
                if (apiKey.isNotBlank()) header("X-Goog-Api-Key", apiKey)
                if (bearer != null) header("Authorization", "Bearer $bearer")
                if (payload != null) { contentType(ContentType.Application.Json); setBody(payload.toString()) }
                values.forEach { (key, value) -> parameter(key, value) }
            }
            when (response.status.value) {
                401 -> throw MediaServiceException(MediaFailure.Authentication)
                403 -> throw MediaServiceException(MediaFailure.Permission)
                404 -> throw MediaServiceException(MediaFailure.Unavailable)
                429 -> throw MediaServiceException(MediaFailure.RateLimited)
                !in 200..299 -> throw MediaServiceException(MediaFailure.Unavailable)
            }
            if (response.status.value == 204) return JsonObject(emptyMap())
            val body = readBoundedResponseBody(response.bodyAsChannel(), response.headers["Content-Length"]?.toLongOrNull(), 2 * 1024 * 1024)
            return json.parseToJsonElement(body).jsonObject
        } catch (cancelled: CancellationException) { throw cancelled } catch (failure: MediaServiceException) { throw failure } catch (_: SerializationException) { throw MediaServiceException(MediaFailure.InvalidResponse) } catch (_: IllegalArgumentException) { throw MediaServiceException(MediaFailure.InvalidResponse) } catch (_: Exception) { throw MediaServiceException(MediaFailure.Network) }
    }
    override suspend fun home(continuation: String?): MediaPage<MediaEntry> {
        val response = request("videos", pageParameters(continuation) + mapOf("part" to "snippet,contentDetails", "chart" to "mostPopular") + if (musicOnly) mapOf("videoCategoryId" to "10") else emptyMap())
        return mapPage(response) { entry(it, MediaKind.Video, it.text("id")) }
    }
    /** Documented API categories, not an undocumented personalized/Shorts feed. */
    suspend fun feed(feed: YouTubeFeed, continuation: String? = null): MediaPage<MediaEntry> = when (feed) {
        YouTubeFeed.Home -> home(continuation)
        YouTubeFeed.Subscriptions -> subscriptions(continuation)
        YouTubeFeed.Playlists -> { require(continuation == null); MediaPage(playlists()) }
        YouTubeFeed.Live, YouTubeFeed.Channels -> {
            val parameters = pageParameters(continuation) + mapOf("part" to "snippet", "safeSearch" to "moderate") +
                if (feed == YouTubeFeed.Live) mapOf("type" to "video", "eventType" to "live") else mapOf("type" to "channel")
            mapPage(request("search", parameters)) {
                val id = it["id"] as? JsonObject ?: throw MediaServiceException(MediaFailure.InvalidResponse)
                if (feed == YouTubeFeed.Live) entry(it, MediaKind.Video, id.text("videoId"))
                else entry(it, MediaKind.Channel, id.text("channelId"))
            }
        }
        YouTubeFeed.Music, YouTubeFeed.Gaming, YouTubeFeed.News, YouTubeFeed.Sports -> {
            val category = when (feed) { YouTubeFeed.Music -> "10"; YouTubeFeed.Gaming -> "20"; YouTubeFeed.News -> "25"; else -> "17" }
            mapPage(request("videos", pageParameters(continuation) + mapOf("part" to "snippet,contentDetails", "chart" to "mostPopular", "videoCategoryId" to category))) {
                entry(it, MediaKind.Video, it.text("id"))
            }
        }
        // No documented Data API watch-history/Shorts feed. Do not fabricate one.
        YouTubeFeed.History, YouTubeFeed.Shorts -> throw MediaServiceException(MediaFailure.Unavailable)
    }
    override suspend fun search(query: String, continuation: String?): MediaPage<MediaEntry> {
        require(query.isNotBlank() && query.length <= 500)
        val response = request("search", pageParameters(continuation) + mapOf("part" to "snippet", "q" to query.trim(), "type" to if (musicOnly) "video" else "video,channel,playlist", "safeSearch" to "moderate") + if (musicOnly) mapOf("videoCategoryId" to "10") else emptyMap())
        return mapPage(response) {
            val id = it["id"] as? JsonObject ?: throw MediaServiceException(MediaFailure.InvalidResponse)
            when (id.text("kind")) {
                "youtube#video" -> entry(it, MediaKind.Video, id.text("videoId"))
                "youtube#channel" -> entry(it, MediaKind.Channel, id.text("channelId"))
                "youtube#playlist" -> entry(it, MediaKind.Playlist, id.text("playlistId"))
                else -> throw MediaServiceException(MediaFailure.InvalidResponse)
            }
        }
    }
    override suspend fun browse(key: MediaKey, continuation: String?): MediaPage<MediaEntry> {
        require(key.service == identity)
        val id = key.id.substringAfter(':')
        return when {
            key.id.startsWith("channel:") -> {
                val response = request("search", pageParameters(continuation) + mapOf("part" to "snippet", "channelId" to id, "type" to "video", "order" to "date"))
                mapPage(response) { entry(it, MediaKind.Video, (it["id"] as? JsonObject)?.text("videoId")) }
            }
            key.id.startsWith("playlist:") -> {
                val response = request("playlistItems", pageParameters(continuation) + mapOf("part" to "snippet", "playlistId" to id))
                mapPage(response) { entry(it, MediaKind.Video, ((it["snippet"] as? JsonObject)?.get("resourceId") as? JsonObject)?.text("videoId")) }
            }
            else -> throw MediaServiceException(MediaFailure.InvalidInput)
        }
    }
    override suspend fun comments(video: MediaKey, order: CommentOrder, continuation: String?): MediaPage<MediaComment> {
        require(video.service == identity && video.id.startsWith("video:"))
        val response = request("commentThreads", pageParameters(continuation) + mapOf("part" to "snippet", "videoId" to video.id.removePrefix("video:"), "order" to if (order == CommentOrder.Top) "relevance" else "time", "textFormat" to "plainText"))
        return mapPage(response) { thread ->
            val snippet = thread["snippet"]?.jsonObject ?: throw MediaServiceException(MediaFailure.InvalidResponse)
            val comment = snippet["topLevelComment"]?.jsonObject ?: throw MediaServiceException(MediaFailure.InvalidResponse)
            comment(comment, snippet.text("totalReplyCount")?.toIntOrNull() ?: 0)
        }
    }
    override suspend fun replies(commentId: String, continuation: String?): MediaPage<MediaComment> {
        require(commentId.isNotBlank())
        return mapPage(request("comments", pageParameters(continuation) + mapOf("part" to "snippet", "parentId" to commentId, "textFormat" to "plainText"))) { comment(it, 0) }
    }
    suspend fun account(): MediaAccountSummary {
        require(signedIn)
        val response = request("channels", mapOf("part" to "snippet", "mine" to "true"))
        val channel = (response["items"] as? JsonArray)?.firstOrNull()?.jsonObject ?: throw MediaServiceException(MediaFailure.Unavailable)
        val id = channel.text("id") ?: throw MediaServiceException(MediaFailure.InvalidResponse)
        return MediaAccountSummary(id, (channel["snippet"] as? JsonObject)?.text("title").orEmpty(), id)
    }
    suspend fun subscriptions(continuation: String? = null): MediaPage<MediaEntry> {
        require(signedIn)
        return mapPage(request("subscriptions", pageParameters(continuation) + mapOf("part" to "snippet", "mine" to "true", "order" to "alphabetical"))) {
            val snippet = it["snippet"]?.jsonObject ?: throw MediaServiceException(MediaFailure.InvalidResponse)
            val id = snippet["resourceId"]?.jsonObject?.text("channelId")
            entry(it, MediaKind.Channel, id)
        }
    }
    suspend fun subscriptionId(channel: MediaKey): String? {
        require(signedIn && channel.service == identity && channel.id.startsWith("channel:"))
        val response = request("subscriptions", mapOf("part" to "id", "mine" to "true", "forChannelId" to channel.id.removePrefix("channel:")))
        val items = response["items"] as? JsonArray ?: throw MediaServiceException(MediaFailure.InvalidResponse)
        return items.firstOrNull()?.jsonObject?.text("id")
    }
    suspend fun subscribe(channel: MediaKey): String {
        require(signedIn && channel.service == identity && channel.id.startsWith("channel:"))
        val response = request("subscriptions", mapOf("part" to "snippet"), HttpMethod.Post, buildJsonObject {
            putJsonObject("snippet") { putJsonObject("resourceId") { put("kind", "youtube#channel"); put("channelId", channel.id.removePrefix("channel:")) } }
        })
        return response.text("id") ?: throw MediaServiceException(MediaFailure.InvalidResponse)
    }
    suspend fun unsubscribe(subscriptionId: String) {
        require(signedIn && subscriptionId.isNotBlank())
        request("subscriptions", mapOf("id" to subscriptionId), HttpMethod.Delete)
    }
    override suspend fun playlists(): List<MediaEntry> {
        require(signedIn)
        val result = mutableListOf<MediaEntry>()
        var token: String? = null
        val seen = mutableSetOf<String>()
        do {
            val page = mapPage(request("playlists", pageParameters(token) + mapOf("part" to "snippet", "mine" to "true"))) { entry(it, MediaKind.Playlist, it.text("id")) }
            result += page.items
            token = page.continuation
            if (token != null && !seen.add(token)) throw MediaServiceException(MediaFailure.InvalidResponse)
            if (result.size > 3000) throw MediaServiceException(MediaFailure.Unavailable)
        } while (token != null)
        return result
    }
    override suspend fun liked(): List<MediaEntry> {
        require(signedIn)
        val result = mutableListOf<MediaEntry>()
        var token: String? = null
        val seen = mutableSetOf<String>()
        do {
            val page = mapPage(request("videos", pageParameters(token) + mapOf("part" to "snippet", "myRating" to "like"))) { entry(it, MediaKind.Video, it.text("id")).copy(liked = true) }
            result += page.items
            token = page.continuation
            if (token != null && !seen.add(token)) throw MediaServiceException(MediaFailure.InvalidResponse)
            if (result.size > 3000) throw MediaServiceException(MediaFailure.Unavailable)
        } while (token != null)
        return result
    }
    override suspend fun setLiked(key: MediaKey, liked: Boolean) {
        require(signedIn && key.service == identity && key.id.startsWith("video:"))
        request("videos/rate", mapOf("id" to key.id.removePrefix("video:"), "rating" to if (liked) "like" else "none"), HttpMethod.Post)
    }
    override suspend fun createPlaylist(title: String): MediaKey {
        require(signedIn && title.isNotBlank() && title.length <= 200)
        val response = request("playlists", mapOf("part" to "snippet,status"), HttpMethod.Post, buildJsonObject {
            putJsonObject("snippet") { put("title", title.trim()) }
            putJsonObject("status") { put("privacyStatus", "private") }
        })
        return MediaKey(identity, "playlist:" + (response.text("id") ?: throw MediaServiceException(MediaFailure.InvalidResponse)))
    }
    override suspend fun addToPlaylist(playlist: MediaKey, entries: List<MediaKey>) {
        require(signedIn && playlist.service == identity && playlist.id.startsWith("playlist:"))
        require(entries.size in 1..200 && entries.all { it.service == identity && it.id.startsWith("video:") })
        // The service has no batch transaction. A failure can leave earlier inserts committed;
        // callers must refresh rather than blindly retry the complete list.
        for (key in entries) request("playlistItems", mapOf("part" to "snippet"), HttpMethod.Post, buildJsonObject {
            putJsonObject("snippet") {
                put("playlistId", playlist.id.removePrefix("playlist:"))
                putJsonObject("resourceId") { put("kind", "youtube#video"); put("videoId", key.id.removePrefix("video:")) }
            }
        })
    }
    override suspend fun deletePlaylist(playlist: MediaKey) {
        require(signedIn && playlist.service == identity && playlist.id.startsWith("playlist:"))
        request("playlists", mapOf("id" to playlist.id.removePrefix("playlist:")), HttpMethod.Delete)
    }
    private fun comment(value: JsonObject, replies: Int): MediaComment {
        val snippet = value["snippet"]?.jsonObject ?: throw MediaServiceException(MediaFailure.InvalidResponse)
        return MediaComment(value.text("id") ?: throw MediaServiceException(MediaFailure.InvalidResponse), snippet.text("authorDisplayName").orEmpty(), snippet.text("textDisplay").orEmpty(), replies)
    }
    private fun entry(value: JsonObject, kind: MediaKind, id: String?): MediaEntry {
        if (id.isNullOrBlank()) throw MediaServiceException(MediaFailure.InvalidResponse)
        val snippet = value["snippet"] as? JsonObject ?: throw MediaServiceException(MediaFailure.InvalidResponse)
        val prefix = when (kind) { MediaKind.Channel -> "channel"; MediaKind.Playlist -> "playlist"; else -> "video" }
        val thumbnails = snippet["thumbnails"] as? JsonObject
        val image = ((thumbnails?.get("medium") ?: thumbnails?.get("default")) as? JsonObject)?.text("url")
            ?.takeIf { it.startsWith("https://") }
        return MediaEntry(MediaKey(identity, "$prefix:$id"), kind, snippet.text("title").orEmpty(), snippet.text("channelTitle").orEmpty(),
            artwork = image, channelId = snippet.text("channelId"), isLive = snippet.text("liveBroadcastContent") == "live", upcoming = snippet.text("liveBroadcastContent") == "upcoming")
    }
    private fun pageParameters(token: String?): Map<String, String> {
        require(token == null || token.length in 1..4096)
        return mapOf("maxResults" to "30") + if (token == null) emptyMap() else mapOf("pageToken" to token)
    }
    private fun <T> mapPage(value: JsonObject, transform: (JsonObject) -> T): MediaPage<T> {
        val items = value["items"] as? JsonArray ?: throw MediaServiceException(MediaFailure.InvalidResponse)
        return MediaPage(items.map { transform(it.jsonObject) }, value.text("nextPageToken")?.takeIf { it.isNotBlank() })
    }
    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
