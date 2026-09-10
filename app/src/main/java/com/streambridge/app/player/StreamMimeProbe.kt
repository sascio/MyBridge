package com.streambridge.app.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Best-effort probe of a stream URL's real Content-Type, used only
 * when the URL itself carries no container evidence (no extension, no
 * manifest token). Mirrors the reference behavior: HEAD first, then a
 * GET whose response headers are read without consuming the body.
 *
 * The probe sends THIS stream's own request context (its headers) so
 * the server answers with the honest content type rather than a
 * bot-check page. Any failure simply yields null — the player then
 * falls back to byte sniffing, exactly as before.
 */
object StreamMimeProbe {

    private val PROBE_TIMEOUT_MS = 4_000L

    /** Resolves a canonical stream MIME from the server, or null. */
    suspend fun probe(
        client: OkHttpClient,
        url: String,
        headers: Map<String, String>
    ): String? = withContext(Dispatchers.IO) {
        val probeClient = client.newBuilder()
            .callTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
        for (method in listOf("HEAD", "GET")) {
            val contentType = runCatching {
                val request = Request.Builder()
                    .url(url)
                    .method(method, null)
                    .header("User-Agent", PlaybackUserAgent.DEFAULT)
                    .apply { headers.forEach { (name, value) -> header(name, value) } }
                    .build()
                probeClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@runCatching null
                    val fromType = StreamMimeTypes.fromContentType(
                        response.header("Content-Type")
                    )
                    fromType ?: StreamMimeTypes.fromContentDisposition(
                        response.header("Content-Disposition")
                    )
                }
            }.getOrNull() ?: continue
            // Header-only answers: some servers answer HEAD with no
            // content type — the GET pass then gets its chance.
            if (contentType != null) return@withContext contentType
        }
        null
    }
}
