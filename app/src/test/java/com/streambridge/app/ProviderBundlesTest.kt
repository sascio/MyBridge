package com.streambridge.app

import com.streambridge.app.addon.plugin.NuvioPluginRuntime
import com.streambridge.app.addon.plugin.NuvioStreamRequest
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The REAL compatibility bundles (cheerio and node-forge, shipped as
 * app assets) executed inside the sandboxed runtime — the same code
 * production runs, loaded from the same files. This is where
 * "cheerio providers work" is proven, not assumed: the animepahe-style
 * interop pattern (__toESM(require('cheerio-without-node-native'))
 * .default.load) runs against the actual library.
 */
class ProviderBundlesTest {

    private lateinit var server: MockWebServer
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    private val runtime = NuvioPluginRuntime()

    private val base: String get() = server.url("/").toString().trimEnd('/')

    private val request = NuvioStreamRequest(
        tmdbId = "550",
        mediaType = "movie",
        season = null,
        episode = null
    )

    /** The module map exactly as the manager registers the bundles. */
    private val modules: Map<String, String> by lazy {
        val cheerio = loadAsset("cheerio.bundle.js")
        val forge = loadAsset("forge.bundle.js")
        mapOf(
            "cheerio" to cheerio,
            "cheerio-without-node-native" to cheerio,
            "node-forge" to forge
        )
    }

