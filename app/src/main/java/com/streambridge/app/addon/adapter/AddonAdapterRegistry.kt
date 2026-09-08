package com.streambridge.app.addon.adapter

/**
 * Picks the right adapter for a URL or stored ecosystem id. Manifest
 * URLs default to the Stremio protocol (which Nuvio addons speak too).
 */
object AddonAdapterRegistry {

    val stremio: StremioAddonAdapter = StremioAddonAdapter.instance
    val nuvio: NuvioAddonAdapter = NuvioAddonAdapter.instance
    val cloudstream: CloudstreamAdapter = CloudstreamAdapter.instance

    fun forUrl(url: String): AddonAdapter = when {
        cloudstream.acceptsUrl(url) -> cloudstream
        else -> stremio
    }

    fun forEcosystem(ecosystem: String): AddonAdapter = when (ecosystem) {
        "nuvio" -> nuvio
        "cloudstream" -> cloudstream
        else -> stremio
    }

    /** All adapters that can actually serve media requests. */
    val executableAdapters: List<AddonAdapter> = listOf(stremio, nuvio)
}
