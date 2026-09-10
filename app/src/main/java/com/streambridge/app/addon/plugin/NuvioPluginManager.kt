package com.streambridge.app.addon.plugin

import android.content.Context
import com.streambridge.app.addon.SourceResult
import com.streambridge.app.addon.SourceStatus
import com.streambridge.app.addon.StreamEnrichment
import com.streambridge.app.addon.StreamHeaders
import com.streambridge.app.addon.StreamSource
import com.streambridge.app.addon.UrlValidator
import com.streambridge.app.addon.model.StreamClassification
import com.streambridge.app.addon.model.StreamOption
import com.streambridge.app.addon.plugin.compat.ProviderAnalyzer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap

/** A plugin provider that failed during the last picker resolution. */
data class ProviderFailure(
    val providerName: String,
    val repositoryName: String,
    val reason: String
)

/**
 * Manages Nuvio-compatible plugin repositories: installing, refreshing,
 * removing and enabling/disabling their providers, plus the stream
 * resolution pipeline (download provider code → run in the sandboxed
 * JS runtime → normalize into Stream Bridge's stream model).
 *
 * Failure isolation is absolute: one broken repository, provider or
 * network call never affects the others and never crashes the app.
 */
class NuvioPluginManager(
    context: Context,
    private val scope: CoroutineScope,
    private val http: OkHttpClient
) {

    private val appContext = context.applicationContext
    private val store = NuvioPluginStore(context, scope)
    private val runtime = NuvioPluginRuntime()

    /** Failures of the LAST picker resolution, surfaced as picker rows. */
    private val _lastProviderErrors = MutableStateFlow<List<ProviderFailure>>(emptyList())
    val lastProviderErrors: StateFlow<List<ProviderFailure>> = _lastProviderErrors.asStateFlow()

    /** Short-lived cache of provider code, so repeated resolution does not re-download. */
    private val codeCache = ConcurrentHashMap<String, CacheEntry>()

    private class CacheEntry(val code: String, val at: Long)

    private val fetchMutex = Mutex()

    val repositories: StateFlow<List<NuvioInstalledRepository>> = store.repositories

    // -----------------------------------------------------------------
    // Repository management
    // -----------------------------------------------------------------

    /** Installs (or refreshes) a repository from a manifest URL. */
    suspend fun addRepository(rawUrl: String): Result<NuvioInstalledRepository> {
        val validated = when (val verdict = UrlValidator.validate(rawUrl)) {
            is UrlValidator.Result.Invalid -> return Result.failure(
                NuvioPluginException(verdict.reason)
            )
            is UrlValidator.Result.Valid -> verdict.url
        }

        return try {
            val manifestText = fetchText(validated)
            when (val parsed = NuvioManifest.parse(manifestText)) {
                is NuvioManifest.ParseResult.Invalid ->
                    Result.failure(NuvioPluginException(parsed.reason))

                is NuvioManifest.ParseResult.Valid -> {
                    val existing = store.repositories.first()
                        .firstOrNull { it.manifestUrl == validated }
                    val providers = parsed.providers.map { it.toStored() }
                    // Keep the user's enabled set across refreshes; honor
                    // the manifest's suggested defaults only for a
                    // brand-new repository (never overriding the user).
                    val enabled = if (existing == null) {
                        parsed.providers.filter { it.enabled }.map { it.id }
                    } else {
                        providers.filter { it.id in existing.enabledProviderIds }.map { it.id }
                    }
                    val repository = NuvioInstalledRepository(
                        manifestUrl = validated,
                        // Prefer the repository's own display name; fall
                        // back to the manifest URL's host.
                        name = parsed.repositoryName.ifBlank {
                            NuvioPluginStore.repositoryNameFor(validated)
                        },
                        providers = providers,
                        enabledProviderIds = enabled,
                        addedAt = existing?.addedAt ?: System.currentTimeMillis(),
                        lastUpdated = System.currentTimeMillis(),
                        lastError = ""
                    )
                    store.upsertRepository(repository)
                    Result.success(repository)
                }
            }
        } catch (e: Exception) {
            val message = e.message ?: "Could not load the repository"
            // Persist the failure so the Plugins screen can show it next
            // to the repository (the URL is already validated at this point).
            runCatching { store.setRecordedError(validated, message) }
            Result.failure(NuvioPluginException(message))
        }
    }

    /** Re-fetches a repository's manifest, preserving user choices. */
    suspend fun refreshRepository(manifestUrl: String): Result<NuvioInstalledRepository> {
        // Clear cached provider code so refreshes pick up new code too.
        codeCache.keys.removeAll { it.startsWith(manifestUrl) }
        return addRepository(manifestUrl)
    }

    suspend fun removeRepository(manifestUrl: String) {
        codeCache.keys.removeAll { it.startsWith(manifestUrl) }
        store.removeRepository(manifestUrl)
    }

    suspend fun setProviderEnabled(manifestUrl: String, providerId: String, enabled: Boolean) {
        store.setProviderEnabled(manifestUrl, providerId, enabled)
    }

    // -----------------------------------------------------------------
    // Stream resolution
    // -----------------------------------------------------------------

    /**
     * Runs every enabled provider matching the media type and maps the
     * results into Stream Bridge's stream model. Each provider runs
     * with its own timeout; failures are swallowed per-provider.
     */
    suspend fun resolveStreams(
        type: String,
        tmdbId: String?,
        imdbId: String?,
        metaId: String?,
        season: Int?,
        episode: Int?
    ): List<StreamOption> {
        val sources = providerSources(type, tmdbId, imdbId, metaId, season, episode)
        if (sources.isEmpty()) {
            _lastProviderErrors.value = emptyList()
            return emptyList()
        }
        val results = coroutineScope {
            sources.map { source -> async { source.resolve() } }.awaitAll()
        }
        return finishResolution(results)
    }

    /**
     * The enabled plugin providers matching [type], each wrapped as an
     * independently executable [StreamSource] with its own timeout and
     * status classification. Sources never throw, so the caller can
     * fan them out (concurrently, progressively) any way it likes.
     */
    fun providerSources(
        type: String,
        tmdbId: String?,
        imdbId: String?,
        metaId: String?,
        season: Int?,
        episode: Int?
    ): List<StreamSource> {
        val mediaType = if (type.equals("series", true)) "tv" else type
        val repos = store.repositories.value
        return repos
            .flatMap { repo -> repo.enabledProviders.map { provider -> repo to provider } }
            .filter { (_, provider) -> provider.supportsType(mediaType) }
            .map { (repo, provider) ->
                NuvioProviderSource(
                    repo = repo,
                    provider = provider,
                    request = NuvioStreamRequest.from(
                        type, bestProviderId(tmdbId, imdbId, metaId), season, episode
                    )
                )
            }
    }

    /**
     * Reduces the per-provider outcomes of one resolution into the
     * combined stream list and records provider failures for the
     * picker (unchanged contract of [resolveStreams]).
     */
    fun finishResolution(results: List<SourceResult>): List<StreamOption> {
        _lastProviderErrors.value = providerFailures(results)
        return results
            .flatMap { result -> (result.status as? SourceStatus.Success)?.streams ?: emptyList() }
            .distinctBy { it.id }
    }

    /** Nuvio providers key their scrapes off TMDB ids; fall back honestly. */
    private fun bestProviderId(tmdbId: String?, imdbId: String?, metaId: String?): String =
        when {
            !tmdbId.isNullOrBlank() -> tmdbId
            !imdbId.isNullOrBlank() -> imdbId
            !metaId.isNullOrBlank() -> metaId
            else -> ""
        }

    /**
     * One Nuvio plugin provider as an executable source: fetches its
     * code (cached), runs it in the sandboxed JS runtime with the
     * compatibility bundles and its relative modules, and normalizes
     * the streams it returned. Failures and timeouts are classified,
     * never thrown — isolation is absolute.
     */
    private inner class NuvioProviderSource(
        private val repo: NuvioInstalledRepository,
        private val provider: NuvioInstalledRepository.StoredProvider,
        private val request: NuvioStreamRequest
    ) : StreamSource {

        override val id: String = "plugin:${repo.manifestUrl}:${provider.id}"
        override val name: String = provider.displayName
        override val origin: String = repo.name

        override suspend fun resolve(): SourceResult {
            val codeUrl = NuvioManifest.providerCodeUrl(repo.manifestUrl, provider.filename)
                ?: return SourceResult(
                    id, name, origin, SourceStatus.Failed("provider code URL is invalid")
                )
            return try {
                val raw = withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                    val code = providerCode(codeUrl)
                    runtime.execute(
                        http, codeUrl, code, request,
                        extraModules = compatBundles + providerModules(codeUrl, code)
                    )
                }
                when {
                    raw == null -> {
                        logPlugin("error", "provider ${provider.id} timed out")
                        SourceResult(id, name, origin, SourceStatus.Timeout)
                    }
                    else -> {
                        val mapped = raw.mapNotNull { it.toStreamOption(provider, repo) }
                            .distinctBy { it.id }
                        val enriched = StreamEnrichment.sortForPicker(StreamEnrichment.enrichAll(mapped))
                        SourceResult(
                            id, name, origin,
                            if (enriched.isEmpty()) SourceStatus.Empty
                            else SourceStatus.Success(enriched)
                        )
                    }
                }
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (e: Exception) {
                // One broken provider must never affect the others;
                // its failure is reported to the picker instead.
                logPlugin("error", "provider ${provider.id} failed: ${e.message}")
                SourceResult(id, name, origin, SourceStatus.fromException(e))
            }
        }
    }

    /**
     * The standard compatibility bundles (real cheerio and node-forge,
     * browser builds) shipped as app assets. Loaded once, lazily; a
     * missing bundle is logged and skipped — providers that need it
     * then fail with a clear MODULE_NOT_FOUND instead of a crash.
     */
    private val compatBundles: Map<String, String> by lazy {
        val bundles = HashMap<String, String>()
        val bundleFiles = listOf(
            "compat/cheerio.bundle.js" to
                listOf("cheerio", "cheerio-without-node-native"),
            "compat/forge.bundle.js" to listOf("node-forge")
        )
        for ((file, names) in bundleFiles) {
            val source = try {
                appContext.assets.open(file).use { input ->
                    input.readBytes().toString(Charsets.UTF_8)
                }
            } catch (e: Exception) {
                logPlugin("compat", "bundle " + file + " unavailable: " +
                    (e.message?.take(60) ?: ""))
                continue
            }
            for (name in names) { bundles[name] = source }
        }
        bundles
    }

    /**
     * Pre-fetches the relative modules a provider requires (bounded:
     * at most [MAX_RELATIVE_MODULES] files), so multi-file providers
     * work. Failures are logged and skipped — a missing module then
     * surfaces as the provider's own controlled MODULE_NOT_FOUND.
     */
    private suspend fun providerModules(codeUrl: String, code: String): Map<String, String> {
        val specs = try {
            ProviderAnalyzer.analyze(code).relativeModules
        } catch (_: Exception) {
            emptyList()
        }
        if (specs.isEmpty()) return emptyMap()
        val base = codeUrl.toHttpUrlOrNull() ?: return emptyMap()
        val modules = HashMap<String, String>()
        for (spec in specs.take(MAX_RELATIVE_MODULES)) {
            val absolute = runCatching { base.resolve(spec)?.toString() }.getOrNull()
            if (absolute == null || !absolute.startsWith("http")) continue
            val source = try {
                withContext(Dispatchers.IO) { runtime.fetchProviderCode(http, absolute) }
            } catch (e: Exception) {
                logPlugin(
                    "compat",
                    "relative module " + spec + " unavailable: " + (e.message?.take(60) ?: "")
                )
                continue
            }
            // Register under both the raw specifier and the absolute URL.
            modules[spec] = source
            modules[absolute] = source
        }
        return modules
    }

    private suspend fun providerCode(codeUrl: String): String {
        codeCache[codeUrl]?.let { entry ->
            if (System.currentTimeMillis() - entry.at < CODE_CACHE_TTL_MS) return entry.code
        }
        return fetchMutex.withLock {
            codeCache[codeUrl]?.let { entry ->
                if (System.currentTimeMillis() - entry.at < CODE_CACHE_TTL_MS) return@withLock entry.code
            }
            val code = withContext(Dispatchers.IO) {
                runtime.fetchProviderCode(http, codeUrl)
            }
            codeCache[codeUrl] = CacheEntry(code, System.currentTimeMillis())
            // Opportunistic trim: keep at most 24 entries.
            if (codeCache.size > 24) {
                codeCache.entries.sortedBy { it.value.at }.take(codeCache.size - 24)
                    .forEach { codeCache.remove(it.key) }
            }
            code
        }
    }

    private suspend fun fetchText(url: String): String = withContext(Dispatchers.IO) {
        http.newCall(
            okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", "StreamBridge/1.0 (Android)")
                .build()
        ).execute().use { response ->
            if (!response.isSuccessful) {
                throw NuvioPluginException("The repository returned HTTP ${response.code}")
            }
            val body = response.body ?: throw NuvioPluginException("Empty repository response")
            val text = body.string()
            if (text.length > MAX_MANIFEST_BYTES) {
                throw NuvioPluginException("Repository manifest is too large")
            }
            text
        }
    }

    private fun NuvioManifest.Provider.toStored(): NuvioInstalledRepository.StoredProvider =
        NuvioInstalledRepository.StoredProvider(
            id = id,
            name = name,
            description = description,
            version = version,
            author = author,
            supportedTypes = supportedTypes,
            filename = filename,
            hasSettings = hasSettings,
            formats = formats,
            logo = logo,
            contentLanguage = contentLanguage,
            limited = limited,
            resources = resources
        )

    companion object {
        private const val PROVIDER_TIMEOUT_MS = 35_000L
        private const val MAX_RELATIVE_MODULES = 4
        private const val CODE_CACHE_TTL_MS = 10 * 60 * 1000L
        private const val MAX_MANIFEST_BYTES = 2 * 1024 * 1024
    }
}

