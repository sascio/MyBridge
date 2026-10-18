package com.nuvio.app.features.settings

import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.tracking.TrackingExternalIds
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.extractTrackingYear
import com.nuvio.app.features.tracking.parseTrackingExternalIds
import com.nuvio.app.features.tracking.sharesIdentityWith
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watched.watchedItemKey
import com.nuvio.app.features.watchprogress.WatchProgressEntry

internal const val PROFILE_INSIGHTS_NUVIO_SOURCE_ID = "nuvio"

internal enum class ProfileInsightsFetchStatus {
    LOCAL,
    AVAILABLE,
    CACHED,
    REFRESHING,
    STALE,
    FAILED,
    NOT_CONNECTED,
    NOT_LOADED,
    UNSUPPORTED,
}

internal data class ProfileInsightsCapabilityState(
    val status: ProfileInsightsFetchStatus,
    val itemCount: Int = 0,
    val errorMessage: String? = null,
    val updatedAtEpochMs: Long? = null,
)

internal fun ProfileInsightsCapabilityState.hasFetchFailure(): Boolean =
    status !in setOf(ProfileInsightsFetchStatus.UNSUPPORTED, ProfileInsightsFetchStatus.NOT_CONNECTED) &&
        (errorMessage != null ||
            status == ProfileInsightsFetchStatus.FAILED ||
            status == ProfileInsightsFetchStatus.STALE)

/** A source snapshot keeps list membership, history, and playback progress separate. */
internal data class ProfileInsightsSourceSnapshot(
    val sourceId: String,
    val displayName: String,
    val connected: Boolean,
    val library: ProfileInsightsCapabilityState,
    val watched: ProfileInsightsCapabilityState,
    val progress: ProfileInsightsCapabilityState,
    val libraryItems: List<LibraryItem> = emptyList(),
    val watchedItems: List<WatchedItem> = emptyList(),
    val progressEntries: List<WatchProgressEntry> = emptyList(),
    val fullyWatchedSeriesKeys: Set<String> = emptySet(),
    val accountGeneration: Long? = null,
    val accountIdentity: String? = null,
)

internal data class ProfileInsightsSourcesState(
    val profileId: Int? = null,
    val sources: List<ProfileInsightsSourceSnapshot> = emptyList(),
    val isRefreshing: Boolean = false,
    val lastAttemptAtEpochMs: Long? = null,
    val lastSuccessAtEpochMs: Long? = null,
) {
    val unavailableConnectedCapabilityCount: Int
        get() = sources.sumOf { source ->
            if (!source.connected) return@sumOf 0
            listOf(source.library, source.watched, source.progress).count { it.hasFetchFailure() }
        }

    val hasPartialFailure: Boolean
        get() = unavailableConnectedCapabilityCount > 0
}

internal data class ProfileInsightsAggregate(
    val libraryItems: List<LibraryItem>,
    val watchedItems: List<WatchedItem>,
    val progressEntries: List<WatchProgressEntry>,
    val fullyWatchedSeriesKeys: Set<String>,
    val libraryGenresByTitleKey: Map<String, List<String>>,
    val watchedTitleKeys: List<String>,
    val sourceStatuses: List<ProfileInsightsSourceSnapshot>,
)

internal data class ProfileInsightsTasteSegment(
    val label: String,
    val share: Float,
)

internal data class ProfileInsightsWatchTime(
    val movieMs: Long = 0L,
    val seriesMs: Long = 0L,
    val estimatedMs: Long = 0L,
    val recordedMs: Long = 0L,
) {
    val totalMs: Long get() = movieMs + seriesMs

    val movieShare: Float?
        get() = if (totalMs > 0L) movieMs.toFloat() / totalMs.toFloat() else null
}

internal data class ProfileInsightsCalculatedMetrics(
    val libraryCount: Int,
    val continueCount: Int,
    val watchedMovieCount: Int,
    val completedSeriesCount: Int,
    val ongoingSeriesCount: Int,
    val episodesWatchedCount: Int,
    val watchedTitleCount: Int,
    val watchedTitleKeys: List<String>,
    val recentActivityCount: Int,
    val movieShare: Float,
    val topType: String?,
    val tasteSegments: List<ProfileInsightsTasteSegment>,
    val tasteKnownTitleCount: Int,
    val watchTime: ProfileInsightsWatchTime,
)

private data class ProfileIdentityCandidate(
    val sourceId: String,
    val kind: String,
    val id: String,
    val providerItemId: String?,
    val title: String,
    val releaseInfo: String?,
    val trackingProviderId: String? = null,
    val extraExternalIds: TrackingExternalIds = TrackingExternalIds(),
)

private data class ProfileIdentity(val kind: String, val canonicalId: String) {
    val titleKey: String get() = "$kind:$canonicalId"
}

private class ProfileIdentityIndex(candidates: List<ProfileIdentityCandidate>) {
    private val parent = IntArray(candidates.size) { it }
    private val rank = IntArray(candidates.size)
    private val candidatesWithIds = candidates.map { candidate -> candidate.externalIds() }

