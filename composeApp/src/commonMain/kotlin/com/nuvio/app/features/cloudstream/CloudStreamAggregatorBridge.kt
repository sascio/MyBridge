package com.nuvio.app.features.cloudstream

/**
 * Selection layer between the CloudStream extension catalogue and
 * StreamBridge's existing source aggregator.
 *
 * This deliberately contains **no** networking, no coroutines and no player
 * code: aggregation, concurrency, cancellation, timeouts, ordering and failure
 * isolation all stay in `StreamsRepository`. This object only answers one
 * question — *which CloudStream providers should take part in this request, and
 * under which identity* — so the rules are pure and unit-testable.
 */
internal object CloudStreamAggregatorBridge {

    /**
     * One CloudStream provider participating in a source request.
     *
     * [addonId] is the stable identity from [CloudStreamProviderIdentity]; it
     * is what the source picker groups by and what per-provider state keys off.
     */
    data class Target(
        val addonId: String,
        val addonName: String,
        val extensionId: String,
        val sourceId: String,
        val contentType: String?,
        val iconUrl: String?,
    )

    /**
     * CloudStream `tvTypes` that can serve a StreamBridge `movie` request.
     *
     * Matching is capability-based rather than an allowlist of extensions:
     * any extension declaring a compatible type participates, so newly
     * published extensions work without code changes.
     */
    private val MOVIE_TYPES = setOf("movie", "anime", "cartoon", "documentary", "asiandrama", "live")

    /** CloudStream `tvTypes` that can serve a StreamBridge `series` request. */
    private val SERIES_TYPES = setOf(
        "tvseries",
        "anime",
        "animemovie",
        "cartoon",
        "asiandrama",
        "documentary",
        "ova",
        "live",
    )

    /**
     * Whether a declared CloudStream type can answer a StreamBridge media type.
     *
     * An extension that declares no types at all is treated as unrestricted:
     * CloudStream itself allows that, and excluding it would silently drop
     * otherwise working providers.
     */
    fun matchesMediaType(contentType: String?, mediaType: String): Boolean {
        val declared = contentType?.trim()?.lowercase()
        if (declared.isNullOrEmpty()) return true
        return when (mediaType.trim().lowercase()) {
            "movie" -> declared in MOVIE_TYPES
            "series", "tv", "tvseries" -> declared in SERIES_TYPES
            // Unknown StreamBridge type: do not silently exclude the provider.
            else -> true
        }
    }

    /**
     * Resolves the providers that should run for one source request.
     *
     * A provider participates only when **all** of these hold:
     *  - its extension is genuinely executable (never merely "metadata parsed"),
     *  - the user has enabled that specific source,
     *  - its declared content type can serve the requested media type.
     *
     * Results are deduplicated by [Target.addonId], so the same provider
     * discovered through several repositories runs once and appears once.
     */
    fun resolveTargets(
        extensions: List<CloudStreamExtension>,
        mediaType: String,
    ): List<Target> {
        val deduplicated = CloudStreamProviderIdentity.deduplicate(extensions)
        val targets = LinkedHashMap<String, Target>()

        deduplicated.forEach { extension ->
            // Honesty gate: an extension that cannot actually execute must
            // never contribute a provider, whatever is persisted for it.
            if (!extension.plugin.isExecutable) return@forEach

            extension.sources.forEach { source ->
                if (!source.enabled || !source.canActivate) return@forEach
                if (!matchesMediaType(source.contentType, mediaType)) return@forEach

                val addonId = CloudStreamProviderIdentity.addonId(
                    internalName = extension.plugin.internalNameOrId(),
                    contentType = source.contentType,
                )
                if (targets.containsKey(addonId)) return@forEach

                targets[addonId] = Target(
                    addonId = addonId,
                    addonName = providerLabel(extension, source),
                    extensionId = extension.id,
                    sourceId = source.id,
                    contentType = source.contentType,
                    iconUrl = extension.plugin.iconUrl,
                )
            }
        }
        return targets.values.toList()
    }

    /**
     * Label shown as the source group heading.
     *
     * The extension name carries the branding users recognise; the content
     * type is appended only when the extension exposes more than one provider,
     * so single-provider extensions stay clean.
     */
    private fun providerLabel(extension: CloudStreamExtension, source: CloudStreamSource): String {
        val base = extension.name.trim().ifBlank { extension.id }
        val type = source.contentType?.trim().orEmpty()
        return if (type.isEmpty() || extension.sources.size <= 1) base else "$base · $type"
    }
}
