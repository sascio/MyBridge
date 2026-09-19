package com.nuvio.app.features.cloudstream

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Provider identity, deduplication and aggregator target selection.
 *
 * These rules decide which CloudStream providers take part in a source request
 * and under which identity they appear in the source picker, so they are
 * covered independently of any networking.
 */
class CloudStreamAggregatorBridgeTest {

    private fun plugin(
        id: String,
        tvTypes: List<String> = emptyList(),
        version: Int? = 1,
        executable: Boolean = true,
    ) = CloudStreamPlugin(
        id = id,
        displayName = id,
        version = version,
        description = null,
        authors = emptyList(),
        language = "en",
        tvTypes = tvTypes,
        iconUrl = "https://e.com/$id.png",
        artifactUrl = "https://e.com/$id.cs3",
        repositoryUrl = "https://e.com",
        fileSize = null,
        fileHash = null,
        apiVersion = 1,
        compatibility = if (executable) {
            CloudStreamCompatibility.COMPATIBLE
        } else {
            CloudStreamCompatibility.UNSUPPORTED
        },
        compatibilityReason = if (executable) {
            CloudStreamCompatibilityReason.NONE
        } else {
            CloudStreamCompatibilityReason.REQUIRES_NATIVE_EXECUTION
        },
    )

    /** Builds an extension with every source enabled, as if the user opted in. */
    private fun enabledExtension(
        id: String,
        tvTypes: List<String> = emptyList(),
        version: Int? = 1,
        executable: Boolean = true,
        repositoryUrl: String = "https://repo-a.example/repo.json",
    ): CloudStreamExtension {
        val base = CloudStreamExtensionMapping.toExtension(
            plugin = plugin(id, tvTypes, version, executable),
            repositoryUrl = repositoryUrl,
        )
        return base.copy(
            // Participation now also requires the package to be on disk, which
            // is what the user opting in genuinely implies.
            installStatus = CloudStreamInstallStatus(
                state = CloudStreamInstallState.ENABLED,
                installedVersion = version,
            ),
            sources = base.sources.map { it.copy(installed = true, enabled = true) },
        )
    }

    // ---- installation gate ----------------------------------------------

    @Test
    fun `an enabled extension whose package is not installed never aggregates`() {
        // Simulates a stale persisted enable flag after the package was removed:
        // there is no provider code on disk, so nothing may run.
        val uninstalled = enabledExtension("Ghost", listOf("Movie"))
            .copy(installStatus = CloudStreamInstallStatus())

        val targets = CloudStreamAggregatorBridge.resolveTargets(listOf(uninstalled), "movie")
        assertTrue(targets.isEmpty())
    }

    @Test
    fun `an installed and enabled extension does aggregate`() {
        val targets = CloudStreamAggregatorBridge.resolveTargets(
            listOf(enabledExtension("Real", listOf("Movie"))),
            "movie",
        )
        assertEquals(1, targets.size)
        assertEquals("cloudstream:real::movie", targets.single().addonId)
    }

    @Test
    fun `an extension still downloading does not aggregate`() {
        val downloading = enabledExtension("Pending", listOf("Movie"))
            .copy(installStatus = CloudStreamInstallStatus(CloudStreamInstallState.DOWNLOADING))

        assertTrue(
            CloudStreamAggregatorBridge.resolveTargets(listOf(downloading), "movie").isEmpty(),
        )
    }

    // ---- identity -------------------------------------------------------

    @Test
    fun addonIdIsStableLowercaseAndNamespaced() {
        assertEquals(
            "cloudstream:kicktr::movie",
            CloudStreamProviderIdentity.addonId("KickTR", "Movie"),
        )
    }

    @Test
    fun addonIdOmitsTypeSegmentWhenExtensionDeclaresNoType() {
        assertEquals("cloudstream:kicktr", CloudStreamProviderIdentity.addonId("KickTR", null))
        assertEquals("cloudstream:kicktr", CloudStreamProviderIdentity.addonId("KickTR", "  "))
    }

    @Test
    fun differentTypesOfOneExtensionAreDifferentProviders() {
        val movie = CloudStreamProviderIdentity.addonId("Multi", "Movie")
        val series = CloudStreamProviderIdentity.addonId("Multi", "TvSeries")
        assertTrue(movie != series)
    }

    // ---- deduplication --------------------------------------------------

    @Test
    fun sameExtensionFromTwoRepositoriesIsDeduplicated() {
        val result = CloudStreamProviderIdentity.deduplicate(
            listOf(
                enabledExtension("Shared", repositoryUrl = "https://a.example/repo.json"),
                enabledExtension("Shared", repositoryUrl = "https://b.example/repo.json"),
            ),
        )
        assertEquals(1, result.size)
    }

    @Test
    fun deduplicationKeepsTheHighestVersion() {
        val result = CloudStreamProviderIdentity.deduplicate(
            listOf(
                enabledExtension("Shared", version = 2),
                enabledExtension("Shared", version = 9),
                enabledExtension("Shared", version = 5),
            ),
        )
        assertEquals(1, result.size)
        assertEquals(9, result.single().plugin.version)
    }

    @Test
    fun genuinelyDifferentExtensionsAreNeverMerged() {
        val result = CloudStreamProviderIdentity.deduplicate(
            listOf(enabledExtension("Alpha"), enabledExtension("Beta")),
        )
        assertEquals(2, result.size)
    }

    // ---- media type matching -------------------------------------------

    @Test
    fun movieRequestMatchesMovieCapableTypesOnly() {
        assertTrue(CloudStreamAggregatorBridge.matchesMediaType("Movie", "movie"))
        assertTrue(CloudStreamAggregatorBridge.matchesMediaType("Anime", "movie"))
        assertFalse(CloudStreamAggregatorBridge.matchesMediaType("TvSeries", "movie"))
    }

