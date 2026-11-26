package com.nuvio.app.features.media.service

import kotlinx.serialization.Serializable

/** Service identities are deliberately distinct, including their credential namespaces. */
@Serializable
internal enum class MediaServiceId { YouTube, YouTubeMusic, Navidrome, WebDav, LastFm }

@Serializable
internal enum class MediaKind { Video, Track, Album, Artist, Playlist, Channel }

@Serializable
internal data class MediaKey(val service: MediaServiceId, val id: String) {
    init { require(id.isNotBlank()) }
}

@Serializable
internal data class MediaEntry(
    val key: MediaKey,
    val kind: MediaKind,
    val title: String,
    val subtitle: String = "",
    val artwork: String? = null,
    val durationMs: Long? = null,
    val channelId: String? = null,
    val isShort: Boolean = false,
    val isLive: Boolean = false,
    val upcoming: Boolean = false,
    val watched: Boolean = false,
    val liked: Boolean = false,
)

internal data class MediaPage<T>(val items: List<T>, val continuation: String? = null)
internal data class MediaShelf(val title: String, val page: MediaPage<MediaEntry>)
internal enum class MediaFailure { Authentication, Permission, RateLimited, Unavailable, Network, InvalidResponse, InvalidInput }

/** Never attach raw HTTP exceptions, request URLs, cookies or response bodies to UI errors. */
internal class MediaServiceException(val reason: MediaFailure) : Exception(reason.name)

internal interface CatalogueService {
    suspend fun home(continuation: String? = null): MediaPage<MediaEntry>
    suspend fun search(query: String, continuation: String? = null): MediaPage<MediaEntry>
    suspend fun browse(key: MediaKey, continuation: String? = null): MediaPage<MediaEntry>
}

internal interface MediaLibraryService {
    suspend fun playlists(): List<MediaEntry>
    suspend fun liked(): List<MediaEntry>
    suspend fun setLiked(key: MediaKey, liked: Boolean)
    suspend fun createPlaylist(title: String): MediaKey
    suspend fun addToPlaylist(playlist: MediaKey, entries: List<MediaKey>)
    suspend fun deletePlaylist(playlist: MediaKey)
}

/** A resolved URL is a short-lived capability; it must not enter persisted catalogue state. */
internal class ResolvedMedia(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val expiresAtEpochMs: Long? = null,
    val audioUrl: String? = null,
) {
    override fun toString(): String = "ResolvedMedia(redacted)"
}
internal interface MediaResolver {
    suspend fun resolve(key: MediaKey): ResolvedMedia
}
internal data class MediaProgress(val key: MediaKey, val positionMs: Long, val durationMs: Long, val updatedAtEpochMs: Long)
internal interface MediaHistoryService {
    suspend fun history(continuation: String? = null): MediaPage<MediaEntry>
    suspend fun record(progress: MediaProgress)
    suspend fun remove(key: MediaKey)
}
internal data class LyricLine(val atMs: Long, val text: String)
internal data class MediaLyrics(val source: String, val lines: List<LyricLine>, val synced: Boolean)
internal interface MediaLyricsService {
    suspend fun lyrics(entry: MediaEntry): MediaLyrics?
}
internal data class MediaComment(val id: String, val author: String, val text: String, val replyCount: Int)
internal enum class CommentOrder { Top, Newest }
internal interface MediaCommentsService {
    suspend fun comments(video: MediaKey, order: CommentOrder, continuation: String? = null): MediaPage<MediaComment>
    suspend fun replies(commentId: String, continuation: String? = null): MediaPage<MediaComment>
}

internal enum class MediaDownloadState { Queued, Downloading, Complete, Failed, Cancelled }
internal data class MediaDownload(val id: String, val key: MediaKey, val bytes: Long, val totalBytes: Long?, val state: MediaDownloadState)
/** Implementations must use the existing platform download owner, not a second manager. */
internal interface MediaDownloadService {
    suspend fun enqueue(key: MediaKey): String
    suspend fun cancel(id: String)
    suspend fun downloads(): List<MediaDownload>
}
internal interface MediaImportService {
    suspend fun importPublicPlaylist(url: String): MediaPage<MediaEntry>
}
internal interface MediaScrobbleService {
    suspend fun listened(entry: MediaEntry, startedAtEpochMs: Long, listenedMs: Long)
}
