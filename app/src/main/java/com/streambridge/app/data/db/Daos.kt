package com.streambridge.app.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ExtensionDao {

    @Query("SELECT * FROM extensions ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<ExtensionEntity>>

    @Query("SELECT * FROM extensions WHERE enabled = 1 ORDER BY name COLLATE NOCASE ASC")
    fun observeEnabled(): Flow<List<ExtensionEntity>>

    @Query("SELECT * FROM extensions WHERE addonId = :addonId")
    suspend fun byId(addonId: String): ExtensionEntity?

    @Upsert
    suspend fun upsert(entity: ExtensionEntity)

    @Query("DELETE FROM extensions WHERE addonId = :addonId")
    suspend fun delete(addonId: String)
}

@Dao
interface LibraryDao {

    @Upsert
    suspend fun upsert(item: LibraryItemEntity)

    @Query("SELECT * FROM library_items WHERE metaKey = :metaKey")
    suspend fun byKey(metaKey: String): LibraryItemEntity?

    @Query("SELECT * FROM library_items WHERE metaKey = :metaKey")
    fun observeByKey(metaKey: String): Flow<LibraryItemEntity?>

    @Query("SELECT * FROM library_items WHERE favorite = 1 ORDER BY addedAt DESC")
    fun observeFavorites(): Flow<List<LibraryItemEntity>>

    @Query("SELECT * FROM library_items WHERE watchlist = 1 ORDER BY addedAt DESC")
    fun observeWatchlist(): Flow<List<LibraryItemEntity>>

    @Query("SELECT * FROM library_items ORDER BY addedAt DESC LIMIT :limit")
    fun observeRecentlyAdded(limit: Int): Flow<List<LibraryItemEntity>>

    @Query("UPDATE library_items SET favorite = :favorite WHERE metaKey = :metaKey")
    suspend fun setFavorite(metaKey: String, favorite: Boolean)

    @Query("UPDATE library_items SET watchlist = :watchlist WHERE metaKey = :metaKey")
    suspend fun setWatchlist(metaKey: String, watchlist: Boolean)

    @Query("DELETE FROM library_items WHERE metaKey = :metaKey AND favorite = 0 AND watchlist = 0")
    suspend fun pruneDetached(metaKey: String)
}

@Dao
interface ProgressDao {

    @Upsert
    suspend fun upsert(progress: WatchProgressEntity)

    @Query("SELECT * FROM watch_progress WHERE metaKey = :metaKey AND videoId = :videoId")
    suspend fun get(metaKey: String, videoId: String): WatchProgressEntity?

    @Query("SELECT * FROM watch_progress WHERE metaKey = :metaKey ORDER BY updatedAt DESC")
    fun observeForMeta(metaKey: String): Flow<List<WatchProgressEntity>>

    @Query(
        "SELECT * FROM watch_progress wp WHERE wp.durationMs > 0 " +
            "AND wp.positionMs < (wp.durationMs * :thresholdPercent / 100) " +
            "AND wp.updatedAt = (SELECT MAX(w2.updatedAt) FROM watch_progress w2 WHERE w2.metaKey = wp.metaKey) " +
            "ORDER BY wp.updatedAt DESC"
    )
    fun observeContinueWatching(thresholdPercent: Int): Flow<List<WatchProgressEntity>>

    @Query("SELECT * FROM watch_progress ORDER BY updatedAt DESC LIMIT :limit")
    fun observeHistory(limit: Int): Flow<List<WatchProgressEntity>>

    @Query("SELECT * FROM watch_progress")
    suspend fun all(): List<WatchProgressEntity>

    @Query("DELETE FROM watch_progress WHERE metaKey = :metaKey AND videoId = :videoId")
    suspend fun delete(metaKey: String, videoId: String)

    @Query("DELETE FROM watch_progress")
    suspend fun clearAll()
}
