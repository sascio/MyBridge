package com.streambridge.app.addon.adapter

import com.streambridge.app.addon.HttpAddonApi
import com.streambridge.app.addon.InstalledExtension
import com.streambridge.app.addon.SbHttpClient
import com.streambridge.app.addon.model.PluginListing
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Cloudstream ecosystem compatibility layer — REPOSITORY BROWSING ONLY.
 *
 * Cloudstream sources are distributed as `.cs3` plugin packages:
 * compiled Kotlin/Dex bundles that the Cloudstream app loads and
 * executes at runtime. Executing arbitrary downloaded code is
 * categorically unsafe for Stream Bridge (and forbidden by this
 * project's security policy), so this adapter deliberately does NOT
 * install or run plugins.
 *
 * What it DOES provide, for real:
 *  - parses Cloudstream `repo.json` repository descriptors
 *  - parses the repository's plugin lists (`plugins.json`)
 *  - presents the plugins with full metadata so users can see what a
 *    repository offers
 *
 * The Plugins screen clearly labels every entry as non-executable in
 * Stream Bridge. This is the safest practical compatibility
 * architecture; the limitation is documented in README and About.
 */
class CloudstreamAdapter(
    private val http: SbHttpClient = SbHttpClient(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }
) : AddonAdapter {

    override val ecosystem: String = "cloudstream"
    override val label: String = "Cloudstream repository"
    override val supportsExecution: Boolean = false

    override fun acceptsUrl(url: String): Boolean {
        // A Cloudstream repository URL points at a repo.json (or hosts one).
        val lower = url.trim().lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
        return lower.endsWith("repo.json") || lower.endsWith("plugins.json")
    }

    override fun canServe(
        extension: InstalledExtension,
        resource: String,
        type: String,
        id: String
    ): Boolean = false // Cloudstream plugins are never executable here.

    // -----------------------------------------------------------------
    // Repository models
    // -----------------------------------------------------------------

    @Serializable
    data class CloudstreamRepo(
        val name: String = "",
        val description: String = "",
        val manifestVersion: Int = 0,
        val pluginLists: List<String> = emptyList()
    )

    @Serializable
    data class CloudstreamPluginEntry(
        val name: String = "",
        val internalName: String = "",
        val version: Int = 0,
        val description: String = "",
        val repositoryUrl: String = "",
        val url: String = "",
        val authors: List<String> = emptyList(),
        val tvTypes: List<String> = emptyList(),
        val language: String = "",
        val iconUrl: String = "",
        val status: Int = 0,
        val apiVersion: Int = 0
    )

    // -----------------------------------------------------------------
    // Fetching
    // -----------------------------------------------------------------

    /** Loads a repository: repo.json (or a direct plugins.json URL). */
    suspend fun loadRepository(repoUrl: String): CloudstreamRepository {
        val normalized = repoUrl.trim()
        return if (normalized.substringBefore('?').endsWith("/plugins.json") ||
            normalized.endsWith("plugins.json")
        ) {
            val plugins = fetchPluginList(normalized)
            CloudstreamRepository(
                repoUrl = normalized,
                name = "Plugin list",
                description = "",
                plugins = plugins
            )
        } else {
            val base = normalized.substringBefore('?').removeSuffix("repo.json").trimEnd('/')
            val body = http.get(normalized, timeoutMs = 15000L)
            val repo = json.decodeFromString(CloudstreamRepo.serializer(), body)
            val plugins = repo.pluginLists
                .mapNotNull { listUrl -> resolveUrl(base, listUrl) }
                .flatMap { url -> runCatching { fetchPluginList(url) }.getOrDefault(emptyList()) }
            CloudstreamRepository(
                repoUrl = normalized,
                name = repo.name,
                description = repo.description,
                plugins = plugins
            )
        }
    }

    private suspend fun fetchPluginList(url: String): List<PluginListing> {
        val body = http.get(url, timeoutMs = 15000L)
        return json.decodeFromString(ListSerializer(CloudstreamPluginEntry.serializer()), body)
            .map { it.toListing() }
    }

    private fun resolveUrl(base: String, url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return null
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        return "$base/$trimmed"
    }

    private fun CloudstreamPluginEntry.toListing() = PluginListing(
        name = name.ifBlank { internalName },
        internalName = internalName,
        version = version,
        description = description,
        fileUrl = url,
        repositoryUrl = repositoryUrl,
        authors = authors,
        tvTypes = tvTypes,
        language = language,
        iconUrl = iconUrl,
        status = status
    )

    data class CloudstreamRepository(
        val repoUrl: String,
        val name: String,
        val description: String,
        val plugins: List<PluginListing>
    )

    companion object {
        val instance: CloudstreamAdapter = CloudstreamAdapter()
    }
}
