package com.nuvio.app.features.cloudstream

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fixtures mirror real published CloudStream repositories
 * (`repo.json` / `plugins.json`) field-for-field.
 */
class CloudStreamRepositoryParserTest {

    private val realRepoJson = """
        {
          "name": "CDEScript",
          "description": "Curated plugin collection",
          "manifestVersion": 1,
          "pluginLists": [
            "https://raw.githubusercontent.com/example/repo/main/plugins.json"
          ]
        }
    """.trimIndent()

    private val realPluginsJson = """
        [
          {
            "url": "https://example.com/AllMovieLandProvider.cs3",
            "status": 1,
            "version": 25,
            "name": "AllMovieLandProvider",
            "internalName": "AllMovieLandProvider",
            "authors": ["Phisher98"],
            "description": "Indian MultiLanguage Provider",
            "fileSize": 96001,
            "repositoryUrl": "https://github.com/example/ext",
            "language": "hi",
            "tvTypes": ["Movie", "TvSeries", "Cartoon"],
            "iconUrl": "https://example.com/icon.png",
            "apiVersion": 1,
            "fileHash": "sha256-76967d2992879b09511d35b0e1e44ec4"
          }
        ]
    """.trimIndent()

    @Test
    fun `parses a real repo manifest`() {
        val manifest = CloudStreamRepositoryParser.parseRepositoryManifest(realRepoJson)
        assertNotNull(manifest)
        assertEquals("CDEScript", manifest.name)
        assertEquals(1, manifest.manifestVersion)
        assertEquals(1, manifest.pluginLists.size)
        assertTrue(CloudStreamRepositoryParser.isSupportedManifestVersion(manifest))
    }

    @Test
    fun `parses a real plugin list preserving every metadata field`() {
        val plugins = CloudStreamRepositoryParser.parsePluginList(realPluginsJson)
        assertNotNull(plugins)
        assertEquals(1, plugins.size)

        val plugin = CloudStreamRepositoryParser.toPlugin(plugins.first())
        assertEquals("AllMovieLandProvider", plugin.id)
        assertEquals("AllMovieLandProvider", plugin.displayName)
        assertEquals(25, plugin.version)
        assertEquals(listOf("Phisher98"), plugin.authors)
        assertEquals("hi", plugin.language)
        assertEquals(listOf("Movie", "TvSeries", "Cartoon"), plugin.tvTypes)
        assertEquals(96001L, plugin.fileSize)
        assertEquals("sha256-76967d2992879b09511d35b0e1e44ec4", plugin.fileHash)
        assertEquals("https://example.com/icon.png", plugin.iconUrl)
    }

    @Test
    fun `a well-formed plugin is reported unsupported because it needs DEX execution`() {
        val plugin = CloudStreamRepositoryParser.toPlugin(
            CloudStreamRepositoryParser.parsePluginList(realPluginsJson)!!.first(),
        )
        assertEquals(CloudStreamCompatibility.UNSUPPORTED, plugin.compatibility)
        assertEquals(
            CloudStreamCompatibilityReason.REQUIRES_NATIVE_EXECUTION,
            plugin.compatibilityReason,
        )
        assertTrue(!plugin.isExecutable)
    }

    @Test
    fun `discovery never marks a plugin installed`() {
        val plugin = CloudStreamRepositoryParser.toPlugin(
            CloudStreamRepositoryParser.parsePluginList(realPluginsJson)!!.first(),
        )
        assertTrue(!plugin.installed)
    }

    @Test
    fun `malformed json is rejected without throwing`() {
        assertNull(CloudStreamRepositoryParser.parseRepositoryManifest("{ not json"))
        assertNull(CloudStreamRepositoryParser.parsePluginList("{ not json"))
        assertNull(CloudStreamRepositoryParser.parseRepositoryManifest(""))
        assertNull(CloudStreamRepositoryParser.parsePluginList(""))
    }

    @Test
    fun `an object where an array is expected is rejected`() {
        assertNull(CloudStreamRepositoryParser.parsePluginList("""{"name":"x"}"""))
    }

    @Test
    fun `one broken entry does not discard the rest of the list`() {
        val mixed = """
            [
              {"name":"Good","internalName":"Good","url":"https://e.com/a.cs3","apiVersion":1},
              12345,
              {"name":"AlsoGood","internalName":"AlsoGood","url":"https://e.com/b.cs3","apiVersion":1}
            ]
        """.trimIndent()
        val plugins = CloudStreamRepositoryParser.parsePluginList(mixed)
        assertNotNull(plugins)
        assertEquals(2, plugins.size)
    }

    @Test
    fun `unknown future fields are ignored rather than failing`() {
        val withExtras = """
            [{"name":"X","internalName":"X","url":"https://e.com/x.cs3",
              "apiVersion":1,"somethingBrandNew":{"nested":true}}]
        """.trimIndent()
        val plugins = CloudStreamRepositoryParser.parsePluginList(withExtras)
        assertNotNull(plugins)
        assertEquals(1, plugins.size)
    }

    @Test
    fun `an entry with no identity is reported failed`() {
        val anonymous = """[{"url":"https://e.com/x.cs3","apiVersion":1}]"""
        val plugin = CloudStreamRepositoryParser.toPlugin(
            CloudStreamRepositoryParser.parsePluginList(anonymous)!!.first(),
        )
        assertEquals(CloudStreamCompatibility.FAILED, plugin.compatibility)
        assertEquals(
            CloudStreamCompatibilityReason.INCOMPLETE_METADATA,
            plugin.compatibilityReason,
        )
    }

    @Test
    fun `an entry without an artifact url is incomplete`() {
        val noUrl = """[{"name":"X","internalName":"X","apiVersion":1}]"""
        val plugin = CloudStreamRepositoryParser.toPlugin(
            CloudStreamRepositoryParser.parsePluginList(noUrl)!!.first(),
        )
        assertEquals(
            CloudStreamCompatibilityReason.INCOMPLETE_METADATA,
            plugin.compatibilityReason,
        )
    }

    @Test
    fun `a newer plugin api version is reported as unsupported api version`() {
        val futureApi = """[{"name":"X","internalName":"X","url":"https://e.com/x.cs3","apiVersion":99}]"""
        val plugin = CloudStreamRepositoryParser.toPlugin(
            CloudStreamRepositoryParser.parsePluginList(futureApi)!!.first(),
        )
        assertEquals(
            CloudStreamCompatibilityReason.UNSUPPORTED_API_VERSION,
            plugin.compatibilityReason,
        )
    }

    @Test
    fun `a newer repository manifest version is detected`() {
        val future = CloudStreamRepositoryParser.parseRepositoryManifest(
            """{"name":"R","manifestVersion":99,"pluginLists":[]}""",
        )
        assertNotNull(future)
        assertTrue(!CloudStreamRepositoryParser.isSupportedManifestVersion(future))
    }
}
