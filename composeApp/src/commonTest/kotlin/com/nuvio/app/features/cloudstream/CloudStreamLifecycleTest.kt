package com.nuvio.app.features.cloudstream

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End-to-end lifecycle across the real component boundaries:
 *
 *   repository JSON -> discovery -> compatibility -> extension/source mapping
 *   -> install state -> enablement -> aggregator target selection
 *
 * Only the network and the installed-package fact are substituted. Everything
 * else is the production code path, so a regression anywhere in the chain that
 * would leave CloudStream non-functional on device fails here.
 *
 * Fixtures are shaped after genuine published CloudStream repositories
 * (phisher98 / CDEScript style) rather than idealised inputs.
 */
class CloudStreamLifecycleTest {

    private val repoUrl = "https://repo.example/repo.json"
    private val listUrl = "https://repo.example/plugins.json"

    private val repoJson = """
        {
          "name": "Example Repo",
          "description": "Community extensions",
          "manifestVersion": 1,
          "pluginLists": ["$listUrl"]
        }
    """.trimIndent()

    /**
     * Deliberately heterogeneous, mirroring real repositories:
     *  - a movie-only provider
     *  - a multi-type provider (movie + series)
     *  - an anime provider with no declared hash
     *  - an entry whose apiVersion we cannot support
     */
    private val pluginsJson = """
        [
          {"name":"MovieHub","internalName":"MovieHub","url":"https://cdn.example/moviehub.cs3",
           "apiVersion":1,"version":12,"tvTypes":["Movie"],"language":"en",
           "fileSize":96001,"fileHash":"sha256-aa11","authors":["dev1"]},
          {"name":"SeriesHub","internalName":"SeriesHub","url":"https://cdn.example/serieshub.cs3",
           "apiVersion":1,"version":4,"tvTypes":["Movie","TvSeries"],"language":"en"},
          {"name":"AnimeHub","internalName":"AnimeHub","url":"https://cdn.example/animehub.cs3",
           "apiVersion":1,"version":2,"tvTypes":["Anime"],"language":"ja"},
          {"name":"FutureHub","internalName":"FutureHub","url":"https://cdn.example/f.cs3",
           "apiVersion":99,"version":1,"tvTypes":["Movie"]}
        ]
    """.trimIndent()

    private fun loader(canExecute: Boolean) = CloudStreamRepositoryLoader(
        fetch = { url ->
            when (url) {
                repoUrl -> repoJson
                listUrl -> pluginsJson
                else -> throw IllegalStateException("404 $url")
            }
        },
        canExecute = canExecute,
    )

    /** Discovery + mapping, with installation supplied as an explicit fact. */
    private fun discover(
        canExecute: Boolean,
        installedIds: Set<String> = emptySet(),
        enabledSourceIds: Set<String> = emptySet(),
    ): List<CloudStreamExtension> = runBlocking {
        val repository = loader(canExecute).load(repoUrl)
        repository.plugins.map { plugin ->
            val states = plugin.tvTypes
                .ifEmpty { listOf<String?>(null) }
                .mapNotNull { type ->
                    val id = CloudStreamExtensionMapping.sourceId(plugin, type)
                    if (id in enabledSourceIds) {
                        id to CloudStreamSourceState(id, enabled = true, installed = true)
                    } else {
                        null
                    }
                }.toMap()

            CloudStreamExtensionMapping.toExtension(
                plugin = plugin,
                repositoryUrl = repository.url,
                states = states,
            ).copy(
                installStatus = if (plugin.id in installedIds) {
                    CloudStreamInstallStatus(
                        state = CloudStreamInstallState.ENABLED,
                        installedVersion = plugin.version,
                    )
                } else {
                    CloudStreamInstallStatus()
                },
            )
        }
    }

    // ---- discovery --------------------------------------------------------

    @Test
    fun `a real repository yields every well-formed extension`() {
        val extensions = discover(canExecute = true)
        assertEquals(4, extensions.size)
        assertEquals(
            listOf("AnimeHub", "FutureHub", "MovieHub", "SeriesHub"),
            extensions.map { it.id }.sorted(),
        )
    }

    @Test
    fun `an unsupported api version stays unusable while its peers are usable`() {
        val extensions = discover(canExecute = true).associateBy { it.id }
        assertTrue(extensions.getValue("MovieHub").plugin.isExecutable)
        // One bad entry must not contaminate the rest of the repository.
        assertFalse(extensions.getValue("FutureHub").plugin.isExecutable)
        assertEquals(
            CloudStreamCompatibilityReason.UNSUPPORTED_API_VERSION,
            extensions.getValue("FutureHub").compatibilityReason,
        )
    }

