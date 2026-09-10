package com.streambridge.app

import com.streambridge.app.addon.plugin.NuvioPluginException
import com.streambridge.app.addon.plugin.NuvioPluginRuntime
import com.streambridge.app.addon.plugin.NuvioStreamRequest
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * End-to-end execution of Nuvio-style providers through the sandboxed
 * QuickJS runtime, using MockWebServer as the scraped source. This is
 * the compatibility pipeline exactly as production uses it: CommonJS
 * module + getStreams() + fetch() + promise chains + normalization.
 *
 * Runs on the desktop JVM QuickJS artifact (see the dependency
 * substitution in app/build.gradle.kts).
 */
class NuvioRuntimeTest {

    private lateinit var server: MockWebServer
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    private val runtime = NuvioPluginRuntime()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val request = NuvioStreamRequest(
        tmdbId = "550",
        mediaType = "movie",
        season = null,
        episode = null
    )

    @Test
    fun `a real shaped provider fetches, resolves and returns streams`() = runBlocking {
        // The scraped endpoint answers with a stream list, exactly like
        // the sites Nuvio providers target.
        server.enqueue(
            MockResponse().setBody(
                """
                {"streams":[
                  {"name":"VixSrc","title":"1080p Multi","url":"https://cdn.example.com/v.mp4",
                   "quality":"1080p","headers":{"User-Agent":"Mozilla/5.0","Referer":"https://ref.example.com/"}},
                  {"name":"VixSrc","title":"CAM","url":"https://cdn.example.com/cam.mp4","quality":"CAM"}
                ]}
                """.trimIndent()
            )
        )

        // A faithful Nuvio provider: CommonJS export, fetch with headers,
        // async/await (transpiled repos use promise chains; both run).
        val code = """
            var HEADERS = { "User-Agent": "Mozilla/5.0 (Windows NT 10.0)" };
            async function getStreams(tmdbId, mediaType, season, episode) {
              var response = await fetch("$base/source?tmdb=" + tmdbId, {
                headers: HEADERS
              });
              if (!response.ok) { throw new Error("HTTP error " + response.status); }
              var data = await response.json();
              return data.streams;
            }
            module.exports = { getStreams: getStreams };
        """.trimIndent()

        val streams = runtime.execute(http, "https://unused/code.js", code, request)

        assertEquals(2, streams.size)
        assertEquals("1080p Multi", streams[0].title)
        assertEquals("1080p", streams[0].quality)
        assertEquals("Mozilla/5.0", streams[0].headers["User-Agent"])
        assertEquals("https://ref.example.com/", streams[0].headers["Referer"])
        // The provider received the right arguments.
        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.startsWith("/source?tmdb=550"))
        assertTrue(recorded.getHeader("User-Agent")!!.contains("Mozilla"))
    }

    @Test
    fun `promise-chain providers without async await work`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"url":"https://cdn.example.com/x.m3u8"}"""))
        val code = """
            function getStreams(tmdbId, mediaType, season, episode) {
              return fetch("$base/source")
                .then(function(r) { return r.json(); })
                .then(function(data) {
                  return [{ name: "P", title: "HLS", url: data.url, quality: "720p", format: "m3u8" }];
                });
            }
            module.exports = { getStreams: getStreams };
        """.trimIndent()

        val streams = runtime.execute(http, "https://unused/code.js", code, request)
        assertEquals(1, streams.size)
        assertEquals("HLS", streams[0].title)
        assertEquals("m3u8", streams[0].format)
    }

    @Test
    fun `providers that throw surface as controlled failures`() = runBlocking {
        val code = """
            function getStreams() { throw new Error("boom: scraper layout changed"); }
            module.exports = { getStreams: getStreams };
        """.trimIndent()
        try {
            runtime.execute(http, "https://unused/code.js", code, request)
            fail("expected a controlled failure")
        } catch (e: NuvioPluginException) {
            assertTrue(e.message!!.contains("boom"))
        }
    }

    @Test
    fun `providers without getStreams are reported unsupported`() = runBlocking {
        val code = "module.exports = { somethingElse: function() {} };"
        try {
            runtime.execute(http, "https://unused/code.js", code, request)
            fail("expected a controlled failure")
        } catch (e: NuvioPluginException) {
            assertTrue(e.message!!.contains("getStreams"))
        }
    }

    @Test
    fun `require is refused with a helpful message`() = runBlocking {
        val code = """
            var cheerio = require("cheerio");
            module.exports = { getStreams: function() { return []; } };
        """.trimIndent()
        try {
            runtime.execute(http, "https://unused/code.js", code, request)
            fail("expected a controlled failure")
        } catch (e: NuvioPluginException) {
            assertTrue(e.message!!.contains("require"))
        }
    }

    @Test
    fun `non http fetch urls are rejected inside the sandbox`() = runBlocking {
        val code = """
            function getStreams() {
              return fetch("file:///data/local/tmp/secret")
                .then(function(r) { return []; })
                .catch(function(e) { throw new Error(e.message); });
            }
            module.exports = { getStreams: getStreams };
        """.trimIndent()
        try {
            runtime.execute(http, "https://unused/code.js", code, request)
            fail("expected a controlled failure")
        } catch (e: NuvioPluginException) {
            assertTrue(e.message!!.contains("http"))
        }
    }

    @Test
    fun `atob and btoa are available for encoded sources`() = runBlocking {
        // base64("streams-ok") = c3RyZWFtcy1vaw==
        server.enqueue(
            MockResponse().setBody("""{"hint":"c3RyZWFtcy1vaw=="}""")
        )
        val code = """
            function getStreams() {
              return fetch("$base/source").then(function(r) { return r.json(); }).then(function(d) {
                var decoded = atob(d.hint);
                return [{ name: "B", title: decoded, url: "https://cdn.example.com/b.mp4" }];
              });
            }
            module.exports = { getStreams: getStreams };
        """.trimIndent()
        val streams = runtime.execute(http, "https://unused/code.js", code, request)
        assertEquals("streams-ok", streams[0].title)
    }

    @Test
    fun `response headers expose a web-like Headers interface`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setBody("""{"ok":true}""")
                .addHeader("X-Stream-Bridge", "header-probe")
        )
        val code = """
            function getStreams() {
              return fetch("$base/h").then(function(r) {
                var direct = r.headers.get("x-stream-bridge");
                var missing = r.headers.get("does-not-exist");
                var has = r.headers.has("X-Stream-Bridge");
                return [{
                  name: "H",
                  title: direct + "|" + String(missing) + "|" + String(has),
                  url: "https://cdn.example.com/v.mp4"
                }];
              });
            }
            module.exports = { getStreams: getStreams };
        """.trimIndent()
        val streams = runtime.execute(http, "https://unused/code.js", code, request)
        assertEquals("header-probe|null|true", streams[0].title)
    }

    @Test
    fun `URL and TextDecoder shims serve provider code`() = runBlocking {
        val code = """
            function getStreams() {
              var u = new URL("https://api.example.com/search/page?q=ninja&lang=en#top");
              var host = u.hostname;
              var q = u.searchParams.get("q");
              var rel = new URL("../b/c.js", "https://cdn.example.com/a/x.js").href;
              var bytes = new Uint8Array([0x68, 0x65, 0x6c, 0x6c, 0x6f, 0x20, 0xe2, 0x9c, 0x93]);
              var decoded = new TextDecoder().decode(bytes);
              return [{
                name: "U",
                title: host + "|" + q + "|" + rel + "|" + decoded,
                url: "https://cdn.example.com/v.mp4"
              }];
            }
            module.exports = { getStreams: getStreams };
        """.trimIndent()
        val streams = runtime.execute(http, "https://unused/code.js", code, request)
        assertEquals(
            "api.example.com|ninja|https://cdn.example.com/b/c.js|hello \u2713",
            streams[0].title
        )
    }

    @Test
    fun `episode arguments reach the provider`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"streams":[]}"""))
        val code = """
            function getStreams(tmdbId, mediaType, season, episode) {
              return fetch("$base/ep?m=" + mediaType + "&s=" + season + "&e=" + episode + "&id=" + tmdbId)
                .then(function(r) { return r.json(); });
            }
            module.exports = { getStreams: getStreams };
        """.trimIndent()
        runtime.execute(
            http, "https://unused/code.js", code,
            NuvioStreamRequest(tmdbId = "1399", mediaType = "tv", season = 1, episode = 2)
        )
        val recorded = server.takeRequest()
        assertEquals("/ep?m=tv&s=1&e=2&id=1399", recorded.path)
    }

    private val base: String get() = server.url("/").toString().trimEnd('/')

    // -----------------------------------------------------------------
    // Sandbox limits — the security claims, exercised for real
    // -----------------------------------------------------------------

    @Test
    fun `an infinite loop is killed by the execution time limit`() = runBlocking {
        // 1.5s busy timeout instead of the 30s production ceiling.
        val tight = NuvioPluginRuntime(busyTimeoutMs = 1500L)
        val code = """
            function getStreams(tmdbId, mediaType, season, episode) {
              var i = 0;
              while (true) { i = i + 1; } // never returns
            }
            module.exports = { getStreams: getStreams };
        """.trimIndent()
        val started = System.currentTimeMillis()
        try {
            tight.execute(http, "https://unused/code.js", code, request)
            fail("Expected the busy loop to be killed")
        } catch (e: NuvioPluginException) {
            // Controlled failure, fast — not a frozen test (and in the app,
            // not an ANR).
            assertTrue(System.currentTimeMillis() - started < 20_000)
            assertTrue(e.message!!.isNotBlank())
        }
    }

    @Test
    fun `deep recursion hits the stack limit and fails in a controlled way`() = runBlocking {
        // A small JS stack (128KB) trips the ENGINE's own check far below
        // the test thread's native stack — a larger limit would risk a
        // native SIGSEGV before the catchable InternalError. (The
        // production default is quickjs-kt's 256KB for the same reason.)
        val tight = NuvioPluginRuntime(stackLimitBytes = 128L * 1024)
        val code = """
            function recurse(n) { return recurse(n + 1); }
            function getStreams(tmdbId, mediaType, season, episode) {
              recurse(0);
              return [];
            }
            module.exports = { getStreams: getStreams };
        """.trimIndent()
        try {
            tight.execute(http, "https://unused/code.js", code, request)
            fail("Expected a stack overflow")
        } catch (e: NuvioPluginException) {
            assertTrue(e.message!!.isNotBlank())
        }
    }

    @Test
    fun `excessive allocation hits the memory limit`() = runBlocking {
        // 8 MB heap cap instead of the 128 MB production ceiling.
        val tight = NuvioPluginRuntime(memoryLimitBytes = 8L * 1024 * 1024)
        val code = """
            function getStreams(tmdbId, mediaType, season, episode) {
              var chunks = [];
              // Bounded: enough to trip an 8MB cap, never unbounded even
              // if the limit somehow failed.
              while (chunks.length < 400) { chunks.push(new Array(65536).fill("x")); }
              return [];
            }
            module.exports = { getStreams: getStreams };
        """.trimIndent()
        try {
            tight.execute(http, "https://unused/code.js", code, request)
            fail("Expected the allocation bomb to be killed")
        } catch (e: NuvioPluginException) {
            assertTrue(e.message!!.isNotBlank())
        }
    }

    @Test
    fun `the request count cap stops runaway fetch loops`() = runBlocking {
        // 3 requests per call instead of the 80 production cap.
        val tight = NuvioPluginRuntime(maxFetchesPerCall = 3)
        repeat(5) {
            server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        }
        val code = """
            function getStreams(tmdbId, mediaType, season, episode) {
              var p = Promise.resolve([]);
              for (var i = 0; i < 5; i++) {
                p = p.then(function() { return fetch("$base/loop?i=" + i); })
                     .then(function(r) { return r.json(); });
              }
              return p.then(function() { return []; });
            }
            module.exports = { getStreams: getStreams };
        """.trimIndent()
        try {
            tight.execute(http, "https://unused/code.js", code, request)
            fail("Expected the fetch cap to trip")
        } catch (e: NuvioPluginException) {
            assertTrue(e.message!!.contains("requests per call"))
        }
        // Only the allowed requests reached the network.
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `oversized fetch responses are truncated, never oom`() = runBlocking {
        // 1 KB response cap instead of the 10 MB production ceiling.
        val tight = NuvioPluginRuntime(maxResponseBytes = 1024)
        val huge = buildString {
            repeat(8) { append("0123456789abcdef".repeat(128)) } // 16 KB
        }
        server.enqueue(MockResponse().setBody("""{"data":"$huge"}"""))
        val code = """
            function getStreams(tmdbId, mediaType, season, episode) {
              return fetch("$base/big")
                .then(function(r) { return r.text(); })
                .then(function(body) {
                  var marker = body.indexOf("truncated by the plugin sandbox") !== -1;
                  return [{ name: "X", title: marker ? "TRUNCATED" : "FULL",
                            url: "https://cdn.example.com/v.mp4",
                            quality: "test", format: "mp4" }];
                });
            }
            module.exports = { getStreams: getStreams };
        """.trimIndent()
        val streams = tight.execute(http, "https://unused/code.js", code, request)
        assertEquals(1, streams.size)
        // The sandbox appended its truncation marker in place of the bytes
        // beyond the cap — the body was capped, never fully materialized.
        assertEquals("TRUNCATED", streams[0].title)
        val recorded = server.takeRequest()
        assertEquals("/big", recorded.path)
    }
}
