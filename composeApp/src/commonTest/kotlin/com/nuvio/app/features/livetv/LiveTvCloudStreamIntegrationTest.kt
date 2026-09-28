package com.nuvio.app.features.livetv

import com.nuvio.app.features.cloudstream.CloudStreamLiveCatalogItem
import com.nuvio.app.features.cloudstream.CloudStreamResponseMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class LiveTvCloudStreamIntegrationTest {

    @Test
    fun `a normalized CloudStream live catalog item reaches the Live TV channel model`() {
        val item = CloudStreamLiveCatalogItem(
            title = "Provider channel",
            url = "https://provider.example/channel/detail",
            poster = "https://cdn.example/poster.png",
            category = "News",
            sectionPath = listOf("Generic CloudStream provider", "News"),
            providerName = "Generic CloudStream provider",
            addonId = "cloudstream:generic::movie",
            metadata = CloudStreamResponseMetadata(
                title = "Provider channel",
                url = "https://provider.example/channel/detail",
                dataUrl = "https://cdn.example/live.m3u8?token=opaque",
                poster = "https://cdn.example/poster.png",
                description = "Provider supplied channel description",
                providerName = "Generic CloudStream provider",
                mediaType = "Live",
                isLive = true,
                liveStatus = "live",
                channelName = "Provider channel",
            ),
        )

        val channel = item.toLiveTvChannel()

        assertEquals("Provider channel", channel.name)
        assertEquals("https://provider.example/channel/detail", channel.streamUrl)
        assertEquals("https://cdn.example/poster.png", channel.logoUrl)
        assertEquals("News", channel.group)
        assertEquals(listOf("Generic CloudStream provider", "News"), channel.hierarchy)
        assertEquals("Generic CloudStream provider", channel.providerName)
        assertEquals("Provider supplied channel description", channel.description)
        assertEquals("https://cdn.example/live.m3u8?token=opaque", channel.metadata?.dataUrl)
        assertEquals(true, channel.metadata?.isLive)
        assertNotNull(channel.cloudStreamItem)
    }

    @Test
    fun `playlist channels remain independent of CloudStream catalog channels`() {
        val channels = parseM3uPlaylist(
            """
            #EXTM3U
            #EXTINF:-1 group-title="Existing",Existing playlist channel
            https://playlist.example/live.ts
            """.trimIndent(),
        )

        assertEquals(1, channels.size)
        assertEquals("Existing playlist channel", channels.single().name)
        assertEquals("https://playlist.example/live.ts", channels.single().streamUrl)
        assertEquals(null, channels.single().cloudStreamItem)
    }
}
