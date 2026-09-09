package com.streambridge.app

import com.streambridge.app.addon.plugin.NuvioPluginException
import com.streambridge.app.addon.plugin.NuvioPluginRuntime
import com.streambridge.app.addon.plugin.NuvioStreamRequest
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The provider compatibility layer, executed exactly as production uses
 * it: real provider JS through the sandboxed QuickJS runtime. The JS
 * semantics themselves are validated differentially against Node in the
 * development harness; these tests cover the RUNTIME integration — the
 * quickjs-kt behaviors (real-delay timers through async bindings, sync
 * binding exceptions), the host crypto capability, the cookie flow into
 * the HTTP engine, the module registry and the structured diagnostics.
 */
class CompatRuntimeTest {

    private lateinit var server: MockWebServer
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    private val runtime = NuvioPluginRuntime()

    private val base: String get() = server.url("/").toString().trimEnd('/')

    /** A realistic provider code URL for location/filename semantics. */
    private val codeUrl = "https://repo.example/providers/myprovider.js"

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

    /** Runs [code] and returns the first stream's title. */
    private fun firstTitle(code: String, codeUrl: String = this.codeUrl): String {
        val streams = runBlocking {
            runtime.execute(http, codeUrl, code, request)
        }
        assertTrue("Provider returned no streams", streams.isNotEmpty())
        return streams[0].title
    }

    // -----------------------------------------------------------------
    // Timers (quickjs-kt integration: async bindings with real delays)
    // -----------------------------------------------------------------

    @Test
    fun `timers fire with real delays and pass arguments`() {
        val title = firstTitle(
            """
            async function getStreams() {
              var value = await new Promise(function(resolve) {
                setTimeout(function(a, b) { resolve(a + b); }, 40, 20, 22);
              });
              return [{ name: "T", title: String(value),
                        url: "https://cdn.example.com/v.mp4", quality: "test" }];
            }
            module.exports = { getStreams: getStreams };
            """.trimIndent()
        )
        assertEquals("42", title)
    }

    @Test
    fun `clearTimeout cancels scheduled work`() {
        val title = firstTitle(
            """
            async function getStreams() {
              var fired = "not-fired";
              var id = setTimeout(function() { fired = "fired"; }, 10);
              clearTimeout(id);
              await new Promise(function(r) { setTimeout(r, 60); });
              return [{ name: "T", title: fired,
                        url: "https://cdn.example.com/v.mp4", quality: "test" }];
            }
            module.exports = { getStreams: getStreams };
            """.trimIndent()
        )
        assertEquals("not-fired", title)
    }

    @Test
    fun `setInterval fires repeatedly until cleared`() {
        val title = firstTitle(
            """
            async function getStreams() {
              var ticks = 0;
              var id = setInterval(function() {
                ticks++;
                if (ticks >= 3) { clearInterval(id); }
              }, 5);
              await new Promise(function(r) { setTimeout(r, 120); });
              return [{ name: "T", title: String(ticks),
                        url: "https://cdn.example.com/v.mp4", quality: "test" }];
            }
            module.exports = { getStreams: getStreams };
            """.trimIndent()
        )
        assertEquals("3", title)
    }

    @Test
    fun `timer ordering follows real elapsed time`() {
        val title = firstTitle(
            """
            async function getStreams() {
              var order = [];
              setTimeout(function() { order.push("slow"); }, 40);
              setTimeout(function() { order.push("fast"); }, 5);
              await new Promise(function(r) { setTimeout(r, 80); });
              return [{ name: "T", title: order.join(","),
                        url: "https://cdn.example.com/v.mp4", quality: "test" }];
            }
            module.exports = { getStreams: getStreams };
            """.trimIndent()
        )
        assertEquals("fast,slow", title)
    }

    @Test
    fun `fire and forget timers land before the result is read`() {
        // The result array is captured by reference: a stream pushed by
        // a fire-and-forget timer must be visible when the runtime maps
        // the results — this exercises the whole grace-window path.
        val streams = runBlocking {
            runtime.execute(
                http, codeUrl,
                """
                function getStreams() {
                  var out = [];
                  setTimeout(function() {
                    out.push({ name: "T", title: "from-timer",
                               url: "https://cdn.example.com/t.mp4", quality: "test" });
                  }, 20);
                  return out;
                }
                module.exports = { getStreams: getStreams };
                """.trimIndent(),
                request
            )
        }
        assertEquals(1, streams.size)
        assertEquals("from-timer", streams[0].title)
    }

