package com.streambridge.app

import com.streambridge.app.addon.plugin.NuvioPluginRuntime
import com.streambridge.app.addon.plugin.NuvioStreamRequest
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import java.util.zip.DeflaterOutputStream

/**
 * The provider HTTP engine (ProviderHttpEngine), exercised exactly as
 * production uses it: untrusted provider JS calling fetch() inside the
 * sandboxed QuickJS runtime, against a local MockWebServer.
 *
 * Covers what real-world scrapers need beyond plain GET/POST text:
 * redirects, cookies, request bodies, binary bodies both ways,
 * transparent gzip/deflate/brotli decoding, timeouts and size caps.
 */
class ProviderHttpEngineTest {

    private lateinit var server: MockWebServer
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    private val runtime = NuvioPluginRuntime()

    /** base url of the mock server, interpolated into provider code. */
    private val base: String get() = server.url("/").toString().trimEnd('/')

    private val request = NuvioStreamRequest(
        tmdbId = "550",
        mediaType = "movie",
        season = null,
        episode = null
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** Runs [code] as a provider and maps the first stream's title. */
    private fun firstTitle(code: String): String {
        val streams = runBlocking {
            runtime.execute(http, "https://unused/code.js", code, request)
        }
        assertTrue("Provider returned no streams", streams.isNotEmpty())
        return streams[0].title
    }

    @Test
    fun `redirects are followed and the final url is reported`() {
        server.enqueue(
            MockResponse().setResponseCode(301).addHeader("Location", "/final")
        )
        server.enqueue(MockResponse().setBody("""{"body":"FINAL"}"""))

        val title = firstTitle(
            """
            async function getStreams(tmdbId, mediaType, season, episode) {
              var response = await fetch("$base/hop");
              var data = await response.json();
              var urlOk = response.url.slice(-6) === "/final";
              return [{ name: "T", title: response.redirected + "|" + urlOk + "|" + data.body,
                        url: "https://cdn.example.com/v.mp4", quality: "test" }];
            }
            module.exports = { getStreams: getStreams };
            """.trimIndent()
        )

        assertEquals("true|true|FINAL", title)
        // Both hops hit the server.
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `set-cookie from one request is sent on the next request`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return if (request.path == "/login") {
                    MockResponse()
                        .setResponseCode(200)
                        .addHeader("Set-Cookie", "sid=abc123; Path=/")
                        .setBody("{}")
                } else {
                    val cookie = request.getHeader("Cookie") ?: "none"
                    MockResponse().setBody("{\"cookie\":\"$cookie\"}")
                }
            }
        }

        val title = firstTitle(
            """
            async function getStreams(tmdbId, mediaType, season, episode) {
              var response = await fetch("$base/login");
              var setCookie = response.headers.get("set-cookie");
              var data = await fetch("$base/data").then(function(r) { return r.json(); });
              return [{ name: "T", title: setCookie + "|" + data.cookie,
                        url: "https://cdn.example.com/v.mp4", quality: "test" }];
            }
            module.exports = { getStreams: getStreams };
            """.trimIndent()
        )