    @Test
    fun `a missing fileHash does not block an otherwise valid extension`() {
        // Real repositories omit fileHash; that weakens the guarantee but is
        // not a compatibility failure.
        val serieshub = discover(canExecute = true).single { it.id == "SeriesHub" }
        assertTrue(serieshub.plugin.fileHash == null)
        assertTrue(serieshub.plugin.isExecutable)
    }

    // ---- sources ----------------------------------------------------------

    @Test
    fun `each declared tvType becomes its own manageable source`() {
        val serieshub = discover(canExecute = true).single { it.id == "SeriesHub" }
        assertEquals(2, serieshub.sourceCount)
        assertEquals(
            listOf("SeriesHub::Movie", "SeriesHub::TvSeries"),
            serieshub.sources.map { it.id },
        )
    }

    @Test
    fun `configure is never offered because no real configuration contract exists`() {
        discover(canExecute = true).forEach { extension ->
            extension.sources.forEach { source ->
                assertFalse(source.supportsConfiguration)
            }
        }
    }

    // ---- installation gate -------------------------------------------------

    @Test
    fun `an enabled source does not aggregate until its package is installed`() {
        val notInstalled = discover(
            canExecute = true,
            enabledSourceIds = setOf("MovieHub::Movie"),
        )
        assertTrue(CloudStreamAggregatorBridge.resolveTargets(notInstalled, "movie").isEmpty())

        val installed = discover(
            canExecute = true,
            installedIds = setOf("MovieHub"),
            enabledSourceIds = setOf("MovieHub::Movie"),
        )
        assertEquals(1, CloudStreamAggregatorBridge.resolveTargets(installed, "movie").size)
    }

    @Test
    fun `nothing aggregates on a build without an execution runtime`() {
        // The Play Store / iOS boundary, proven end to end rather than assumed.
        val extensions = discover(
            canExecute = false,
            installedIds = setOf("MovieHub", "SeriesHub"),
            enabledSourceIds = setOf("MovieHub::Movie", "SeriesHub::TvSeries"),
        )
        assertTrue(CloudStreamAggregatorBridge.resolveTargets(extensions, "movie").isEmpty())
        assertTrue(CloudStreamAggregatorBridge.resolveTargets(extensions, "series").isEmpty())
    }

    // ---- aggregation ------------------------------------------------------

    @Test
    fun `movie and series requests select the right providers`() {
        val extensions = discover(
            canExecute = true,
            installedIds = setOf("MovieHub", "SeriesHub", "AnimeHub"),
            enabledSourceIds = setOf(
                "MovieHub::Movie",
                "SeriesHub::Movie",
                "SeriesHub::TvSeries",
                "AnimeHub::Anime",
            ),
        )

        val movieTargets = CloudStreamAggregatorBridge.resolveTargets(extensions, "movie")
        // Anime legitimately serves movie requests in CloudStream.
        assertEquals(
            listOf("cloudstream:animehub::anime", "cloudstream:moviehub::movie", "cloudstream:serieshub::movie"),
            movieTargets.map { it.addonId }.sorted(),
        )

        val seriesTargets = CloudStreamAggregatorBridge.resolveTargets(extensions, "series")
        assertEquals(
            listOf("cloudstream:animehub::anime", "cloudstream:serieshub::tvseries"),
            seriesTargets.map { it.addonId }.sorted(),
        )
    }

    @Test
    fun `provider identity is derived from internal name not display name`() {
        val extensions = discover(
            canExecute = true,
            installedIds = setOf("MovieHub"),
            enabledSourceIds = setOf("MovieHub::Movie"),
        )
        val target = CloudStreamAggregatorBridge.resolveTargets(extensions, "movie").single()
        assertEquals("cloudstream:moviehub::movie", target.addonId)
        assertEquals("MovieHub", target.extensionId)
    }

    @Test
    fun `a disabled source is excluded while its sibling stays active`() {
        val extensions = discover(
            canExecute = true,
            installedIds = setOf("SeriesHub"),
            // Only the TvSeries source is switched on.
            enabledSourceIds = setOf("SeriesHub::TvSeries"),
        )
        assertTrue(CloudStreamAggregatorBridge.resolveTargets(extensions, "movie").isEmpty())
        assertEquals(1, CloudStreamAggregatorBridge.resolveTargets(extensions, "series").size)
    }
}
