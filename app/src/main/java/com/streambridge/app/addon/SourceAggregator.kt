package com.streambridge.app.addon

import com.streambridge.app.addon.model.StreamOption
import com.streambridge.app.addon.model.toStreamOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException

/**
 * Outcome of ONE source's resolution for ONE request. Sources are
 * independent: a failing source expresses its failure through its
 * status and never affects the others.
 */
sealed interface SourceStatus {
    /** The source is still working. */
    data object Loading : SourceStatus

    /** The source returned streams (possibly none playable). */
    data class Success(val streams: List<StreamOption>) : SourceStatus

    /** The source answered successfully but had nothing for this title. */
    data object Empty : SourceStatus

    /** The source exceeded its per-source timeout. */
    data object Timeout : SourceStatus

    /** The source could not be reached (connectivity, DNS, HTTP layer). */
    data class NetworkError(val reason: String) : SourceStatus

    /** The source itself failed (bad code, runtime error, bad manifest). */
    data class Failed(val reason: String) : SourceStatus

    val isTerminal: Boolean get() = this !is Loading

    companion object {
        /** Classifies an exception that escaped a source's execution. */
        fun fromException(e: Exception): SourceStatus = when (e) {
            is IOException ->
                NetworkError(e.message?.take(120)?.ifBlank { "network error" } ?: "network error")
            else ->
                Failed(e.message?.take(120)?.ifBlank { "source failed" } ?: "source failed")
        }
    }
}

/**
 * One resolution event for a named source. [playableStreams] is the
 * honest subset the built-in player can actually open; [usable] is
 * what qualifies a source for a selector tab.
 */
data class SourceResult(
    val sourceId: String,
    val name: String,
    /** Where this source comes from (repository name, addon host). */
    val origin: String,
    val status: SourceStatus
) {
    val playableStreams: List<StreamOption>
        get() = (status as? SourceStatus.Success)?.streams?.filter { it.isPlayable } ?: emptyList()

    val usable: Boolean get() = playableStreams.isNotEmpty()
}

/**
 * An independently executable stream source — a Stremio-style addon or
 * a Nuvio plugin provider. [resolve] never throws: every failure is
 * expressed as the returned status, so one broken source can never
 * break the resolution of the others.
 */
interface StreamSource {
    val id: String
    val name: String
    val origin: String
    suspend fun resolve(): SourceResult
}

/**
 * A Stremio-style addon as an executable source: asks the addon for
 * every candidate id (its own id plus the IMDb fallback), normalizes
 * and enriches whatever it returns. Per-candidate failures are
 * swallowed exactly as before; the whole source runs under one
 * timeout.
 */
class StremioAddonSource(
    private val api: AddonApi,
    private val extension: InstalledExtension,
    private val type: String,
    private val candidateIds: List<String>,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS
) : StreamSource {

    override val id: String = "addon:${extension.addonId}"
    override val name: String = extension.displayName
    override val origin: String =
        extension.baseUrl.toHttpUrlOrNull()?.host ?: extension.baseUrl

    override suspend fun resolve(): SourceResult {
        val streams = try {
            withTimeoutOrNull(timeoutMs) {
                candidateIds.mapNotNull { candidate ->
                    try {
                        api.fetchStreams(extension.baseUrl, type, candidate)
                    } catch (_: Exception) {
                        null
                    }
                }
                    .flatMap { response -> response.streams }
                    .mapNotNull { stream -> stream.toStreamOption(extension.displayName) }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            return SourceResult(id, name, origin, SourceStatus.fromException(e))
        }
        if (streams == null) {
            return SourceResult(id, name, origin, SourceStatus.Timeout)
        }
        val enriched = StreamEnrichment.enrichAll(streams.distinctBy { it.id })
        val status = if (enriched.isEmpty()) {
            SourceStatus.Empty
        } else {
            SourceStatus.Success(StreamEnrichment.sortForPicker(enriched))
        }
        return SourceResult(id, name, origin, status)
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 20_000L
    }
}

/**
 * Runs every source CONCURRENTLY and reports results PROGRESSIVELY:
 * each source emits a Loading event up front, then its final result
 * the moment it settles — no waiting for slower sources. This is the
 * aggregator between the provider runtime and the source-selection
 * state: all independent providers execute at once and results appear
 * as soon as they are available.
 */
class StreamSourceAggregator {

    /**
     * @param sources every source that should run for this request
     * @param onEvent invoked for every state change; must be safe to
     *        call from any coroutine (called once per source while
     *        starting, then once per completed source)
     * @return the final results in source order, after all have settled
     */
    suspend fun aggregate(
        sources: List<StreamSource>,
        onEvent: (SourceResult) -> Unit
    ): List<SourceResult> = coroutineScope {
        sources.forEach { source ->
            onEvent(SourceResult(source.id, source.name, source.origin, SourceStatus.Loading))
        }
        sources.map { source ->
            async {
                val result = source.resolve()
                onEvent(result)
                result
            }
        }.awaitAll()
    }
}