        // The provider saw the Set-Cookie header AND the jar replayed it
        // on the second request automatically.
        assertEquals("sid=abc123; Path=/|sid=abc123", title)
    }

    @Test
    fun `post sends the exact body and content type`() {
        server.enqueue(MockResponse().setBody("""{"accepted":true}"""))

        val title = firstTitle(
            """
            async function getStreams(tmdbId, mediaType, season, episode) {
              var response = await fetch("$base/submit", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ tmdb: tmdbId, kind: "movie" })
              });
              var data = await response.json();
              return [{ name: "T", title: data.accepted ? "OK" : "NO",
                        url: "https://cdn.example.com/v.mp4", quality: "test" }];
            }
            module.exports = { getStreams: getStreams };
            """.trimIndent()
        )

        assertEquals("OK", title)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("application/json", recorded.getHeader("Content-Type"))
        assertEquals("""{"tmdb":"550","kind":"movie"}""", recorded.body.readUtf8())
    }

    @Test
    fun `put and delete requests reach the server`() {
        server.enqueue(MockResponse().setBody("{}"))
        server.enqueue(MockResponse().setBody("{}"))

        val title = firstTitle(
            """
            async function getStreams(tmdbId, mediaType, season, episode) {
              await fetch("$base/item/1", { method: "PUT", body: "state=paused" });
              await fetch("$base/item/2", { method: "DELETE" });
              return [{ name: "T", title: "DONE",
                        url: "https://cdn.example.com/v.mp4", quality: "test" }];
            }
            module.exports = { getStreams: getStreams };
            """.trimIndent()
        )

        assertEquals("DONE", title)
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("state=paused", put.body.readUtf8())
        val delete = server.takeRequest()
        assertEquals("DELETE", delete.method)
    }

    @Test
    fun `binary responses survive the sandbox boundary byte for byte`() {
        // 0xFF and lone 0xFE are invalid UTF-8: the engine must deliver
        // these as a binary (base64) body, not as mangled text.
        val bytes = byteArrayOf(0, 1, 0xFF.toByte(), 0xFE.toByte(), 0x7F, 0x80.toByte())
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/octet-stream")
                .setBody(Buffer().write(bytes))
        )

        val title = firstTitle(
            """
            async function getStreams(tmdbId, mediaType, season, episode) {
              var response = await fetch("$base/blob");
              var buffer = await response.arrayBuffer();
              var u8 = new Uint8Array(buffer);
              var parts = [];
              for (var i = 0; i < u8.length; i++) { parts.push(String(u8[i])); }
              return [{ name: "T", title: parts.join(","),
                        url: "https://cdn.example.com/v.mp4", quality: "test" }];
            }
            module.exports = { getStreams: getStreams };
            """.trimIndent()
        )

        assertEquals("0,1,255,254,127,128", title)
    }

    @Test
    fun `binary request bodies are sent byte for byte`() {
        server.enqueue(MockResponse().setBody("{}"))

        val title = firstTitle(
            """
            async function getStreams(tmdbId, mediaType, season, episode) {
              var payload = new Uint8Array([0, 1, 2, 250, 255]);
              await fetch("$base/upload", {
                method: "POST",
                headers: { "Content-Type": "application/octet-stream" },
                body: payload
              });
              return [{ name: "T", title: "SENT",
                        url: "https://cdn.example.com/v.mp4", quality: "test" }];
            }
            module.exports = { getStreams: getStreams };
            """.trimIndent()
        )

        assertEquals("SENT", title)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        val sent = recorded.body.readByteArray()
        assertTrue(byteArrayOf(0, 1, 2, 250.toByte(), 255.toByte()).contentEquals(sent))
    }

    @Test
    fun `gzip responses are transparently decompressed`() {
        val payload = "GZIP_DECODED_" + "x".repeat(500)
        val gzip = ByteArrayOutputStream().let { out ->
            GZIPOutputStream(out).use { it.write(payload.toByteArray()) }
            out.toByteArray()
        }
        // The provider sets its own Accept-Encoding, which disables
        // OkHttp's transparent gzip and forces OUR decoder to run.
        server.enqueue(
            MockResponse()
                .setHeader("Content-Encoding", "gzip")
                .setBody(Buffer().write(gzip))
        )

        val title = firstTitle(
            """
            async function getStreams(tmdbId, mediaType, season, episode) {
              var response = await fetch("$base/gz", { headers: { "Accept-Encoding": "gzip" } });
              var text = await response.text();
              return [{ name: "T", title: text.slice(0, 12) + "|" + text.length,
                        url: "https://cdn.example.com/v.mp4", quality: "test" }];
            }
            module.exports = { getStreams: getStreams };
            """.trimIndent()
        )

        assertEquals("GZIP_DECODED|" + payload.length, title)
    }

    @Test
    fun `deflate responses are transparently decompressed`() {
        val payload = "DEFLATE_DECODED_OK"
        val deflated = ByteArrayOutputStream().let { out ->
            DeflaterOutputStream(out).use { it.write(payload.toByteArray()) }
            out.toByteArray()
        }
        server.enqueue(
            MockResponse()
                .setHeader("Content-Encoding", "deflate")
                .setBody(Buffer().write(deflated))
        )

        val title = firstTitle(
            """
            async function getStreams(tmdbId, mediaType, season, episode) {
              var text = await fetch("$base/deflate").then(function(r) { return r.text(); });
              return [{ name: "T", title: text,
                        url: "https://cdn.example.com/v.mp4", quality: "test" }];
            }
            module.exports = { getStreams: getStreams };
            """.trimIndent()
        )

        assertEquals("DEFLATE_DECODED_OK", title)
    }

    @Test
    fun `brotli responses are transparently decompressed`() {
        // node: zlib.brotliCompressSync("BROTLI_DECODED_OK_42")
        val brotli = Base64.getDecoder().decode("iwmAQlJPVExJX0RFQ09ERURfT0tfNDID")
        server.enqueue(
            MockResponse()
                .setHeader("Content-Encoding", "br")
                .setBody(Buffer().write(brotli))
        )

        val title = firstTitle(
            """
            async function getStreams(tmdbId, mediaType, season, episode) {
              var text = await fetch("$base/br").then(function(r) { return r.text(); });
              return [{ name: "T", title: text,
                        url: "https://cdn.example.com/v.mp4", quality: "test" }];
            }
            module.exports = { getStreams: getStreams };
            """.trimIndent()
        )

        assertEquals("BROTLI_DECODED_OK_42", title)
    }

    @Test
    fun `per-request timeouts reject with a catchable controlled error`() {
        val tight = NuvioPluginRuntime(fetchTimeoutMs = 300L)
        server.enqueue(
            MockResponse()
                .setBody("slow")
                .setBodyDelay(3, TimeUnit.SECONDS)
        )

        val startedAt = System.currentTimeMillis()
        val streams = runBlocking {
            tight.execute(http, "https://unused/code.js", """
                async function getStreams(tmdbId, mediaType, season, episode) {
                  try {
                    await fetch("$base/slow");
                    return [{ name: "T", title: "NO_TIMEOUT",
                              url: "https://cdn.example.com/v.mp4", quality: "test" }];
                  } catch (e) {
                    return [{ name: "T", title: "caught:" + String(e.message),
                              url: "https://cdn.example.com/v.mp4", quality: "test" }];
                  }
                }
                module.exports = { getStreams: getStreams };
            """.trimIndent(), request)
        }
        val elapsedMs = System.currentTimeMillis() - startedAt

        assertEquals(1, streams.size)
        // The rejection must be controlled (a sandbox-shaped message,
        // not a raw stack) and catchable by provider code.
        assertTrue(
            "Expected a controlled rejection, got: " + streams[0].title,
            streams[0].title.startsWith("caught:") &&
                (streams[0].title.contains("timed out") ||
                    streams[0].title.contains("Network error"))
        )
        // The 3-second body never arrived: the request budget of 300ms
        // must have cut it short well before that.
        assertTrue("Request outlived its timeout budget: ${elapsedMs}ms", elapsedMs < 2500)
    }

    @Test
    fun `oversized binary responses are capped, never oom`() {
        // 64-byte cap instead of the 10 MB production ceiling. The
        // fixture keeps an invalid-UTF-8 byte (0xFF) inside every
        // 64-byte block, so the CAPPED prefix is genuinely binary —
        // otherwise a text body would gain the truncation marker and
        // grow past the cap in the byte count.
        val tight = NuvioPluginRuntime(maxResponseBytes = 64)
        val big = ByteArray(1024) { if (it % 64 == 63) 0xFF.toByte() else (it % 64).toByte() }
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/octet-stream")
                .setBody(Buffer().write(big))
        )

        val streams = runBlocking {
            tight.execute(http, "https://unused/code.js", """
                async function getStreams(tmdbId, mediaType, season, episode) {
                  var response = await fetch("$base/big");
                  var buffer = await response.arrayBuffer();
                  return [{ name: "T", title: String(buffer.byteLength),
                            url: "https://cdn.example.com/v.mp4", quality: "test" }];
                }
                module.exports = { getStreams: getStreams };
            """.trimIndent(), request)
        }

        assertEquals(1, streams.size)
        assertEquals("64", streams[0].title)
    }

    @Test
    fun `http errors report ok false and the status code`() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("gone"))

        val title = firstTitle(
            """
            async function getStreams(tmdbId, mediaType, season, episode) {
              var response = await fetch("$base/missing");
              return [{ name: "T", title: response.ok + "/" + response.status,
                        url: "https://cdn.example.com/v.mp4", quality: "test" }];
            }
            module.exports = { getStreams: getStreams };
            """.trimIndent()
        )

        assertEquals("false/404", title)
        assertFalse(title.startsWith("true"))
    }

    @Test
    fun `get requests cannot smuggle a body`() {
        server.enqueue(MockResponse().setBody("{}"))
        val streams = runBlocking {
            runtime.execute(http, "https://unused/code.js", """
                async function getStreams(tmdbId, mediaType, season, episode) {
                  try {
                    await fetch("$base/nope", { method: "GET", body: "x" });
                    return [{ name: "T", title: "ALLOWED",
                              url: "https://cdn.example.com/v.mp4", quality: "test" }];
                  } catch (e) {
                    return [{ name: "T", title: "rejected",
                              url: "https://cdn.example.com/v.mp4", quality: "test" }];
                  }
                }
                module.exports = { getStreams: getStreams };
            """.trimIndent(), request)
        }
        assertEquals(1, streams.size)
        assertEquals("rejected", streams[0].title)
    }

    @Test
    fun `unsupported schemes and methods are controlled errors`() {
        val streams = runBlocking {
            runtime.execute(http, "https://unused/code.js", """
                async function getStreams(tmdbId, mediaType, season, episode) {
                  var results = [];
                  try { await fetch("ftp://example.com/x"); results.push("ftp-ok"); }
                  catch (e) { results.push("ftp-blocked"); }
                  try { await fetch("$base/x", { method: "TRACE" }); results.push("trace-ok"); }
                  catch (e) { results.push("trace-blocked"); }
                  return [{ name: "T", title: results.join(","),
                            url: "https://cdn.example.com/v.mp4", quality: "test" }];
                }
                module.exports = { getStreams: getStreams };
            """.trimIndent(), request)
        }
        assertEquals(1, streams.size)
        assertEquals("ftp-blocked,trace-blocked", streams[0].title)
    }
}