    @Test
    fun `process dot nextTick runs before timers`() {
        val title = firstTitle(
            """
            async function getStreams() {
              var order = [];
              process.nextTick(function() { order.push("tick"); });
              setTimeout(function() { order.push("timer"); }, 0);
              await new Promise(function(r) { setTimeout(r, 30); });
              return [{ name: "T", title: order.join(","),
                        url: "https://cdn.example.com/v.mp4", quality: "test" }];
            }
            module.exports = { getStreams: getStreams };
            """.trimIndent()
        )
        assertEquals("tick,timer", title)
    }

    // -----------------------------------------------------------------
    // Buffer / crypto (host capability through the binding)
    // -----------------------------------------------------------------

    @Test
    fun `Buffer and the crypto module hash with known vectors`() {
        val title = firstTitle(
            """
            var crypto = require("crypto");
            function getStreams() {
              var md5 = crypto.createHash("md5").update("hello").digest("hex");
              var sha256 = crypto.createHash("sha256").update("hello").digest("hex");
              var hmac = crypto.createHmac("sha256", "key")
                .update("hello").digest("hex").slice(0, 8);
              var bytes = Buffer.from("hello", "utf8").toString("base64");
              return [{ name: "T", title: md5 + "|" + sha256.slice(0, 8) + "|" + hmac + "|" + bytes,
                        url: "https://cdn.example.com/v.mp4", quality: "test" }];
            }
            module.exports = { getStreams: getStreams };
            """.trimIndent()
        )
        // md5("hello") and sha256("hello") are public test vectors.
        assertEquals(
            "5d41402abc4b2a76b9719d911017c592|2cf24dba|9307b3b9|aGVsbG8=",
            title
        )
    }

    @Test
    fun `aes-cbc and gcm round-trip inside the provider`() {
        val title = firstTitle(
            """
            var crypto = require("crypto");
            function getStreams() {
              var key = Buffer.from("0123456789abcdef0123456789abcdef");
              var iv = Buffer.from("abcdef9876543210");
              var plain = Buffer.from("attack at dawn!!");
              var c = crypto.createCipheriv("aes-256-cbc", key, iv);
              var secret = Buffer.concat([c.update(plain), c.final()]);
              var d = crypto.createDecipheriv("aes-256-cbc", key, iv);
              var back = Buffer.concat([d.update(secret), d.final()]).toString("utf8");
              var g = crypto.createCipheriv("aes-256-gcm", key, iv);
              var gct = Buffer.concat([g.update(plain), g.final()]);
              var gtag = g.getAuthTag();
              var gd = crypto.createDecipheriv("aes-256-gcm", key, iv);
              gd.setAuthTag(gtag);
              var gback = Buffer.concat([gd.update(gct), gd.final()]).toString("utf8");
              return [{ name: "T", title: back + "|" + gback,
                        url: "https://cdn.example.com/v.mp4", quality: "test" }];
            }
            module.exports = { getStreams: getStreams };
            """.trimIndent()
        )
        assertEquals("attack at dawn!!|attack at dawn!!", title)
    }

    @Test
    fun `unsupported crypto surfaces as a controlled error`() {
        try {
            firstTitle(
                """
                var crypto = require("crypto");
                function getStreams() {
                  var h = crypto.createHash("sha3-512").update("x").digest("hex");
                  return [{ name: "T", title: h,
                            url: "https://cdn.example.com/v.mp4", quality: "test" }];
                }
                module.exports = { getStreams: getStreams };
                """.trimIndent()
            )
            fail("expected a controlled crypto failure")
        } catch (e: NuvioPluginException) {
            assertTrue(e.message!!.contains("Unsupported hash algorithm"))
        }
    }

    // -----------------------------------------------------------------
    // Browser globals / location / document
    // -----------------------------------------------------------------

    @Test
    fun `window self and navigator exist and location is the code url`() {
        val title = firstTitle(
            """
            function getStreams() {
              var parts = [
                window === globalThis,
                self === globalThis,
                navigator.userAgent.indexOf("StreamBridge") !== -1,
                location.pathname,
                __filename,
                __dirname
              ];
              return [{ name: "T", title: parts.join("|"),
                        url: "https://cdn.example.com/v.mp4", quality: "test" }];
            }
            module.exports = { getStreams: getStreams };
            """.trimIndent()
        )
        assertEquals(
            "true|true|true|/providers/myprovider.js|" +
                "https://repo.example/providers/myprovider.js|" +
                "https://repo.example/providers",
            title
        )
    }

