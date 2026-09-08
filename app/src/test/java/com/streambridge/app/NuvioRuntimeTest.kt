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
}
