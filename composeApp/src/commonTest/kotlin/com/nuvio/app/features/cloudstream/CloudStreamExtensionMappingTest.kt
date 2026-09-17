package com.nuvio.app.features.cloudstream

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Extension/source hierarchy, Overview counters and the honesty rules that
 * stop unsupported plugins from ever appearing installed or active.
 */
class CloudStreamExtensionMappingTest {

    private fun plugin(
        id: String = "Alpha",
        tvTypes: List<String> = emptyList(),
        compatibility: CloudStreamCompatibility = CloudStreamCompatibility.UNSUPPORTED,
        reason: CloudStreamCompatibilityReason = CloudStreamCompatibilityReason.REQUIRES_NATIVE_EXECUTION,
        language: String? = "en",
    ) = CloudStreamPlugin(
        id = id,
        displayName = id,
        version = 3,
        description = "desc",
        authors = listOf("Author"),
        language = language,
        tvTypes = tvTypes,
        iconUrl = "https://e.com/i.png",
        artifactUrl = "https://e.com/$id.cs3",
        repositoryUrl = "https://e.com",
        fileSize = 1234,
        fileHash = "sha256-abc",
        apiVersion = 1,
        compatibility = compatibility,
        compatibilityReason = reason,
    )

    /** Only an executable plugin can produce activatable sources. */
    private fun executablePlugin(id: String = "Alpha", tvTypes: List<String> = emptyList()) =
        plugin(
            id = id,
            tvTypes = tvTypes,
            compatibility = CloudStreamCompatibility.COMPATIBLE,
            reason = CloudStreamCompatibilityReason.NONE,
        )

    @Test
    fun `each declared tv type becomes its own source`() {
        val extension = CloudStreamExtensionMapping.toExtension(
            plugin(tvTypes = listOf("Movie", "TvSeries", "Anime")),
            repositoryUrl = "https://e.com/repo.json",
        )
        assertEquals(3, extension.sourceCount)
        assertEquals(
            listOf("Movie", "TvSeries", "Anime"),
            extension.sources.map { it.contentType },
        )
    }

    @Test
    fun `an extension declaring no types still exposes one source`() {
        val extension = CloudStreamExtensionMapping.toExtension(
            plugin(tvTypes = emptyList()),
            repositoryUrl = "https://e.com/repo.json",
        )
        assertEquals(1, extension.sourceCount)
        assertEquals(null, extension.sources.single().contentType)
    }

    @Test
    fun `source ids are unique per content type`() {
        val extension = CloudStreamExtensionMapping.toExtension(
            plugin(tvTypes = listOf("Movie", "TvSeries")),
            repositoryUrl = "https://e.com/repo.json",
        )
        assertEquals(
            extension.sources.map { it.id }.distinct().size,
            extension.sources.size,
        )
        assertEquals(listOf("Alpha::Movie", "Alpha::TvSeries"), extension.sources.map { it.id })
    }

    @Test
    fun `overview counts extensions active and catalogs`() {
        val extensions = listOf(
            CloudStreamExtensionMapping.toExtension(
                plugin(id = "A", tvTypes = listOf("Movie", "TvSeries")),
                "https://e.com/repo.json",
            ),
            CloudStreamExtensionMapping.toExtension(
                plugin(id = "B", tvTypes = listOf("Anime")),
                "https://e.com/repo.json",
            ),
        )
        val overview = CloudStreamExtensionMapping.overview(extensions)
        assertEquals(2, overview.extensionCount)
        assertEquals(3, overview.catalogCount)
        // Nothing is executable, so nothing can be active.
        assertEquals(0, overview.activeCount)
    }

    @Test
    fun `an unsupported plugin can never be installed or enabled even if persisted`() {
        val states = mapOf(
            "Alpha" to CloudStreamSourceState("Alpha", enabled = true, installed = true),
        )
        val extension = CloudStreamExtensionMapping.toExtension(
            plugin(),
            repositoryUrl = "https://e.com/repo.json",
            states = states,
        )
        val source = extension.sources.single()
        assertTrue(!source.enabled)
        assertTrue(!source.installed)
        assertTrue(!source.canActivate)
        assertTrue(!extension.isActive)
    }

    @Test
    fun `an executable source honours persisted enabled state`() {
        val states = mapOf(
            "Alpha" to CloudStreamSourceState("Alpha", enabled = true, installed = true),
        )
        val extension = CloudStreamExtensionMapping.toExtension(
            executablePlugin(),
            repositoryUrl = "https://e.com/repo.json",
            states = states,
        )
        val source = extension.sources.single()
        assertTrue(source.canActivate)
        assertTrue(source.enabled)
        assertTrue(source.installed)
        assertTrue(extension.isActive)
    }

    @Test
    fun `active count reflects only extensions with an enabled source`() {
        val states = mapOf(
            "A::Movie" to CloudStreamSourceState("A::Movie", enabled = true, installed = true),
        )
        val extensions = listOf(
            CloudStreamExtensionMapping.toExtension(
                executablePlugin("A", listOf("Movie", "TvSeries")),
                "https://e.com/repo.json",
                states,
            ),
            CloudStreamExtensionMapping.toExtension(
                executablePlugin("B", listOf("Anime")),
                "https://e.com/repo.json",
                states,
            ),
        )
        val overview = CloudStreamExtensionMapping.overview(extensions)
        assertEquals(2, overview.extensionCount)
        assertEquals(1, overview.activeCount)
        assertEquals(3, overview.catalogCount)
    }

    @Test
    fun `overview of no extensions is all zeroes`() {
        val overview = CloudStreamExtensionMapping.overview(emptyList())
        assertEquals(0, overview.extensionCount)
        assertEquals(0, overview.activeCount)
        assertEquals(0, overview.catalogCount)
    }

    @Test
    fun `configure is hidden because cs3 exposes no declarative configuration`() {
        val extension = CloudStreamExtensionMapping.toExtension(
            plugin(tvTypes = listOf("Movie")),
            repositoryUrl = "https://e.com/repo.json",
        )
        val source = extension.sources.single()
        assertTrue(source.configuration.isEmpty())
        assertTrue(!source.supportsConfiguration)
    }

    @Test
    fun `extension metadata is carried through to the ui model`() {
        val extension = CloudStreamExtensionMapping.toExtension(
            plugin(tvTypes = listOf("Movie")),
            repositoryUrl = "https://e.com/repo.json",
        )
        assertEquals("Alpha", extension.name)
        assertEquals(3, extension.plugin.version)
        assertEquals(listOf("Author"), extension.plugin.authors)
        assertEquals("en", extension.plugin.language)
        assertEquals("https://e.com/i.png", extension.plugin.iconUrl)
        assertEquals("sha256-abc", extension.plugin.fileHash)
        assertEquals(1234L, extension.plugin.fileSize)
        assertEquals("https://e.com/repo.json", extension.repositoryUrl)
    }

    @Test
    fun `sources inherit the extension compatibility state and reason`() {
        val extension = CloudStreamExtensionMapping.toExtension(
            plugin(tvTypes = listOf("Movie")),
            repositoryUrl = "https://e.com/repo.json",
        )
        val source = extension.sources.single()
        assertEquals(CloudStreamCompatibility.UNSUPPORTED, source.compatibility)
        assertEquals(
            CloudStreamCompatibilityReason.REQUIRES_NATIVE_EXECUTION,
            source.compatibilityReason,
        )
    }
}