    private fun loadAsset(name: String): String {
        // Unit tests run with the module directory as working dir; be
        // tolerant of the repo root as well.
        for (path in listOf("src/main/assets/compat/$name", "app/src/main/assets/compat/$name")) {
            val file = File(path)
            if (file.exists()) return file.readText(Charsets.UTF_8)
        }
        throw IllegalStateException("compat bundle not found: $name")
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `real cheerio resolves the animepahe interop pattern`() {
        val streams = runBlocking {
            runtime.execute(
                http, "https://repo.example/providers/p.js",
                """
                // The exact esbuild interop shape the real animepahe
                // provider bundle uses.
                var __toESM = (mod, isNodeMode) => {
                  if (isNodeMode || mod && mod.__esModule) return mod;
                  var result = {};
                  if (mod != null) for (var k in mod)
                    if (k !== "default" && Object.prototype.hasOwnProperty.call(mod, k))
                      result[k] = mod[k];
                  result.default = mod;
                  return result;
                };
                var import_cheerio = __toESM(require("cheerio-without-node-native"));
                function getStreams(tmdbId, mediaType, season, episode) {
                  var html = "<div class='list'>" +
                    "<a data-src='https://cdn.example.com/1.mp4' href='/w/1'>Episode 1</a>" +
                    "<a data-src='https://cdn.example.com/2.mp4' href='/w/2'>Episode 2</a>" +
                    "</div>";
                  var dolby = import_cheerio.default.load(html);
                  var out = [];
                  dolby("a").each(function(i, el) {
                    var el2 = dolby(el);
                    out.push({
                      name: "Src",
                      title: el2.text(),
                      url: el2.attr("data-src"),
                      quality: "1080p"
                    });
                  });
                  return out;
                }
                module.exports = { getStreams: getStreams };
                """.trimIndent(),
                request,
                extraModules = modules
            )
        }
        assertEquals(2, streams.size)
        assertEquals("Episode 1", streams[0].title)
        assertEquals("https://cdn.example.com/1.mp4", streams[0].url)
        assertEquals("Episode 2", streams[1].title)
    }

    @Test
    fun `real cheerio handles selectors traversal and extraction`() {
        val streams = runBlocking {
            runtime.execute(
                http, "https://repo.example/providers/p.js",
                """
                var cheerio = require("cheerio");
                function getStreams() {
                  var html = "<html><body>" +
                    "<table id='eps'><tr class='row' data-id='7'><td class='name'>Alpha</td><td class='q'>720p</td></tr>" +
                    "<tr class='row' data-id='9'><td class='name'>Beta</td><td class='q'>1080p</td></tr></table>" +
                    "</body></html>";
                  var dollar = cheerio.load(html);
                  var first = dollar("tr.row").first();
                  var parent = dollar("#eps");
                  var checks = [
                    first.find(".name").text(),                     // 'Alpha'
                    dollar("td.q").last().text(),                    // '1080p'
                    String(parent.children().length),                // '2'
                    first.attr("data-id"),                           // '7'
                    dollar(".row[data-id='9'] .name").text(),        // 'Beta'
                    String(dollar("tr").hasClass("row")),            // 'true'
                    dollar("tr.row").map(function(i, el) {
                      return dollar(el).attr("data-id");
                    }).get().join(","),                              // '7,9'
                    dollar("#eps").html().indexOf("data-id") !== -1 ? "html-ok" : "no-html"
                  ];
                  return [{ name: "T", title: checks.join("|"),
                            url: "https://cdn.example.com/v.mp4", quality: "test" }];
                }
                module.exports = { getStreams: getStreams };
                """.trimIndent(),
                request,
                extraModules = modules
            )
        }
        assertEquals(
            "Alpha|1080p|2|7|Beta|true|7,9|html-ok",
            streams[0].title
        )
    }

    @Test
    fun `real node-forge hashes and ciphers inside the sandbox`() {
        val streams = runBlocking {
            runtime.execute(
                http, "https://repo.example/providers/p.js",
                """
                var forge = require("node-forge");
                function getStreams() {
                  var md5 = forge.md5.create().update("hello").digest().toHex();
                  var sha256 = forge.sha256.create().update("hello").digest().toHex();
                  var key = forge.random.getBytesSync(32);
                  var iv = forge.random.getBytesSync(16);
                  var cipher = forge.cipher.createCipher("AES-CBC", key);
                  cipher.start({ iv: iv });
                  cipher.update(forge.util.createBuffer("attack at dawn!!"));
                  cipher.finish();
                  var decipher = forge.cipher.createDecipher("AES-CBC", key);
                  decipher.start({ iv: iv });
                  decipher.update(cipher.output);
                  decipher.finish();
                  var roundtrip = decipher.output.toString();
                  var b64 = forge.util.encode64("hi");
                  return [{ name: "T",
                            title: md5 + "|" + sha256.slice(0, 8) + "|" + roundtrip + "|" + b64,
                            url: "https://cdn.example.com/v.mp4", quality: "test" }];
                }
                module.exports = { getStreams: getStreams };
                """.trimIndent(),
                request,
                extraModules = modules
            )
        }
        // md5("hello") and sha256("hello") are public test vectors; the
        // AES round-trip proves forge's cipher stack runs end to end.
        assertEquals(
            "5d41402abc4b2a76b9719d911017c592|2cf24dba|attack at dawn!!|aGk=",
            streams[0].title
        )
    }

    @Test
    fun `a full scraping pipeline fetches html and parses it with real cheerio`() {
        server.enqueue(
            MockResponse().setBody(
                """<html><body><div class="servers">
                   <div class="server" data-src="https://cdn.example.com/embed/alpha">Alpha Host</div>
                   <div class="server" data-src="https://cdn.example.com/embed/beta">Beta Host</div>
                   </div></body></html>"""
            )
        )
        val streams = runBlocking {
            runtime.execute(
                http, "https://repo.example/providers/p.js",
                """
                var cheerio = require("cheerio");
                async function getStreams(tmdbId, mediaType, season, episode) {
                  var response = await fetch("$base/watch?tmdb=" + tmdbId, {
                    headers: { "Accept": "text/html" }
                  });
                  if (!response.ok) { throw new Error("upstream HTTP " + response.status); }
                  var html = await response.text();
                  var dollar = cheerio.load(html);
                  return dollar(".server").map(function(i, el) {
                    var host = dollar(el);
                    return {
                      name: "Host",
                      title: host.text().trim(),
                      url: host.attr("data-src"),
                      quality: "1080p"
                    };
                  }).get();
                }
                module.exports = { getStreams: getStreams };
                """.trimIndent(),
                request,
                extraModules = modules
            )
        }
        assertEquals(2, streams.size)
        assertEquals("Alpha Host", streams[0].title)
        assertEquals("https://cdn.example.com/embed/alpha", streams[0].url)
        assertEquals("Beta Host", streams[1].title)
        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.startsWith("/watch?tmdb=550"))
    }

    @Test
    fun `cheerio and forge coexist and memoize within one execution`() {
        val streams = runBlocking {
            runtime.execute(
                http, "https://repo.example/providers/p.js",
                """
                function getStreams() {
                  var forge = require("node-forge");
                  var forgeAgain = require("node-forge");
                  var cheerio1 = require("cheerio");
                  var cheerio2 = require("cheerio-without-node-native");
                  var checks = [
                    forge === forgeAgain,
                    typeof cheerio1.load === "function",
                    typeof cheerio2.load === "function"
                  ];
                  return [{ name: "T", title: checks.join("|"),
                            url: "https://cdn.example.com/v.mp4", quality: "test" }];
                }
                module.exports = { getStreams: getStreams };
                """.trimIndent(),
                request,
                extraModules = modules
            )
        }
        // The same forge instance is memoized; both cheerio specifiers
        // resolve to (separately evaluated) working modules.
        assertEquals("true|true|true", streams[0].title)
    }
}
