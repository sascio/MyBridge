package com.streambridge.app

import com.streambridge.app.addon.SourceResult
import com.streambridge.app.addon.SourceStatus
import com.streambridge.app.addon.model.StreamOption
import com.streambridge.app.player.PlaybackRequest
import com.streambridge.app.ui.sources.SourceSelectionUiState
import com.streambridge.app.ui.sources.applyResult
import com.streambridge.app.ui.sources.restart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The source selector's dynamics, exactly as specified: tabs are
 * generated from ACTUAL results, empty/failed sources disappear for
 * the current request (and can reappear on the next one), ALL combines
 * usable streams, and switching tabs never re-requests anything.
 */
class SourceSelectionStateTest {

    private val request = PlaybackRequest(
        type = "movie",
        metaId = "tmdb:550",
        metaName = "Fight Club",
        imdbId = "tt0137523",
        poster = null,
        backdrop = null,
        videoId = "",
        season = 0,
        episode = 0,
        episodeTitle = null
    )

    private fun stream(url: String, label: String = "1080p WEB-DL") = StreamOption(
        id = "url:$url",
        label = label,
        description = null,
        url = url,
        infoHash = null,
        externalUrl = null,
        addonName = "test",
        isTorrent = false,
        isExternal = false,
        bingeGroup = ""
    )

    private fun loading(id: String, name: String = id) =
        SourceResult(id, name, "origin-$id", SourceStatus.Loading)

    private fun found(id: String, name: String = id, urls: List<String>) =
        SourceResult(id, name, "origin-$id", SourceStatus.Success(urls.map { stream(it) }))

    private fun empty(id: String, name: String = id) =
        SourceResult(id, name, "origin-$id", SourceStatus.Empty)

    private fun timedOut(id: String, name: String = id) =
        SourceResult(id, name, "origin-$id", SourceStatus.Timeout)

    // -----------------------------------------------------------------
    // The specification's exact scenario
    // -----------------------------------------------------------------

    @Test
    fun `only sources with usable results get tabs`() {
        // A found · B no results · C found · D timeout · E found
        val state = SourceSelectionUiState(
            request = request,
            results = listOf(
                found("a", "Provider A", listOf("https://a/1", "https://a/2")),
                empty("b", "Provider B"),
                found("c", "Provider C", listOf("https://c/1")),
                timedOut("d", "Provider D"),
                found("e", "Provider E", listOf("https://e/1"))
            )
        )

        assertEquals(
            listOf("ALL", "Provider A", "Provider C", "Provider E"),
            state.tabs.map { it.label }
        )
        assertEquals(listOf(SourceSelectionUiState.TAB_ALL, "a", "c", "e"), state.tabs.map { it.id })
        assertEquals(4, state.tabs.first().count) // ALL combines every usable stream
        assertEquals(2, state.hiddenCount) // B and D are hidden for this request
        assertTrue(state.hasStreams)
        assertFalse(state.running)
    }

    @Test
    fun `hidden sources stay installed - they are simply not tabs`() {
        val state = SourceSelectionUiState(
            request = request,
            results = listOf(found("a", "A", listOf("https://a/1")), empty("b", "B"))
        )
        // B is still part of the request's results; it has no tab.
        assertEquals(2, state.results.size)
        assertEquals(1, state.hiddenCount)
    }

    @Test
    fun `tabs appear progressively as results arrive`() {
        var state = SourceSelectionUiState(
            request = request,
            results = listOf(loading("a", "A"), loading("c", "C"))
        )
        assertEquals(listOf("ALL"), state.tabs.map { it.label })
        assertTrue(state.running)
        assertFalse(state.finished)

        state = state.applyResult(found("a", "A", listOf("https://a/1")), expectedSerial = 0)
        assertEquals(listOf("ALL", "A"), state.tabs.map { it.label })

        state = state.applyResult(found("c", "C", listOf("https://c/1")), expectedSerial = 0)
        assertEquals(listOf("ALL", "A", "C"), state.tabs.map { it.label })
        assertFalse(state.running)
        assertTrue(state.finished)
    }

    @Test
    fun `a source that empties out late never gets a tab`() {
        var state = SourceSelectionUiState(
            request = request,
            results = listOf(loading("a", "A"), loading("b", "B"))
        )
        state = state.applyResult(empty("b", "B"), expectedSerial = 0)
        state = state.applyResult(found("a", "A", listOf("https://a/1")), expectedSerial = 0)
        assertEquals(listOf("ALL", "A"), state.tabs.map { it.label })
    }

    // -----------------------------------------------------------------
    // ALL and per-provider views
    // -----------------------------------------------------------------

    @Test
    fun `ALL combines usable streams grouped by source without empty sections`() {
        val state = SourceSelectionUiState(
            request = request,
            results = listOf(
                found("a", "A", listOf("https://a/1", "https://a/2")),
                empty("b", "B"),
                found("c", "C", listOf("https://c/1"))
            )
        )
        val groups = state.groups
        assertEquals(listOf("A", "C"), groups.map { it.title })
        assertEquals(listOf("https://a/1", "https://a/2"), groups[0].streams.map { it.url })
        assertEquals(listOf("https://c/1"), groups[1].streams.map { it.url })
    }

