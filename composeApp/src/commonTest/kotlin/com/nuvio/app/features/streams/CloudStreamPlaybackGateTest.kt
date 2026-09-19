package com.nuvio.app.features.streams

import com.nuvio.app.features.cloudstream.CloudStreamExtension
import com.nuvio.app.features.cloudstream.CloudStreamExtensionMapping
import com.nuvio.app.features.cloudstream.CloudStreamInstallState
import com.nuvio.app.features.cloudstream.CloudStreamInstallStatus
import com.nuvio.app.features.cloudstream.CloudStreamPluginManifest
import com.nuvio.app.features.cloudstream.CloudStreamRepositoryParser
import com.nuvio.app.features.cloudstream.CloudStreamSourceState
import com.nuvio.app.features.plugins.PluginScraper
import com.nuvio.app.features.plugins.PluginsUiState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression guard for the reported device failure: with a CloudStream
 * extension installed and enabled, tapping Play showed
 * "Playback isn't available for this title with your current setup" and the
 * Source Results screen never opened.
 *
 * The cause was that [hasCompatiblePlaybackSource] -- the gate that runs
 * *before* navigation -- only knew about Stremio addons and plugin scrapers.
 * CloudStream was a first-class source everywhere downstream, but invisible to
 * the one check that decides whether Play may proceed, so playback was refused
 * before the aggregator was ever consulted.
 *
 * The two outcomes below must stay distinguishable: an eligible CloudStream
 * provider has to open Source Results, and no eligible provider at all must
 * still produce the existing unavailable behaviour.
 */
class CloudStreamPlaybackGateTest {

    // ---- the exact reported bug -------------------------------------------

    @Test
    fun `an installed and enabled cloudstream extension allows Play to proceed`() {
        assertTrue(
            available(cloudStream = listOf(extension())),
            "Play must be allowed when an eligible CloudStream provider exists, " +
                "so the Source Results screen can open and run it",
        )
    }

    @Test
    fun `no sources at all still refuses playback`() {
        // The complementary case, which must remain distinguishable.
        assertFalse(available())
        assertFalse(available(cloudStream = emptyList()))
    }

    // ---- the gate must agree with the aggregator ---------------------------

    @Test
    fun `an extension that is not installed does not allow Play`() {
        val notInstalled = extension().copy(installStatus = CloudStreamInstallStatus())
        assertFalse(available(cloudStream = listOf(notInstalled)))
    }

    @Test
    fun `an installed but disabled extension does not allow Play`() {
        assertFalse(available(cloudStream = listOf(extension(enabled = false))))
    }

    @Test
    fun `an extension that cannot execute on this build does not allow Play`() {
        // A Play Store build restoring the same persisted state must not offer
        // playback it has no runtime to deliver.
        assertFalse(available(cloudStream = listOf(extension(canExecute = false))))
    }

    // ---- types -------------------------------------------------------------

    @Test
    fun `a movie-only extension does not allow Play for a series`() {
        val movieOnly = extension(tvTypes = listOf("Movie"), enabledTypes = setOf("Movie"))
        assertTrue(available(cloudStream = listOf(movieOnly), type = "movie"))
        assertFalse(available(cloudStream = listOf(movieOnly), type = "series"))
    }

    @Test
    fun `a series extension allows Play for a series`() {
        val series = extension(tvTypes = listOf("TvSeries"), enabledTypes = setOf("TvSeries"))
        assertTrue(available(cloudStream = listOf(series), type = "series"))
    }

    @Test
    fun `cloudstream eligibility does not depend on the stremio id prefix`() {
        // CloudStream providers search by title, so an id an addon would reject
        // must not disqualify a CloudStream provider.
        assertTrue(available(cloudStream = listOf(extension()), videoId = "kitsu:123"))
    }

    // ---- coexistence with the existing sources -----------------------------

    @Test
    fun `cloudstream is additive and never suppresses the existing sources`() {
        // Existing behaviour must be untouched when CloudStream is absent.
        assertTrue(available(plugins = PluginsUiState(scrapers = listOf(scraper()))))
        // And an ineligible CloudStream extension must not veto a working addon.
        assertTrue(
            available(
                plugins = PluginsUiState(scrapers = listOf(scraper())),
                cloudStream = listOf(extension(enabled = false)),
            ),
        )
    }

    // ---- helpers -----------------------------------------------------------

    private fun available(
        plugins: PluginsUiState = PluginsUiState(),
        cloudStream: List<CloudStreamExtension> = emptyList(),
        type: String = "movie",
        videoId: String = "tt123",
    ): Boolean = hasCompatiblePlaybackSource(
        addons = emptyList(),
        plugins = plugins,
        type = type,
        videoId = videoId,
        cloudStreamExtensions = cloudStream,
    )

    private fun extension(
        tvTypes: List<String> = listOf("Movie", "TvSeries"),
        enabledTypes: Set<String> = setOf("Movie", "TvSeries"),
        enabled: Boolean = true,
        canExecute: Boolean = true,
    ): CloudStreamExtension {
        val plugin = CloudStreamRepositoryParser.toPlugin(
            CloudStreamPluginManifest(
                name = "ExampleProvider",
                internalName = "ExampleProvider",
                url = "https://cdn.example/example.cs3",
                version = 3,
                tvTypes = tvTypes,
                fileHash = "sha256-2f0c1b",
                apiVersion = 1,
            ),
            canExecute = canExecute,
        )
        val states = if (enabled) {
            enabledTypes.associate { type ->
                val id = CloudStreamExtensionMapping.sourceId(plugin, type)
                id to CloudStreamSourceState(id, enabled = true, installed = true)
            }
        } else {
            emptyMap()
        }
        return CloudStreamExtensionMapping.toExtension(
            plugin = plugin,
            repositoryUrl = "https://example.com/repo.json",
            states = states,
        ).copy(
            installStatus = CloudStreamInstallStatus(
                state = CloudStreamInstallState.ENABLED,
                installedVersion = 3,
            ),
        )
    }

    private fun scraper(): PluginScraper = PluginScraper(
        id = "test",
        repositoryUrl = "https://example.com/plugins.json",
        name = "Test",
        description = "",
        version = "1.0.0",
        filename = "test.js",
        supportedTypes = listOf("movie", "tv"),
        enabled = true,
        manifestEnabled = true,
        code = "",
    )
}
