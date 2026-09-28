package com.nuvio.app.features.cloudstream

/**
 * Process-local identity store for CloudStream homepage items.
 *
 * CloudStream catalog URLs are provider-owned and are not Stremio ids. Keeping
 * the normalized item behind a stable UI id lets the existing detail and
 * player routes carry the exact provider URL without pretending it is a
 * Nuvio/Stremio addon id.
 */
internal object CloudStreamCatalogStore {
    private val items = linkedMapOf<String, CloudStreamCatalogItem>()

    fun register(item: CloudStreamCatalogItem): String {
        val id = "cloudstream:${stableKey(item).hashCode().toUInt().toString(16)}"
        items[id] = item
        return id
    }

    fun get(id: String): CloudStreamCatalogItem? = items[id]

    private fun stableKey(item: CloudStreamCatalogItem): String =
        listOf(item.addonId, item.providerName, item.url, item.title).joinToString("\u001f")
}
