package com.nuvio.app.features.cloudstream

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpGetText
import kotlinx.coroutines.CancellationException

/**
 * Fetches and normalises CloudStream repositories.
 *
 * Reuses StreamBridge's existing HTTP stack ([httpGetText]) rather than
 * introducing a second networking implementation, so redirects, gzip/deflate/
 * Brotli, and timeout handling behave exactly as they do everywhere else.
 *
 * Failure policy: unreachable or malformed repositories resolve to a FAILED
 * [CloudStreamRepository] carrying an actionable message. Nothing here throws
 * into the caller, so one bad repository cannot break discovery for the rest.
 */
internal class CloudStreamRepositoryLoader(
    private val fetch: suspend (String) -> String = { url -> httpGetText(url) },
) {
    private val log = Logger.withTag("CloudStreamRepo")

    suspend fun load(repositoryUrl: String): CloudStreamRepository {
        val url = repositoryUrl.trim()
        if (url.isEmpty() || !url.looksLikeHttpUrl()) {
            return failed(url, "Enter a valid http(s) repository URL.")
        }

        val rawManifest = try {
            fetch(url)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.w { "CloudStream repository unreachable: ${error.message}" }
            return failed(url, "Could not reach the repository. Check the URL and your connection.")
        }

        val manifest = CloudStreamRepositoryParser.parseRepositoryManifest(rawManifest)
            ?: return failed(url, "The repository did not return a valid CloudStream repo.json.")

        val repositoryName = manifest.name?.takeIf { it.isNotBlank() } ?: url

        if (!CloudStreamRepositoryParser.isSupportedManifestVersion(manifest)) {
            return CloudStreamRepository(
                url = url,
                name = repositoryName,
                description = manifest.description,
                compatibility = CloudStreamCompatibility.UNSUPPORTED,
                compatibilityReason = CloudStreamCompatibilityReason.UNSUPPORTED_API_VERSION,
                errorMessage = "This repository uses a newer CloudStream format " +
                    "(manifestVersion ${manifest.manifestVersion}) than StreamBridge supports.",
            )
        }

        if (manifest.pluginLists.isEmpty()) {
            return CloudStreamRepository(
                url = url,
                name = repositoryName,
                description = manifest.description,
                compatibility = CloudStreamCompatibility.PARTIALLY_COMPATIBLE,
                compatibilityReason = CloudStreamCompatibilityReason.INCOMPLETE_METADATA,
                errorMessage = "The repository does not list any plugins.",
            )
        }

        val plugins = mutableListOf<CloudStreamPlugin>()
        var anyListLoaded = false

        for (listUrl in manifest.pluginLists) {
            val target = listUrl.trim()
            if (target.isEmpty() || !target.looksLikeHttpUrl()) continue

            val rawList = try {
                fetch(target)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                // One dead plugin list must not sink the whole repository.
                log.w { "CloudStream plugin list unreachable: ${error.message}" }
                continue
            }

            val parsed = CloudStreamRepositoryParser.parsePluginList(rawList)
            if (parsed == null) {
                log.w { "CloudStream plugin list was not valid JSON" }
                continue
            }
            anyListLoaded = true
            parsed.forEach { plugins += CloudStreamRepositoryParser.toPlugin(it) }
        }

        if (!anyListLoaded) {
            return failed(
                url,
                "The repository's plugin list could not be read.",
                name = repositoryName,
            )
        }

        // Deduplicate by identity, preferring the highest version seen.
        val deduplicated = plugins
            .filter { it.id.isNotEmpty() }
            .groupBy { it.id }
            .map { (_, group) -> group.maxByOrNull { it.version ?: -1 } ?: group.first() }
            .sortedBy { it.displayName.lowercase() }
        val broken = plugins.filter { it.id.isEmpty() }

        return CloudStreamRepository(
            url = url,
            name = repositoryName,
            description = manifest.description,
            plugins = deduplicated + broken,
            compatibility = CloudStreamCompatibility.COMPATIBLE,
            compatibilityReason = CloudStreamCompatibilityReason.NONE,
        )
    }

    private fun failed(
        url: String,
        message: String,
        name: String = url,
    ) = CloudStreamRepository(
        url = url,
        name = name,
        compatibility = CloudStreamCompatibility.FAILED,
        compatibilityReason = CloudStreamCompatibilityReason.MALFORMED_OR_UNREACHABLE,
        errorMessage = message,
    )
}

private fun String.looksLikeHttpUrl(): Boolean =
    startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true)