    private fun root(index: Int): Int {
        var current = index
        while (parent[current] != current) {
            parent[current] = parent[parent[current]]
            current = parent[current]
        }
        return current
    }

    private fun join(left: Int, right: Int) {
        var leftRoot = root(left)
        var rightRoot = root(right)
        if (leftRoot == rightRoot) return
        if (rank[leftRoot] < rank[rightRoot]) {
            val swap = leftRoot
            leftRoot = rightRoot
            rightRoot = swap
        }
        parent[rightRoot] = leftRoot
        if (rank[leftRoot] == rank[rightRoot]) rank[leftRoot] += 1
    }

    private val canonicalByRoot: Map<Int, ProfileIdentity> = buildMap {
        val indexesByExternalToken = mutableMapOf<String, MutableList<Int>>()
        candidates.forEachIndexed { index, candidate ->
            val ids = candidatesWithIds[index]
            ids.tokens().forEach { token ->
                indexesByExternalToken.getOrPut("${candidate.kind}|$token", ::mutableListOf) += index
            }
        }
        indexesByExternalToken.values.forEach { indexes ->
            indexes.drop(1).forEach { index -> join(indexes.first(), index) }
        }

        // Name matching is deliberately narrow: only an ID-less row can use an exact normalized
        // title + known year, and it may join an ID-backed group only when that match is unique.
        val idlessByTitleYear = mutableMapOf<String, MutableList<Int>>()
        candidates.forEachIndexed { index, candidate ->
            if (candidatesWithIds[index].hasAny) return@forEachIndexed
            val titleYear = candidate.titleYearKey() ?: return@forEachIndexed
            idlessByTitleYear.getOrPut("${candidate.kind}|$titleYear", ::mutableListOf) += index
        }
        idlessByTitleYear.forEach { (titleYearKey, idlessIndexes) ->
            val idBackedRoots = candidates.indices
                .filter { index -> candidates[index].kind + "|" + candidates[index].titleYearKey() == titleYearKey }
                .filter { index -> candidatesWithIds[index].hasAny }
                .map(::root)
                .distinct()
            val idlessRoots = idlessIndexes.map(::root).distinct()
            when (idBackedRoots.size) {
                0 -> idlessRoots.drop(1).forEach { join(idlessRoots.first(), it) }
                1 -> idlessRoots.forEach { join(idBackedRoots.single(), it) }
                else -> Unit
            }
        }

        val groupedIndexes = candidates.indices.groupBy(::root)
        groupedIndexes.forEach { (root, indexes) ->
            val group = indexes.map(candidates::get)
            val kind = group.first().kind
            val canonicalId = group.asSequence()
                .flatMap { candidate -> candidate.externalIds().orderedDisplayIds().asSequence() }
                .distinct()
                .minWithOrNull(
                    compareBy<String>(::profileCanonicalIdPriority)
                        .thenBy(String::lowercase),
                )
                ?: group.mapNotNull { it.id.trim().takeIf(String::isNotBlank) }
                    .sorted()
                    .firstOrNull()
                ?: group.mapNotNull(ProfileIdentityCandidate::titleYearKey)
                    .sorted()
                    .firstOrNull()
                    ?.let { "title:$it" }
                ?: "source:${group.first().sourceId}:$root"
            put(root, ProfileIdentity(kind, canonicalId))
        }
    }

    fun identity(index: Int): ProfileIdentity = canonicalByRoot.getValue(root(index))

    fun identityForRawKey(kind: String, rawId: String): ProfileIdentity? {
        val normalizedKind = profileInsightsKind(kind) ?: return null
        val targetIds = parseProfileExternalIds(rawId, PROFILE_INSIGHTS_NUVIO_SOURCE_ID, null)
        if (targetIds.hasAny) {
            val matches = candidates.indices.filter { index ->
                candidates[index].kind == normalizedKind &&
                    candidatesWithIds[index].sharesIdentityWith(targetIds)
            }.map(::root).distinct()
            if (matches.size == 1) return canonicalByRoot[matches.single()]
            if (matches.size > 1) return null
        }
        val cleanId = rawId.trim().takeIf(String::isNotBlank) ?: return null
        val localMatches = candidates.indices.filter { index ->
            candidates[index].kind == normalizedKind && candidates[index].id.equals(cleanId, ignoreCase = true)
        }.map(::root).distinct()
        if (localMatches.size == 1) return canonicalByRoot[localMatches.single()]
        return ProfileIdentity(normalizedKind, canonicalProfileId(targetIds, cleanId))
    }
}

private fun ProfileIdentityCandidate.externalIds(): TrackingExternalIds =
    parseProfileExternalIds(id, sourceId, trackingProviderId)
        .mergeMissing(parseProfileExternalIds(providerItemId, sourceId, trackingProviderId))
        .mergeMissing(extraExternalIds)

