package com.nuvio.app.features.cloudstream

import android.content.Context
import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.AddonHttpClientProvider
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.util.zip.ZipException
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Request

/**
 * Android **full (sideload)** installer for CloudStream `.cs3` packages.
 *
 * Downloads reuse StreamBridge's existing OkHttp stack
 * ([AddonHttpClientProvider]) rather than introducing a second HTTP engine, so
 * timeouts, redirects, TLS and caching behave exactly as they do everywhere
 * else in the app.
 *
 * ## Security boundary
 *
 * Installing is the step *before* execution, and it enforces the first four
 * gates documented on [CloudStreamPlatformRuntime]: private storage, SHA-256
 * against the repository's published `fileHash`, archive-layout validation
 * (`manifest.json` + `classes.dex`, no Zip Slip), and a well-formed
 * `pluginClassName`. Bytes are held in memory and validated *before*
 * [CloudStreamPackageStorage.install] commits them, so an artifact that fails
 * any check never reaches the install directory and can never be loaded.
 *
 * An update that fails verification leaves the previously installed, working
 * package untouched.
 */
internal actual object CloudStreamPackageInstaller {

    private val log = Logger.withTag("CloudStreamInstall")
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Upper bound on a package download.
     *
     * Real `.cs3` files are tens to hundreds of kilobytes; this simply stops a
     * hostile or misconfigured server from streaming unbounded data into memory.
     */
    private const val MAX_PACKAGE_BYTES = 32L * 1024 * 1024

    /** Headroom required beyond the package itself, for staging plus commit. */
    private const val STORAGE_HEADROOM_MULTIPLIER = 3

    actual val supportsInstallation: Boolean = true

    private var appContext: Context? = null

    /** Wired from the same place the runtime is initialised. */
    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    actual fun isInstalled(plugin: CloudStreamPlugin): Boolean {
        val context = appContext ?: return false
        return CloudStreamPackageStorage.isInstalled(context, plugin)
    }

    /**
     * Version of the installed package, read from the `manifest.json` inside
     * the committed archive.
     *
     * Reading it back from disk rather than trusting a remembered number means
     * "update available" reflects what is genuinely installed, even across
     * reinstalls and app updates.
     */
    actual fun installedVersion(plugin: CloudStreamPlugin): Int? {
        val context = appContext ?: return null
        val file = CloudStreamPackageStorage.packageFile(context, plugin) ?: return null
        return runCatching {
            ZipFile(file).use { zip ->
                val entry = zip.getEntry(CloudStreamPackageValidation.MANIFEST_ENTRY)
                    ?: return@use null
                val text = zip.getInputStream(entry).use { it.readBytes().decodeToString() }
                json.decodeFromString<CloudStreamPluginArchiveManifest>(text).version
            }
        }.getOrNull()
    }

    actual suspend fun install(plugin: CloudStreamPlugin): CloudStreamInstallResult =
        withContext(Dispatchers.IO) {
            val context = appContext
                ?: return@withContext failure(
                    plugin,
                    CloudStreamInstallError.UNKNOWN,
                    "CloudStream installer is not initialised.",
                )

            val url = plugin.artifactUrl
            if (url.isNullOrBlank()) {
                return@withContext failure(
                    plugin,
                    CloudStreamInstallError.REPOSITORY_GONE,
                    "This extension no longer publishes a download URL.",
                )
            }

            val bytes = when (val download = download(url, plugin)) {
                is Downloaded.Ok -> download.bytes
                is Downloaded.Error -> return@withContext failure(
                    plugin,
                    download.error,
                    download.message,
                )
            }

            if (!hasRoomFor(context, bytes.size.toLong())) {
                return@withContext failure(
                    plugin,
                    CloudStreamInstallError.INSUFFICIENT_STORAGE,
                    "Not enough free space to install this extension.",
                )
            }

            // Validate the archive *before* committing anything to the install
            // directory. Staging happens in the cache directory so a rejected
            // package leaves no trace in app-private plugin storage.
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { byte -> "%02x".format(byte) }

            val staged = File(context.cacheDir, "cloudstream-verify-${plugin.id.hashCode()}.cs3")
            try {
                staged.writeBytes(bytes)
                when (val verdict = inspect(staged, plugin, digest)) {
                    is CloudStreamPackageValidation.Result.Invalid ->
                        return@withContext failure(
                            plugin,
                            verdict.reason.toInstallError(),
                            verdict.detail,
                        )

                    is CloudStreamPackageValidation.Result.Valid -> Unit
                }
            } catch (error: ZipException) {
                return@withContext failure(
                    plugin,
                    CloudStreamInstallError.CORRUPT_PACKAGE,
                    "The downloaded package is not a readable .cs3 archive.",
                )
            } catch (error: IOException) {
                return@withContext failure(
                    plugin,
                    CloudStreamInstallPolicy.classify(error),
                    error.message ?: "Could not stage the downloaded package.",
                )
            } finally {
                staged.delete()
            }

            // Verified. Commit it; storage re-checks the hash before writing.
            return@withContext runCatching {
                CloudStreamPackageStorage.install(context, plugin, bytes)
            }.fold(
                onSuccess = {
                    log.i { "Installed CloudStream extension '${plugin.id}' v${plugin.version}" }
                    CloudStreamInstallResult.Success(plugin.id, plugin.version)
                },
                onFailure = { error ->
                    failure(
                        plugin,
                        CloudStreamInstallPolicy.classify(error),
                        error.message ?: "Could not install this extension.",
                    )
                },
            )
        }

    actual fun remove(plugin: CloudStreamPlugin): Boolean {
        val context = appContext ?: return false
        return CloudStreamPackageStorage.remove(context, plugin)
    }

    /** Opens the staged archive and runs the pure validation rules over it. */
    private fun inspect(
        staged: File,
        plugin: CloudStreamPlugin,
        digest: String,
    ): CloudStreamPackageValidation.Result = ZipFile(staged).use { zip ->
        val entries = zip.entries().asSequence().map { it.name }.toList()
        val manifestEntry = zip.getEntry(CloudStreamPackageValidation.MANIFEST_ENTRY)
        val pluginClassName = manifestEntry?.let { entry ->
            runCatching {
                val text = zip.getInputStream(entry).use { it.readBytes().decodeToString() }
                json.decodeFromString<CloudStreamPluginArchiveManifest>(text).pluginClassName
            }.getOrNull()
        }
        CloudStreamPackageValidation.validate(
            entryNames = entries,
            manifestPluginClassName = pluginClassName,
            expectedHash = plugin.fileHash,
            actualSha256Hex = digest,
        )
    }

    private sealed interface Downloaded {
        data class Ok(val bytes: ByteArray) : Downloaded
        data class Error(val error: CloudStreamInstallError, val message: String) : Downloaded
    }

    /** Fetches the artifact, distinguishing every failure the user can act on. */
    private fun download(url: String, plugin: CloudStreamPlugin): Downloaded {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/octet-stream")
            .build()

        return try {
            AddonHttpClientProvider.get().newCall(request).execute().use { response ->
                if (response.code == 404 || response.code == 410) {
                    return Downloaded.Error(
                        CloudStreamInstallError.REPOSITORY_GONE,
                        "The repository no longer hosts this extension (HTTP ${response.code}).",
                    )
                }
                if (!response.isSuccessful) {
                    return Downloaded.Error(
                        CloudStreamInstallError.INVALID_RESPONSE,
                        "Download failed with HTTP ${response.code}.",
                    )
                }

                val body = response.body
                    ?: return Downloaded.Error(
                        CloudStreamInstallError.INVALID_RESPONSE,
                        "The server returned an empty response.",
                    )

                val declared = body.contentLength()
                if (declared > MAX_PACKAGE_BYTES) {
                    return Downloaded.Error(
                        CloudStreamInstallError.INVALID_RESPONSE,
                        "The package is larger than the ${MAX_PACKAGE_BYTES / (1024 * 1024)} MB limit.",
                    )
                }

                val bytes = body.byteStream().use { stream ->
                    stream.readAtMost(MAX_PACKAGE_BYTES)
                } ?: return Downloaded.Error(
                    CloudStreamInstallError.INVALID_RESPONSE,
                    "The package exceeded the maximum allowed size while downloading.",
                )

                if (bytes.isEmpty()) {
                    return Downloaded.Error(
                        CloudStreamInstallError.INVALID_RESPONSE,
                        "The server returned an empty package.",
                    )
                }
                // A truncated transfer is a corrupt package, not a mismatch.
                if (declared > 0 && bytes.size.toLong() != declared) {
                    return Downloaded.Error(
                        CloudStreamInstallError.CORRUPT_PACKAGE,
                        "The download was interrupted before it completed.",
                    )
                }
                // Repositories publish fileSize; a disagreement means we did not
                // get the artifact the repository described.
                plugin.fileSize?.let { expected ->
                    if (expected > 0 && bytes.size.toLong() != expected) {
                        return Downloaded.Error(
                            CloudStreamInstallError.CORRUPT_PACKAGE,
                            "Downloaded ${bytes.size} bytes but the repository lists $expected.",
                        )
                    }
                }
                Downloaded.Ok(bytes)
            }
        } catch (error: InterruptedIOException) {
            Downloaded.Error(
                CloudStreamInstallError.TIMEOUT,
                "The download timed out.",
            )
        } catch (error: IOException) {
            Downloaded.Error(
                CloudStreamInstallError.NETWORK_UNREACHABLE,
                error.message ?: "Could not reach the repository.",
            )
        }
    }

    /** Reads the stream, refusing to buffer more than [limit] bytes. */
    private fun java.io.InputStream.readAtMost(limit: Long): ByteArray? {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val read = read(chunk)
            if (read == -1) break
            total += read
            if (total > limit) return null
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    private fun hasRoomFor(context: Context, size: Long): Boolean =
        runCatching {
            context.filesDir.usableSpace > size * STORAGE_HEADROOM_MULTIPLIER
        }.getOrDefault(true)

    private fun failure(
        plugin: CloudStreamPlugin,
        error: CloudStreamInstallError,
        message: String,
    ): CloudStreamInstallResult.Failure {
        log.w { "CloudStream install of '${plugin.id}' failed ($error): $message" }
        return CloudStreamInstallResult.Failure(plugin.id, error, message)
    }

    private fun CloudStreamPackageValidation.Rejection.toInstallError(): CloudStreamInstallError =
        when (this) {
            CloudStreamPackageValidation.Rejection.HASH_MISMATCH ->
                CloudStreamInstallError.HASH_MISMATCH
            CloudStreamPackageValidation.Rejection.MISSING_MANIFEST ->
                CloudStreamInstallError.MALFORMED_MANIFEST
            CloudStreamPackageValidation.Rejection.MISSING_DEX ->
                CloudStreamInstallError.CORRUPT_PACKAGE
            CloudStreamPackageValidation.Rejection.UNSAFE_ARCHIVE_ENTRY ->
                CloudStreamInstallError.CORRUPT_PACKAGE
            CloudStreamPackageValidation.Rejection.MISSING_PLUGIN_CLASS,
            CloudStreamPackageValidation.Rejection.MALFORMED_PLUGIN_CLASS ->
                CloudStreamInstallError.INVALID_PLUGIN_CLASS
        }
}