    @Test
    fun seriesRequestMatchesSeriesCapableTypesOnly() {
        assertTrue(CloudStreamAggregatorBridge.matchesMediaType("TvSeries", "series"))
        assertTrue(CloudStreamAggregatorBridge.matchesMediaType("Anime", "series"))
        assertFalse(CloudStreamAggregatorBridge.matchesMediaType("Movie", "series"))
    }

    @Test
    fun undeclaredTypeParticipatesRatherThanBeingSilentlyDropped() {
        assertTrue(CloudStreamAggregatorBridge.matchesMediaType(null, "movie"))
        assertTrue(CloudStreamAggregatorBridge.matchesMediaType("", "series"))
    }

    // ---- target resolution ---------------------------------------------

    @Test
    fun resolvesOneTargetPerEnabledMatchingProvider() {
        val targets = CloudStreamAggregatorBridge.resolveTargets(
            extensions = listOf(enabledExtension("Multi", listOf("Movie", "TvSeries"))),
            mediaType = "movie",
        )
        assertEquals(1, targets.size)
        assertEquals("cloudstream:multi::movie", targets.single().addonId)
    }

    @Test
    fun unsupportedExtensionNeverParticipatesEvenWhenPersistedEnabled() {
        // Mirrors a tampered persistence file: state says enabled, plugin is
        // not executable, so it must still be excluded.
        val unsupported = enabledExtension("Dexy", listOf("Movie"), executable = false)
        val targets = CloudStreamAggregatorBridge.resolveTargets(listOf(unsupported), "movie")
        assertTrue(targets.isEmpty())
    }

    @Test
    fun disabledSourceDoesNotParticipate() {
        val extension = enabledExtension("Alpha", listOf("Movie"))
        val disabled = extension.copy(sources = extension.sources.map { it.copy(enabled = false) })
        assertTrue(CloudStreamAggregatorBridge.resolveTargets(listOf(disabled), "movie").isEmpty())
    }

    @Test
    fun duplicateProvidersAcrossRepositoriesYieldASingleTarget() {
        val targets = CloudStreamAggregatorBridge.resolveTargets(
            extensions = listOf(
                enabledExtension("Shared", listOf("Movie"), repositoryUrl = "https://a.example/r.json"),
                enabledExtension("Shared", listOf("Movie"), repositoryUrl = "https://b.example/r.json"),
            ),
            mediaType = "movie",
        )
        assertEquals(1, targets.size)
    }

    @Test
    fun multiTypeExtensionKeepsItsProvidersSeparatePerRequestType() {
        val extension = enabledExtension("Multi", listOf("Movie", "TvSeries"))
        val movieTargets = CloudStreamAggregatorBridge.resolveTargets(listOf(extension), "movie")
        val seriesTargets = CloudStreamAggregatorBridge.resolveTargets(listOf(extension), "series")

        assertEquals("cloudstream:multi::movie", movieTargets.single().addonId)
        assertEquals("cloudstream:multi::tvseries", seriesTargets.single().addonId)
    }

    @Test
    fun providerLabelDisambiguatesOnlyMultiProviderExtensions() {
        val single = CloudStreamAggregatorBridge
            .resolveTargets(listOf(enabledExtension("Solo", listOf("Movie"))), "movie")
            .single()
        assertEquals("Solo", single.addonName)

        val multi = CloudStreamAggregatorBridge
            .resolveTargets(listOf(enabledExtension("Multi", listOf("Movie", "TvSeries"))), "movie")
            .single()
        assertEquals("Multi · Movie", multi.addonName)
    }

    @Test
    fun targetCarriesIdentityNeededByTheAggregatorAndPicker() {
        val target = CloudStreamAggregatorBridge
            .resolveTargets(listOf(enabledExtension("Alpha", listOf("Movie"))), "movie")
            .single()

        assertEquals("Alpha", target.extensionId)
        assertEquals("Alpha::Movie", target.sourceId)
        assertEquals("Movie", target.contentType)
        assertEquals("https://e.com/Alpha.png", target.iconUrl)
    }

    // ---- aggregator group identity --------------------------------------

    @Test
    fun adaptedStreamsCarryTheAggregatorGroupIdentity() {
        // The addonId the bridge assigns must be the addonId on the resulting
        // StreamItem, otherwise streams land outside their source-picker group.
        val target = CloudStreamAggregatorBridge
            .resolveTargets(listOf(enabledExtension("Alpha", listOf("Movie"))), "movie")
            .single()

        val items = CloudStreamProviderAdapter.adaptLinks(
            links = listOf(
                CloudStreamLink(
                    name = "Server 1",
                    url = "https://cdn.example.com/a.mp4",
                    referer = "https://alpha.example",
                    quality = 1080,
                ),
            ),
            pluginName = target.addonName,
            pluginId = "Alpha",
            pluginLogo = target.iconUrl,
            addonId = target.addonId,
        )

        assertEquals("cloudstream:alpha::movie", items.single().addonId)
    }

    @Test
    fun adaptedStreamsPreserveRefererForPlayback() {
        val items = CloudStreamProviderAdapter.adaptLinks(
            links = listOf(
                CloudStreamLink(
                    name = "S",
                    url = "https://cdn.example.com/a.mp4",
                    referer = "https://alpha.example",
                ),
            ),
            pluginName = "Alpha",
            pluginId = "Alpha",
            addonId = "cloudstream:alpha::movie",
        )
        val headers = items.single().behaviorHints?.proxyHeaders?.request.orEmpty()
        assertEquals("https://alpha.example", headers["Referer"])
    }
}
