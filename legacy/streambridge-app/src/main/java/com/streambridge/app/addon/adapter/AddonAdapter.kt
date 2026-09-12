package com.streambridge.app.addon.adapter

import com.streambridge.app.addon.InstalledExtension

/**
 * Normalization boundary between external addon ecosystems and the
 * internal media model. Implementations know how to talk to (or at
 * least describe) their ecosystem; the rest of the app only ever
 * deals with the common model.
 */
interface AddonAdapter {

    /** Ecosystem id, persisted with the extension. */
    val ecosystem: String

    /** Human-readable ecosystem name. */
    val label: String

    /**
     * Whether this adapter can drive the given manifest URL.
     * Manifest-URL ecosystems (Stremio, Nuvio) accept any http(s)
     * manifest; others declare their own URL shapes.
     */
    fun acceptsUrl(url: String): Boolean

    /**
     * Capability check: may this extension be asked for [resource]
     * ("catalog" / "meta" / "stream" / "subtitles") for the given
     * media type and id? Respects declared resources, types and
     * idPrefixes so we never send pointless requests.
     */
    fun canServe(
        extension: InstalledExtension,
        resource: String,
        type: String,
        id: String
    ): Boolean

    /** Whether the ecosystem's sources can be executed in this client. */
    val supportsExecution: Boolean
}