/** An unprefixed number is ambiguous outside Trakt; never guess it is a Trakt ID. */
private fun parseProfileExternalIds(
    rawValue: String?,
    sourceId: String,
    attributedProviderId: String?,
): TrackingExternalIds {
    val cleanValue = rawValue?.trim()?.takeIf(String::isNotBlank) ?: return TrackingExternalIds()
    val attributedProvider = attributedProviderId?.trim()?.lowercase()
    if (
        cleanValue.toLongOrNull() != null &&
        sourceId != TrackingProviderId.TRAKT.storageId &&
        attributedProvider != TrackingProviderId.TRAKT.storageId
    ) {
        return TrackingExternalIds()
    }
    return parseTrackingExternalIds(cleanValue)
}

private fun profileCanonicalIdPriority(id: String): Int = when {
    id.startsWith("tt", ignoreCase = true) || id.startsWith("imdb:", ignoreCase = true) -> 0
    id.startsWith("tmdb:", ignoreCase = true) -> 1
    id.startsWith("tvdb:", ignoreCase = true) -> 2
    id.startsWith("trakt:", ignoreCase = true) -> 3
    id.startsWith("simkl:", ignoreCase = true) -> 4
    id.startsWith("mal:", ignoreCase = true) -> 5
    id.startsWith("anidb:", ignoreCase = true) -> 6
    id.startsWith("anilist:", ignoreCase = true) -> 7
    id.startsWith("kitsu:", ignoreCase = true) -> 8
    id.startsWith("mdblist:", ignoreCase = true) -> 9
    else -> 10
}

private fun TrackingExternalIds.tokens(): List<String> = buildList {
    imdb?.trim()?.lowercase()?.takeIf(String::isNotBlank)?.let { add("imdb:$it") }
    tmdb?.let { add("tmdb:$it") }
    tvdb?.trim()?.lowercase()?.takeIf(String::isNotBlank)?.let { add("tvdb:$it") }
    trakt?.let { add("trakt:$it") }
    simkl?.let { add("simkl:$it") }
    mal?.let { add("mal:$it") }
    anidb?.let { add("anidb:$it") }
    anilist?.let { add("anilist:$it") }
    kitsu?.let { add("kitsu:$it") }
    mdblist?.trim()?.lowercase()?.takeIf(String::isNotBlank)?.let { add("mdblist:$it") }
}

private fun TrackingExternalIds.orderedDisplayIds(): List<String> = buildList {
    imdb?.trim()?.takeIf(String::isNotBlank)?.let(::add)
    tmdb?.let { add("tmdb:$it") }
    tvdb?.trim()?.takeIf(String::isNotBlank)?.let { add("tvdb:$it") }
    trakt?.let { add("trakt:$it") }
    simkl?.let { add("simkl:$it") }
    mal?.let { add("mal:$it") }
    anidb?.let { add("anidb:$it") }
    anilist?.let { add("anilist:$it") }
    kitsu?.let { add("kitsu:$it") }
    mdblist?.trim()?.takeIf(String::isNotBlank)?.let { add("mdblist:$it") }
}

private fun canonicalProfileId(ids: TrackingExternalIds, fallback: String): String =
    ids.orderedDisplayIds().firstOrNull() ?: fallback.trim()

private fun ProfileIdentityCandidate.titleYearKey(): String? {
    val normalized = normalizeProfileTitle(title)
    val year = extractTrackingYear(releaseInfo) ?: return null
    if (normalized.isBlank()) return null
    return "$normalized:$year"
}

internal fun normalizeProfileTitle(title: String): String =
    title.trim()
        .lowercase()
        .filter(Char::isLetterOrDigit)

internal fun profileInsightsKind(raw: String): String? = when (raw.trim().lowercase()) {
    "movie", "film" -> "movie"
    "series", "show", "tv", "tvshow", "anime" -> "series"
    else -> null
}

internal fun normalizeProfileGenres(genres: List<String>): List<String> =
    genres.flatMap { genre -> genre.split('&') }
        .map(String::trim)
        .filter(String::isNotBlank)
        .map { genre ->
            when (genre.lowercase()) {
                "sci-fi", "sci fi", "scifi", "science-fiction" -> "Science Fiction"
                else -> genre
            }
        }
        .distinctBy { it.lowercase() }

