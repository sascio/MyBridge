package com.streambridge.app.addon.plugin.compat

import com.streambridge.app.addon.plugin.NuvioPluginException
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.brotli.dec.BrotliInputStream
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * The sandbox's dedicated HTTP engine — the ONLY network path provider
 * code has.
 *
 * One engine instance exists per provider execution, which also scopes
 * its cookie jar to that single call: providers can neither share
 * cookies with each other nor persist them across calls.
 *
 * Beyond the original plain GET/POST it provides what real-world
 * scrapers need:
 *  - GET / HEAD / POST / PUT / PATCH / DELETE / OPTIONS, with text AND
 *    binary (base64) bodies;
 *  - automatic redirect following, with the final URL reported back so
 *    scraped relative links can be resolved against the real location;
 *  - cookies within the call (server Set-Cookie -> later requests);
 *  - transparent gzip / deflate / brotli decoding — a fetch() body is
 *    always delivered decoded, exactly like a browser;
 *  - per-request timeouts (provider-controlled, clamped to sane bounds)
 *    as controlled, catchable rejections;
 *  - a hard response size cap that applies to the DECODED body, so a
 *    compressed "decompression bomb" is capped too;
 *  - binary responses transported as base64 so byte-exact data (images,
 *    encrypted blobs, packed players) survives the sandbox boundary.
 *
 * The whole response still travels as ONE JSON envelope string: binding
 * returns must be primitives to convert reliably across the JS bridge.
 */
