package com.nuvio.app.features.cloudstream

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression guard for the real-device failure where an installed and enabled
 * extension (Phisher) never appeared in Play → Sources.
 *
 * Two independent defects caused it, and both are covered here:
 *
 *  1. **Cold start.** `CloudStreamExtensionsRepository.initialize()` kicked off
 *     an *asynchronous* repository refresh, while `StreamsRepository` reads
 *     `uiState.value.extensions` *synchronously* when the user presses Play.
 *     With no persisted extension metadata the list was still empty, so every
 *     CloudStream provider was dropped before aggregation.
 *  2. **Installed but disabled.** Sources were only enabled when a preference
 *     had been persisted, so a freshly installed extension contributed nothing
 *     and the install appeared to have done nothing.
 *
 * These tests operate on the pure decision layer, which is what the device
 * behaviour ultimately reduces to.
 */
class CloudStreamProviderVisibilityTest {

    /** A Phisher-shaped entry: multi-type, hash-published, real field names. */
    private fun phisherManifest() = CloudStreamPluginManifest(
        name = "AllMovieLandProvider",
        internalName = "AllMovieLandProvider",
        url = "https://cdn.example/allmovieland.cs3",
        version = 25,
        authors = listOf("Phisher98"),
        tvTypes = listOf("Movie", "TvSeries"),
        language = "en",
        fileSize = 96001,
        fileHash = "sha256-76967d2992879b09511d35b0e1e44ec4",
        apiVersion = 1,
    )

    private fun installedExtension(
        manifest: CloudStreamPluginManifest = phisherManifest(),
        enabledSources: Set<String>,
        canExecute: Boolean = true,
    ): CloudStreamExtension {
        val plugin = CloudStreamRepositoryParser.toPlugin(manifest, canExecute = canExecute)
        val states = enabledSources.associateWith { id ->
            CloudStreamSourceState(id, enabled = true, installed = true)
        }
        return CloudStreamExtensionMapping.toExtension(
            plugin = plugin,
            repositoryUrl = "https://phisher.example/repo.json",
            states = states,
        ).copy(
            installStatus = CloudStreamInstallStatus(
                state = CloudStreamInstallState.ENABLED,
                installedVersion = manifest.version,
            ),
        )
    }

    // ---- the device failure ------------------------------------------------

    @Test
    fun `an installed and enabled extension reaches the aggregator`() {
        val extension = installedExtension(
            enabledSources = setOf("AllMovieLandProvider::Movie"),
        )
        val targets = CloudStreamAggregatorBridge.resolveTargets(listOf(extension), "movie")

        assertEquals(1, targets.size)
        assertEquals("cloudstream:allmovielandprovider::movie", targets.single().addonId)
    }

    @Test
    fun `an empty extension list produces no targets which is the cold-start bug`() {
        // Before the fix this was the state at Play time on every cold start:
        // the async refresh had not completed, so nothing aggregated.
        assertTrue(CloudStreamAggregatorBridge.resolveTargets(emptyList(), "movie").isEmpty())
    }

    @Test
    fun `installed but with no enabled source contributes nothing`() {
        // The second defect: install succeeded, yet no source was switched on.
        val extension = installedExtension(enabledSources = emptySet())
        assertTrue(CloudStreamAggregatorBridge.resolveTargets(listOf(extension), "movie").isEmpty())
        assertFalse(extension.isActive)
    }

    // ---- manifest round-trip (what the cache persists) ---------------------

    @Test
    fun `a plugin survives the manifest round-trip used by the install cache`() {
        val original = CloudStreamRepositoryParser.toPlugin(phisherManifest(), canExecute = true)
        val restored = CloudStreamRepositoryParser.toPlugin(
            original.toManifest(),
            canExecute = true,
        )

        assertEquals(original.id, restored.id)
        assertEquals(original.displayName, restored.displayName)
        assertEquals(original.version, restored.version)
        assertEquals(original.tvTypes, restored.tvTypes)
        assertEquals(original.artifactUrl, restored.artifactUrl)
        assertEquals(original.fileHash, restored.fileHash)
        assertEquals(original.fileSize, restored.fileSize)
        assertEquals(original.iconUrl, restored.iconUrl)
        assertEquals(original.language, restored.language)
        assertEquals(original.authors, restored.authors)
        assertEquals(original.apiVersion, restored.apiVersion)
    }

    @Test
    fun `a restored plugin is reclassified for the current build not the cached one`() {
        // Compatibility must never be restored from disk: the same cache read
        // on a Play Store build has to report unsupported.
        val manifest = CloudStreamRepositoryParser
            .toPlugin(phisherManifest(), canExecute = true)
            .toManifest()

        assertTrue(CloudStreamRepositoryParser.toPlugin(manifest, canExecute = true).isExecutable)
        assertFalse(CloudStreamRepositoryParser.toPlugin(manifest, canExecute = false).isExecutable)
    }

    @Test
    fun `a cached extension restored on a build without a runtime never aggregates`() {
        val extension = installedExtension(
            enabledSources = setOf("AllMovieLandProvider::Movie"),
            canExecute = false,
        )
        assertTrue(CloudStreamAggregatorBridge.resolveTargets(listOf(extension), "movie").isEmpty())
    }

    // ---- multi-type providers ---------------------------------------------

    @Test
    fun `a multi-type provider serves both movie and series requests`() {
        val extension = installedExtension(
            enabledSources = setOf(
                "AllMovieLandProvider::Movie",
                "AllMovieLandProvider::TvSeries",
            ),
        )
        assertEquals(
            listOf("cloudstream:allmovielandprovider::movie"),
            CloudStreamAggregatorBridge.resolveTargets(listOf(extension), "movie")
                .map { it.addonId },
        )
        assertEquals(
            listOf("cloudstream:allmovielandprovider::tvseries"),
            CloudStreamAggregatorBridge.resolveTargets(listOf(extension), "series")
                .map { it.addonId },
        )
    }

    @Test
    fun `a provider declaring no tvTypes is not filtered out`() {
        // CloudStream permits an empty tvTypes list; excluding such providers
        // would silently drop working extensions.
        val extension = installedExtension(
            manifest = phisherManifest().copy(tvTypes = emptyList()),
            enabledSources = setOf("AllMovieLandProvider"),
        )
        assertEquals(1, CloudStreamAggregatorBridge.resolveTargets(listOf(extension), "movie").size)
        assertEquals(1, CloudStreamAggregatorBridge.resolveTargets(listOf(extension), "series").size)
    }

    @Test
    fun `an unknown declared type is not discarded against an unknown request`() {
        val extension = installedExtension(
            manifest = phisherManifest().copy(tvTypes = listOf("Documentary")),
            enabledSources = setOf("AllMovieLandProvider::Documentary"),
        )
        assertEquals(1, CloudStreamAggregatorBridge.resolveTargets(listOf(extension), "movie").size)
    }
}
