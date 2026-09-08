package com.streambridge.app

import com.streambridge.app.addon.StreamEnrichment
import com.streambridge.app.addon.model.StreamOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamEnrichmentTest {

    private fun option(
        label: String,
        url: String? = null,
        infoHash: String? = null,
        addon: String = "A"
    ) = StreamOption(
        id = "$addon::$label",
        label = label,
        description = null,
        url = url,
        infoHash = infoHash,
        externalUrl = null,
        addonName = addon,
        isTorrent = infoHash != null,
        isExternal = false,
        bingeGroup = ""
    )

    @Test
    fun `enrich parses resolution quality language size and seeders`() {
        val enriched = StreamEnrichment.enrich(
            option("Movie 2019 1080p BluRay x264 English 1.4 GB (43 seeders)", infoHash = "abc")
        )
        assertEquals(1080, enriched.resolution)
        assertEquals("1080P BLURAY AVC", enriched.quality)
        assertEquals("en", enriched.language)
        assertEquals(1_400_000_000L, enriched.sizeBytes)
        assertEquals(43, enriched.seeders)
    }

    @Test
    fun `enrich handles 4k and missing descriptors`() {
        val enriched = StreamEnrichment.enrich(option("Best stream 4K 2160p WEB-DL", url = "https://x"))
        assertEquals(2160, enriched.resolution)
        assertTrue(enriched.quality.contains("4K"))
        assertTrue(enriched.quality.contains("WEBDL"))
        assertEquals("", enriched.language)
        assertEquals(0L, enriched.sizeBytes)
    }

    @Test
    fun `enrich maps language codes`() {
        assertEquals("hi", StreamEnrichment.enrich(option("hindi")).language)
        assertEquals("multi", StreamEnrichment.enrich(option("MULTI AUDIO")).language)
    }

    @Test
    fun `picker sort puts playable direct streams before torrents`() {
        val sorted = StreamEnrichment.sortForPicker(
            listOf(
                StreamEnrichment.enrich(option("1080p", infoHash = "aa", addon = "T")),
                StreamEnrichment.enrich(option("480p", url = "https://sd", addon = "D")),
                StreamEnrichment.enrich(option("1080p", url = "https://hd", addon = "D"))
            )
        )
        assertEquals("https://hd", sorted[0].url)
        assertEquals("https://sd", sorted[1].url)
        assertEquals("aa", sorted[2].infoHash)
    }

    @Test
    fun `picker sort ranks torrents by seeders`() {
        val sorted = StreamEnrichment.sortForPicker(
            listOf(
                StreamEnrichment.enrich(option("720p (5 seeders)", infoHash = "aa", addon = "T")),
                StreamEnrichment.enrich(option("720p (500 seeders)", infoHash = "bb", addon = "T")),
                StreamEnrichment.enrich(option("720p (43 seeders)", infoHash = "cc", addon = "T"))
            )
        )
        assertEquals(500, sorted[0].seeders)
        assertEquals(43, sorted[1].seeders)
        assertEquals(5, sorted[2].seeders)
    }

    @Test
    fun `grouping by provider sorts groups by playable count`() {
        val groups = StreamEnrichment.groupByProvider(
            listOf(
                option("1080p", infoHash = "aa", addon = "Torrents"),
                option("720p", infoHash = "xx", addon = "Torrents"),
                option("480p", url = "https://c", addon = "Direct")
            )
        )
        assertEquals(listOf("Direct", "Torrents"), groups.map { it.first })
        assertEquals(2, groups.first { it.first == "Torrents" }.second.size)
    }
}
