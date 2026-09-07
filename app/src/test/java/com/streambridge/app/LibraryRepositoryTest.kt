package com.streambridge.app

import com.streambridge.app.addon.model.MediaDetails
import com.streambridge.app.addon.model.MediaItem
import com.streambridge.app.data.db.ExtensionDao
import com.streambridge.app.data.db.ExtensionEntity
import com.streambridge.app.data.db.LibraryDao
import com.streambridge.app.data.db.LibraryItemEntity
import com.streambridge.app.data.db.ProgressDao
import com.streambridge.app.data.db.WatchProgressEntity
import com.streambridge.app.data.library.LibraryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Library persistence logic against in-memory fake DAOs that mirror the
 * Room query semantics.
 */
class LibraryRepositoryTest {

    private class FakeLibraryDao : LibraryDao {
        val items = MutableStateFlow<Map<String, LibraryItemEntity>>(emptyMap())

        override suspend fun upsert(item: LibraryItemEntity) {
            items.value = items.value + (item.metaKey to item)
        }

        override suspend fun byKey(metaKey: String): LibraryItemEntity? = items.value[metaKey]

        override fun observeByKey(metaKey: String): Flow<LibraryItemEntity?> =
            items.map { it[metaKey] }

        override fun observeFavorites(): Flow<List<LibraryItemEntity>> =
            items.map { map -> map.values.filter { it.favorite }.sortedByDescending { it.addedAt } }

        override fun observeWatchlist(): Flow<List<LibraryItemEntity>> =
            items.map { map -> map.values.filter { it.watchlist }.sortedByDescending { it.addedAt } }

        override fun observeRecentlyAdded(limit: Int): Flow<List<LibraryItemEntity>> =
            items.map { map -> map.values.sortedByDescending { it.addedAt }.take(limit) }

        override suspend fun setFavorite(metaKey: String, favorite: Boolean) {
            items.value[metaKey]?.let {
                items.value = items.value + (metaKey to it.copy(favorite = favorite))
            }
        }

        override suspend fun setWatchlist(metaKey: String, watchlist: Boolean) {
            items.value[metaKey]?.let {
                items.value = items.value + (metaKey to it.copy(watchlist = watchlist))
            }
        }

        override suspend fun pruneDetached(metaKey: String) {
            items.value[metaKey]?.let { entity ->
                if (!entity.favorite && !entity.watchlist) {
                    items.value = items.value - metaKey
                }
            }
        }
    }

    private class FakeProgressDao : ProgressDao {
        val rows = MutableStateFlow<List<WatchProgressEntity>>(emptyList())

        override suspend fun upsert(progress: WatchProgressEntity) {
            val existing = rows.value.indexOfFirst {
                it.metaKey == progress.metaKey && it.videoId == progress.videoId
            }
            rows.value = if (existing >= 0) {
                rows.value.toMutableList().also { it[existing] = progress }
            } else {
                rows.value + progress
            }
        }

        override suspend fun get(metaKey: String, videoId: String): WatchProgressEntity? =
            rows.value.firstOrNull { it.metaKey == metaKey && it.videoId == videoId }

        override fun observeForMeta(metaKey: String): Flow<List<WatchProgressEntity>> =
            rows.map { list -> list.filter { it.metaKey == metaKey }.sortedByDescending { it.updatedAt } }

        override fun observeContinueWatching(thresholdPercent: Int): Flow<List<WatchProgressEntity>> =
            rows.map { list ->
                list.filter { row ->
                    row.durationMs > 0 &&
                        row.positionMs < (row.durationMs * thresholdPercent / 100)
                }.sortedByDescending { row ->
                    list.filter { it.metaKey == row.metaKey }.maxOf { it.updatedAt }
                }.distinctBy { it.metaKey }
            }

        override fun observeHistory(limit: Int): Flow<List<WatchProgressEntity>> =
            rows.map { list -> list.sortedByDescending { it.updatedAt }.take(limit) }

        override suspend fun all(): List<WatchProgressEntity> = rows.value

        override suspend fun delete(metaKey: String, videoId: String) {
            rows.value = rows.value.filterNot {
                it.metaKey == metaKey && it.videoId == videoId
            }
        }

        override suspend fun clearAll() {
            rows.value = emptyList()
        }
    }

    private class FakeExtensionDao : ExtensionDao {
        val rows = MutableStateFlow<Map<String, ExtensionEntity>>(emptyMap())
        override fun observeAll(): Flow<List<ExtensionEntity>> =
            rows.map { it.values.sortedBy { entity -> entity.name } }