/**
 * Maps per-provider resolution outcomes to picker failure rows.
 * Successes and honest empty results never become failures; timeouts,
 * network errors and provider errors do, with a bounded reason.
 */
internal fun providerFailures(results: List<SourceResult>): List<ProviderFailure> =
    results.mapNotNull { result ->
        val reason = when (val status = result.status) {
            is SourceStatus.Timeout -> "timed out"
            is SourceStatus.NetworkError -> status.reason.ifBlank { "network error" }
            is SourceStatus.Failed -> status.reason.ifBlank { "provider failed" }
            else -> return@mapNotNull null
        }
        ProviderFailure(
            providerName = result.name,
            repositoryName = result.origin,
            reason = reason.take(120)
        )
    }

/**
 * Normalizes a Nuvio stream object into Stream Bridge's stream model.
 * Headers are sanitized through the existing [StreamHeaders] filter and
 * the URL must be a playable http(s) link.
 */
fun NuvioRawStream.toStreamOption(
    provider: NuvioInstalledRepository.StoredProvider,
    repo: NuvioInstalledRepository
): StreamOption? {
    // Only plain web URLs are playable; UrlValidator normalizes
    // scheme-less input (e.g. "magnet:?..." would become a URL on some
    // host), so the raw scheme is checked before validation.
    val raw = url.trim()
    if (!raw.startsWith("http://", ignoreCase = true) &&
        !raw.startsWith("https://", ignoreCase = true)
    ) {
        return null
    }
    val verdict = UrlValidator.validate(raw, allowPublicHttp = true)
    val playableUrl = when (verdict) {
        is UrlValidator.Result.Invalid -> null
        is UrlValidator.Result.Valid -> verdict.url
    } ?: return null

    var label = title.ifBlank { name.ifBlank { provider.displayName } }
    // Fold the quality into the label so the existing enrichment pass
    // (which parses quality/resolution from label text) sees it.
    if (quality.isNotBlank() && !label.contains(quality, ignoreCase = true)) {
        label = "$label · $quality"
    }
    return StreamOption(
        id = "nuvio:${repo.manifestUrl}:${provider.id}:${playableUrl.hashCode()}",
        label = label,
        description = provider.description.ifBlank { repo.name },
        url = playableUrl,
        infoHash = null,
        externalUrl = null,
        addonName = provider.displayName,
        isTorrent = false,
        isExternal = false,
        bingeGroup = "nuvio:${provider.id}",
        classification = StreamClassification.DIRECT,
        headers = StreamHeaders.sanitize(headers),
        // The provider's declared format is real metadata it supplied —
        // map only clean known values, never a guess.
        mimeType = StreamMimeTypes.fromProviderHint(format) ?: ""
    )
}
