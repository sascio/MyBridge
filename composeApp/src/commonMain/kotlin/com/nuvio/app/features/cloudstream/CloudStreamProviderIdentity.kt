package com.nuvio.app.features.cloudstream

/**
 * Stable identity and deduplication for CloudStream providers.
 *
 * ## Why identity is not the display name
 *
 * The same CloudStream extension is frequently published through several
 * repositories (a primary repo plus mirrors/forks). Keying a provider by its
 * display name produces duplicate cards in the Extensions list and duplicate
 * groups in the source picker, and it also collides whenever two unrelated
 * extensions happen to share a friendly name.
 *
 * Identity is therefore derived from CloudStream's own stable metadata:
 *
 *  - `internalName` — the plugin's canonical identifier inside the ecosystem,
 *  - the declared `tvType` — because one extension legitimately exposes
 *    genuinely different providers (Movie / TvSeries / Anime).
 *
 * That yields `cloudstream:<internalName>::<tvType>`, which is stable across
 * repositories, stable across refreshes, and distinct per real provider. It is
 * the same string used as the aggregator `addonId`, so the source picker, the
 * enable/disable state and the dedup key all agree.
 */
internal object CloudStreamProviderIdentity {

    const val ADDON_ID_PREFIX = "cloudstream"

    /**
     * Aggregator-facing id for one provider.
     *
     * [contentType] is the CloudStream `tvType`. It is part of the key on
     * purpose: Movie and TvSeries from one extension are different providers
     * and must remain separately selectable.
     */
    fun addonId(internalName: String, contentType: String?): String {
        val base = "$ADDON_ID_PREFIX:${internalName.trim().lowercase()}"
        val type = contentType?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        return if (type == null) base else "$base::$type"
    }

    /**
     * Identity of a plugin irrespective of which repository served it.
     *
     * Used to collapse the same extension discovered from several
     * repositories into one entry.
     */
    fun extensionKey(plugin: CloudStreamPlugin): String =
        plugin.internalNameOrId().trim().lowercase()

    /**
     * Deduplicates extensions discovered across repositories.
     *
     * When the same extension appears more than once the highest [version]
     * wins, so a mirror pinned to an older build never shadows the newest
     * one. Ties keep the first occurrence, which preserves repository order.
     * Genuinely different extensions are always kept apart.
     */
    fun deduplicate(extensions: List<CloudStreamExtension>): List<CloudStreamExtension> {
        val best = LinkedHashMap<String, CloudStreamExtension>()
        extensions.forEach { extension ->
            val key = extensionKey(extension.plugin)
            val existing = best[key]
            if (existing == null) {
                best[key] = extension
                return@forEach
            }
            val incomingVersion = extension.plugin.version ?: -1
            val existingVersion = existing.plugin.version ?: -1
            if (incomingVersion > existingVersion) {
                best[key] = extension
            }
        }
        return best.values.toList()
    }
}

/**
 * The plugin's canonical name.
 *
 * The parser sets [CloudStreamPlugin.id] to the raw `internalName` when the
 * repository supplies one, falling back to the display name. Malformed rows
 * get a blank id, so fall back once more to keep identity non-empty.
 */
internal fun CloudStreamPlugin.internalNameOrId(): String =
    id.ifBlank { displayName }
