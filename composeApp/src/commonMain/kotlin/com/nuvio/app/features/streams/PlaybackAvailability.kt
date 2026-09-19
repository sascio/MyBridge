package com.nuvio.app.features.streams

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.cloudstream.CloudStreamAggregatorBridge
import com.nuvio.app.features.cloudstream.CloudStreamExtension
import com.nuvio.app.features.cloudstream.CloudStreamExtensionsRepository
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.plugins.PluginRepository
import com.nuvio.app.features.plugins.PluginsUiState

internal fun AddonManifest.supportsStream(type: String, videoId: String): Boolean =
    resources.any { resource ->
        resource.name == "stream" &&
            resource.types.contains(type) &&
            (resource.idPrefixes.isEmpty() || resource.idPrefixes.any { videoId.startsWith(it) })
    }

internal fun hasCompatiblePlaybackSource(
    addons: List<ManagedAddon>,
    plugins: PluginsUiState,
    type: String,
    videoId: String,
    cloudStreamExtensions: List<CloudStreamExtension> = emptyList(),
): Boolean = addons.any { it.enabled && it.manifest?.supportsStream(type, videoId) == true } ||
    (plugins.pluginsEnabled && plugins.scrapers.any { it.enabled && it.supportsType(type) }) ||
    // CloudStream providers are full stream sources, so an installed, enabled
    // and genuinely executable extension has to satisfy this gate too.
    // `resolveTargets` is reused deliberately: the check that decides whether
    // Play may proceed must be the exact same check the aggregator later uses
    // to build its targets, otherwise the two can disagree and Play fails for
    // a provider that would in fact have produced sources.
    CloudStreamAggregatorBridge.resolveTargets(cloudStreamExtensions, type).isNotEmpty()

internal class PlaybackAvailability(
    private val addons: List<ManagedAddon>,
    private val plugins: PluginsUiState,
    private val cloudStreamExtensions: List<CloudStreamExtension> = emptyList(),
) {
    fun canStream(type: String, videoId: String): Boolean =
        hasCompatiblePlaybackSource(addons, plugins, type, videoId, cloudStreamExtensions) ||
            MetaDetailsRepository.findEmbeddedStreams(videoId).isNotEmpty()

    /**
     * Explains, without leaking user data, why [canStream] refused a title.
     *
     * Each source family is reported separately so "no CloudStream provider was
     * eligible" can never be confused with "no addons installed".
     */
    fun describeUnavailability(type: String, videoId: String): String {
        val addonCount = addons.count { it.enabled && it.manifest?.supportsStream(type, videoId) == true }
        val scraperCount = if (plugins.pluginsEnabled) {
            plugins.scrapers.count { it.enabled && it.supportsType(type) }
        } else {
            0
        }
        val cloudStreamTargets = CloudStreamAggregatorBridge
            .resolveTargets(cloudStreamExtensions, type).size
        val installedCloudStream = cloudStreamExtensions.count { it.installStatus.isInstalled }
        val executableCloudStream = cloudStreamExtensions.count { it.plugin.isExecutable }
        val enabledCloudStream = cloudStreamExtensions.count { it.isActive }

        return "Playback unavailable for type=$type: " +
            "compatible addons=$addonCount, enabled plugin scrapers=$scraperCount, " +
            "cloudstream targets=$cloudStreamTargets " +
            "(known=${cloudStreamExtensions.size}, installed=$installedCloudStream, " +
            "executable=$executableCloudStream, enabled=$enabledCloudStream), " +
            "embedded streams=${MetaDetailsRepository.findEmbeddedStreams(videoId).size}"
    }

    fun canPlay(
        type: String,
        videoId: String,
        parentMetaId: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
    ): Boolean = canStream(type, videoId) || DownloadsRepository.findPlayableDownload(
        parentMetaId = parentMetaId,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        videoId = videoId,
    ) != null

    companion object {
        fun current(): PlaybackAvailability {
            // Pressing Play can be the first thing that ever touches CloudStream
            // in a cold process, so make sure persisted extensions are restored
            // before the gate reads them. `initialize()` hydrates installed
            // extensions synchronously and is idempotent.
            CloudStreamExtensionsRepository.initialize()
            return PlaybackAvailability(
                addons = AddonRepository.uiState.value.addons,
                plugins = if (AppFeaturePolicy.pluginsEnabled) {
                    PluginRepository.uiState.value
                } else {
                    PluginsUiState(pluginsEnabled = false)
                },
                cloudStreamExtensions = CloudStreamExtensionsRepository.uiState.value.extensions,
            )
        }
    }
}

@Composable
internal fun rememberPlaybackAvailability(): PlaybackAvailability {
    val addons by remember {
        AddonRepository.initialize()
        AddonRepository.uiState
    }.collectAsStateWithLifecycle()
    val plugins = if (AppFeaturePolicy.pluginsEnabled) {
        val state by remember {
            PluginRepository.initialize()
            PluginRepository.uiState
        }.collectAsStateWithLifecycle()
        state
    } else {
        PluginsUiState(pluginsEnabled = false)
    }
    val downloads by remember {
        DownloadsRepository.ensureLoaded()
        DownloadsRepository.uiState
    }.collectAsStateWithLifecycle()
    // Observed, not snapshotted: installing or enabling an extension has to
    // light up the Play button without reopening the screen or restarting.
    val cloudStream by remember {
        CloudStreamExtensionsRepository.initialize()
        CloudStreamExtensionsRepository.uiState
    }.collectAsStateWithLifecycle()
    return remember(addons, plugins, downloads, cloudStream) {
        PlaybackAvailability(addons.addons, plugins, cloudStream.extensions)
    }
}