    @Test
    fun `document createElement a parses urls and other tags fail controlled`() {
        val title = firstTitle(
            """
            function getStreams() {
              var a = document.createElement("a");
              a.href = "https://sub.example.com:8443/p/q?x=1";
              var divError = "";
              try { document.createElement("div"); } catch (e) { divError = e.message; }
              return [{ name: "T",
                        title: a.hostname + "|" + a.pathname + "|" + a.search + "|" +
                               (divError.indexOf("no DOM rendering") !== -1),
                        url: "https://cdn.example.com/v.mp4", quality: "test" }];
            }
            module.exports = { getStreams: getStreams };
            """.trimIndent()
        )
        assertEquals("sub.example.com|/p/q|?x=1|true", title)
    }

    @Test
    fun `document cookie shares state with the http engine`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return if (request.path == "/login") {
                    MockResponse()
                        .addHeader("Set-Cookie", "session=abc123; Path=/")
                        .setBody("{}")
                } else {
                    val cookie = request.getHeader("Cookie") ?: "none"
                    MockResponse().setBody("{\"cookie\":\"" + cookie + "\"}")
                }
            }
        }

        val title = firstTitle(
            """
            async function getStreams() {
              await fetch("$base/login");
              var seenByJs = document.cookie;
              var data = await fetch("$base/data").then(function(r) { return r.json(); });
              return [{ name: "T", title: seenByJs + "|" + data.cookie,
                        url: "https://cdn.example.com/v.mp4", quality: "test" }];
            }
            module.exports = { getStreams: getStreams };
            """.trimIndent()
        )
        // JS read the jar after the response set it, and fetch() replayed
        // it automatically on the next request.
        assertEquals("session=abc123|session=abc123", title)
    }

    @Test
    fun `localStorage persists within the call and enforces its quota`() {
        val title = firstTitle(
            """
            function getStreams() {
              localStorage.setItem("k", "v");
              var quotaError = "none";
              try { localStorage.setItem("big", "x".repeat(100 * 1024)); }
              catch (e) { quotaError = e.message.indexOf("quota") !== -1 ? "quota" : e.message; }
              return [{ name: "T", title: localStorage.getItem("k") + "|" + quotaError,
                        url: "https://cdn.example.com/v.mp4", quality: "test" }];
            }
            module.exports = { getStreams: getStreams };
            """.trimIndent()
        )
        assertEquals("v|quota", title)
    }

    @Test
    fun `process exit is a controlled error`() {
        try {
            firstTitle(
                """
                function getStreams() { process.exit(1); return []; }
                module.exports = { getStreams: getStreams };
                """.trimIndent()
            )
            fail("expected process.exit to be refused")
        } catch (e: NuvioPluginException) {
            assertTrue(e.message!!.contains("process.exit()"))
        }
    }

    // -----------------------------------------------------------------
    // Module registry
    // -----------------------------------------------------------------

    @Test
    fun `require resolves built-ins with and without the node prefix`() {
        val title = firstTitle(
            """
            var crypto1 = require("crypto");
            var crypto2 = require("node:crypto");
            var EventEmitter = require("events").EventEmitter;
            var path = require("path");
            var joined = path.join("/a", "b", "..", "c");
            function getStreams() {
              return [{ name: "T",
                        title: (crypto1 === crypto2) + "|" +
                               (typeof EventEmitter === "function") + "|" + joined,
                        url: "https://cdn.example.com/v.mp4", quality: "test" }];
            }
            module.exports = { getStreams: getStreams };
            """.trimIndent()
        )
        assertEquals("true|true|/a/c", title)
    }

    @Test
    fun `unknown modules fail with a structured compatibility diagnostic`() {
        try {
            firstTitle(
                """
                var sdk = require("some-vendor-sdk");
                function getStreams() { return []; }
                module.exports = { getStreams: getStreams };
                """.trimIndent()
            )
            fail("expected a controlled module failure")
        } catch (e: NuvioPluginException) {
            val message = e.message!!
            // Headline states the problem compactly (the picker shows ~120 chars).
            assertTrue(message.contains("Cannot find module 'some-vendor-sdk'"))
            // The structured block names the dependency and the action.
            assertTrue(message.contains("Status: Unsupported Dependency"))
            assertTrue(message.contains("Dependency: some-vendor-sdk"))
            assertTrue(message.contains("Action:"))
        }
    }

    @Test
    fun `pre-fetched relative modules resolve via require`() {
        val streams = runBlocking {
            runtime.execute(
                http, codeUrl,
                """
                var helper = require("./helper.js");
                function getStreams() {
                  return [{ name: "T", title: helper.value,
                            url: "https://cdn.example.com/v.mp4", quality: "test" }];
                }
                module.exports = { getStreams: getStreams };
                """.trimIndent(),
                request,
                extraModules = mapOf(
                    "./helper.js" to "module.exports = { value: 42 };",
                    "https://repo.example/providers/helper.js" to "module.exports = { value: 42 };"
                )
            )
        }
        assertEquals(1, streams.size)
        assertEquals("42", streams[0].title)
    }

    // -----------------------------------------------------------------
    // ES modules
    // -----------------------------------------------------------------

    @Test
    fun `esm providers with named exports and node imports run`() {
        val title = firstTitle(
            """
            import { createHash } from "node:crypto";
            export async function getStreams(tmdbId, mediaType, season, episode) {
              var hash = createHash("md5").update(tmdbId).digest("hex").slice(0, 6);
              return [{ name: "T", title: hash,
                        url: "https://cdn.example.com/v.mp4", quality: "test" }];
            }
            """.trimIndent()
        )
        // md5("550").slice(0, 6)
        assertEquals("01f78b", title)
    }

    @Test
    fun `esm default export objects run`() {
        val title = firstTitle(
            """
            import querystring from "querystring";
            export default {
              getStreams: function() {
                var qs = querystring.stringify({ a: "1", b: ["2", "3"] });
                return [{ name: "T", title: qs,
                          url: "https://cdn.example.com/v.mp4", quality: "test" }];
              }
            };
            """.trimIndent()
        )
        assertEquals("a=1&b=2&b=3", title)
    }

    @Test
    fun `esm default export functions run`() {
        val title = firstTitle(
            """
            import { Buffer } from "buffer";
            export default async function getStreams() {
              var b64 = Buffer.from("hello", "utf8").toString("base64");
              return [{ name: "T", title: b64,
                        url: "https://cdn.example.com/v.mp4", quality: "test" }];
            }
            """.trimIndent()
        )
        assertEquals("aGVsbG8=", title)
    }

    @Test
    fun `esm providers import relative modules by url resolution`() {
        val streams = runBlocking {
            runtime.execute(
                http, codeUrl,
                """
                import { double } from "./util.js";
                export async function getStreams() {
                  return [{ name: "T", title: String(double(21)),
                            url: "https://cdn.example.com/v.mp4", quality: "test" }];
                }
                """.trimIndent(),
                request,
                extraModules = mapOf(
                    "./util.js" to "export function double(x) { return x * 2; }",
                    "https://repo.example/providers/util.js" to
                        "export function double(x) { return x * 2; }"
                )
            )
        }
        assertEquals(1, streams.size)
        assertEquals("42", streams[0].title)
    }

    // -----------------------------------------------------------------
    // Misc compat surfaces
    // -----------------------------------------------------------------

    @Test
    fun `AbortController aborts fetch before the request with AbortError`() {
        val title = firstTitle(
            """
            async function getStreams() {
              var controller = new AbortController();
              controller.abort();
              try {
                await fetch("$base/x", { signal: controller.signal });
                return [{ name: "T", title: "no-error",
                          url: "https://cdn.example.com/v.mp4", quality: "test" }];
              } catch (e) {
                return [{ name: "T", title: e.name,
                          url: "https://cdn.example.com/v.mp4", quality: "test" }];
              }
            }
            module.exports = { getStreams: getStreams };
            """.trimIndent()
        )
        assertEquals("AbortError", title)
        // The aborted request never reached the network.
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `URLSearchParams has the full method surface forge depends on`() {
        val title = firstTitle(
            """
            function getStreams() {
              var qs = new URLSearchParams("a=1&b=2&a=3");
              var checks = [
                qs.has("b"),                 // forge's debug path needs has()
                qs.getAll("a").join(","),
                (function() { qs.delete("b"); return qs.has("b"); })(),
                (function() {
                  var seen = [];
                  qs.forEach(function(v, k) { seen.push(k + "=" + v); });
                  return seen.join(";");
                })(),
                (function() {
                  var entries = [];
                  var it = qs.entries();
                  for (var r = it.next(); !r.done; r = it.next()) {
                    entries.push(r.value[0] + ":" + r.value[1]);
                  }
                  return entries.join("&");
                })(),
                new URL("https://x/y?a=1").searchParams.has("a"),
                new URL("https://x/y").toJSON()
              ];
              return [{ name: "T", title: checks.join("|"),
                        url: "https://cdn.example.com/v.mp4", quality: "test" }];
            }
            module.exports = { getStreams: getStreams };
            """.trimIndent()
        )
        assertEquals(
            "true|1,3|false|a=1;a=3|a:1&a:3|true|https://x/y",
            title
        )
    }

    @Test
    fun `getRandomValues accepts every integer typed array`() {
        val title = firstTitle(
            """
            function getStreams() {
              var u8 = new Uint8Array(8);
              var u32 = new Uint32Array(4);   // what node-forge seeds with
              var i16 = new Int16Array(4);
              crypto.getRandomValues(u8);
              crypto.getRandomValues(u32);
              crypto.getRandomValues(i16);
              var floatRejected = "";
              try { crypto.getRandomValues(new Float64Array(2)); }
              catch (e) { floatRejected = "rejected"; }
              return [{ name: "T",
                        title: (u8.length + u32.length + i16.length) + "|" + floatRejected,
                        url: "https://cdn.example.com/v.mp4", quality: "test" }];
            }
            module.exports = { getStreams: getStreams };
            """.trimIndent()
        )
        assertEquals("16|rejected", title)
    }

    @Test
    fun `TextEncoder and TextDecoder handle multibyte text`() {
        val title = firstTitle(
            """
            function getStreams() {
              var encoder = new TextEncoder();
              var bytes = encoder.encode("h\u00e9\u4f60");
              var decoder = new TextDecoder();
              var back = decoder.decode(bytes);
              return [{ name: "T", title: bytes.length + "|" + back,
                        url: "https://cdn.example.com/v.mp4", quality: "test" }];
            }
            module.exports = { getStreams: getStreams };
            """.trimIndent()
        )
        assertEquals("6|h\u00e9\u4f60", title)
    }

    @Test
    fun `util querystring and url modules work for real scraping patterns`() {
        val title = firstTitle(
            """
            var querystring = require("querystring");
            var url = require("url");
            var util = require("util");
            function getStreams() {
              var parsed = url.parse("https://host.example.com/search?q=1&page=2", true);
              var qs = querystring.stringify({ a: "1", b: ["2", "3"] });
              var formatted = util.format("%s-%d", "x", 7);
              return [{ name: "T",
                        title: parsed.query.q + parsed.query.page + "|" + qs + "|" + formatted,
                        url: "https://cdn.example.com/v.mp4", quality: "test" }];
            }
            module.exports = { getStreams: getStreams };
            """.trimIndent()
        )
        assertEquals("12|a=1&b=2&b=3|x-7", title)
    }

    @Test
    fun `a full realistic provider uses fetch crypto cheerio-style flows`() {
        server.enqueue(
            MockResponse().setBody(
                """{"token":"c3RyZWFtYnJpZGdl","link":"https://cdn.example.com/embed/42"}"""
            )
        )
        val title = firstTitle(
            """
            var crypto = require("crypto");
            async function getStreams(tmdbId, mediaType, season, episode) {
              var response = await fetch("$base/resolve?tmdb=" + tmdbId, {
                headers: { "Accept": "application/json" }
              });
              if (!response.ok) { throw new Error("upstream HTTP " + response.status); }
              var data = await response.json();
              var decoded = Buffer.from(data.token, "base64").toString("utf8");
              var checksum = crypto.createHash("md5").update(decoded).digest("hex").slice(0, 6);
              return [{
                name: "Src", title: decoded + "|" + checksum,
                url: data.link, quality: "1080p"
              }];
            }
            module.exports = { getStreams: getStreams };
            """.trimIndent()
        )
        // md5("streambridge").slice(0, 6)
        assertEquals("streambridge|787502", title)
        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.startsWith("/resolve?tmdb=550"))
        assertTrue(recorded.getHeader("Accept")!!.contains("application/json"))
    }
}
