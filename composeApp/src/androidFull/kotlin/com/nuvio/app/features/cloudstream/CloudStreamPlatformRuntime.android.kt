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
import com.lagradost.cloudstream3.utils.loadExtractor
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
import kotlinx.coroutines.TimeoutCancellationException
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
        // The installer shares this distribution's execution boundary, so it is
        // initialised from the same call rather than from shared Android code
        // that also runs in the Play Store build.
        appContext?.let(CloudStreamPackageInstaller::initialize)
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

        /**
         * Runs one provider stage under a bound, converting a timeout into an
         * ordinary exception.
         *
         * `withTimeout` throws [TimeoutCancellationException], which *is* a
         * [kotlinx.coroutines.CancellationException]. Left as-is it propagates
         * as cancellation: the coroutine that was supposed to publish this
         * provider's completion dies quietly, the aggregator never receives it,
         * and the source picker waits on a result that can never arrive.
         * Converting it here keeps a hung provider a reportable failure while
         * leaving genuine cancellation (user navigates away, new request)
         * untouched, because only the timeout subtype is caught.
         */
        private suspend fun <T> stage(
            name: String,
            api: MainAPI,
            timeoutMs: Long,
            block: suspend () -> T,
        ): T = try {
            withTimeout(timeoutMs) { block() }
        } catch (timeout: TimeoutCancellationException) {
            throw CloudStreamStageTimeoutException(
                "CloudStream provider '${api.name}' timed out during $name after ${timeoutMs}ms",
                timeout,
            )
        }

        override suspend fun search(
            plugin: CloudStreamPlugin,
            query: String,
        ): List<CloudStreamSearchResult> = withContext(Dispatchers.IO) {
            val apis = providersFor(plugin)
            apis.flatMap { api ->
                runCatching {
                    stage("search", api, SEARCH_TIMEOUT_MS) { api.search(query, 1)?.items.orEmpty() }
                }.onFailure { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    log.w(error) { "CloudStream search failed api=${api.name} query=$query" }
                }.getOrDefault(emptyList()).map { response ->
                    CloudStreamSearchResult(
                        name = response.name,
                        url = response.url,
                        posterUrl = response.posterUrl,
                        year = response.searchYear(),
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
            val response = stage("loadEpisodes", api, LOAD_TIMEOUT_MS) { api.load(url) }
                ?: return@withContext emptyList()
            // Episode lists live in a different field per LoadResponse shape;
            // CloudStreamLoadResponseTargets knows all of them.
            CloudStreamLoadResponseTargets.episodes(response).map { ref ->
                CloudStreamEpisode(
                    name = ref.name,
                    url = ref.data,
                    season = ref.season,
                    episode = ref.episode,
                )
            }
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

                        val detail = stage("load", api, LOAD_TIMEOUT_MS) { api.load(match.url) }
                            ?: error("Provider returned no details for '${match.name}'")

                        val shape = CloudStreamLoadResponseTargets.describe(detail)
                        val target = if (wantsSeries) {
                            // CloudStream has five LoadResponse shapes and the
                            // episode list lives in a different place in each.
                            // See CloudStreamLoadResponseTargets.
                            val offered = CloudStreamLoadResponseTargets.episodes(detail)
                            if (offered.isEmpty()) {
                                error(
                                    "Provider returned $shape with no episode list, " +
                                        "so S${request.season}E${request.episode} cannot be resolved.",
                                )
                            }
                            val selected = CloudStreamEpisodeSelector.select(
                                episodes = offered,
                                season = request.season,
                                episode = request.episode,
                            ) ?: error(
                                "Provider has no S${request.season}E${request.episode}: " +
                                    "$shape offers ${CloudStreamEpisodeSelector.describe(offered)}.",
                            )
                            log.d {
                                "CloudStream episode matched api=${api.name} shape=$shape " +
                                    "want=S${request.season}E${request.episode} " +
                                    "got=S${selected.season}E${selected.episode} " +
                                    "variant=${selected.variant ?: "-"}"
                            }
                            selected.data
                        } else {
                            CloudStreamLoadResponseTargets.movieTarget(detail) ?: match.url
                        }
                        collectLinks(api, target)
                    }.onFailure { error ->
                        // A genuine cancellation (user left the screen, new
                        // request) must stay a cancellation and never be
                        // recorded as a provider failure.
                        if (error is kotlinx.coroutines.CancellationException) throw error
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
            val results = stage("search", api, SEARCH_TIMEOUT_MS) { api.search(title, 1)?.items.orEmpty() }
                .map { response ->
                    CloudStreamSearchResult(
                        name = response.name,
                        url = response.url,
                        posterUrl = response.posterUrl,
                        year = response.searchYear(),
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

        /**
         * Invokes `loadLinks` and converts CloudStream results to StreamBridge
         * models.
         *
         * Providers behave in two ways here, and both must work:
         *
         *  1. the provider resolves media itself and emits [ExtractorLink]s
         *     directly through the callback, or
         *  2. the provider only knows an *embed/host page* URL and relies on
         *     CloudStream's shared extractor registry to turn it into media.
         *
         * Case 2 is why `loadLinks` can legitimately return `true` having
         * emitted nothing: the provider expects the host to run the extractor
         * chain. StreamBridge therefore falls back to CloudStream's generic
         * `loadExtractor`, which dispatches to whichever of the runtime's 328
         * registered extractors (plus any the plugin itself registered) matches
         * the URL. This is registry-driven, so no per-provider or
         * per-extractor special-casing is involved.
         */
        private suspend fun collectLinks(api: MainAPI, data: String): CloudStreamLinkResult {
            val links = Collections.synchronizedList(mutableListOf<ExtractorLink>())
            val subtitles = Collections.synchronizedList(mutableListOf<SubtitleFile>())

            stage("loadLinks", api, LINK_TIMEOUT_MS) {
                api.loadLinks(data, false, { subtitles += it }, { links += it })
            }

            if (synchronized(links) { links.isEmpty() }) {
                runExtractorFallback(api, data, links, subtitles)
            }

            val linkSnapshot = synchronized(links) { links.toList() }
            val subtitleSnapshot = synchronized(subtitles) { subtitles.toList() }
            log.d {
                "CloudStream links api=${api.name} links=${linkSnapshot.size} " +
                    "subtitles=${subtitleSnapshot.size} " +
                    "types=${linkSnapshot.map { it.type.name }.distinct()}"
            }

            return CloudStreamLinkResult(
                links = linkSnapshot
                    .distinctBy { listOf(it.url, it.quality, it.type.name) }
                    .mapNotNull { link ->
                        // A provider can emit a placeholder with no URL (e.g. an
                        // ExtractorLinkPlayList whose entries failed to resolve).
                        // Dropping it is correct; inventing a URL is not.
                        val url = link.url.trim().takeIf { it.isNotEmpty() }
                            ?: return@mapNotNull null
                        CloudStreamLink(
                            name = link.name.ifBlank { link.source },
                            url = url,
                            referer = link.referer.takeIf(String::isNotBlank),
                            quality = link.quality.takeIf { it > 0 },
                            isM3u8 = link.type == ExtractorLinkType.M3U8,
                            isDash = link.type == ExtractorLinkType.DASH,
                            // TORRENT/MAGNET are real CloudStream link types.
                            // Labelling them "http" would hand a magnet URI to
                            // the HTTP player; StreamBridge already has torrent
                            // handling keyed off this flag.
                            isTorrent = link.type == ExtractorLinkType.TORRENT ||
                                link.type == ExtractorLinkType.MAGNET,
                            // Referer stays a separate field: CloudStream keeps it
                            // out of `headers`, and the adapter merges the two
                            // (case-insensitively) when building request headers.
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

        /**
         * Resolves an embed/host URL through CloudStream's extractor registry.
         *
         * Only attempted when the provider emitted no links of its own and the
         * payload actually looks like a URL — a provider's `data` string is
         * often an opaque token, and handing that to the extractor chain would
         * be pointless work rather than a resolution attempt.
         *
         * Failure is logged and swallowed *here only*: an extractor that cannot
         * handle a URL is a normal outcome, and the caller correctly reports
         * "no streams" rather than a provider error. No stream is ever invented.
         */
        private suspend fun runExtractorFallback(
            api: MainAPI,
            data: String,
            links: MutableList<ExtractorLink>,
            subtitles: MutableList<SubtitleFile>,
        ) {
            val target = data.trim()
            if (!target.startsWith("http://", true) && !target.startsWith("https://", true)) return

            runCatching {
                stage("loadExtractor", api, LINK_TIMEOUT_MS) {
                    // Referer defaults to the provider's own main URL, which is
                    // what most hosts check before serving media.
                    loadExtractor(
                        target,
                        api.mainUrl,
                        { subtitle -> subtitles += subtitle },
                        { link -> links += link },
                    )
                }
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                log.w(error) { "CloudStream extractor fallback failed api=${api.name}" }
            }

            if (links.isNotEmpty()) {
                log.i { "Extractor fallback resolved ${links.size} link(s) for ${api.name}" }
            }
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
 * A provider stage exceeded its time budget.
 *
 * Deliberately **not** a [kotlinx.coroutines.CancellationException]: a hung
 * provider must be reported as a failure for that provider, while genuine
 * cancellation of the surrounding request must keep cancelling.
 */
internal class CloudStreamStageTimeoutException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

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

/**
 * Reads the release year from a CloudStream search result.
 *
 * `year` is declared on the concrete subtypes (Movie/TvSeries/Anime search
 * responses) rather than on the `SearchResponse` base type, so it is read
 * through those subtypes and is simply absent for other result kinds.
 */
private fun com.lagradost.cloudstream3.SearchResponse.searchYear(): Int? = when (this) {
    is com.lagradost.cloudstream3.MovieSearchResponse -> year
    is com.lagradost.cloudstream3.TvSeriesSearchResponse -> year
    is com.lagradost.cloudstream3.AnimeSearchResponse -> year
    else -> null
}

/** Normalises a title for tolerant comparison between metadata and provider results. */
private fun String.normalisedTitle(): String =
    lowercase().filter { it.isLetterOrDigit() || it == ' ' }.trim().replace(Regex("\\s+"), " ")
