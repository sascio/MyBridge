package com.streambridge.app.data.discovery

import com.streambridge.app.addon.AddonApi
import com.streambridge.app.addon.ExtensionManager
import com.streambridge.app.addon.InstalledExtension
import com.streambridge.app.addon.model.GenreInfo
import com.streambridge.app.addon.model.HomeData
import com.streambridge.app.addon.model.HomeSection
import com.streambridge.app.addon.model.MediaDetails
import com.streambridge.app.addon.model.MediaItem
import com.streambridge.app.addon.model.toMediaDetails
import com.streambridge.app.addon.model.toMediaItem
import com.streambridge.app.data.integrations.MdbListClient
import com.streambridge.app.data.integrations.TmdbClient
import com.streambridge.app.data.settings.SettingsState
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Aggregates catalogs from every enabled extension (plus the optional
 * TMDB/MDBList integrations when the user has enabled them) into home
 * sections, search results, genre browsing and detail lookups.
 */
class DiscoveryRepository(
    private val api: AddonApi,
    private val extensionManager: ExtensionManager,
    private val tmdb: TmdbClient,
    private val mdblist: MdbListClient
) {

    /**
     * Builds the home content. No fake data: sections only appear when
     * extensions (or integrations) actually return items.
     */
    suspend fun loadHome(
        settings: SettingsState,
        watchedKeys: Set<String>
    ): HomeData {
        val extensions = extensionManager.enabledExtensions.value
        val refs = extensionManager.catalogRefs(extensions)
        val errors = ConcurrentLinkedQueue<String>()

        val catalogResults: List<Triple<String, String, List<MediaItem>>> = coroutineScope {
            refs.map { ref ->
                async {
                    val items = try {
                        withTimeoutOrNull(CATALOG_TIMEOUT_MS) {
                            api.fetchCatalog(ref.baseUrl, ref.type, ref.catalogId)
                        }?.metas?.map { it.toMediaItem(ref.addonId) }
                            ?: run {
                                errors.add("${ref.addonName}: request timed out")
                                emptyList()
                            }
                    } catch (e: Exception) {
                        errors.add("${ref.addonName}: ${friendlyError(e)}")
                        emptyList()
                    }
                    Triple(ref.compositeId + ":" + ref.type, ref.catalogName, items)
                }
            }.awaitAll()
        }

        val sections = mutableListOf<HomeSection>()

        // Optional TMDB rails
        if (settings.tmdbActive) {
            val tmdbRails: List<Pair<String, suspend () -> List<MediaItem>>> = listOf(
                "TMDB · Trending" to { tmdb.trending(settings.tmdbApiKey) },
                "TMDB · Popular Movies" to { tmdb.popularMovies(settings.tmdbApiKey) },
                "TMDB · Popular Series" to { tmdb.popularSeries(settings.tmdbApiKey) }
            )
            for ((title, loader) in tmdbRails) {
                val items = try {
                    loader().take(RAIL_SIZE)
                } catch (e: Exception) {
                    errors.add("$title: ${friendlyError(e)}")
                    emptyList()
                }
                if (items.isNotEmpty()) {
                    sections.add(HomeSection.Rail("tmdb:$title", title, null, items, "tmdb"))
                }
            }
        }

        // Optional MDBList rails
        if (settings.mdblistActive) {
            try {
                val lists = mdblist.userLists(settings.mdblistApiKey).take(MAX_MDBLIST_RAILS)
                val rails = coroutineScope {
                    lists.map { list ->
                        async {
                            try {
                                mdblist.listItems(settings.mdblistApiKey, list.id).take(RAIL_SIZE)
                            } catch (e: Exception) {
                                errors.add("MDBList ${list.name}: ${friendlyError(e)}")
                                emptyList()
                            }
                        }
                    }.awaitAll()
                }
                lists.zip(rails).forEach { (list, items) ->
                    if (items.isNotEmpty()) {
                        sections.add(
                            HomeSection.Rail("mdblist:${list.id}", list.name, "MDBList", items, "mdblist")
                        )
                    }
                }
            } catch (e: Exception) {
                errors.add("MDBList: ${friendlyError(e)}")
            }
        }

        val allItems = catalogResults.flatMap { it.third }

        // Hero: rotate through the first catalog that has artwork.
        val heroPool = catalogResults.firstOrNull { it.third.any { item -> !item.backdrop.isNullOrBlank() } }
            ?: catalogResults.firstOrNull { it.third.isNotEmpty() }
        heroPool?.let { (_, _, items) ->
            val heroItems = items
                .sortedByDescending { !it.backdrop.isNullOrBlank() }
                .take(HERO_SIZE)
            if (heroItems.isNotEmpty()) {
                sections.add(HomeSection.Hero(heroItems))
            }
        }

        // Merged type rails when several catalogs contribute.
        val movieItems = allItems.filter { it.type == "movie" }
        val seriesItems = allItems.filter { it.type == "series" }
        val movieCatalogCount = catalogResults.count { it.third.any { item -> item.type == "movie" } }
        val seriesCatalogCount = catalogResults.count { it.third.any { item -> item.type == "series" } }
        if (movieCatalogCount > 1 && movieItems.isNotEmpty()) {
            sections.add(
                HomeSection.Rail(
                    "merged:movies", "Movies", null,
                    MetaMerger.merge(movieItems).take(RAIL_SIZE), "merged"
                )
            )
        }
        if (seriesCatalogCount > 1 && seriesItems.isNotEmpty()) {
            sections.add(
                HomeSection.Rail(
                    "merged:series", "TV Shows", null,
                    MetaMerger.merge(seriesItems).take(RAIL_SIZE), "merged"
                )
            )
        }

        // One rail per extension catalog.
        for ((key, title, items) in catalogResults) {
            if (items.isNotEmpty()) {
                sections.add(HomeSection.Rail(key, title, null, items.take(RAIL_SIZE), key.substringBefore("::")))
            }
        }

        // Recommendations from watch-history genre affinity.
        val watchedGenres = allItems.asSequence()
            .filter { it.key in watchedKeys }
            .flatMap { item -> item.genres.asSequence() }
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .toSet()
        val recommendations = MetaMerger.recommendations(allItems, watchedGenres, watchedKeys, RAIL_SIZE)
        if (recommendations.isNotEmpty()) {
            sections.add(HomeSection.Rail("recommended", "Recommended for you", "Based on what you watch", recommendations, "merged"))
        }

        // Genre chips when catalogs expose genre data.
        val genres: List<GenreInfo> = MetaMerger.topGenres(allItems, MAX_GENRES)
        if (genres.isNotEmpty()) {
            sections.add(HomeSection.Genres(genres))
        }

        return HomeData(sections, errors.toList())
    }

    /** Searches across all enabled extensions (and TMDB when active). */
    suspend fun search(query: String, settings: SettingsState): List<MediaItem> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        val extensions = extensionManager.enabledExtensions.value
        val refs = extensionManager.catalogRefs(extensions).filter { it.supportsSearch }

        val addonResults = coroutineScope {
            refs.map { ref ->
                async {
                    try {
                        withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                            api.fetchCatalog(ref.baseUrl, ref.type, ref.catalogId, search = trimmed)
                        }?.metas?.map { it.toMediaItem(ref.addonId) } ?: emptyList()
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }

        val tmdbResults = if (settings.tmdbActive) {
            try {
                tmdb.search(settings.tmdbApiKey, trimmed)
            } catch (_: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }

        return MetaMerger.merge(tmdbResults + addonResults)
    }

    /** Browses a genre across all enabled catalogs. */
    suspend fun browseGenre(genre: String, settings: SettingsState): List<MediaItem> {
        val extensions = extensionManager.enabledExtensions.value
        val refs = extensionManager.catalogRefs(extensions)

        val results = coroutineScope {
            refs.map { ref ->
                async {
                    try {
                        if (ref.supportsGenre) {
                            withTimeoutOrNull(CATALOG_TIMEOUT_MS) {
                                api.fetchCatalog(ref.baseUrl, ref.type, ref.catalogId, genre = genre)
                            }?.metas?.map { it.toMediaItem(ref.addonId) } ?: emptyList()
                        } else {
                            // Server does not support the genre extra: fetch and filter locally.
                            withTimeoutOrNull(CATALOG_TIMEOUT_MS) {
                                api.fetchCatalog(ref.baseUrl, ref.type, ref.catalogId)
                            }?.metas
                                ?.map { it.toMediaItem(ref.addonId) }
                                ?.filter { item ->
                                    item.genres.any { it.equals(genre, ignoreCase = true) }
                                }
                                ?: emptyList()
                        }
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }

        return MetaMerger.merge(results)
    }

    /**
     * Loads full details for an item: the source extension first, then a
     * fan-out across all enabled extensions, then TMDB (when active) for
     * TMDB-sourced items.
     */
    suspend fun loadDetails(
        item: MediaItem,
        extensions: List<InstalledExtension>,
        settings: SettingsState
    ): MediaDetails? {
        val isTmdbSource = item.source == TmdbClient.SOURCE || item.id.startsWith(TmdbClient.SOURCE_PREFIX)
        if (isTmdbSource) {
            val tmdbId = item.id.removePrefix(TmdbClient.SOURCE_PREFIX).toLongOrNull()
            if (settings.tmdbActive && tmdbId != null) {
                try {
                    val details = tmdb.details(settings.tmdbApiKey, item.type, tmdbId)
                    if (details != null) return details
                } catch (_: Exception) {
                }
            }
            // Fall through to addon lookups by imdb id when available.
        }

        val candidates = buildList {
            item.imdbId?.takeIf { it.isNotBlank() }?.let { add(it) }
            if (item.id != item.imdbId && item.id.isNotBlank()) add(item.id)
        }.distinct()
        if (candidates.isEmpty()) return null

        val ordered = extensions
            .filter { it.enabled && it.supportsMeta }
            .sortedWith(
                compareByDescending<InstalledExtension> {
                    it.addonId == settings.preferredMetadataAddon && settings.preferredMetadataAddon.isNotBlank()
                }.thenByDescending { it.addonId == item.source }
            )

        val metas = coroutineScope {
            ordered.map { extension ->
                async {
                    val meta = candidates.mapNotNull { candidate ->
                        try {
                            api.fetchMeta(extension.baseUrl, item.type, candidate)
                        } catch (_: Exception) {
                            null
                        }
                    }.firstOrNull()
                    if (meta != null) extension to meta else null
                }
            }.awaitAll().filterNotNull()
        }

        // Prefer a meta that actually carries episodes for series.
        val best = metas.maxWithOrNull(
            compareBy(
                { it.second.videos.isNotEmpty() },
                { it.first.addonId == item.source }
            )
        )
        return best?.let { (extension, meta) -> meta.toMediaDetails(extension.baseUrl) }
    }

    /** Loads a season of a TMDB-sourced series. */
    suspend fun loadTmdbSeason(item: MediaItem, season: Int, settings: SettingsState): List<com.streambridge.app.addon.model.Episode> {
        val tmdbId = item.id.removePrefix(TmdbClient.SOURCE_PREFIX).toLongOrNull() ?: return emptyList()
        if (!settings.tmdbActive || tmdbId == null) return emptyList()
        return try {
            tmdb.seasonEpisodes(settings.tmdbApiKey, tmdbId, season)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun friendlyError(e: Exception): String {
        return when (e) {
            is java.net.UnknownHostException -> "host not found"
            is java.net.SocketTimeoutException -> "timed out"
            is java.net.ConnectException -> "connection refused"
            else -> e.message?.take(80) ?: "request failed"
        }
    }

    companion object {
        private const val CATALOG_TIMEOUT_MS = 25000L
        private const val SEARCH_TIMEOUT_MS = 20000L
        private const val RAIL_SIZE = 30
        private const val HERO_SIZE = 5
        private const val MAX_GENRES = 12
        private const val MAX_MDBLIST_RAILS = 4
    }
}