/** Reconciles only authenticated, capability-readable data supplied by the source reader. */
internal fun aggregateProfileInsights(sources: List<ProfileInsightsSourceSnapshot>): ProfileInsightsAggregate {
    val includedSources = sources.filter { source ->
        source.connected && source.library.status != ProfileInsightsFetchStatus.NOT_CONNECTED
    }
    val libraryRecords = includedSources.flatMap { source ->
        source.libraryItems.mapNotNull { item ->
            val kind = profileInsightsKind(item.type) ?: return@mapNotNull null
            if (item.id.isBlank()) return@mapNotNull null
            source.sourceId to item.copy(type = kind)
        }
    }
    val watchedRecords = includedSources.flatMap { source ->
        if (!source.supportsProfileHistory()) return@flatMap emptyList()
        source.watchedItems.mapNotNull { item ->
            val kind = profileInsightsKind(item.type) ?: return@mapNotNull null
            if (item.id.isBlank() || !isProfileInsightsHistoryEligible(source.sourceId, item)) return@mapNotNull null
            source.sourceId to item.copy(type = kind)
        }
    }
    val progressRecords = includedSources.flatMap { source ->
        source.progressEntries.mapNotNull { entry ->
            if (!isProfileInsightsProgressEligible(source.sourceId, entry)) return@mapNotNull null
            val kind = profileInsightsKind(entry.parentMetaType) ?: return@mapNotNull null
            if (entry.parentMetaId.isBlank()) return@mapNotNull null
            if (kind == "series" && (entry.seasonNumber == null) != (entry.episodeNumber == null)) {
                return@mapNotNull null
            }
            source.sourceId to entry.copy(parentMetaType = kind)
        }
    }

    val candidates = buildList {
        libraryRecords.forEach { (sourceId, item) ->
            add(
                ProfileIdentityCandidate(
                    sourceId = sourceId,
                    kind = item.type,
                    id = item.id,
                    providerItemId = item.trackingProviderItemId,
                    title = item.name,
                    releaseInfo = item.releaseInfo,
                    trackingProviderId = item.trackingProviderId,
                    extraExternalIds = TrackingExternalIds(
                        imdb = item.imdbId,
                        tmdb = item.tmdbId?.toLong(),
                        trakt = item.traktId?.toLong(),
                    ),
                ),
            )
        }
        watchedRecords.forEach { (sourceId, item) ->
            add(
                ProfileIdentityCandidate(
                    sourceId = sourceId,
                    kind = item.type,
                    id = item.id,
                    providerItemId = item.trackingProviderItemId,
                    title = item.name,
                    releaseInfo = item.releaseInfo,
                    trackingProviderId = item.trackingProviderId,
                ),
            )
        }
        progressRecords.forEach { (sourceId, entry) ->
            add(
                ProfileIdentityCandidate(
                    sourceId = sourceId,
                    kind = entry.parentMetaType,
                    id = entry.parentMetaId,
                    providerItemId = entry.trackingProviderItemId,
                    title = entry.title,
                    releaseInfo = null,
                    trackingProviderId = entry.trackingProviderId,
                ),
            )
        }
    }
    val identityIndex = ProfileIdentityIndex(candidates)
    var cursor = 0
    val libraryIdentityIndexes = libraryRecords.indices.map { cursor++ }
    val watchedIdentityIndexes = watchedRecords.indices.map { cursor++ }
    val progressIdentityIndexes = progressRecords.indices.map { cursor++ }

    val libraryByTitleKey = libraryRecords.indices
        .groupBy { index -> identityIndex.identity(libraryIdentityIndexes[index]).titleKey }
        .mapValues { (_, indexes) ->
            indexes.map { index -> libraryRecords[index].second }
                .sortedWith(
                    compareByDescending<LibraryItem> { normalizeProfileGenres(it.genres).size }
                        .thenByDescending { it.name.isNotBlank() }
                        .thenByDescending { it.poster != null || it.banner != null }
                        .thenByDescending(LibraryItem::savedAtEpochMs)
                        .thenBy(LibraryItem::id),
                )
                .first()
        }
    val canonicalIdByLibraryKey = libraryRecords.indices.associate { index ->
        val identity = identityIndex.identity(libraryIdentityIndexes[index])
        identity.titleKey to identity.canonicalId
    }
    val normalizedLibraryItems = libraryByTitleKey.map { (titleKey, item) ->
        val kind = titleKey.substringBefore(':')
        item.copy(
            id = canonicalIdByLibraryKey[titleKey] ?: item.id,
            type = kind,
            genres = normalizeProfileGenres(item.genres),
        )
    }.sortedByDescending(LibraryItem::savedAtEpochMs)

    val deduplicatedWatched = watchedRecords.indices
        .groupBy { index ->
            val item = watchedRecords[index].second
            val identity = identityIndex.identity(watchedIdentityIndexes[index])
            val activity = if (item.type == "series" && item.season != null && item.episode != null) {
                ":${item.season}:${item.episode}"
            } else {
                ":-1:-1"
            }
            identity.titleKey + activity
        }
        .mapNotNull { (_, indexes) ->
            val selectedIndex = indexes.maxWithOrNull(
                compareBy<Int> { watchedRecords[it].second.name.isNotBlank() }
                    .thenBy { watchedRecords[it].second.poster != null }
                    .thenBy { watchedRecords[it].second.markedAtEpochMs }
                    .thenBy { watchedRecords[it].first },
            ) ?: return@mapNotNull null
            val identity = identityIndex.identity(watchedIdentityIndexes[selectedIndex])
            watchedRecords[selectedIndex].second.copy(
                id = identity.canonicalId,
                type = identity.kind,
            )
        }
        .sortedByDescending(WatchedItem::markedAtEpochMs)

    // A provider's membership/list status is not a whole-series completion marker. Only Nuvio's
    // explicit local whole-series mark is accepted here; provider episode rows stay episodes.
    val canonicalFullyWatchedKeys = linkedSetOf<String>()
    deduplicatedWatched
        .filter { item -> item.type == "series" && item.season == null && item.episode == null }
        .forEach { item -> canonicalFullyWatchedKeys += watchedItemKey("series", item.id) }
    includedSources.filter { it.sourceId == PROFILE_INSIGHTS_NUVIO_SOURCE_ID }
        .flatMap(ProfileInsightsSourceSnapshot::fullyWatchedSeriesKeys)
        .forEach { key ->
            val parsed = parseWholeSeriesWatchedKey(key) ?: return@forEach
            val identity = identityIndex.identityForRawKey(parsed.first, parsed.second) ?: return@forEach
            if (identity.kind == "series") canonicalFullyWatchedKeys += watchedItemKey("series", identity.canonicalId)
        }

    // If the explicit Nuvio whole-series mark has no watched row, retain a minimal marker so
    // completion remains visible. The ID is only a display fallback; no metadata is fabricated.
    val existingCompletedIds = deduplicatedWatched
        .filter { it.type == "series" && it.season == null && it.episode == null }
        .mapTo(mutableSetOf()) { it.id }
    val normalizedWatchedItems = deduplicatedWatched.toMutableList()
    canonicalFullyWatchedKeys.forEach { key ->
        val parsed = parseWholeSeriesWatchedKey(key) ?: return@forEach
        val identity = identityIndex.identityForRawKey(parsed.first, parsed.second) ?: return@forEach
        if (identity.canonicalId in existingCompletedIds) return@forEach
        normalizedWatchedItems += WatchedItem(
            id = identity.canonicalId,
            type = "series",
            name = identity.canonicalId,
            markedAtEpochMs = 0L,
        )
    }

    val genresByTitleKey = normalizedLibraryItems.mapNotNull { item ->
        val genres = normalizeProfileGenres(item.genres)
        if (genres.isEmpty()) null else "${item.type}:${item.id}" to genres
    }.toMap()

    val normalizedProgress = progressRecords.indices
        .groupBy { index ->
            val entry = progressRecords[index].second
            val identity = identityIndex.identity(progressIdentityIndexes[index])
            val episodeKey = if (entry.seasonNumber != null && entry.episodeNumber != null) {
                ":${entry.seasonNumber}:${entry.episodeNumber}"
            } else {
                ":-1:-1"
            }
            identity.titleKey + episodeKey
        }
        .mapNotNull { (_, indexes) ->
            val selectedIndex = indexes.maxWithOrNull(
                compareBy<Int> { if (progressRecords[it].second.hasProgressEvidence()) 1 else 0 }
                    .thenBy { progressRecords[it].second.lastUpdatedEpochMs }
                    .thenBy { if (progressRecords[it].second.source == "local") 1 else 0 }
                    .thenBy { progressEntryQuality(progressRecords[it].second) }
                    .thenBy { progressRecords[it].first },
            ) ?: return@mapNotNull null
            val entry = progressRecords[selectedIndex].second
            val identity = identityIndex.identity(progressIdentityIndexes[selectedIndex])
            entry.copy(
                parentMetaId = identity.canonicalId,
                parentMetaType = identity.kind,
            )
        }
        .sortedByDescending(WatchProgressEntry::lastUpdatedEpochMs)

    val watchedTitleKeys = buildSet {
        normalizedWatchedItems.forEach { item ->
            val kind = item.type
            add("$kind:${item.id}")
        }
        canonicalFullyWatchedKeys.forEach { key ->
            val parsed = parseWholeSeriesWatchedKey(key) ?: return@forEach
            val identity = identityIndex.identityForRawKey(parsed.first, parsed.second)
            if (identity != null) add(identity.titleKey)
        }
    }.sorted()

    return ProfileInsightsAggregate(
        libraryItems = normalizedLibraryItems,
        watchedItems = normalizedWatchedItems.sortedByDescending(WatchedItem::markedAtEpochMs),
        progressEntries = normalizedProgress,
        fullyWatchedSeriesKeys = canonicalFullyWatchedKeys,
        libraryGenresByTitleKey = genresByTitleKey,
        watchedTitleKeys = watchedTitleKeys,
        sourceStatuses = sources,
    )
}

