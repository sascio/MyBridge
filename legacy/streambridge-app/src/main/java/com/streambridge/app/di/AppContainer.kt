package com.streambridge.app.di

import android.content.Context
import com.streambridge.app.addon.AddonApi
import com.streambridge.app.addon.ExtensionManager
import com.streambridge.app.addon.HttpAddonApi
import com.streambridge.app.addon.SbHttpClient
import com.streambridge.app.addon.StreamResolver
import com.streambridge.app.addon.plugin.NuvioPluginManager
import com.streambridge.app.addon.SubtitleResolver
import com.streambridge.app.core.NetworkMonitor
import com.streambridge.app.data.db.StreamBridgeDatabase
import com.streambridge.app.data.discovery.DiscoveryRepository
import com.streambridge.app.data.integrations.MdbListClient
import com.streambridge.app.data.integrations.OpenSubtitlesClient
import com.streambridge.app.data.integrations.TmdbClient
import com.streambridge.app.data.library.LibraryRepository
import com.streambridge.app.data.settings.SettingsRepository
import com.streambridge.app.server.AggregatingBridgeProvider
import com.streambridge.app.server.ServerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json

/**
 * Simple manual dependency container. Created once per process.
 */
class AppContainer(context: Context) {

    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    val httpClient: SbHttpClient = SbHttpClient()

    val addonApi: AddonApi = HttpAddonApi(httpClient, json)

    val database: StreamBridgeDatabase = StreamBridgeDatabase.build(context)

    val settingsRepository: SettingsRepository = SettingsRepository(context)

    val extensionManager: ExtensionManager =
        ExtensionManager(database.extensionDao(), addonApi, json, applicationScope)

    // Nuvio-compatible plugin system (Settings > Content & Discovery > Plugin).
    val pluginManager: NuvioPluginManager by lazy {
        NuvioPluginManager(context.applicationContext, applicationScope, httpClient.client)
    }

    val openSubtitlesClient: OpenSubtitlesClient =
        OpenSubtitlesClient(httpClient, json)

    val streamResolver: StreamResolver = StreamResolver(api = addonApi, pluginManager = pluginManager)
    val subtitleResolver: SubtitleResolver = SubtitleResolver(
        api = addonApi,
        openSubtitles = openSubtitlesClient,
        settingsRepository = settingsRepository
    )

    val libraryRepository: LibraryRepository =
        LibraryRepository(database.libraryDao(), database.progressDao())

    val tmdbClient: TmdbClient = TmdbClient(httpClient, json)

    val mdblistClient: MdbListClient = MdbListClient(httpClient, json)

    val discoveryRepository: DiscoveryRepository =
        DiscoveryRepository(addonApi, extensionManager, tmdbClient, mdblistClient, applicationScope)

    val networkMonitor: NetworkMonitor = NetworkMonitor(context)

    val bridgeProvider: AggregatingBridgeProvider =
        AggregatingBridgeProvider(extensionManager, addonApi, json, "1.0.0")

    val serverManager: ServerManager =
        ServerManager(settingsRepository, networkMonitor, bridgeProvider, applicationScope)
}
