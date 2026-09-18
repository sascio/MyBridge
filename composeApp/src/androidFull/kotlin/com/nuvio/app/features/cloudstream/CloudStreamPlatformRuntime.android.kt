package com.nuvio.app.features.cloudstream

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Resources
import co.touchlab.kermit.Logger
import com.lagradost.api.setContext
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.PluginData
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.extractorApis
import dalvik.system.PathClassLoader
import java.io.File
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.Collections
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json

/**
 * Android **full (sideload)** CloudStream execution backend.
 *
 * ## Security boundary
 *
 * This is the only place in StreamBridge that executes downloaded third-party
 * code, and it is compiled **only** into the sideload distribution. The
 * Play Store and iOS builds use stubs that return `null` and are built without
 * the CloudStream runtime dependency entirely.
 *
 * Execution is not blind. A package must clear every gate before its code is
 * reachable:
 *
 *  1. downloaded to app-private storage (never shared/world-readable),
 *  2. SHA-256 compared against the repository's published `fileHash`,
 *  3. archive layout validated (`manifest.json` + `classes.dex`, no Zip Slip),
 *  4. `pluginClassName` present and well formed,
 *  5. file marked read-only so it cannot change between check and load,
 *  6. the loaded class must extend CloudStream's `BasePlugin`,
 *  7. the plugin must register at least one provider, else it is rolled back.
 *
 * All decisions in 2-4 live in [CloudStreamPackageValidation], which is pure
 * and unit-tested; this class performs the I/O and the loading.
 *
 * This is deliberately **not** a general-purpose DEX facility: the only entry
 * point takes a [CloudStreamPlugin] discovered from a user-added CloudStream
 * repository. It accepts no caller-supplied file paths or class names.
 */