class ProviderHttpEngine(
    baseClient: OkHttpClient,
    private val defaultTimeoutMs: Long,
    private val maxResponseBytes: Int
) {
    private val cookieJar = ProviderCookieJar()

    /**
     * Derived client: shares the base client's connection pool and
     * dispatcher, but owns the per-execution cookie jar and its own
     * socket timeouts so plugin requests are bounded by the engine's
     * limits, not by whatever the host app client was configured with.
     */
    private val client = baseClient.newBuilder()
        .cookieJar(cookieJar)
        .connectTimeout(MAX_REQUEST_TIMEOUT_MS, TimeUnit.SECONDS)
        .readTimeout(MAX_REQUEST_TIMEOUT_MS, TimeUnit.SECONDS)
        .writeTimeout(MAX_REQUEST_TIMEOUT_MS, TimeUnit.SECONDS)
        .build()

    /**
     * Performs one provider-requested HTTP call and returns the JSON
     * envelope. Any failure is a [NuvioPluginException] — which the
     * runtime turns into a rejected promise the provider can catch,
     * never a crash.
     */
    fun perform(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?,
        bodyBase64: Boolean,
        timeoutMs: Long?
    ): String {
        val methodUpper = method.uppercase()
        if (methodUpper !in SUPPORTED_METHODS) {
            throw NuvioPluginException("fetch() does not support the $methodUpper method")
        }
        val httpUrl = validateFetchUrl(url)

        val builder = Request.Builder()
            .url(httpUrl)
            .header("User-Agent", PROVIDER_UA)
        headers.forEach { (name, value) ->
            try {
                builder.header(name, value)
            } catch (e: IllegalArgumentException) {
                throw NuvioPluginException(
                    "fetch() received an invalid header: " + (e.message?.take(80) ?: name)
                )
            }
        }

        if (methodUpper == "GET" || methodUpper == "HEAD") {
            // The fetch() spec rejects bodies on GET/HEAD; so do we.
            if (body != null) {
                throw NuvioPluginException("fetch() cannot send a body with a $methodUpper request")
            }
        } else {
            val contentType = headers.entries
                .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value
                ?: "application/x-www-form-urlencoded"
            val mediaType = runCatching { contentType.toMediaType() }
                .getOrDefault("application/x-www-form-urlencoded".toMediaType())
            val bodyBytes: ByteArray? = when {
                bodyBase64 -> try {
                    Base64.getMimeDecoder().decode(body ?: "")
                } catch (_: IllegalArgumentException) {
                    throw NuvioPluginException("fetch() received an invalid base64 body")
                }
                body != null -> body.toByteArray(Charsets.UTF_8)
                else -> null
            }
            val requestBody = when {
                bodyBytes != null -> bodyBytes.toRequestBody(mediaType)
                // DELETE/OPTIONS may legally have no body at all.
                methodUpper == "DELETE" || methodUpper == "OPTIONS" -> null
                // POST/PUT/PATCH with no body: an empty body keeps the
                // request valid and the content type explicit.
                else -> ByteArray(0).toRequestBody(mediaType)
            }
            builder.method(methodUpper, requestBody)
        }

        val effectiveTimeoutMs = when {
            timeoutMs != null -> timeoutMs.coerceIn(MIN_REQUEST_TIMEOUT_MS, MAX_REQUEST_TIMEOUT_MS)
            else -> defaultTimeoutMs
        }
        val call = client.newCall(builder.build())
        // The call timeout spans the entire call — connect, redirects
        // and the full body — so no request can outlive its budget.
        call.timeout().timeout(effectiveTimeoutMs, TimeUnit.MILLISECONDS)

        val response = try {
            call.execute()
        } catch (e: java.io.InterruptedIOException) {
            throw controlledTimeout(httpUrl, effectiveTimeoutMs)
        } catch (e: IOException) {
            throw controlledNetworkError(e)
        }

        return try {
            response.use { r -> readEnvelope(r) }
        } catch (e: java.io.InterruptedIOException) {
            // Timeouts can fire while the body is still streaming in.
            throw controlledTimeout(httpUrl, effectiveTimeoutMs)
        } catch (e: IOException) {
            throw controlledNetworkError(e)
        }
    }

    private fun controlledTimeout(url: HttpUrl, timeoutMs: Long) =
        NuvioPluginException("Request to ${url.host} timed out after $timeoutMs ms")

    private fun controlledNetworkError(e: IOException) =
        NuvioPluginException(
            "Network error: " + (e.message?.take(120) ?: e.javaClass.simpleName)
        )

    /**
     * Reads, decodes and envelopes the (already successful) response.
     */
    private fun readEnvelope(r: Response): String {
        val raw = r.body?.byteStream()?.use { it.readAtMost(maxResponseBytes) }
            ?: ByteArray(0)
        val rawTruncated = raw.size > maxResponseBytes
        val capped = if (rawTruncated) raw.copyOf(maxResponseBytes) else raw

        // A truncated compressed stream cannot be decoded honestly:
        // deliver the capped bytes as binary instead of inventing data.
        val encodings = if (rawTruncated) {
            emptyList()
        } else {
            r.header("Content-Encoding")
                ?.lowercase()
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                // Content-Encoding lists encodings in the order they
                // were applied; decoding runs in reverse.
                ?.reversed()
                ?: emptyList()
        }
        var decoded = if (encodings.isEmpty()) capped else decompress(capped, encodings)
        val decodedTruncated = decoded.size > maxResponseBytes
        if (decodedTruncated) decoded = decoded.copyOf(maxResponseBytes)
        val truncated = rawTruncated || decodedTruncated

        val text = decodeStrictUtf8(decoded)
        return buildEnvelope(r, decoded, truncated, text == null, text)
    }

    // -----------------------------------------------------------------
    // Envelope
    // -----------------------------------------------------------------

    private fun buildEnvelope(
        response: Response,
        body: ByteArray,
        truncated: Boolean,
        binary: Boolean,
        strictText: String?
    ): String = buildJsonObject {
        put("ok", response.isSuccessful)
        put("status", response.code)
        put("statusText", response.message)
        put("url", response.request.url.toString())
        put("redirected", response.priorResponse != null)
        put("truncated", truncated)
        put("binary", binary)
        put("headers", buildJsonObject {
            response.headers.toMultimap().forEach { (name, values) ->
                val lower = name.lowercase()
                // The decoded body no longer matches these two; fetch()
                // semantics hide transport encoding from JS.
                if (lower != "content-encoding" && lower != "content-length") {
                    put(lower, values.firstOrNull() ?: "")
                }
            }
        })
        put("setCookie", buildJsonArray {
            response.headers.values("set-cookie").forEach { add(it) }
        })
        if (binary) {
            put("bodyBase64", Base64.getEncoder().encodeToString(body))
        } else {
            put("bodyText", (strictText ?: "") + if (truncated) TRUNCATION_NOTE else "")
        }
    }.toString()

    // -----------------------------------------------------------------
    // URL validation (unchanged security posture)
    // -----------------------------------------------------------------

    private fun validateFetchUrl(raw: String): HttpUrl {
        val url = raw.trim()
        if (url.length > 2048 || url.contains(' ') || url.contains('\n') || url.contains('\r')) {
            throw NuvioPluginException("Provider requested an invalid URL")
        }
        // Legacy guard first so the message stays specific (and stable
        // for providers that catch and inspect it).
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw NuvioPluginException("Plugin fetch supports http(s) URLs only")
        }
        val httpUrl = url.toHttpUrlOrNull()
            ?: throw NuvioPluginException("Provider requested an invalid URL")
        return httpUrl
    }

    // -----------------------------------------------------------------
    // Content decoding
    // -----------------------------------------------------------------

    /**
     * Decodes the applied encodings in reverse order. Any failure (or
     * unknown encoding) falls back to the bytes as received — the body
     * is then reported as binary, never fabricated.
     */
    private fun decompress(data: ByteArray, encodingsReversed: List<String>): ByteArray {
        var current = data
        for (encoding in encodingsReversed) {
            current = when (encoding) {
                "gzip", "x-gzip" -> gunzip(current)
                "deflate" -> inflate(current)
                "br" -> unbr(current)
                "identity" -> current
                // Unknown encodings pass through untouched.
                else -> current
            }
        }
        return current
    }

    private fun gunzip(data: ByteArray): ByteArray = try {
        GZIPInputStream(ByteArrayInputStream(data)).use { it.readAtMost(maxResponseBytes) }
    } catch (_: Exception) {
        data
    }

    private fun inflate(data: ByteArray): ByteArray = try {
        // "deflate" is officially zlib-wrapped (RFC 1950)…
        InflaterInputStream(ByteArrayInputStream(data)).use { it.readAtMost(maxResponseBytes) }
    } catch (_: Exception) {
        try {
            // …but some servers send raw deflate (RFC 1951); decode that
            // as a fallback so either variant works.
            InflaterInputStream(ByteArrayInputStream(data), Inflater(true))
                .use { it.readAtMost(maxResponseBytes) }
        } catch (_: Exception) {
            data
        }
    }

    private fun unbr(data: ByteArray): ByteArray = try {
        BrotliInputStream(ByteArrayInputStream(data)).use { it.readAtMost(maxResponseBytes) }
    } catch (_: Exception) {
        data
    }

    /** Strict UTF-8 decode: null means the payload is binary. */
    private fun decodeStrictUtf8(bytes: ByteArray): String? = try {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        decoder.decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        null
    }

    companion object {
        internal const val PROVIDER_UA = "Mozilla/5.0 (Linux; Android 14) StreamBridge/1.0"

        private val SUPPORTED_METHODS =
            setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")

        /** Bounds for provider-requested per-request timeouts. */
        private const val MIN_REQUEST_TIMEOUT_MS = 1_000L
        private const val MAX_REQUEST_TIMEOUT_MS = 30_000L

        private const val TRUNCATION_NOTE = "\n<!-- response truncated by the plugin sandbox -->"
    }
}

/**
 * In-memory cookie jar scoped to ONE provider execution. OkHttp's
 * [Cookie.matches] enforces domain, path, expiry, secure and hostOnly.
 */
private class ProviderCookieJar : CookieJar {
    private val cookies = mutableListOf<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        this.cookies.removeAll { existing ->
            cookies.any { fresh ->
                fresh.name == existing.name &&
                    fresh.domain == existing.domain &&
                    fresh.path == existing.path
            }
        }
        this.cookies.addAll(cookies)
        // A server can flood the jar; keep it bounded.
        while (this.cookies.size > MAX_COOKIES) {
            this.cookies.removeAt(0)
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        cookies.filter { it.matches(url) }

    private companion object {
        const val MAX_COOKIES = 200
    }
}

/** Reads at most [max] bytes (bounds memory for untrusted bodies). */
private fun InputStream.readAtMost(max: Int): ByteArray {
    val buffer = ByteArray(minOf(max + 1, 1 shl 16))
    val out = java.io.ByteArrayOutputStream()
    while (true) {
        val read = this.read(buffer)
        if (read < 0) break
        out.write(buffer, 0, read)
        if (out.size() > max) return out.toByteArray()
    }
    return out.toByteArray()
}
