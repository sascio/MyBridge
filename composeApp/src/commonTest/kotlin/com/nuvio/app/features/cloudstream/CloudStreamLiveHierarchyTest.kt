package com.nuvio.app.features.cloudstream

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

class CloudStreamLiveHierarchyTest {
    @Test
    fun `provider and arbitrary depth section nodes are preserved`() {
        val items = listOf(
            live("One", "repository-a", "extension-a", "provider-a", listOf("Provider", "Sports", "Football")),
            live("Two", "repository-a", "extension-a", "provider-a", listOf("Provider", "Sports", "Cricket")),
            live("Three", "repository-a", "extension-a", "provider-a", listOf("Provider", "News")),
        )

        val hierarchy = CloudStreamLiveHierarchy.fromCatalog(items)
        val extension = hierarchy.roots.single()
        val provider = extension.children.single()
        val sectionTitles = provider.children.map { it.title }

        assertEquals(1, hierarchy.roots.size)
        assertEquals(CloudStreamLiveNodeType.ROOT, hierarchy.root.nodeType)
        assertEquals(CloudStreamLiveNodeType.EXTENSION, extension.nodeType)
        assertEquals(CloudStreamLiveNodeType.PROVIDER, provider.nodeType)
        assertEquals(listOf("News", "Sports"), sectionTitles)
        assertEquals(listOf("Cricket", "Football"), provider.children[1].children.map { it.title })
        assertEquals(3, hierarchy.channels.size)
        assertNotEquals(hierarchy.channels[0].nodeId, hierarchy.channels[1].nodeId)
        assertEquals(
            hierarchy.channels.single { it.title == "One" }.catalogItem?.url,
            hierarchy.channels.single { it.title == "One" }.navigationPayload,
        )
    }

    @Test
    fun `equal display names from different providers remain separate`() {
        val first = live("Same", "repository-a", "extension-a", "provider-a", listOf("Provider", "Channels"))
        val second = live("Same", "repository-b", "extension-b", "provider-b", listOf("Provider", "Channels"))

        val hierarchy = CloudStreamLiveHierarchy.fromCatalog(listOf(first, second))

        assertEquals(2, hierarchy.roots.size)
        assertNotEquals(hierarchy.roots[0].nodeId, hierarchy.roots[1].nodeId)
        assertNotEquals(hierarchy.channels[0].nodeId, hierarchy.channels[1].nodeId)
        assertNotNull(hierarchy.node(hierarchy.channels.first().nodeId))
    }

    private fun live(
        title: String,
        repositoryId: String,
        extensionId: String,
        providerId: String,
        sectionPath: List<String>,
    ) = CloudStreamLiveCatalogItem(
        title = title,
        url = "https://example.test/$repositoryId/$extensionId/$providerId/$title",
        providerName = "Provider",
        providerId = providerId,
        repositoryId = repositoryId,
        extensionId = extensionId,
        sectionPath = sectionPath,
        mediaType = "Live",
        metadata = CloudStreamResponseMetadata(
            title = title,
            mediaType = "Live",
            isLive = true,
        ),
    )
}
