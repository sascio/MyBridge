package com.streambridge.app.data.discovery

import com.streambridge.app.addon.model.GenreInfo
import com.streambridge.app.addon.model.MediaItem

/**
 * Pure helpers for merging, ranking and slicing media lists.
 * Unit tested.
 */
object MetaMerger {

    /**
     * Merges items from multiple sources, dropping duplicates.
     * Identity is the imdb id when available, otherwise type:id.
     * Entries with artwork win over entries without.
     */
    fun merge(items: List<MediaItem>): List<MediaItem> {
        val byKey = LinkedHashMap<String, MediaItem>()
        for (item in items) {
            val key = mergeKey(item)
            val existing = byKey[key]
            if (existing == null || score(item) > score(existing)) {
                byKey[key] = item
            }
        }
        return byKey.values.toList()
    }

    fun mergeKey(item: MediaItem): String {
        val imdb = item.imdbId?.takeIf { it.isNotBlank() }
        return if (imdb != null) "imdb:$imdb" else "${item.type}:${item.id}"
    }

    private fun score(item: MediaItem): Int {
        var score = 0
        if (!item.poster.isNullOrBlank()) score += 2
        if (!item.backdrop.isNullOrBlank()) score += 2
        if (!item.rating.isNullOrBlank()) score += 1
        if (!item.description.isNullOrBlank()) score += 1
        return score
    }

    /** Top genres by frequency across the given items. */
    fun topGenres(items: List<MediaItem>, max: Int): List<GenreInfo> {
        return items.asSequence()
            .flatMap { item ->
                item.genres.asSequence()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
            }
            .groupBy { it }
            .map { (genre, occurrences) -> GenreInfo(genre, occurrences.size) }
            .sortedWith(compareByDescending<GenreInfo> { it.count }.thenBy { it.name })
            .take(max)
    }

    /**
     * Simple genre-affinity recommendations: items that share genres with
     * what the user has been watching, excluding already-watched content.
     */
    fun recommendations(
        candidates: List<MediaItem>,
        watchedGenres: Set<String>,
        excludeKeys: Set<String>,
        max: Int
    ): List<MediaItem> {
        if (watchedGenres.isEmpty()) return emptyList()
        val normalizedWatched = watchedGenres.map { it.trim().lowercase() }.toSet()
        return candidates
            .asSequence()
            .filter { item ->
                item.genres.any { it.trim().lowercase() in normalizedWatched }
            }
            .filter { item.key !in excludeKeys }
            .groupBy { mergeKey(it) }
            .map { (_, group) -> group.first() }
            .sortedByDescending { item ->
                item.genres.count { it.trim().lowercase() in normalizedWatched }
            }
            .take(max)
            .toList()
    }
}
