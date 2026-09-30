package com.nuvio.app.features.livetv

import com.nuvio.app.features.cloudstream.CloudStreamLiveCatalogItem
import com.nuvio.app.features.streams.StreamMediaMetadata
import com.nuvio.app.features.streams.StreamSubtitle

data class LiveTvChannel(
    val id: String,
    val name: String,
    val streamUrl: String,
    val logoUrl: String? = null,
    val group: String? = null,
    /** Provider/category path supplied by a CloudStream homepage section. */
    val hierarchy: List<String> = emptyList(),
    val playlistId: String? = null,
    val playlistName: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val streamType: String? = null,
    val stalkerCommand: String? = null,
    /** Provider/source attribution for generic live catalogs. */
    val providerName: String? = null,
    /** LoadResponse metadata retained for the live item/player. */
    val metadata: StreamMediaMetadata? = null,
    val subtitles: List<StreamSubtitle> = emptyList(),
    /** Non-null only for a CloudStream homepage item awaiting resolution. */
    val cloudStreamItem: CloudStreamLiveCatalogItem? = null,
    val description: String? = null,
)

data class LiveTvStalkerSettings(
    val portalUrl: String = "",
    val macAddress: String = "",
    val username: String = "",
    val password: String = "",
    val isEnabled: Boolean = true,
) {
    val isConfigured: Boolean get() = portalUrl.isNotBlank() && macAddress.isNotBlank()
}

data class LiveTvXtreamSettings(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val isEnabled: Boolean = true,
) {
    val isConfigured: Boolean get() = serverUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()
}

enum class LiveTvPlaylistType {
    Url,
    LocalFile,
}

data class LiveTvPlaylist(
    val id: String,
    val name: String,
    val type: LiveTvPlaylistType,
    val source: String,
    val isEnabled: Boolean = true,
)

internal fun CloudStreamLiveCatalogItem.toLiveTvChannel(): LiveTvChannel {
    // Keep the provider as the root of the visible path. Live TV merges M3U,
    // Xtream, Stalker and CloudStream channels into one list; dropping that
    // root made equal section names from different extensions collide and made
    // a multi-section provider look like one flat "Live Events" category.
    val visibleHierarchy = (sectionPath.ifEmpty {
        listOfNotNull(providerName.takeIf(String::isNotBlank), category?.takeIf(String::isNotBlank))
    })
        .map(String::trim)
        .filter(String::isNotBlank)
        .ifEmpty { listOf(providerName.ifBlank { "CloudStream" }) }
    val channelIdentity = listOf(addonId, providerName, visibleHierarchy.joinToString("\u001e"), url)
        .joinToString("\u001f")
    val channelId = "cloudstream:${channelIdentity.hashCode().toUInt().toString(16)}"
    val normalizedMetadata = metadata?.toStreamMediaMetadata()
    return LiveTvChannel(
        id = channelId,
        name = title,
        // Homepage URLs are detail URLs, not claimed playable URLs. The
        // CloudStream item stays attached until prepareForPlayback loads the
        // actual links.
        streamUrl = url,
        logoUrl = normalizedMetadata?.logo ?: normalizedMetadata?.poster ?: poster,
        group = visibleHierarchy.joinToString(" / "),
        hierarchy = visibleHierarchy,
        playlistId = addonId,
        playlistName = providerName,
        providerName = providerName,
        metadata = normalizedMetadata,
        description = normalizedMetadata?.description,
        cloudStreamItem = this,
    )
}

data class LiveTvUiState(
    val playlistUrl: String = "",
    val playlists: List<LiveTvPlaylist> = emptyList(),
    val stalkerSettings: LiveTvStalkerSettings = LiveTvStalkerSettings(),
    val xtreamSettings: LiveTvXtreamSettings = LiveTvXtreamSettings(),
    val hasCloudStreamLiveSources: Boolean = false,
    val channels: List<LiveTvChannel> = emptyList(),
    val favoriteChannelIds: Set<String> = emptySet(),
    val lastWatchedChannelId: String? = null,
    val isNavigationEnabled: Boolean = true,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
) {
    val hasPlaylist: Boolean
        get() = playlists.isNotEmpty() || playlistUrl.isNotBlank() ||
            stalkerSettings.isConfigured || xtreamSettings.isConfigured ||
            hasCloudStreamLiveSources

    val showInNavigation: Boolean
        get() = hasPlaylist && isNavigationEnabled
}
