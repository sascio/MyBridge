package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The source picker's empty state must distinguish "this provider had nothing"
 * from "this provider failed", and when it failed it must say why.
 *
 * Before this, every CloudStream failure — extension could not be loaded, the
 * provider has no match for the title, the provider timed out — reduced to the
 * same sentence, which is what made the reported bug undiagnosable both for the
 * user and from a bug report.
 */
class StreamsEmptyStateReportingTest {

    private fun group(
        addonId: String,
        addonName: String = addonId,
        streams: List<StreamItem> = emptyList(),
        error: String? = null,
        isLoading: Boolean = false,
    ) = AddonStreamGroup(
        addonName = addonName,
        addonId = addonId,
        streams = streams,
        isLoading = isLoading,
        error = error,
    )

    private fun stream(addonId: String) = StreamItem(
        name = "Server 1",
        url = "https://cdn.example.com/video.mp4",
        addonName = addonId,
        addonId = addonId,
    )

    @Test
    fun `a provider that failed is reported as a failure, not as no streams`() {
        val groups = listOf(
            group("cloudstream:HiAnime", error = "Provider has no S1E3: AnimeLoadResponse offers 12 entries"),
        )
        assertEquals(
            StreamsEmptyStateReason.StreamFetchFailed,
            groups.toEmptyStateReason(anyLoading = false),
        )
        assertEquals(
            "Provider has no S1E3: AnimeLoadResponse offers 12 entries",
            groups.toEmptyStateDetail(anyLoading = false),
        )
    }

    @Test
    fun `a provider with nothing to offer is not reported as an error`() {
        val groups = listOf(group("cloudstream:HiAnime"))
        assertEquals(
            StreamsEmptyStateReason.NoStreamsFound,
            groups.toEmptyStateReason(anyLoading = false),
        )
        assertNull(groups.toEmptyStateDetail(anyLoading = false))
    }

    @Test
    fun `the failure detail names the provider when several are involved`() {
        val groups = listOf(
            group("cloudstream:HiAnime", addonName = "HiAnime", error = "timed out during loadLinks"),
            group("addon:torrentio", addonName = "Torrentio", error = "HTTP 502"),
        )
        val detail = groups.toEmptyStateDetail(anyLoading = false)
        assertTrue(detail!!.startsWith("HiAnime: "), detail)
        assertTrue("timed out during loadLinks" in detail, detail)
    }

    @Test
    fun `one working provider suppresses the empty state entirely`() {
        val groups = listOf(
            group("cloudstream:HiAnime", error = "boom"),
            group("addon:torrentio", streams = listOf(stream("addon:torrentio"))),
        )
        assertNull(groups.toEmptyStateReason(anyLoading = false))
        assertNull(groups.toEmptyStateDetail(anyLoading = false))
    }

    @Test
    fun `nothing is reported while providers are still loading`() {
        val groups = listOf(group("cloudstream:HiAnime", error = "boom", isLoading = true))
        assertNull(groups.toEmptyStateReason(anyLoading = true))
        assertNull(groups.toEmptyStateDetail(anyLoading = true))
    }

    @Test
    fun `a mix of failure and genuine emptiness is not called a failure`() {
        // "All of them failed" is the only honest basis for a failure message.
        val groups = listOf(
            group("cloudstream:HiAnime", error = "boom"),
            group("addon:torrentio"),
        )
        assertEquals(
            StreamsEmptyStateReason.NoStreamsFound,
            groups.toEmptyStateReason(anyLoading = false),
        )
        assertNull(groups.toEmptyStateDetail(anyLoading = false))
    }
}