        override fun observeEnabled(): Flow<List<ExtensionEntity>> =
            rows.map { map -> map.values.filter { it.enabled } }

        override suspend fun byId(addonId: String): ExtensionEntity? = rows.value[addonId]

        override suspend fun upsert(entity: ExtensionEntity) {
            rows.value = rows.value + (entity.addonId to entity)
        }

        override suspend fun delete(addonId: String) {
            rows.value = rows.value - addonId
        }
    }

    private val libraryDao = FakeLibraryDao()
    private val progressDao = FakeProgressDao()
    private val repository = LibraryRepository(libraryDao, progressDao)

    private fun movieItem() = MediaItem(
        id = "tt123",
        imdbId = "tt123",
        type = "movie",
        name = "Test Movie",
        poster = "https://example.com/p.jpg",
        backdrop = "https://example.com/b.jpg",
        releaseInfo = "2024",
        rating = "7.8",
        source = "test"
    )

    private fun seriesDetails() = MediaDetails(
        id = "tt456",
        imdbId = "tt456",
        type = "series",
        name = "Test Series",
        poster = null,
        backdrop = null,
        logo = null,
        description = null,
        releaseInfo = "2010-2020",
        runtime = null,
        rating = null,
        genres = listOf("Drama"),
        cast = emptyList(),
        director = emptyList(),
        country = null,
        awards = null,
        episodes = emptyList(),
        sourceAddonBase = "https://addon.example.com"
    )

    @Test
    fun `favoriting an item creates a library entry`() = runTest {
        val item = movieItem()
        repository.setFavorite(item, true)

        val entity = libraryDao.byKey("movie:tt123")
        assertTrue(entity?.favorite == true)
        assertEquals("Test Movie", entity?.name)
    }

    @Test
    fun `unfavoriting keeps the row but clears the flag`() = runTest {
        val item = movieItem()
        repository.setFavorite(item, true)
        repository.setFavorite(item, false)

        val entity = libraryDao.byKey("movie:tt123")
        assertFalse(entity?.favorite == true)
    }

    @Test
    fun `favoriting details persists metadata`() = runTest {
        repository.setFavorite(seriesDetails(), true)
        val entity = libraryDao.byKey("series:tt456")
        assertTrue(entity?.favorite == true)
        assertEquals(listOf("Drama"), entity?.genres?.split(","))
    }

    @Test
    fun `progress is upserted per video`() = runTest {
        val item = movieItem()
        repository.saveProgress(item, videoId = "", season = 0, episode = 0, episodeTitle = null, positionMs = 1000, durationMs = 90000)
        repository.saveProgress(item, videoId = "", season = 0, episode = 0, episodeTitle = null, positionMs = 5000, durationMs = 90000)

        assertEquals(1, progressDao.rows.value.size)
        assertEquals(5000, progressDao.rows.value.first().positionMs)
    }

    @Test
    fun `saving progress keeps poster from earlier entry`() = runTest {
        val item = movieItem()
        repository.saveProgress(item, "", 0, 0, null, 1000, 90000)
        // A later save without artwork should not wipe the stored poster
        val bare = item.copy(poster = null, backdrop = null)
        repository.saveProgress(bare, "", 0, 0, null, 2000, 90000)

        val row = progressDao.rows.value.first()
        assertEquals("https://example.com/p.jpg", row.poster)
    }

    @Test
    fun `continue watching hides finished items`() = runTest {
        val item = movieItem()
        // Halfway through -> continue watching
        repository.saveProgress(item, "", 0, 0, null, 45_000, 90_000)
        // Finished -> filtered out at 95%
        repository.saveProgress(item.copy(id = "tt999"), "", 0, 0, null, 96_000, 100_000)

        val first = progressDao.observeContinueWatching(95).first()
        assertEquals(listOf("movie:tt123"), first.map { it.metaKey })
    }

    @Test
    fun `history returns most recent first`() = runTest {
        val item = movieItem()
        repository.saveProgress(item, "", 0, 0, null, 1000, 90_000)
        Thread.sleep(5)
        repository.saveProgress(
            item.copy(id = "tt888", name = "Second"),
            "", 0, 0, null, 1000, 90_000
        )

        val history = progressDao.observeHistory(10).first()
        assertEquals(listOf("movie:tt888", "movie:tt123"), history.map { it.metaKey })
    }

    @Test
    fun `clearing history empties progress`() = runTest {
        repository.saveProgress(movieItem(), "", 0, 0, null, 1000, 90_000)
        repository.clearAllHistory()
        assertTrue(progressDao.rows.value.isEmpty())
    }
}