    @Test
    fun `provider tab shows only its own streams`() {
        val state = SourceSelectionUiState(
            request = request,
            selectedTab = "c",
            results = listOf(
                found("a", "A", listOf("https://a/1")),
                found("c", "C", listOf("https://c/1", "https://c/2")),
                found("e", "E", listOf("https://e/1"))
            )
        )
        val groups = state.groups
        assertEquals(1, groups.size)
        assertEquals("C", groups[0].title)
        assertEquals(listOf("https://c/1", "https://c/2"), groups[0].streams.map { it.url })
    }

    @Test
    fun `switching tabs only changes the selection, never the results`() {
        val state = SourceSelectionUiState(
            request = request,
            results = listOf(found("a", "A", listOf("https://a/1")), found("c", "C", listOf("https://c/1")))
        )
        val switched = state.withSelectedTab("c")
        assertEquals("c", switched.selectedTab)
        assertEquals(state.results, switched.results) // same request data, no re-resolution
        assertEquals(state.totalStreams, switched.totalStreams)
    }

    @Test
    fun `selecting an unknown or non-usable tab is a no-op`() {
        val state = SourceSelectionUiState(
            request = request,
            results = listOf(found("a", "A", listOf("https://a/1")), empty("b", "B"))
        )
        assertEquals(SourceSelectionUiState.TAB_ALL, state.withSelectedTab("b").selectedTab)
        assertEquals(SourceSelectionUiState.TAB_ALL, state.withSelectedTab("nope").selectedTab)
    }

    // -----------------------------------------------------------------
    // Reload
    // -----------------------------------------------------------------

    @Test
    fun `reload clears results, resets to ALL and re-evaluates every source`() {
        val first = SourceSelectionUiState(
            request = request,
            results = listOf(
                found("a", "A", listOf("https://a/1")),
                empty("b", "B"),
                timedOut("d", "D")
            )
        ).withSelectedTab("a")

        // User taps reload: everything goes back to Loading.
        val second = first.restart(
            loading = listOf(loading("a", "A"), loading("b", "B"), loading("d", "D")),
            serial = 1
        )
        assertEquals(SourceSelectionUiState.TAB_ALL, second.selectedTab)
        assertTrue(second.results.all { it.status is SourceStatus.Loading })
        assertEquals(listOf("ALL"), second.tabs.map { it.label })
        assertEquals(0, second.hiddenCount)

        // Late events from the FIRST pass must be dropped.
        val stale = second.applyResult(found("a", "A", listOf("https://a/old")), expectedSerial = 0)
        assertTrue(stale.results.all { it.status is SourceStatus.Loading })

        // On the new pass, previously hidden sources can succeed again.
        var revived = second.applyResult(found("a", "A", listOf("https://a/1")), expectedSerial = 1)
        revived = revived.applyResult(found("b", "B", listOf("https://b/1")), expectedSerial = 1)
        revived = revived.applyResult(found("d", "D", listOf("https://d/1")), expectedSerial = 1)
        assertEquals(listOf("ALL", "A", "B", "D"), revived.tabs.map { it.label })
        assertEquals(0, revived.hiddenCount)
    }

    // -----------------------------------------------------------------
    // Honest metadata and edge cases
    // -----------------------------------------------------------------

    @Test
    fun `a torrent-only source is a success but not usable - no tab`() {
        val torrentOnly = SourceResult(
            "t", "Torrents", "origin-t",
            SourceStatus.Success(
                listOf(
                    StreamOption(
                        id = "torrent:abc", label = "720p", description = null, url = null,
                        infoHash = "abc", externalUrl = null, addonName = "t",
                        isTorrent = true, isExternal = false, bingeGroup = ""
                    )
                )
            )
        )
        val state = SourceSelectionUiState(request = request, results = listOf(torrentOnly))
        assertEquals(listOf("ALL"), state.tabs.map { it.label })
        assertTrue(state.results[0].status is SourceStatus.Success)
        assertFalse(state.results[0].usable)
    }

    @Test
    fun `no sources at all finishes with an honest empty state`() {
        val state = SourceSelectionUiState(request = request, results = emptyList())
        // No sources installed: nothing is loading, but there is also
        // nothing finished — the UI must explain this case itself.
        assertFalse(state.running)
        assertFalse(state.finished)
        assertFalse(state.hasStreams)
        assertEquals(0, state.totalStreams)
        assertEquals(0, state.hiddenCount)
    }

    @Test
    fun `failed sources do not hide successful ones`() {
        val state = SourceSelectionUiState(
            request = request,
            results = listOf(
                SourceResult("x", "X", "origin-x", SourceStatus.Failed("provider crashed")),
                SourceResult("n", "N", "origin-n", SourceStatus.NetworkError("no route")),
                found("a", "A", listOf("https://a/1"))
            )
        )
        assertEquals(listOf("ALL", "A"), state.tabs.map { it.label })
        assertEquals(2, state.hiddenCount)
        assertEquals(1, state.totalStreams)
    }

    @Test
    fun `episode requests carry their own identity`() {
        val episodeRequest = request.copy(
            type = "series", videoId = "tt0137523:1:2", season = 1, episode = 2,
            episodeTitle = "The Beginning"
        )
        val state = SourceSelectionUiState(request = episodeRequest)
        assertEquals("series", state.request.type)
        assertEquals(1, state.request.season)
        assertEquals(2, state.request.episode)
    }
}
