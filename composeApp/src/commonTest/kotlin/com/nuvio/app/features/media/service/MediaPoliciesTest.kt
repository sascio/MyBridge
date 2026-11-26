package com.nuvio.app.features.media.service

import kotlin.test.*

class MediaPoliciesTest {
    private fun entry(id: String) = MediaEntry(MediaKey(MediaServiceId.YouTubeMusic, id), MediaKind.Track, id)
    @Test fun continueWatchingIsOptIn() { assertFalse(YouTubePreferences().continueWatchingOnHome) }
    @Test fun invalidTabOrderIsNormalized() {
        val tabs = YouTubePreferences(order = listOf(YouTubeFeed.Music, YouTubeFeed.Music), hidden = setOf(YouTubeFeed.News)).visibleTabs()
        assertEquals(YouTubeFeed.Music, tabs.first()); assertEquals(tabs.size, tabs.distinct().size); assertFalse(YouTubeFeed.News in tabs)
    }
    @Test fun atLeastOneTabRemains() { assertEquals(listOf(YouTubeFeed.Home), YouTubePreferences(hidden = YouTubeFeed.entries.toSet()).visibleTabs()) }
    @Test fun filtersDoNotHideUnrelatedEntries() {
        val prefs = YouTubePreferences(hideShorts = true, hideWatched = true, hideUpcoming = true, hiddenWords = setOf("spoiler", " "), hiddenChannels = setOf("hidden"))
        val video = entry("1")
        assertTrue(prefs.allows(video)); assertFalse(prefs.allows(video.copy(isShort = true)))
        assertFalse(prefs.allows(video.copy(watched = true))); assertFalse(prefs.allows(video.copy(upcoming = true)))
        assertFalse(prefs.allows(video.copy(title = "SPOILER alert"))); assertFalse(prefs.allows(video.copy(channelId = "hidden")))
    }
    @Test fun emptyQueueHasNoNext() { assertNull(MediaQueue().next()); assertNull(MediaQueue().current) }
    @Test fun duplicateTracksRemainIndividuallyRemovable() {
        val track = entry("a")
        val queue = MediaQueue().append(track).append(track).select(1).remove(0)
        assertEquals(listOf(track), queue.entries); assertEquals(0, queue.index)
    }
    @Test fun movingEarlierOrLaterPreservesCurrentEntry() {
        val queue = MediaQueue(listOf(entry("a"), entry("b"), entry("c")), 1)
        assertEquals(queue.current, queue.move(0, 2).current)
        assertEquals(queue.current, queue.move(2, 0).current)
        assertEquals(queue.current, queue.move(1, 2).current)
    }
    @Test fun repeatAndManualNextAreDifferent() {
        val queue = MediaQueue(listOf(entry("a"), entry("b")), 0, QueueRepeat.One)
        assertEquals(queue, queue.next()); assertEquals("b", queue.next(manual = true)?.current?.title)
        assertNull(queue.copy(index = 1, repeat = QueueRepeat.Off).next())
        assertEquals(0, queue.copy(index = 1, repeat = QueueRepeat.All).next()?.index)
    }
    @Test fun removingLastTrackProducesValidEmptyQueue() { assertEquals(MediaQueue(), MediaQueue().append(entry("a")).remove(0)) }
    @Test fun timestampsSupportOffsetsFractionsAndDuplicates() {
        val result = parseSyncedLyrics("[offset:-100]\n[00:01.2][00:02.345]Hello\n[00:00.05]Start\n[ar:Artist]")
        assertEquals(listOf(0L, 1100L, 2245L), result.map { it.atMs }); assertEquals(listOf("Start", "Hello", "Hello"), result.map { it.text })
    }
    @Test fun metadataAndUntimedTextAreNotInventedAsSyncedLyrics() { assertTrue(parseSyncedLyrics("[ar:Artist]\nplain lyrics").isEmpty()) }
    @Test fun credentialsAreRedacted() {
        assertEquals("MediaAccountSession(redacted)", MediaAccountSession("id", "name", "secret").toString())
        assertEquals("ResolvedMedia(redacted)", ResolvedMedia("https://example.org/secret").toString())
    }

    @Test fun searchHistoryIsBoundedDeduplicatedAndClearable() {
        var state = MediaBrowseState()
        repeat(30) { state = state.searched("query $it") }
        assertEquals(20, state.searches.size)
        state = state.searched(" QUERY 29 ")
        assertEquals("QUERY 29", state.searches.first())
        assertEquals(20, state.searches.size)
        assertEquals(state, state.searched(" "))
        assertTrue(state.copy(searches = emptyList()).searches.isEmpty())
    }
}
