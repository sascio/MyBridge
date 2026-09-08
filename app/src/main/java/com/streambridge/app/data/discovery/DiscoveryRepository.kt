package com.streambridge.app.data.discovery

import com.streambridge.app.addon.AddonApi
import com.streambridge.app.addon.ExtensionManager
import com.streambridge.app.addon.InstalledExtension
import com.streambridge.app.addon.model.AddonMeta
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Aggregates catalogs from every enabled extension (plus the optional
 * TMDB/MDBList integrations when the user has enabled them) into home
 * sections, search results, genre browsing and detail lookups.
 *
 * Performance contract:
 *  - Home content is emitted PROGRESSIVELY: every catalog that answers
 *    updates the screen immediately; one slow addon never holds the Home
 *    UI hostage (it surfaces as an error chip when it finally times out).
 *  - Catalog pages and metadata lookups go through a small in-memory
 *    cache with TTL plus in-flight deduplication, so returning to Home,
 *    re-opening a detail page, or several screens asking for the same
 *    data does not re-download it.
 *  - Merging/ranking CPU work runs on Dispatchers.Default, never on main.
 *  - Every addon request is individually timed out and error-contained.
 */
class DiscoveryRepository(
    private val api: AddonApi,
    private val extensionManager: ExtensionManager,
    private val tmdb: TmdbClient,
    private val mdblist: MdbListClient,
    private val dedupeScope: CoroutineScope
) {

    // -----------------------------------------------------------------
    // Cache + in-flight deduplication
    // -----------------------------------------------------------------

    private class CacheEntry<T>(val value: T, val at: Long)

    private class FetchJob<T> {
        val completed = CompletableDeferred<T>()
    }

    private val cache = ConcurrentHashMap<String, CacheEntry<*>>()
    private val inFlight = ConcurrentHashMap<String, FetchJob<*>>()

    /**
     * Shared in-flight fetch; concurrent callers await the same result.
     * The fetch runs on [dedupeScope] so it is not cancelled when one of
     * the awaiting callers goes away (screen closed) — the result stays
     * useful for the others and completes into the cache.
     */
    private suspend fun <T> deduped(key: String, fetch: suspend () -> T): T {
        @Suppress("UNCHECKED_CAST")
        val existing = inFlight[key] as? FetchJob<T>
        val job = existing ?: run {
            val candidate = FetchJob<T>()
            val winner = inFlight.putIfAbsent(key, candidate) ?: candidate
            if (winner === candidate) {
                dedupeScope.launch {
                    try {
                        candidate.completed.complete(fetch())
                    } catch (e: Throwable) {
                        candidate.completed.completeExceptionally(e)
                    } finally {
                        inFlight.remove(key, candidate)
                    }
                }
                candidate
            } else {
                @Suppress("UNCHECKED_CAST")
                winner as FetchJob<T>
            }
        }
        return job.completed.await()
    }

    /**
     * Cached + deduplicated catalog fetch. Catalog pages are cached for
     * [CATALOG_CACHE_TTL_MS]; searches are deduplicated in-flight but not
     * cached (queries are unique and short-lived).
     */
    suspend fun fetchCatalogCached(
        baseUrl: String,
        type: String,
        catalogId: String,
        search: String? = null,
        genre: String? = null,
        skip: Int? = null,
        force: Boolean = false
    ): com.streambridge.app.addon.model.CatalogResponse {
        val key = "catalog|$baseUrl|$type|$catalogId|$search|$genre|$skip"
        if (search != null) {
            return deduped(key) { api.fetchCatalog(baseUrl, type, catalogId, search, genre, skip) }
        }
        return deduped(key) {
            cachedInternal(key, CATALOG_CACHE_TTL_MS, force) {
                api.fetchCatalog(baseUrl, type, catalogId, search, genre, skip)
            }
        }
    }

    /** Cached + deduplicated meta lookup. */
    suspend fun fetchMetaCached(
        baseUrl: String,
        type: String,
        id: String
    ): AddonMeta? {
        val key = "meta|$baseUrl|$type|$id"
        return deduped(key) {
            cachedInternal(key, META_CACHE_TTL_MS, force = false) {
                api.fetchMeta(baseUrl, type, id)
            }
        }
    }

    private suspend fun <T> cachedInternal(
        key: String,
        ttlMs: Long,
        force: Boolean,
        fetch: suspend () -> T
    ): T {
        if (!force) {
            @Suppress("UNCHECKED_CAST")
            (cache[key] as? CacheEntry<T>)?.let { entry ->
                if (System.currentTimeMillis() - entry.at < ttlMs) return entry.value
            }
        }
        val value = fetch()
        cache[key] = CacheEntry(value, System.currentTimeMillis())
        return value
    }

    // -----------------------------------------------------------------
    // Home (progressive)
    // -----------------------------------------------------------------

    /**
     * Builds the home content and emits it progressively: each catalog
     * that resolves produces an updated [HomeData] so the UI renders what
     * it has while slower addons are still loading. The final emission
     * includes error chips for failed/timed-out sources.
     */
    fun loadHomeProgressive(
        settings: SettingsState,
        watchedKeys: Set<String>,
        force: Boolean = false
    ): Flow<HomeData> = channelFlow {
        val extensions = extensionManager.enabledExtensions.value
        val refs = extensionManager.catalogRefs(extensions)
        val errors = ConcurrentLinkedQueue<String>()
        val results = ConcurrentHashMap<String, Triple<String, String, List<MediaItem>>>()
        val extraRails = ConcurrentLinkedQueue<HomeSection.Rail>()

        suspend fun emit(final: Boolean) {
            val data = buildHome(
                results.values.toList(),
                extraRails.toList(),
                errors.toList(),
                watchedKeys,
                includeErrors = final
            )
            // Skip intermediate emissions with nothing visible yet (the
            // first answered catalog may legitimately be empty).
            val hasContent = data.sections.any { it is HomeSection.Rail || it is HomeSection.Hero }
            if (final || hasContent) {
                trySend(data)
            }
        }

        val jobs = mutableListOf<Job>()
        for (ref in refs) {
            jobs += launch {
                val key = ref.compositeId + ":" + ref.type
                val items = try {
                    withTimeoutOrNull(CATALOG_TIMEOUT_MS) {
                        fetchCatalogCached(
                            baseUrl = ref.baseUrl,
                            type = ref.type,
                            catalogId = ref.catalogId,
                            force = force
                        )
                    }?.metas?.map { it.toMediaItem(ref.addonId) }
                        ?: run {
                            errors.add("${ref.addonName}: request timed out")
                            emptyList()
                        }
                } catch (e: Exception) {
                    errors.add("${ref.addonName}: ${friendlyError(e)}")
                    emptyList()
                }
                results[key] = Triple(key, ref.catalogName, items)
                emit(final = false)
            }
        }

        // Optional TMDB rails — loaded in parallel, never sequentially.
        if (settings.tmdbActive) {
            val tmdbRails: List<Pair<String, suspend () -> List<MediaItem>>> = listOf(
                "TMDB · Trending" to { tmdb.trending(settings.tmdbApiKey) },
                "TMDB · Popular Movies" to { tmdb.popularMovies(settings.tmdbApiKey) },
                "TMDB · Popular Series" to { tmdb.popularSeries(settings.tmdbApiKey) }
            )
            for ((title, loader) in tmdbRails) {
                jobs += launch {
                    val items = try {
                        loader().take(RAIL_SIZE)
                    } catch (e: Exception) {
                        errors.add("$title: ${friendlyError(e)}")
                        emptyList()
                    }
                    if (items.isNotEmpty()) {
                        extraRails.add(HomeSection.Rail("tmdb:$title", title, null, items, "tmdb"))
                    }
                    emit(final = false)
                }
            }
        }

        // Optional MDBList rails — parallel.
        if (settings.mdblistActive) {
            jobs += launch {
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
                            extraRails.add(
                                HomeSection.Rail("mdblist:${list.id}", list.name, "MDBList", items, "mdblist")
                            )
                        }
                    }
                    emit(final = false)
                } catch (e: Exception) {
                    errors.add("MDBList: ${friendlyError(e)}")
                }
            }
        }

        jobs.joinAll()
        emit(final = true)
    }

    /** Pure section-building, run on Dispatchers.Default. */
    private suspend fun buildHome(
        catalogResults: List<Triple<String, String, List<MediaItem>>>,
        extraRails: List<HomeSection.Rail>,
        errors: List<String>,
        watchedKeys: Set<String>,
        includeErrors: Boolean
    ): HomeData = withContext(Dispatchers.Default) {
        val sections = mutableListOf<HomeSection>()

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

        // Integration rails keep their relative position after the merged rails.
        sections.addAll(extraRails)

        // One rail per extension catalog.
        for ((key, title, items) in catalogResults) {
            if (items.isNotEmpty()) {
                sections.add(HomeSection.Rail(key, title, null, items.take(RAIL_SIZE), key.substringBefore("::")))
            }
        }

        // Recommendations from watch-history genre affinity.
        if (allItems.isNotEmpty()) {
            val watchedGenres = allItems.asSequence()
                .filter { it.key in watchedKeys }
                .flatMap { item -> item.genres.asSequence() }
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() }
                .toSet()
            val recommendations =
                MetaMerger.recommendations(allItems, watchedGenres, watchedKeys, RAIL_SIZE)
            if (recommendations.isNotEmpty()) {
                sections.add(
                    HomeSection.Rail(
                        "recommended",
                        "Recommended for you",
                        "Based on what you watch",
                        recommendations,
                        "merged"
                    )
                )
            }

            // Genre chips when catalogs expose genre data.
            val genres: List<GenreInfo> = MetaMerger.topGenres(allItems, MAX_GENRES)
            if (genres.isNotEmpty()) {
                sections.add(HomeSection.Genres(genres))
            }
        }

        HomeData(sections, if (includeErrors) errors else emptyList())
    }

    // -----------------------------------------------------------------
    // Search
    // -----------------------------------------------------------------

    /** Searches across all enabled extensions (and TMDB when active). */
    suspend fun search(query: String, settings: SettingsState): List<MediaItem> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        val extensions = extensionManager.enabledExtensions.value
        val refs = extensionManager.catalogRefs(extensions).filter { it.supportsSearch }

        return coroutineScope {
            val addonAsync = refs.map { ref ->
                async {
                    try {
                        withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                            fetchCatalogCached(
                                baseUrl = ref.baseUrl,
                                type = ref.type,
                                catalogId = ref.catalogId,
                                search = trimmed
                            )
                        }?.metas?.map { it.toMediaItem(ref.addonId) } ?: emptyList()
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            }
            val tmdbAsync = async {
                if (settings.tmdbActive) {
                    try {
                        tmdb.search(settings.tmdbApiKey, trimmed)
                    } catch (_: Exception) {
                        emptyList()
                    }
                } else {
                    emptyList()
                }
            }
            val results = (addonAsync + tmdbAsync).awaitAll().flatten()
            withContext(Dispatchers.Default) {
                MetaMerger.merge(results)
            }
        }
    }

    // -----------------------------------------------------------------
    // Genre browsing
    // -----------------------------------------------------------------

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
                                fetchCatalogCached(
                                    baseUrl = ref.baseUrl,
                                    type = ref.type,
                                    catalogId = ref.catalogId,
                                    genre = genre
                                )
                            }?.metas?.map { it.toMediaItem(ref.addonId) } ?: emptyList()
                        } else {
                            // Server does not support the genre extra: fetch and filter locally.
                            withTimeoutOrNull(CATALOG_TIMEOUT_MS) {
                                fetchCatalogCached(
                                    baseUrl = ref.baseUrl,
                                    type = ref.type,
                                    catalogId = ref.catalogId
                                )
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

        return withContext(Dispatchers.Default) {
            MetaMerger.merge(results)
        }
    }

    // -----------------------------------------------------------------
    // Details
    // -----------------------------------------------------------------

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
                            fetchMetaCached(extension.baseUrl, item.type, candidate)
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
    suspend fun loadTmdbSeason(
        item: MediaItem,
        season: Int,
        settings: SettingsState
    ): List<com.streambridge.app.addon.model.Episode> {
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

    private companion object {
        const val CATALOG_TIMEOUT_MS = 25000L
        const val SEARCH_TIMEOUT_MS = 20000L
        const val CATALOG_CACHE_TTL_MS = 5 * 60 * 1000L
        const val META_CACHE_TTL_MS = 10 * 60 * 1000L
        const val RAIL_SIZE = 30
        const val HERO_SIZE = 5
        const val MAX_GENRES = 12
        const val MAX_MDBLIST_RAILS = 4
    }
}
