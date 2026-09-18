package com.nuvio.app.features.cloudstream

/**
 * Platform-independent validation rules applied to a `.cs3` package **before**
 * any of its code is allowed near a class loader.
 *
 * These rules are deliberately pure so the security-critical decisions are unit
 * testable without an Android device. The platform executor performs the I/O
 * (download, hash, unzip) and delegates every *decision* to this object.
 *
 * ## Threat model
 *
 * A `.cs3` is an attacker-influenced ZIP fetched over the network from a
 * user-added repository. Before execution we require that:
 *
 *  - the artifact matches the publisher's declared SHA-256 when one exists,
 *  - the archive layout is the expected `manifest.json` + `classes.dex`,
 *  - no archive entry escapes the extraction directory (Zip Slip),
 *  - the manifest names exactly one plugin entry class,
 *  - that class name is well formed, so we never feed junk to `loadClass`.
 *
 * Failing any rule means the package is rejected, not "best effort" loaded.
 */
internal object CloudStreamPackageValidation {

    /** Entry that must exist in every valid `.cs3`. */
    const val MANIFEST_ENTRY = "manifest.json"

    /** Compiled provider code inside a `.cs3`. */
    const val DEX_ENTRY = "classes.dex"

    /** Why a package was refused. Surfaced to the user and to logs. */
    enum class Rejection {
        HASH_MISMATCH,
        MISSING_MANIFEST,
        MISSING_DEX,
        UNSAFE_ARCHIVE_ENTRY,
        MISSING_PLUGIN_CLASS,
        MALFORMED_PLUGIN_CLASS,
    }

    /** Outcome of validating a package. */
    sealed interface Result {
        data class Valid(val pluginClassName: String) : Result
        data class Invalid(val reason: Rejection, val detail: String) : Result
    }

    /**
     * Verifies a downloaded artifact against the hash published by the
     * repository.
     *
     * CloudStream publishes `fileHash` as `sha256-<hex>`; a bare hex digest is
     * also accepted because some repositories omit the prefix. Comparison is
     * case-insensitive.
     *
     * When the repository supplies **no** hash this returns `true`: an absent
     * hash is not a mismatch, and inventing one would be dishonest. The caller
     * is responsible for reflecting the weaker guarantee to the user.
     */
    fun matchesExpectedHash(expectedHash: String?, actualSha256Hex: String): Boolean {
        val expected = expectedHash?.trim()?.removePrefix("sha256-")?.removePrefix("SHA256-")
        if (expected.isNullOrBlank()) return true
        return expected.equals(actualSha256Hex.trim(), ignoreCase = true)
    }

    /** True when the repository actually pinned the artifact's contents. */
    fun hasVerifiableHash(expectedHash: String?): Boolean =
        !expectedHash?.trim()?.removePrefix("sha256-")?.removePrefix("SHA256-").isNullOrBlank()

    /**
     * Rejects archive entries that would escape the extraction directory.
     *
     * Guards against Zip Slip (`../`), absolute paths and Windows drive/UNC
     * style paths, any of which could otherwise overwrite files outside the
     * package directory.
     */
    fun isSafeArchiveEntry(entryName: String): Boolean {
        if (entryName.isBlank()) return false
        val normalised = entryName.replace('\\', '/')
        if (normalised.startsWith("/")) return false
        if (normalised.contains("..")) return false
        // "C:/..." or any scheme-like prefix.
        if (normalised.length >= 2 && normalised[1] == ':') return false
        return true
    }

    /**
     * A plugin entry class must look like a real JVM binary name.
     *
     * This stops obviously malformed manifest values from reaching
     * `loadClass`, and keeps the loaded symbol to a single named class rather
     * than anything path- or descriptor-shaped.
     */
    fun isValidPluginClassName(className: String?): Boolean {
        val name = className?.trim()
        if (name.isNullOrEmpty()) return false
        if (name.startsWith('.') || name.endsWith('.')) return false
        if (name.contains("..")) return false
        if (name.any { it == '/' || it == '\\' || it == ';' || it.isWhitespace() }) return false
        return name.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '$' }
    }

    /**
     * Applies every structural rule to one package.
     *
     * @param entryNames every entry present in the archive
     * @param manifestPluginClassName `pluginClassName` read from `manifest.json`
     * @param expectedHash `fileHash` from the repository, if published
     * @param actualSha256Hex digest actually computed over the downloaded bytes
     */
    fun validate(
        entryNames: List<String>,
        manifestPluginClassName: String?,
        expectedHash: String?,
        actualSha256Hex: String,
    ): Result {
        if (!matchesExpectedHash(expectedHash, actualSha256Hex)) {
            return Result.Invalid(
                Rejection.HASH_MISMATCH,
                "Package SHA-256 does not match the hash published by the repository.",
            )
        }
        entryNames.firstOrNull { !isSafeArchiveEntry(it) }?.let { unsafe ->
            return Result.Invalid(
                Rejection.UNSAFE_ARCHIVE_ENTRY,
                "Archive contains an unsafe entry path: $unsafe",
            )
        }
        if (entryNames.none { it == MANIFEST_ENTRY }) {
            return Result.Invalid(Rejection.MISSING_MANIFEST, "Package has no $MANIFEST_ENTRY.")
        }
        if (entryNames.none { it == DEX_ENTRY }) {
            return Result.Invalid(Rejection.MISSING_DEX, "Package has no $DEX_ENTRY.")
        }
        if (manifestPluginClassName.isNullOrBlank()) {
            return Result.Invalid(
                Rejection.MISSING_PLUGIN_CLASS,
                "Manifest does not declare pluginClassName.",
            )
        }
        if (!isValidPluginClassName(manifestPluginClassName)) {
            return Result.Invalid(
                Rejection.MALFORMED_PLUGIN_CLASS,
                "Manifest pluginClassName is not a valid class name: $manifestPluginClassName",
            )
        }
        return Result.Valid(manifestPluginClassName.trim())
    }
}
