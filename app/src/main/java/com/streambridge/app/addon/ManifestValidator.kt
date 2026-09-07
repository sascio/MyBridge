package com.streambridge.app.addon

import com.streambridge.app.addon.model.AddonManifest

/**
 * Structural validation of an addon manifest before it is installed.
 * Pure Kotlin, unit tested.
 */
object ManifestValidator {

    private val knownResources = setOf("catalog", "meta", "stream", "subtitles")
    private val knownTypes = setOf("movie", "series", "channel", "tv", "anime", "other")

    sealed class Result {
        object Valid : Result()
        data class Invalid(val issues: List<String>) : Result()
    }

    fun validate(manifest: AddonManifest): Result {
        val issues = buildList {
            if (manifest.id.isBlank()) {
                add("Manifest is missing an addon id")
            }
            if (manifest.id.isNotBlank() && !manifest.id.matches(Regex("[A-Za-z0-9._\\-/]{1,256}"))) {
                add("Addon id contains unexpected characters")
            }
            if (manifest.name.isBlank()) {
                add("Manifest is missing a name")
            }
            if (manifest.version.isBlank()) {
                add("Manifest is missing a version")
            }
            if (manifest.types.isEmpty()) {
                add("Manifest declares no content types")
            } else {
                val unknown = manifest.types.filter { it !in knownTypes }
                if (manifest.types.size == unknown.size) {
                    add("Manifest declares no recognized content types")
                }
            }
            if (manifest.resources.isEmpty()) {
                add("Manifest declares no resources")
            }
            val usefulResources = manifest.resources.intersect(knownResources)
            if (manifest.resources.isNotEmpty() && usefulResources.isEmpty()) {
                add("Manifest provides none of the catalog/meta/stream resources")
            }
            if (manifest.resources.contains("catalog") && manifest.catalogs.isEmpty()) {
                add("Manifest declares the catalog resource but exposes no catalogs")
            }
            manifest.catalogs.forEachIndexed { index, catalog ->
                if (catalog.type.isBlank()) add("Catalog #${index + 1} is missing a type")
                if (catalog.id.isBlank()) add("Catalog #${index + 1} is missing an id")
            }
        }
        return if (issues.isEmpty()) Result.Valid else Result.Invalid(issues)
    }

    /** True when the manifest exposes at least one catalog the app can browse. */
    fun hasBrowsableCatalogs(manifest: AddonManifest): Boolean {
        if (!manifest.resources.contains("catalog")) return false
        return manifest.catalogs.any { it.type.isNotBlank() && it.id.isNotBlank() }
    }
}