private fun ProfileInsightsSourceSnapshot.supportsProfileHistory(): Boolean =
    sourceId != TrackingProviderId.ANILIST.storageId &&
        watched.status != ProfileInsightsFetchStatus.UNSUPPORTED &&
        watched.status != ProfileInsightsFetchStatus.NOT_CONNECTED

private fun isProfileInsightsProgressEligible(
    sourceId: String,
    entry: WatchProgressEntry,
): Boolean {
    val expectedSource = when (sourceId) {
        PROFILE_INSIGHTS_NUVIO_SOURCE_ID -> "local"
        TrackingProviderId.TRAKT.storageId -> "trakt_playback"
        TrackingProviderId.SIMKL.storageId -> "simkl_playback"
        TrackingProviderId.MDBLIST.storageId -> "mdblist_playback"
        TrackingProviderId.WETRAKR.storageId -> "wetrakr_playback"
        TrackingProviderId.ANILIST.storageId -> return false
        else -> return false
    }
    if (entry.source != expectedSource) return false
    val attributedProvider = entry.trackingProviderId?.trim()?.lowercase()
    return attributedProvider == null || sourceId == PROFILE_INSIGHTS_NUVIO_SOURCE_ID || attributedProvider == sourceId
}

internal fun isProfileInsightsHistoryEligible(sourceId: String, item: WatchedItem): Boolean {
    if (sourceId == TrackingProviderId.ANILIST.storageId) return false
    val kind = profileInsightsKind(item.type) ?: return false
    if (kind == "movie") return item.season == null && item.episode == null
    if ((item.season == null) != (item.episode == null)) return false
    if (item.season != null && item.episode != null && item.episode <= 0) return false
    return item.season != null || sourceId == PROFILE_INSIGHTS_NUVIO_SOURCE_ID
}

