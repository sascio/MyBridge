package com.nuvio.app.features.cloudstream

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class CloudStreamCatalogUiBoundaryTest {

    @Test
    fun `homepage item has a stable detail identity and normalized media type`() {
        val item = CloudStreamCatalogItem(
            title = "Provider series",
            url = "https://provider.example/title/series",
            poster = "https://cdn.example/poster.jpg",
            mediaType = "TvSeries",
            providerName = "Generic provider",
            extensionId = "extension-id",
            sourceId = "extension-id::tvseries",
            addonId = "cloudstream:extension-id::tvseries",
            sectionPath = listOf("Generic provider", "Featured", "Series"),
            metadata = CloudStreamResponseMetadata(
                title = "Provider series",
                url = "https://provider.example/title/series",
                description = "Provider detail description",
                year = 2026,
                mediaType = "TvSeries",
                providerName = "Generic provider",
                episodes = listOf(
                    CloudStreamEpisodeMetadata(
                        data = "https://provider.example/episode/1",
                        title = "Episode one",
                        season = 1,
                        episode = 1,
                    ),
                ),
            ),
        )

        val id = CloudStreamCatalogStore.register(item)
        val details = item.metadata!!.toMetaDetails(id)

        assertEquals("series", item.uiMediaType())
        assertEquals(item.catalogSourceKey(), item.copy().catalogSourceKey())
        assertEquals(
            false,
            item.catalogSourceKey() == item.copy(sectionPath = listOf("Generic provider", "Latest"))
                .catalogSourceKey(),
        )
        assertNotNull(CloudStreamCatalogStore.get(id))
        assertEquals(id, details.id)
        assertEquals("series", details.type)
        assertEquals("Provider detail description", details.description)
        assertEquals(1, details.videos.size)
        assertEquals(1, details.videos.single().episode)
    }
}
