package com.nuvio.app.features.media.service

import kotlinx.serialization.Serializable

@Serializable
internal enum class YouTubeFeed { Home, Subscriptions, History, Playlists, Live, Music, Gaming, News, Sports, Shorts, Channels }

@Serializable
internal data class YouTubePreferences(
    val order: List<YouTubeFeed> = YouTubeFeed.entries,
    val hidden: Set<YouTubeFeed> = emptySet(),
    val rememberTab: Boolean = false,
    val lastTab: YouTubeFeed = YouTubeFeed.Home,
    val continueWatchingOnHome: Boolean = false,
    val hideShorts: Boolean = false,
    val hideWatched: Boolean = false,
    val hideUpcoming: Boolean = false,
    val hiddenWords: Set<String> = emptySet(),
    val hiddenChannels: Set<String> = emptySet(),
) {
    fun visibleTabs(): List<YouTubeFeed> = (order + YouTubeFeed.entries).distinct().filterNot { it in hidden }
        .ifEmpty { listOf(YouTubeFeed.Home) }
    fun initialTab(): YouTubeFeed = lastTab.takeIf { rememberTab && it in visibleTabs() } ?: visibleTabs().first()
    fun allows(entry: MediaEntry): Boolean =
        !(hideShorts && entry.isShort) && !(hideWatched && entry.watched) && !(hideUpcoming && entry.upcoming) &&
            entry.channelId !in hiddenChannels && hiddenWords.none { it.isNotBlank() && entry.title.contains(it.trim(), ignoreCase = true) }
}

@Serializable
internal enum class QueueRepeat { Off, All, One }
/** Immutable queue policy, independent of the platform player. Duplicate tracks remain distinct entries. */
@Serializable
internal data class MediaQueue(val entries: List<MediaEntry> = emptyList(), val index: Int = -1, val repeat: QueueRepeat = QueueRepeat.Off) {
    init { require((entries.isEmpty() && index == -1) || index in entries.indices) }
    val current: MediaEntry? get() = entries.getOrNull(index)
    fun append(entry: MediaEntry): MediaQueue = copy(entries = entries + entry, index = if (entries.isEmpty()) 0 else index)
    fun select(position: Int): MediaQueue { require(position in entries.indices); return copy(index = position) }
    fun next(manual: Boolean = false): MediaQueue? = when {
        entries.isEmpty() -> null
        repeat == QueueRepeat.One && !manual -> this
        index < entries.lastIndex -> copy(index = index + 1)
        repeat == QueueRepeat.All -> copy(index = 0)
        else -> null
    }
    fun previous(): MediaQueue = if (index > 0) copy(index = index - 1) else this
    fun remove(position: Int): MediaQueue {
        require(position in entries.indices)
        val remaining = entries.filterIndexed { i, _ -> i != position }
        return copy(entries = remaining, index = when {
            remaining.isEmpty() -> -1
            position < index -> index - 1
            else -> index.coerceAtMost(remaining.lastIndex)
        })
    }
    fun move(from: Int, to: Int): MediaQueue {
        require(from in entries.indices && to in entries.indices)
        val next = entries.toMutableList().apply { add(to, removeAt(from)) }
        val newIndex = when {
            index == from -> to
            from < index && to >= index -> index - 1
            from > index && to <= index -> index + 1
            else -> index
        }
        return copy(entries = next, index = newIndex)
    }
}

/** LRC parser: supports repeated timestamps, fractional seconds and global offset metadata. */
internal fun parseSyncedLyrics(input: String): List<LyricLine> {
    val bounded = input.take(512_000)
    val offset = Regex("\\[offset:([+-]?\\d+)\\]", RegexOption.IGNORE_CASE).find(bounded)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    val timestamp = Regex("\\[(\\d{1,3}):([0-5]\\d)(?:[.:](\\d{1,3}))?\\]")
    return bounded.lineSequence().flatMap { line ->
        val matches = timestamp.findAll(line).toList()
        val text = matches.lastOrNull()?.let { line.substring(it.range.last + 1).trim() }.orEmpty()
        matches.asSequence().map { match ->
            val (minutes, seconds, fraction) = match.destructured
            val ms = minutes.toLong() * 60_000L + seconds.toLong() * 1_000L + fraction.padEnd(3, '0').take(3).toLong()
            LyricLine((ms + offset.coerceIn(-86_400_000, 86_400_000)).coerceAtLeast(0), text)
        }
    }.sortedBy { it.atMs }.distinct().toList()
}

/** User-entered search history only: never presented as remote search suggestions. */
@Serializable
internal data class MediaBrowseState(
    val preferences: YouTubePreferences = YouTubePreferences(),
    val searches: List<String> = emptyList(),
) {
    fun searched(query: String): MediaBrowseState {
        val value = query.trim().take(500)
        return if (value.isEmpty()) this else copy(searches = (listOf(value) + searches.filterNot { it.equals(value, true) }).take(20))
    }
}