private fun parseWholeSeriesWatchedKey(key: String): Pair<String, String>? {
    val parts = key.split(':')
    if (parts.size < 4) return null
    val season = parts[parts.lastIndex - 1].toIntOrNull() ?: return null
    val episode = parts.last().toIntOrNull() ?: return null
    if (season != -1 || episode != -1) return null
    val kind = profileInsightsKind(parts.first()) ?: return null
    val id = parts.subList(1, parts.lastIndex - 1).joinToString(":").trim()
    return (kind to id).takeIf { id.isNotBlank() }
}

private fun WatchProgressEntry.hasProgressEvidence(): Boolean =
    lastPositionMs > 0L ||
        (normalizedProgressPercent ?: 0f) > 0f ||
        isCompleted

private fun progressEntryQuality(entry: WatchProgressEntry): Long {
    val position = entry.lastPositionMs.coerceAtLeast(0L).coerceAtMost(86_400_000_000L)
    val duration = entry.durationMs.coerceAtLeast(0L).coerceAtMost(86_400_000_000L)
    val percent = (entry.normalizedProgressPercent ?: 0f).coerceIn(0f, 100f).toLong()
    return position + duration + percent * 1_000L
}

internal fun calculateProfileInsightsMetrics(
    aggregate: ProfileInsightsAggregate,
    facts: Map<String, ProfileTitleFacts>,
    nowEpochMs: Long,
    recentWindowMs: Long,
): ProfileInsightsCalculatedMetrics {
    val movies = aggregate.watchedItems
        .filter { item -> item.type == "movie" && item.season == null && item.episode == null }
        .mapTo(linkedSetOf()) { item -> "movie:${item.id}" }
    val episodeActivityKeys = aggregate.watchedItems
        .filter { item -> item.type == "series" && item.season != null && item.episode != null }
        .mapTo(linkedSetOf()) { item -> "series:${item.id}:${item.season}:${item.episode}" }
    val series = aggregate.watchedItems
        .asSequence()
        .filter { item -> item.type == "series" }
        .mapTo(linkedSetOf()) { item -> "series:${item.id}" }
    aggregate.fullyWatchedSeriesKeys.forEach { key ->
        parseWholeSeriesWatchedKey(key)?.let { (_, id) -> series += "series:$id" }
    }
    val completedSeries = buildSet {
        aggregate.fullyWatchedSeriesKeys.mapNotNull { key ->
            parseWholeSeriesWatchedKey(key)?.second?.let { id -> "series:$id" }
        }.forEach { add(it) }
        aggregate.watchedItems
            .filter { item -> item.type == "series" && item.season == null && item.episode == null }
            .map { item -> "series:${item.id}" }
            .forEach { add(it) }
    }
    val watchedTitleKeys = (movies + series).sorted()
    val titleCount = movies.size + series.size
    val movieShare = if (titleCount > 0) movies.size.toFloat() / titleCount else 0.5f
    val continueEntries = aggregate.progressEntries
        .filterNot(WatchProgressEntry::isEffectivelyCompleted)
        .filter { entry ->
            entry.lastPositionMs > 0L || (entry.normalizedProgressPercent ?: 0f) > 0f
        }

    val recentCutoff = nowEpochMs - recentWindowMs
    val recentActivityKeys = buildSet {
        aggregate.watchedItems.filter { item -> item.markedAtEpochMs >= recentCutoff }
            .mapNotNullTo(this) { it.profileActivityKey() }
        aggregate.progressEntries.filter { entry -> entry.lastUpdatedEpochMs >= recentCutoff }
            .mapNotNullTo(this) { it.profileActivityKey() }
    }
    val watchTime = calculateProfileInsightsWatchTime(aggregate.watchedItems, aggregate.progressEntries, facts)
    val taste = calculateProfileInsightsTaste(
        watchedTitleKeys = watchedTitleKeys,
        libraryGenresByTitleKey = aggregate.libraryGenresByTitleKey,
        facts = facts,
    )

    return ProfileInsightsCalculatedMetrics(
        libraryCount = aggregate.libraryItems.size,
        continueCount = continueEntries.size,
        watchedMovieCount = movies.size,
        completedSeriesCount = completedSeries.size,
        ongoingSeriesCount = series.size - completedSeries.size,
        episodesWatchedCount = episodeActivityKeys.size,
        watchedTitleCount = titleCount,
        watchedTitleKeys = watchedTitleKeys,
        recentActivityCount = recentActivityKeys.size,
        movieShare = movieShare,
        topType = when {
            titleCount == 0 || movies.size == series.size -> null
            movies.size > series.size -> "movie"
            else -> "series"
        },
        tasteSegments = taste.first,
        tasteKnownTitleCount = taste.second,
        watchTime = watchTime,
    )
}

