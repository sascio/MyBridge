package com.nuvio.app.features.cloudstream

import android.content.Context
import co.touchlab.kermit.Logger
import java.io.File
import java.security.MessageDigest

/**
 * App-private storage for downloaded `.cs3` packages.
 *
 * Packages live under `filesDir/cloudstream-packages/`, which is private to
 * StreamBridge: they are never written to external/shared storage where another
 * app could swap a verified artifact for a malicious one between verification
 * and loading.
 *
 * Downloads are staged to a temporary file and only committed once the bytes
 * hash as expected, so a partial or tampered download can never become the
 * installed package.
 */
internal object CloudStreamPackageStorage {

    private val log = Logger.withTag("CloudStreamStore")
    private const val DIRECTORY = "cloudstream-packages"

    private fun directory(context: Context): File =
        File(context.filesDir, DIRECTORY).apply { if (!exists()) mkdirs() }

    /** Stable on-disk name; the plugin id is sanitised so it cannot traverse paths. */
    private fun fileName(plugin: CloudStreamPlugin): String {
        val safe = plugin.id.map { char ->
            if (char.isLetterOrDigit() || char == '-' || char == '_') char else '_'
        }.joinToString("")
        return "$safe.cs3"
    }

    /** The installed package for [plugin], or null when it is not installed. */
    fun packageFile(context: Context, plugin: CloudStreamPlugin): File? =
        File(directory(context), fileName(plugin)).takeIf { it.isFile }

    fun isInstalled(context: Context, plugin: CloudStreamPlugin): Boolean =
        packageFile(context, plugin) != null

    /**
     * Commits already-downloaded [bytes] as the installed package.
     *
     * The hash is checked **before** the file is committed, so an artifact that
     * does not match the repository's published `fileHash` never reaches the
     * install directory at all.
     *
     * @return the committed read-only file
     * @throws IllegalStateException when the artifact fails verification
     */
    fun install(context: Context, plugin: CloudStreamPlugin, bytes: ByteArray): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

        check(CloudStreamPackageValidation.matchesExpectedHash(plugin.fileHash, digest)) {
            "CloudStream package '${plugin.id}' failed SHA-256 verification; refusing to install."
        }

        val target = File(directory(context), fileName(plugin))
        val staging = File(directory(context), "${fileName(plugin)}.part")
        runCatching {
            staging.writeBytes(bytes)
            if (target.exists()) {
                // A previously installed package is read-only; restore write
                // permission so the replacement can be committed atomically.
                target.setWritable(true)
                target.delete()
            }
            check(staging.renameTo(target)) { "Could not commit CloudStream package" }
            target.setReadOnly()
        }.onFailure {
            staging.delete()
            throw it
        }

        log.i { "Installed CloudStream package '${plugin.id}' (${bytes.size} bytes, sha256=$digest)" }
        return target
    }

    /** Removes an installed package, clearing the read-only flag first. */
    fun remove(context: Context, plugin: CloudStreamPlugin): Boolean {
        val file = File(directory(context), fileName(plugin))
        if (!file.exists()) return false
        file.setWritable(true)
        return file.delete()
    }
}
