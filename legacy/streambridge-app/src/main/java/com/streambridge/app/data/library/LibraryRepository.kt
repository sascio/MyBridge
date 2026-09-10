package com.streambridge.app.data.library

import com.streambridge.app.addon.model.MediaDetails
import com.streambridge.app.addon.model.MediaItem
import com.streambridge.app.data.db.LibraryDao
import com.streambridge.app.data.db.LibraryItemEntity
import com.streambridge.app.data.db.ProgressDao
import com.streambridge.app.data.db.WatchProgressEntity
import kotlinx.coroutines.flow.Flow

/**
 * Favorites, watchlist, watch history, continue-watching and playback
 * positions. All persisted locally with Room.
 */
class LibraryRepository(
    private val libraryDao: LibraryDao,
    private val progressDao: ProgressDao
) {

    // -----------------------------------------------------------------
    // Favorites & watchlist
    // -----------------------------------------------------------------

    fun observeItem(metaKey: String): Flow<LibraryItemEntity?> =
        libraryDao.observeByKey(metaKey)

    fun observeFavorites(): Flow<List<LibraryItemEntity>> =
        libraryDao.observeFavorites()

    fun observeWatchlist(): Flow<List<LibraryItemEntity>> =
        libraryDao.observeWatchlist()

    fun observeRecentlyAdded(limit: Int = 20): Flow<List<LibraryItemEntity>> =
        libraryDao.observeRecentlyAdded(limit)

    suspend fun setFavorite(item: MediaItem, favorite: Boolean) {
        upsertFlags(item.metaKeyFromItem(), item, favorite = favorite)
    }

    suspend fun setWatchlist(item: MediaItem, watchlist: Boolean) {
        upsertFlags(item.metaKeyFromItem(), item, watchlist = watchlist)
    }

    suspend fun setFavorite(details: MediaDetails, favorite: Boolean) {
        upsertFlags("${details.type}:${details.id}", details, favorite = favorite)
    }

    suspend fun setWatchlist(details: MediaDetails, watchlist: Boolean) {
        upsertFlags("${details.type}:${details.id}", details, watchlist = watchlist)
    }

    private suspend fun upsertFlags(
        metaKey: String,
        item: MediaItem,
        favorite: Boolean? = null,
        watchlist: Boolean? = null
    ) {
        val existing = libraryDao.byKey(metaKey)
        val now = System.currentTimeMillis()
        val entity = (existing ?: LibraryItemEntity(
            metaKey = metaKey,
            type = item.type,
            id = item.id,
            name = item.name,
            poster = item.poster,
            backdrop = item.backdrop,
            releaseInfo = item.releaseInfo,
            rating = item.rating,
            runtime = null,
            description = item.description,
            genres = item.genres.joinToString(","),
            favorite = false,
            watchlist = false,
            addedAt = now
        )).let { current ->
            current.copy(
                name = item.name.ifBlank { current.name },
                poster = item.poster ?: current.poster,
                backdrop = item.backdrop ?: current.backdrop,
                releaseInfo = item.releaseInfo ?: current.releaseInfo,
                rating = item.rating ?: current.rating,
                favorite = favorite ?: current.favorite,
                watchlist = watchlist ?: current.watchlist,
                addedAt = now
            )
        }
        libraryDao.upsert(entity)
    }

    private suspend fun upsertFlags(
        metaKey: String,
        details: MediaDetails,
        favorite: Boolean? = null,
        watchlist: Boolean? = null
    ) {
        val existing = libraryDao.byKey(metaKey)
        val now = System.currentTimeMillis()
        val entity = (existing ?: LibraryItemEntity(
            metaKey = metaKey,
            type = details.type,
            id = details.id,
            name = details.name,
            poster = details.poster,
            backdrop = details.backdrop,
            releaseInfo = details.releaseInfo,
            rating = details.rating,
            runtime = details.runtime,
            description = details.description,
            genres = details.genres.joinToString(","),
            favorite = false,
            watchlist = false,
            addedAt = now
        )).let { current ->
            current.copy(
                name = details.name.ifBlank { current.name },
                poster = details.poster ?: current.poster,
                backdrop = details.backdrop ?: current.backdrop,
                releaseInfo = details.releaseInfo ?: current.releaseInfo,
                rating = details.rating ?: current.rating,
                runtime = details.runtime ?: current.runtime,
                description = details.description ?: current.description,
                favorite = favorite ?: current.favorite,
                watchlist = watchlist ?: current.watchlist,
                addedAt = now
            )
        }
        libraryDao.upsert(entity)
    }

    // -----------------------------------------------------------------
    // Progress / history / continue watching
    // -----------------------------------------------------------------

    fun observeContinueWatching(thresholdPercent: Int): Flow<List<WatchProgressEntity>> =
        progressDao.observeContinueWatching(thresholdPercent)

    fun observeHistory(limit: Int = 200): Flow<List<WatchProgressEntity>> =
        progressDao.observeHistory(limit)

    fun observeProgressForMeta(metaKey: String): Flow<List<WatchProgressEntity>> =
        progressDao.observeForMeta(metaKey)

    suspend fun progressFor(metaKey: String, videoId: String): WatchProgressEntity? =
        progressDao.get(metaKey, videoId)

    /** Upserts playback position for a movie or episode. */
    suspend fun saveProgress(
        item: MediaItem,
        videoId: String,
        season: Int,
        episode: Int,
        episodeTitle: String?,
        positionMs: Long,
        durationMs: Long
    ) {
        val metaKey = item.metaKeyFromItem()
        val existing = progressDao.get(metaKey, videoId)
        val now = System.currentTimeMillis()
        val entity = WatchProgressEntity(
            metaKey = metaKey,
            videoId = videoId,
            type = item.type,
            metaId = item.id,
            imdbId = item.imdbId ?: existing?.imdbId,
            metaName = item.name,
            poster = item.poster ?: existing?.poster,
            backdrop = item.backdrop ?: existing?.backdrop,
            season = season,
            episode = episode,
            episodeTitle = episodeTitle,
            positionMs = positionMs.coerceAtLeast(0L),
            durationMs = if (durationMs > 0) durationMs else (existing?.durationMs ?: 0L),
            updatedAt = now
        )
        progressDao.upsert(entity)
    }

    suspend fun clearProgress(metaKey: String, videoId: String) {
        progressDao.delete(metaKey, videoId)
    }

    suspend fun clearAllHistory() {
        progressDao.clearAll()
    }

    suspend fun allProgress(): List<WatchProgressEntity> = progressDao.all()
}

private fun MediaItem.metaKeyFromItem(): String = "$type:$id"