private fun WatchedItem.profileActivityKey(): String? {
    val kind = profileInsightsKind(type) ?: return null
    if (kind == "movie") return "movie:$id:-1:-1"
    if (season == null || episode == null) return null
    return "series:$id:$season:$episode"
}

private fun WatchProgressEntry.profileActivityKey(): String? {
    val kind = profileInsightsKind(parentMetaType) ?: return null
    if (kind == "movie") return "movie:$parentMetaId:-1:-1"
    if (seasonNumber == null || episodeNumber == null) return null
    return "series:$parentMetaId:$seasonNumber:$episodeNumber"
}

private data class WatchTimeValue(val durationMs: Long, val isMovie: Boolean, val estimated: Boolean)

private fun calculateProfileInsightsWatchTime(
    watchedItems: List<WatchedItem>,
    progressEntries: List<WatchProgressEntry>,
    facts: Map<String, ProfileTitleFacts>,
): ProfileInsightsWatchTime {
    val durationByActivity = mutableMapOf<String, WatchTimeValue>()
    fun record(activityKey: String, value: WatchTimeValue) {
        if (value.durationMs <= 0L) return
        val previous = durationByActivity[activityKey]
        if (previous == null || value.durationMs > previous.durationMs ||
            (value.durationMs == previous.durationMs && previous.estimated && !value.estimated)
        ) {
            durationByActivity[activityKey] = value
        }
    }

    progressEntries.forEach { entry ->
        val activityKey = entry.profileActivityKey() ?: return@forEach
        val progress = (entry.normalizedProgressPercent ?: 0f).coerceIn(0f, 100f)
        val isMovie = entry.parentMetaType == "movie"
        val fact = facts["${entry.parentMetaType}:${entry.parentMetaId}"]?.takeIf { !it.partial }
        val knownRuntimeMs = when {
            isMovie -> fact?.runtimeMinutes?.takeIf { it > 0 }?.toLong()?.times(60_000L)
            entry.seasonNumber != null && entry.episodeNumber != null ->
                fact?.episodeRuntimeMinutes?.get(profileEpisodeKey(entry.seasonNumber, entry.episodeNumber))
                    ?.takeIf { it > 0 }?.toLong()?.times(60_000L)
            else -> null
        }
        // Provider-supplied runtime/progress is explicitly an estimate. No default movie or
        // episode runtime is substituted when the provider and metadata do not supply one.
        val suppliedDuration = entry.durationMs.takeIf { it > 0L }
        val runtime = suppliedDuration ?: knownRuntimeMs
        val progressDuration = when {
            runtime == null -> entry.lastPositionMs.takeIf { it > 0L }
            entry.lastPositionMs > 0L -> entry.lastPositionMs.coerceAtMost(runtime)
            progress > 0f -> (runtime * progress / 100f).toLong().coerceIn(0L, runtime)
            else -> null
        } ?: return@forEach
        // Every current source records a playback position or percentage, not elapsed player
        // time; even Nuvio's local position is therefore an explicitly qualified estimate.
        record(activityKey, WatchTimeValue(progressDuration, isMovie, estimated = true))
    }

    watchedItems.forEach { item ->
        val activityKey = item.profileActivityKey() ?: return@forEach
        val kind = profileInsightsKind(item.type) ?: return@forEach
        val factsForTitle = facts["$kind:${item.id}"]
        if (factsForTitle?.partial != false) return@forEach
        val runtimeMs = if (kind == "movie") {
            factsForTitle.runtimeMinutes?.takeIf { it > 0 }?.toLong()?.times(60_000L)
        } else if (item.season != null && item.episode != null) {
            factsForTitle.episodeRuntimeMinutes[profileEpisodeKey(item.season, item.episode)]
                ?.takeIf { it > 0 }?.toLong()?.times(60_000L)
        } else {
            null
        } ?: return@forEach
        record(activityKey, WatchTimeValue(runtimeMs, kind == "movie", estimated = true))
    }

    var movieMs = 0L
    var seriesMs = 0L
    var estimatedMs = 0L
    var recordedMs = 0L
    durationByActivity.values.forEach { value ->
        if (value.isMovie) movieMs += value.durationMs else seriesMs += value.durationMs
        if (value.estimated) estimatedMs += value.durationMs else recordedMs += value.durationMs
    }
    return ProfileInsightsWatchTime(movieMs, seriesMs, estimatedMs, recordedMs)
}

