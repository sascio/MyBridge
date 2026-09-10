package com.streambridge.app.addon.adapter

import com.streambridge.app.addon.IdMapping
import com.streambridge.app.addon.InstalledExtension

/**
 * Stremio addon ecosystem: manifest URLs speaking the open Stremio
 * addon protocol (manifest.json / catalog / meta / stream / subtitles).
 * This is the primary, fully-executable adapter.
 */
open class StremioAddonAdapter : AddonAdapter {

    override val ecosystem: String = "stremio"
    override val label: String = "Stremio-compatible"
    override val supportsExecution: Boolean = true

    override fun acceptsUrl(url: String): Boolean {
        val lower = url.trim().lowercase()
        return lower.startsWith("http://") || lower.startsWith("https://")
    }

    override fun canServe(
        extension: InstalledExtension,
        resource: String,
        type: String,
        id: String
    ): Boolean {
        if (!extension.enabled) return false
        if (!extension.resources.any { it.equals(resource, ignoreCase = true) }) return false
        if (extension.types.isNotEmpty() &&
            !extension.types.any { it.equals(type, ignoreCase = true) }
        ) {
            return false
        }
        // Resource-level prefixes: when the manifest declares global
        // idPrefixes, honor them for id-scoped resources.
        if (resource == "meta" || resource == "stream" || resource == "subtitles") {
            if (extension.idPrefixes.isNotEmpty() &&
                !IdMapping.canServeId(id, type, emptyList(), extension.idPrefixes)
            ) {
                return false
            }
        }
        return true
    }

    companion object {
        /** Shared stateless instance. */
        val instance: StremioAddonAdapter = StremioAddonAdapter()
    }
}

/**
 * Nuvio addon ecosystem. Nuvio consumes the same Stremio manifest
 * protocol (with extra tolerance fields such as behaviorHints.adult
 * and p2p), so this adapter reuses the Stremio request rules and adds
 * Nuvio provenance plus adult-content surfacing.
 */
class NuvioAddonAdapter : StremioAddonAdapter() {

    override val ecosystem: String = "nuvio"
    override val label: String = "Nuvio-compatible"

    /** Nuvio marks adult addons via behaviorHints; exposed for UI badges. */
    fun isAdult(extension: InstalledExtension): Boolean =
        extension.adultContent

    companion object {
        val instance: NuvioAddonAdapter = NuvioAddonAdapter()
    }
}