internal actual object CloudStreamPlatformRuntime {

    actual val supportsExecution: Boolean = true

    private val log = Logger.withTag("CloudStreamRuntime")
    private val json = Json { ignoreUnknownKeys = true }
    private val loadMutex = Mutex()
    private val loaded = linkedMapOf<String, LoadedPlugin>()

    private var appContext: Context? = null

    actual fun initialize(context: Any?) {
        appContext = (context as? Context)?.applicationContext
    }

    actual fun executor(): CloudStreamPluginExecutor? =
        appContext?.let { AndroidCloudStreamExecutor(it) }

    /** Timeout for one provider stage, so a hung provider cannot stall aggregation. */
    private const val SEARCH_TIMEOUT_MS = 20_000L
    private const val LOAD_TIMEOUT_MS = 25_000L
    private const val LINK_TIMEOUT_MS = 40_000L

    private data class LoadedPlugin(
        val path: String,
        val instance: BasePlugin,
        val providers: List<MainAPI>,
        val extractors: List<com.lagradost.cloudstream3.utils.ExtractorApi>,
    )

    /**
     * Loads a plugin once and caches it.
     *
     * Serialised through a mutex because CloudStream registers providers into
     * process-global registries (`APIHolder`, `extractorApis`); concurrent
     * loads would race on those and mis-attribute providers between plugins.
     */
    private suspend fun loadedPlugin(
        plugin: CloudStreamPlugin,
        packageFile: File,
    ): LoadedPlugin = loadMutex.withLock {
        loaded[plugin.id]?.let { return@withLock it }
        val created = loadPluginLocked(plugin, packageFile)
        loaded[plugin.id] = created
        created
    }

    private fun loadPluginLocked(plugin: CloudStreamPlugin, file: File): LoadedPlugin {
        val context = requireNotNull(appContext) { "CloudStream runtime is not initialized" }
        require(file.isFile) { "CloudStream package is missing" }

        // --- gate 2: integrity -------------------------------------------
        val digest = sha256Hex(file)
        val entries = ZipFile(file).use { archive ->
            archive.entries().toList().map { it.name }
        }
        val manifest = readArchiveManifest(file)

        // --- gates 2-4, decided by the pure validator ---------------------
        val validation = CloudStreamPackageValidation.validate(
            entryNames = entries,
            manifestPluginClassName = manifest?.pluginClassName,
            expectedHash = plugin.fileHash,
            actualSha256Hex = digest,
        )
        val pluginClassName = when (validation) {
            is CloudStreamPackageValidation.Result.Valid -> validation.pluginClassName
            is CloudStreamPackageValidation.Result.Invalid ->
                error("Refusing to load '${plugin.id}': ${validation.detail}")
        }
        if (!CloudStreamPackageValidation.hasVerifiableHash(plugin.fileHash)) {
            log.w {
                "CloudStream package '${plugin.id}' has no published fileHash; " +
                    "integrity could not be pinned by the repository."
            }
        }

        // --- gate 5: immutability ----------------------------------------
        file.setReadOnly()

        // --- gate 6: expected plugin type --------------------------------
        val identityContext = CloudStreamIdentityContext(context)
        prepareHostContext(identityContext)

        val loader = PathClassLoader(file.absolutePath, context.classLoader)
        val pluginClass = loader.loadClass(pluginClassName)
        require(BasePlugin::class.java.isAssignableFrom(pluginClass)) {
            "CloudStream entry point '$pluginClassName' does not extend BasePlugin"
        }

        @Suppress("UNCHECKED_CAST")
        val instance = (pluginClass as Class<out BasePlugin>).getDeclaredConstructor().newInstance()
        instance.filename = file.absolutePath
        PluginManager.currentlyLoading = plugin.id
        if (manifest?.requiresResources == true) {
            runCatching { instance.attachResources(identityContext, file) }
                .onFailure { log.w(it) { "Resource attach failed for '${plugin.id}'" } }
        }

        // --- gate 7: must register providers, else roll back --------------
        val providersBefore = APIHolder.allProviders.toSet()
        val extractorsBefore = extractorApis.toSet()
        try {
            if (instance is Plugin) instance.load(identityContext) else instance.load()

            val providers = APIHolder.allProviders
                .filter { it !in providersBefore || it.sourcePlugin == file.absolutePath }
                .distinct()
            require(providers.isNotEmpty()) {
                "Plugin loaded but registered no providers; it may reject this host runtime."
            }
            providers.forEach(MainAPI::init)

            val registered = extractorApis
                .filter { it !in extractorsBefore || it.sourcePlugin == file.absolutePath }
                .distinct()

            PluginManager.register(
                PluginData(
                    internalName = plugin.id,
                    url = plugin.artifactUrl,
                    isOnline = true,
                    filePath = file.absolutePath,
                    version = manifest?.version ?: plugin.version ?: 0,
                ),
                instance,
            )
            log.i {
                "Loaded CloudStream plugin '${plugin.id}': " +
                    "${providers.size} provider(s), ${registered.size} extractor(s)"
            }
            return LoadedPlugin(file.absolutePath, instance, providers, registered)
        } catch (error: Throwable) {
            // Never leave half-registered providers behind for another plugin
            // to pick up: undo everything this load added.
            APIHolder.allProviders.removeAll {
                it !in providersBefore && it.sourcePlugin == file.absolutePath
            }
            extractorApis.removeAll { it !in extractorsBefore && it.sourcePlugin == file.absolutePath }
            log.e(error) { "Failed to load CloudStream plugin '${plugin.id}'" }
            throw error
        } finally {
            PluginManager.currentlyLoading = null
        }
    }

    private fun prepareHostContext(context: Context) {
        CloudStreamApp.context = context
        setContext(WeakReference(context))
    }

    private fun readArchiveManifest(file: File): CloudStreamPluginArchiveManifest? = runCatching {
        ZipFile(file).use { archive ->
            val entry = archive.getEntry(CloudStreamPackageValidation.MANIFEST_ENTRY)
                ?: return@use null
            archive.getInputStream(entry).bufferedReader().use { reader ->
                json.decodeFromString<CloudStreamPluginArchiveManifest>(reader.readText())
            }
        }
    }.getOrNull()

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Suppress("DEPRECATION")
    private fun BasePlugin.attachResources(context: Context, file: File) {
        val plugin = this as? Plugin ?: return
        val assets = AssetManager::class.java.getDeclaredConstructor().newInstance()
        AssetManager::class.java.getMethod("addAssetPath", String::class.java)
            .invoke(assets, file.absolutePath)
        plugin.resources = Resources(
            assets,
            context.resources.displayMetrics,
            context.resources.configuration,
        )
    }

    /**
     * Real execution backend.
     *
     * Each public method isolates its own failures by throwing a descriptive
     * error; `CloudStreamExtensionsRepository` converts that into a failed
     * `Result` for exactly one provider, leaving every other provider — and
     * Stremio addons and plugin scrapers — unaffected.
     */
    private class AndroidCloudStreamExecutor(private val context: Context) :
        CloudStreamPluginExecutor {

        override suspend fun search(
            plugin: CloudStreamPlugin,
            query: String,
        ): List<CloudStreamSearchResult> = withContext(Dispatchers.IO) {
            val apis = providersFor(plugin)
            apis.flatMap { api ->
                runCatching {
                    withTimeout(SEARCH_TIMEOUT_MS) { api.search(query, 1)?.items.orEmpty() }
                }.onFailure { error ->
                    log.w(error) { "CloudStream search failed api=${api.name} query=$query" }
                }.getOrDefault(emptyList()).map { response ->
                    CloudStreamSearchResult(
                        name = response.name,
                        url = response.url,
                        posterUrl = response.posterUrl,
                        year = response.year,
                        type = response.type?.name,
                    )
                }
            }.distinctBy { it.url }
        }

        override suspend fun loadEpisodes(
            plugin: CloudStreamPlugin,
            url: String,
        ): List<CloudStreamEpisode> = withContext(Dispatchers.IO) {
            val api = providersFor(plugin).firstOrNull() ?: return@withContext emptyList()
            val response = withTimeout(LOAD_TIMEOUT_MS) { api.load(url) }
            (response as? com.lagradost.cloudstream3.TvSeriesLoadResponse)?.episodes
                ?.map { episode ->
                    CloudStreamEpisode(
                        name = episode.name,
                        url = episode.data,
                        season = episode.season,
                        episode = episode.episode,
                    )
                }
                .orEmpty()
        }

        override suspend fun loadLinks(
            plugin: CloudStreamPlugin,
            query: CloudStreamStreamQuery,
        ): CloudStreamLinkResult = withContext(Dispatchers.IO) {
            val api = providersFor(plugin).firstOrNull()
                ?: error("Extension exposes no CloudStream provider")
            collectLinks(api, query.url)
        }

        /**
         * Full movie/series lifecycle for one provider.
         *
         * search → best match → load details → (episode selection) → loadLinks.
         * Providers differ in how they navigate this, so each stage is guarded
         * and reports precisely which stage failed.
         */
        override suspend fun resolve(request: CloudStreamResolveRequest): CloudStreamLinkResult =
            withContext(Dispatchers.IO) {
                val apis = providersFor(request.plugin)
                if (apis.isEmpty()) error("Extension exposes no CloudStream provider")

                val title = request.title?.takeIf { it.isNotBlank() }
                    ?: error("No title available to search this CloudStream provider")

                val wantsSeries = request.season != null && request.episode != null
                var lastError: Throwable? = null

                for (api in apis.filter { it.supports(wantsSeries) }) {
                    val attempt = runCatching {
                        val match = bestMatch(api, title, request.year, wantsSeries)
                            ?: return@runCatching null

                        val detail = withTimeout(LOAD_TIMEOUT_MS) { api.load(match.url) }
                            ?: error("Provider returned no details for '${match.name}'")

                        val target = if (wantsSeries) {
                            val episodes =
                                (detail as? com.lagradost.cloudstream3.TvSeriesLoadResponse)
                                    ?.episodes
                                    .orEmpty()
                            val episode = episodes.firstOrNull {
                                it.season == request.season && it.episode == request.episode
                            } ?: error(
                                "Provider has no S${request.season}E${request.episode} for '$title'",
                            )
                            episode.data
                        } else {
                            (detail as? com.lagradost.cloudstream3.MovieLoadResponse)?.dataUrl
                                ?: match.url
                        }
                        collectLinks(api, target)
                    }.onFailure { error ->
                        lastError = error
                        log.w(error) { "CloudStream resolve failed api=${api.name} title=$title" }
                    }.getOrNull()

                    if (attempt != null && attempt.links.isNotEmpty()) return@withContext attempt
                }

                // Nothing resolved: surface the real reason rather than an
                // empty success that would look like "provider has no sources".
                lastError?.let { throw it }
                CloudStreamLinkResult()
            }

        /** Picks the closest search hit, preferring an exact title (and year) match. */
        private suspend fun bestMatch(
            api: MainAPI,
            title: String,
            year: Int?,
            wantsSeries: Boolean,
        ): CloudStreamSearchResult? {
            val results = withTimeout(SEARCH_TIMEOUT_MS) { api.search(title, 1)?.items.orEmpty() }
                .map { response ->
                    CloudStreamSearchResult(
                        name = response.name,
                        url = response.url,
                        posterUrl = response.posterUrl,
                        year = response.year,
                        type = response.type?.name,
                    )
                }
            if (results.isEmpty()) return null

            val normalisedTarget = title.normalisedTitle()
            val typeFiltered = results.filter { result ->
                val type = result.type?.lowercase() ?: return@filter true
                if (wantsSeries) type != "movie" else type != "tvseries"
            }.ifEmpty { results }

            return typeFiltered.firstOrNull {
                it.name.normalisedTitle() == normalisedTarget && (year == null || it.year == year)
            }
                ?: typeFiltered.firstOrNull { it.name.normalisedTitle() == normalisedTarget }
                ?: typeFiltered.first()
        }

        /** Invokes `loadLinks` and converts CloudStream results to StreamBridge models. */
        private suspend fun collectLinks(api: MainAPI, data: String): CloudStreamLinkResult {
            val links = Collections.synchronizedList(mutableListOf<ExtractorLink>())
            val subtitles = Collections.synchronizedList(mutableListOf<SubtitleFile>())

            withTimeout(LINK_TIMEOUT_MS) {
                api.loadLinks(data, false, { subtitles += it }, { links += it })
            }

            val linkSnapshot = synchronized(links) { links.toList() }
            val subtitleSnapshot = synchronized(subtitles) { subtitles.toList() }

            return CloudStreamLinkResult(
                links = linkSnapshot
                    .distinctBy { listOf(it.url, it.quality, it.type.name) }
                    .map { link ->
                        CloudStreamLink(
                            name = link.name.ifBlank { link.source },
                            url = link.url,
                            referer = link.referer.takeIf(String::isNotBlank),
                            quality = link.quality.takeIf { it > 0 },
                            isM3u8 = link.type == ExtractorLinkType.M3U8,
                            isDash = link.type == ExtractorLinkType.DASH,
                            headers = link.headers,
                            source = link.source,
                        )
                    },
                subtitles = subtitleSnapshot.mapNotNull { subtitle ->
                    val url = subtitle.url.trim().takeIf { it.isNotEmpty() }
                        ?: return@mapNotNull null
                    CloudStreamSubtitleFile(
                        url = url,
                        language = subtitle.lang,
                        name = subtitle.lang,
                        headers = subtitle.headers.orEmpty(),
                    )
                },
            )
        }

        /** Loads the plugin on demand and returns the providers it registered. */
        private suspend fun providersFor(plugin: CloudStreamPlugin): List<MainAPI> {
            val file = CloudStreamPackageStorage.packageFile(context, plugin)
                ?: error("CloudStream package is not installed")
            return loadedPlugin(plugin, file).providers
        }

        /** Whether a provider declares support for the requested media shape. */
        private fun MainAPI.supports(wantsSeries: Boolean): Boolean {
            if (supportedTypes.isEmpty()) return true
            return supportedTypes.any { type ->
                if (wantsSeries) type != TvType.Movie else type != TvType.TvSeries
            }
        }
    }
}

/**
 * Presents CloudStream's own package name to loaded plugins.
 *
 * Some providers key their stored preferences or resource lookups off the
 * host package name; without this they behave inconsistently or refuse to run.
 */
private class CloudStreamIdentityContext(base: Context) : ContextWrapper(base) {
    override fun getPackageName(): String = "com.lagradost.cloudstream3"
    override fun getApplicationContext(): Context = this
}

/** Normalises a title for tolerant comparison between metadata and provider results. */
private fun String.normalisedTitle(): String =
    lowercase().filter { it.isLetterOrDigit() || it == ' ' }.trim().replace(Regex("\\s+"), " ")