private fun calculateProfileInsightsTaste(
    watchedTitleKeys: List<String>,
    libraryGenresByTitleKey: Map<String, List<String>>,
    facts: Map<String, ProfileTitleFacts>,
): Pair<List<ProfileInsightsTasteSegment>, Int> {
    val counts = mutableMapOf<String, Float>()
    var knownTitleCount = 0
    watchedTitleKeys.distinct().forEach { titleKey ->
        val genres = normalizeProfileGenres(
            libraryGenresByTitleKey[titleKey]
                ?: facts[titleKey]?.takeIf { !it.partial }?.genres.orEmpty(),
        )
        if (genres.isEmpty()) return@forEach
        knownTitleCount += 1
        val perGenreWeight = 1f / genres.size
        genres.forEach { genre -> counts[genre] = (counts[genre] ?: 0f) + perGenreWeight }
    }
    if (knownTitleCount == 0) return emptyList<ProfileInsightsTasteSegment>() to 0
    val segments = counts.entries
        .sortedWith(compareByDescending<Map.Entry<String, Float>> { it.value }.thenBy { it.key.lowercase() })
        .map { (genre, weight) -> ProfileInsightsTasteSegment(genre, weight / knownTitleCount) }
    return segments to knownTitleCount
}

internal fun profileDurationDisplayParts(durationMs: Long): ProfileDurationDisplayParts {
    val totalMinutes = (durationMs.coerceAtLeast(0L) / 60_000L)
    if (totalMinutes < 60L) return ProfileDurationDisplayParts(totalMinutes, 0L, 0L, totalMinutes.toInt())
    val totalHours = (totalMinutes + 30L) / 60L
    return ProfileDurationDisplayParts(totalMinutes, totalHours / 24L, totalHours % 24L, 0)
}

internal data class ProfileDurationDisplayParts(
    val totalMinutes: Long,
    val days: Long,
    val hours: Long,
    val minutes: Int,
)

internal fun shouldRefreshProfileInsightsCache(
    lastSuccessAtEpochMs: Long?,
    nowEpochMs: Long,
    maxAgeMs: Long,
    userInitiated: Boolean,
): Boolean = userInitiated || lastSuccessAtEpochMs == null || nowEpochMs - lastSuccessAtEpochMs >= maxAgeMs

internal fun isProfileInsightsRefreshCurrent(
    requestedProfileId: Int,
    activeProfileId: Int,
    requestedGeneration: Long,
    currentGeneration: Long,
    requestedAccountGeneration: Long?,
    currentAccountGeneration: Long?,
    isAuthenticated: Boolean,
    requestedAccountIdentity: String? = null,
    currentAccountIdentity: String? = null,
): Boolean = requestedProfileId == activeProfileId &&
    requestedGeneration == currentGeneration &&
    isAuthenticated &&
    (requestedAccountGeneration == null || requestedAccountGeneration == currentAccountGeneration) &&
    (requestedAccountIdentity == null || requestedAccountIdentity == currentAccountIdentity)

internal fun profileInsightsFetchStatus(
    connected: Boolean,
    supported: Boolean,
    hasLoaded: Boolean,
    isLoading: Boolean,
    hasData: Boolean,
    errorMessage: String?,
): ProfileInsightsFetchStatus = when {
    !supported -> ProfileInsightsFetchStatus.UNSUPPORTED
    !connected -> ProfileInsightsFetchStatus.NOT_CONNECTED
    errorMessage != null -> if (hasData) ProfileInsightsFetchStatus.STALE else ProfileInsightsFetchStatus.FAILED
    isLoading -> ProfileInsightsFetchStatus.REFRESHING
    hasLoaded -> ProfileInsightsFetchStatus.AVAILABLE
    hasData -> ProfileInsightsFetchStatus.CACHED
    else -> ProfileInsightsFetchStatus.NOT_LOADED
}

/** A successful empty response is authoritative; failures/unloaded snapshots retain useful cache. */
internal fun <T> profileInsightsSnapshotItems(
    hasLoaded: Boolean,
    errorMessage: String?,
    snapshotItems: List<T>,
    cachedItems: List<T>,
): List<T> = when {
    hasLoaded && errorMessage == null -> snapshotItems
    snapshotItems.isNotEmpty() -> snapshotItems
    else -> cachedItems
}

internal fun recoverProfileInsightsRefreshingStatus(
    capability: ProfileInsightsCapabilityState,
): ProfileInsightsCapabilityState =
    if (capability.status != ProfileInsightsFetchStatus.REFRESHING) {
        capability
    } else {
        capability.copy(
            status = if (capability.itemCount > 0) ProfileInsightsFetchStatus.CACHED else ProfileInsightsFetchStatus.NOT_LOADED,
            errorMessage = null,
        )
    }
