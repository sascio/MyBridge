package com.streambridge.app.addon.plugin

import com.dokar.quickjs.ModuleContent
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.moduleLoader
import com.streambridge.app.addon.plugin.compat.CompatPrelude
import com.streambridge.app.addon.plugin.compat.CryptoCapability
import com.streambridge.app.addon.plugin.compat.ProviderAnalyzer
import com.streambridge.app.addon.plugin.compat.ProviderHttpEngine
import com.streambridge.app.addon.plugin.compat.ProviderProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/**
 * The request handed to a Nuvio provider's getStreams().
 *
 * Nuvio's contract: getStreams(tmdbId, mediaType, season, episode)
 * where mediaType is "movie" | "tv" and season/episode are 1-based
 * numbers (null for movies).
 */
data class NuvioStreamRequest(
    val tmdbId: String,
    val mediaType: String,
    val season: Int?,
    val episode: Int?
) {
    companion object {
        /** Maps StreamBridge's "series" onto Nuvio's "tv". */
        fun from(type: String, tmdbId: String, season: Int?, episode: Int?): NuvioStreamRequest =
            NuvioStreamRequest(
                tmdbId = tmdbId,
                mediaType = if (type.equals("series", ignoreCase = true)) "tv" else "movie",
                season = season,
                episode = episode
            )
    }
}

/** A raw stream object as returned by a provider. */
data class NuvioRawStream(
    val name: String,
    val title: String,
    val url: String,
    val quality: String,
    val format: String,
    val headers: Map<String, String>
)

/**
 * Controlled JavaScript runtime for Nuvio-compatible providers.
 *
 * Providers are untrusted code: every execution gets a FRESH QuickJS
 * instance (no state sharing between providers or calls), a memory
 * cap, a stack cap, a JavaScript busy-loop timeout and an overall
 * call timeout, and a dedicated [ProviderHttpEngine] whose `fetch`
 * speaks http(s) with per-request timeouts, cookies scoped to the
 * call, transparent gzip/deflate/brotli decoding, binary bodies and a
 * response size cap plus a request-count cap. There is no file,
 * Android or Java access — the engine exposes nothing but what is
 * defined here. Any failure is a controlled exception, never a crash.
 */
class NuvioPluginRuntime(
    /**
     * Sandbox limits. The defaults are the production values; they are
     * constructor-injectable so the JVM tests can exercise each limit
     * (busy loop, deep recursion, memory bomb, request cap, response
     * cap) quickly instead of waiting for the real ceilings.
     */
    private val memoryLimitBytes: Long = 128L * 1024 * 1024,
    // quickjs-kt's own default (256KB): a bigger JS stack can overrun
    // the host thread's native stack before the engine's internal check
    // trips — which would crash the process instead of raising a catchable
    // stack-overflow error.
    private val stackLimitBytes: Long = 256L * 1024,
    private val busyTimeoutMs: Long = 30_000L,
    private val fetchTimeoutMs: Long = 15_000L,
    private val maxFetchesPerCall: Int = 80,
    private val maxResponseBytes: Int = 10 * 1024 * 1024,
    private val maxCodeBytes: Int = 8 * 1024 * 1024,
    private val maxStreamsPerCall: Int = 40
) {

    /** Downloads provider code over HTTP with a short timeout. */
    fun fetchProviderCode(http: OkHttpClient, codeUrl: String): String {
        http.newCall(
            Request.Builder()
                .url(codeUrl)
                .header("User-Agent", UA)
                .build()
        ).execute().use { response ->
            if (!response.isSuccessful) {
                throw NuvioPluginException("Provider code download failed (HTTP ${response.code})")
            }
            val body = response.body ?: throw NuvioPluginException("Provider code response was empty")
            val bytes = body.byteStream().use { it.readAtMost(maxCodeBytes) }
            if (bytes.size >= maxCodeBytes) {
                throw NuvioPluginException("Provider code exceeds the ${maxCodeBytes / 1024 / 1024} MB limit")
            }
            return String(bytes, Charsets.UTF_8)
        }
    }

    private val jsIdentifier = Regex("[A-Za-z_$][A-Za-z0-9_$]*")
    private val reservedExportNames = setOf(
        "class", "function", "var", "let", "const", "default", "export",
        "import", "return", "if", "else", "new", "delete", "typeof",
        "void", "in", "of", "for", "while", "do", "switch", "case",
        "break", "continue", "this", "super", "extends", "instanceof",
        "try", "catch", "finally", "throw", "with", "yield", "await",
        "static", "enum", "implements", "package", "protected",
        "interface", "private", "public", "arguments", "eval"
    )

    /**
     * Executes a provider module and returns its raw stream results.
     * Runs entirely on the IO dispatcher; never blocks the main thread.
     *
     * [extraModules] carries pre-fetched relative modules (keyed by both
     * their raw specifier and absolute URL) so providers that ship as
     * several files work without bundling.
     */
    suspend fun execute(
        http: OkHttpClient,
        codeUrl: String,
        code: String,
        request: NuvioStreamRequest,
        extraModules: Map<String, String> = emptyMap()
    ): List<NuvioRawStream> = withContext(Dispatchers.IO) {
        // Static analysis BEFORE execution: powers the structured
        // compatibility diagnostics when the provider fails.
        val profile = try {
            ProviderAnalyzer.analyze(code)
        } catch (_: Exception) {
            null
        }
        if (profile != null) {
            logPlugin(
                "compat",
                ("cjs=" + profile.isCommonJS +
                    " esm=" + profile.isESM +
                    " requires=[" + profile.requiredModules.joinToString(",").take(80) + "]" +
                    " browserGlobals=[" + profile.browserGlobals.joinToString(",").take(40) + "]")
                    .take(220)
            )
        }
        val fetchCount = AtomicInteger(0)
        // One HTTP engine per execution: its cookie jar (and any
        // connection state) dies with this call.
        val engine = ProviderHttpEngine(http, fetchTimeoutMs, maxResponseBytes)
        // ES module graph for ESM providers: populated after the compat
        // layer is evaluated, before the entry module runs.
        val esmModules = HashMap<String, String>()
        val isEsm = profile?.isESM == true
        val loader = moduleLoader {
            normalize { baseName, requestedName ->
                if (requestedName.startsWith("./") || requestedName.startsWith("../")) {
                    runCatching {
                        baseName.toHttpUrlOrNull()?.resolve(requestedName)?.toString()
                    }.getOrNull() ?: requestedName
                } else {
                    requestedName
                }
            }
            load { name -> esmModules[name]?.let { ModuleContent.Source(it) } }
        }
        val quickJs = QuickJs.create(jobDispatcher = Dispatchers.IO, moduleLoader = loader)
        try {
            quickJs.memoryLimit = memoryLimitBytes
            quickJs.maxStackSize = stackLimitBytes
            // Only time spent executing JavaScript counts here; the
            // overall wall clock is bounded by the caller's timeout.
            quickJs.evaluationTimeoutMillis = busyTimeoutMs

            bindHostFunctions(quickJs, engine, fetchCount)

            // NOTE: quickjs-kt's evaluate() resolves to the script's
            // completion value; it must be read as Any? (a Unit cast
            // fails whenever the script ends in a non-undefined value,
            // such as the prelude's final assignment).
            val result = try {
                quickJs.evaluate<Any?>(PRELUDE, filename = "sb-prelude.js")
                // The compatibility layer reads its context (the exact
                // user agent fetch() sends, the provider's real URL) from
                // these assignments before it builds location/navigator.
                quickJs.evaluate<Any?>(
                    "globalThis.__sbUserAgent = " + JsonPrimitive(ProviderHttpEngine.PROVIDER_UA) + ";" +
                        "globalThis.__sbLocationHref = " + JsonPrimitive(codeUrl) + ";",
                    filename = "sb-context.js"
                )
                quickJs.evaluate<Any?>(CompatPrelude.JS, filename = "sb-compat.js")
                extraModules.forEach { (name, moduleSource) ->
                    quickJs.evaluate<Any?>(
                        "globalThis.__sbRegisterModuleSource(" + JsonPrimitive(name) + ", " +
                            JsonPrimitive(moduleSource) + ");",
                        filename = "sb-module.js"
                    )
                }
                if (isEsm) {
                    registerEsmModules(quickJs, esmModules, profile!!, codeUrl, code, extraModules)
                    quickJs.evaluate<Any?>(
                        "import * as __sbProviderModule from " + JsonPrimitive(codeUrl) + ";" +
                            "globalThis.__sbEsmProvider = __sbProviderModule;",
                        filename = "sb-esm-entry.js",
                        asModule = true
                    )
                    quickJs.evaluate<Any?>(
                        buildEsmWrapper(request), filename = "provider-esm.js"
                    )
                } else {
                    quickJs.evaluate<Any?>(buildWrapper(code, request, codeUrl), filename = "provider.js")
                }
                val error = quickJs.evaluate<Any?>(
                    "__sbOut.error == null ? '' : String(__sbOut.error)"
                )
                if (error is String && error.isNotBlank()) {
                    throw providerFailure(error, profile)
                }
                quickJs.evaluate<Any?>(
                    "Array.isArray(__sbOut.result) ? __sbOut.result : []"
                )
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (e: com.dokar.quickjs.QuickJsException) {
                // Syntax errors, synchronous throws, timeouts, conversion
                // failures: a uniform controlled failure carrying the
                // engine's line info.
                throw providerFailure(e.message ?: "", profile)
            } catch (e: NuvioPluginException) {
                throw e
            } catch (e: Exception) {
                // Interruption/limit exceptions that are not derived from
                // QuickJsException in every engine version: still a
                // controlled failure, never a raw escape into the caller.
                throw providerFailure(e.message ?: "", profile)
            }
            mapStreams(result)
        } finally {
            quickJs.close()
        }
    }

    /**
     * Builds the ESM module graph for an ES-module provider: shims that
     * bridge bare imports into the CommonJS registry (with full named
     * exports, enumerated from the real module), pre-fetched relative
     * modules under their absolute URLs, and the provider itself under
     * its real code URL.
     */
    private fun registerEsmModules(
        quickJs: QuickJs,
        esmModules: HashMap<String, String>,
        profile: ProviderProfile,
        codeUrl: String,
        code: String,
        extraModules: Map<String, String>
    ) {
        for (spec in profile.requiredModules) {
            if (ProviderAnalyzer.isRelativeModule(spec)) { continue }
            val moduleName = ProviderAnalyzer.normalizeSpecifier(spec)
            // Enumerate the real module's exports so `import { load } from
            // "cheerio"` resolves — no hardcoded export tables.
            val keysJson = try {
                quickJs.evaluate<Any?>(
                    "JSON.stringify(Object.keys(require(" + JsonPrimitive(moduleName) + ")))"
                ) as? String ?: "[]"
            } catch (e: com.dokar.quickjs.QuickJsException) {
                throw NuvioPluginException(e.message ?: "Cannot load module '" + moduleName + "'")
            }
            val keys = try {
                val array = kotlinx.serialization.json.Json.parseToJsonElement(keysJson)
                (array as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { element ->
                        (element as? kotlinx.serialization.json.JsonPrimitive)?.content
                    }
                    ?.filter { key -> jsIdentifier.matches(key) && key !in reservedExportNames }
                    ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
            val shim = buildString {
                append("var __m = require(").append(JsonPrimitive(moduleName)).append(");\n")
                append("export default __m;\n")
                for (key in keys) {
                    append("export var ").append(key).append(" = __m.").append(key).append(";\n")
                }
            }
            esmModules[spec] = shim
            if (moduleName != spec) { esmModules[moduleName] = shim }
        }
        // Relative modules live under their absolute URLs (the normalizer
        // resolves every relative import). Bare names in extraModules are
        // the CJS bundles — the shims above own those names.
        extraModules.forEach { (name, source) ->
            if (name.startsWith("http")) { esmModules[name] = source }
        }
        esmModules[codeUrl] = code
    }

    /** The classic-script harness that runs an ESM provider's exports. */
    private fun buildEsmWrapper(request: NuvioStreamRequest): String {
        val head = """
            var __sbOut = { result: null, error: null, done: false };
            var __sbNamespace = globalThis.__sbEsmProvider || {};
            var module = { exports: (typeof __sbNamespace.getStreams === "function")
              ? __sbNamespace
              : (__sbNamespace && typeof __sbNamespace.default === "object" &&
                 typeof __sbNamespace.default.getStreams === "function")
                ? __sbNamespace.default
                : (__sbNamespace && typeof __sbNamespace.default === "function")
                  ? { getStreams: __sbNamespace.default }
                  : {} };
            var exports = module.exports;
        """.trimIndent()
        return head + "\n" + wrapperTail(request)
    }

    /**
     * Wraps a provider error message with the structured compatibility
     * diagnostic (Status / Detected / Missing / Action) whenever static
     * analysis can add context about WHAT the provider needed.
     */
    private fun providerFailure(message: String, profile: ProviderProfile?): NuvioPluginException {
        val headline = message.take(200).ifBlank { "Provider failed to execute" }
        val full = if (profile != null && ProviderAnalyzer.shouldAttachDiagnostic(headline, profile)) {
            headline + "\n\n" + ProviderAnalyzer.diagnostic(profile, headline)
        } else {
            headline
        }
        return NuvioPluginException(full.take(700))
    }

    // -----------------------------------------------------------------
    // Host bindings (the ONLY capabilities exposed to provider code)
    // -----------------------------------------------------------------

    private fun bindHostFunctions(
        quickJs: QuickJs,
        engine: ProviderHttpEngine,
        fetchCount: AtomicInteger
    ) {
        // Controlled HTTP through the dedicated engine (hard limits,
        // per-execution cookie jar, transparent content decoding).
        quickJs.asyncFunction<Any?>("__sbHttp") { args ->
            val options = args.firstOrNull() as? Map<String, Any?> ?: emptyMap()
            val url = options["url"]?.toString().orEmpty()
            val method = (options["method"]?.toString() ?: "GET").uppercase()
            val headers = (options["headers"] as? Map<String, Any?>)
                ?.mapNotNull { (key, value) ->
                    val name = key.toString().trim()
                    if (name.isEmpty()) null else name to value.toString()
                }
                ?.toMap()
                ?: emptyMap()
            val body = options["body"]?.toString()
            val bodyIsBase64 = options["bodyBase64"] == true
            val timeoutMs = (options["timeoutMs"] as? Number)?.toLong()

            if (fetchCount.incrementAndGet() > maxFetchesPerCall) {
                throw NuvioPluginException("Provider exceeded $maxFetchesPerCall requests per call")
            }
            engine.perform(url, method, headers, body, bodyIsBase64, timeoutMs)
        }

        quickJs.function<Any?>("__sbLog") { args ->
            val level = args.getOrNull(0)?.toString() ?: "log"
            val message = args.getOrNull(1)?.toString() ?: ""
            logPlugin(level, message.take(300))
            null
        }

        quickJs.function<Any?>("__sbAtob") { args ->
            val input = args.getOrNull(0)?.toString().orEmpty()
            try {
                // The MIME decoder tolerates line breaks and stray
                // whitespace, which real-world embedded payloads contain.
                Base64.getMimeDecoder().decode(input).toString(Charsets.ISO_8859_1)
            } catch (_: IllegalArgumentException) {
                throw NuvioPluginException("atob() received invalid input")
            }
        }

        quickJs.function<Any?>("__sbBtoa") { args ->
            val input = args.getOrNull(0)?.toString().orEmpty()
            // Standard PADDED base64, exactly like browsers and Node —
            // Buffer.toString("base64") and btoa() must agree with them.
            Base64.getEncoder()
                .encodeToString(input.toByteArray(Charsets.ISO_8859_1))
        }

        // Real-delay timers: the JS side schedules through this binding
        // and the engine's async-job loop keeps the call alive until the
        // pending timers settle (the result wrapper races a short grace
        // window so abandoned timers can never stall a resolved call).
        quickJs.asyncFunction<Any?>("__sbSetTimeout") { args ->
            val ms = (args.getOrNull(1) as? Number)?.toLong() ?: 0L
            delay(ms.coerceIn(0L, 30_000L))
            null
        }

        // document.cookie — the SAME jar the HTTP engine uses, scoped
        // to the origin of the most recent response (browsing context).
        quickJs.function<Any?>("__sbGetCookies") { _ ->
            engine.cookieHeaderForContext()
        }
        quickJs.function<Any?>("__sbSetCookie") { args ->
            engine.setCookieFromJs(args.getOrNull(0)?.toString().orEmpty())
            null
        }

        // The crypto capability: hashing, HMAC, AES (CBC/CTR/ECB/GCM),
        // random bytes and PBKDF2, implemented with javax.crypto.
        quickJs.function<Any?>("__sbCrypto") { args ->
            val op = args.getOrNull(0)?.toString().orEmpty()
            val payload = args.getOrNull(1)?.toString() ?: "{}"
            try {
                CryptoCapability.handle(op, payload)
            } catch (e: CryptoCapability.Unsupported) {
                throw NuvioPluginException(e.message ?: "The crypto operation is not supported")
            } catch (e: Exception) {
                throw NuvioPluginException(
                    "The crypto operation failed: " +
                        (e.message?.take(100) ?: e.javaClass.simpleName)
                )
            }
        }
    }

    // -----------------------------------------------------------------
    // Result mapping
    // -----------------------------------------------------------------

    private fun mapStreams(result: Any?): List<NuvioRawStream> {
        val list = result as? List<Any?> ?: return emptyList()
        return list.mapNotNull { element ->
            val map = element as? Map<String, Any?> ?: return@mapNotNull null
            val url = map["url"]?.toString()?.trim().orEmpty()
            if (url.isBlank() || !url.startsWith("http")) return@mapNotNull null
            NuvioRawStream(
                name = map["name"]?.toString().orEmpty(),
                title = map["title"]?.toString().orEmpty(),
                url = url,
                quality = map["quality"]?.toString().orEmpty(),
                format = map["format"]?.toString().orEmpty(),
                headers = (map["headers"] as? Map<String, Any?>)
                    ?.mapNotNull { (key, value) ->
                        val name = key.toString().trim()
                        if (name.isEmpty()) null else name to value.toString()
                    }
                    ?.toMap()
                    ?: emptyMap()
            )
        }.take(maxStreamsPerCall)
    }

    // -----------------------------------------------------------------
    // JS scaffolding
    // -----------------------------------------------------------------

    /**
     * The CommonJS invocation wrapper. The provider file is evaluated
     * as a classic script with `module`/`exports` in scope and the
     * compatibility layer's global `require` resolving modules; then
     * its exported getStreams() is invoked. The promise chain captures
     * the result into __sbOut (quickjs-kt drains all pending promise
     * jobs before evaluate() returns), giving pending fire-and-forget
     * timers a short grace window first.
     */
    private fun buildWrapper(code: String, request: NuvioStreamRequest, codeUrl: String): String {
        // The provider code is concatenated, never interpolated: real
        // provider bundles freely use JS template literals (`${...}`),
        // which a Kotlin template string would mangle.
        val filename = escapeJs(codeUrl)
        val dirname = escapeJs(codeUrl.substringBeforeLast('/', missingDelimiterValue = "/"))
        val head = """
            var __sbOut = { result: null, error: null, done: false };
            var module = { exports: {} };
            var exports = module.exports;
            var __filename = "$filename";
            var __dirname = "$dirname";
            (function() {
        """.trimIndent()
        return head + "\n" + code + "\n" + wrapperTail(request)
    }

    private fun wrapperTail(request: NuvioStreamRequest): String {
        val id = escapeJs(request.tmdbId)
        val type = escapeJs(request.mediaType)
        val season = request.season?.toString() ?: "null"
        val episode = request.episode?.toString() ?: "null"
        val tail = """
            })();
            Promise.resolve()
              .then(function() {
                if (!module.exports || typeof module.exports.getStreams !== "function") {
                  throw new Error("The provider does not export getStreams(tmdbId, mediaType, season, episode)");
                }
                return module.exports.getStreams("$id", "$type", $season, $episode);
              })
              .then(function(r) {
                // Fire-and-forget timers get a short grace window to
                // land — but calls without pending timers pay nothing,
                // and nothing stalls on an abandoned 30s timer.
                if (!__sbHasPendingTimers()) { return r; }
                return new Promise(function(resolve) {
                  var grace = setTimeout(function() { resolve(); }, 250);
                  __sbTimerIdle().then(function() {
                    clearTimeout(grace);
                    resolve();
                  });
                }).then(function() { return r; });
              })
              .then(function(r) { __sbOut.result = (r == null) ? [] : r; __sbOut.done = true; })
              .catch(function(e) {
                var msg = (e && e.message) ? String(e.message) : String(e);
                if (e && e.stack) {
                  msg += "\n" + String(e.stack).split("\n").slice(0, 4).join("\n");
                }
                __sbOut.error = msg;
                __sbOut.done = true;
              });
        """.trimIndent()
        return tail
    }

    /**
     * Standard-shaped globals for provider code: console, fetch (Web
     * API: ok/status/statusText/url/redirected/headers/text()/json()/
     * arrayBuffer()/bytes(), text AND binary request bodies), atob,
     * btoa and a minimal URLSearchParams. Nothing else is exposed.
     */
    private val PRELUDE = """
        globalThis.console = {
          log: function() { __sbLog("log", __sbJoin(arguments)); },
          warn: function() { __sbLog("warn", __sbJoin(arguments)); },
          error: function() { __sbLog("error", __sbJoin(arguments)); },
          debug: function() { __sbLog("debug", __sbJoin(arguments)); }
        };
        function __sbJoin(args) {
          var out = [];
          for (var i = 0; i < args.length; i++) { out.push(String(args[i])); }
          return out.join(" ");
        }

        // ---- byte helpers (binary request/response bodies) ----------

        function __sbBytesToBase64(input) {
          var u8;
          if (input instanceof Uint8Array) { u8 = input; }
          else if (input instanceof ArrayBuffer) { u8 = new Uint8Array(input); }
          else if (input && typeof input.length === "number") {
            u8 = new Uint8Array(input.length);
            for (var i = 0; i < input.length; i++) { u8[i] = input[i] & 0xff; }
          } else {
            throw new Error("fetch() body must be a string, Uint8Array or ArrayBuffer");
          }
          var binary = "";
          var CHUNK = 0x8000;
          for (var j = 0; j < u8.length; j += CHUNK) {
            var end = Math.min(j + CHUNK, u8.length);
            var part = "";
            for (var m = j; m < end; m++) { part += String.fromCharCode(u8[m]); }
            binary += part;
          }
          return btoa(binary);
        }
        function __sbBase64ToArrayBuffer(b64) {
          var binary = atob(String(b64));
          var buffer = new ArrayBuffer(binary.length);
          var u8 = new Uint8Array(buffer);
          for (var i = 0; i < binary.length; i++) { u8[i] = binary.charCodeAt(i); }
          return buffer;
        }
        function __sbUtf8Encode(text) {
          var bytes = unescape(encodeURIComponent(String(text)));
          var buffer = new ArrayBuffer(bytes.length);
          var u8 = new Uint8Array(buffer);
          for (var i = 0; i < bytes.length; i++) { u8[i] = bytes.charCodeAt(i); }
          return buffer;
        }

        globalThis.fetch = function(url, options) {
          options = options || {};
          var headers = {};
          if (options.headers) {
            var h = options.headers;
            if (Array.isArray(h)) {
              for (var i = 0; i < h.length; i++) {
                if (h[i] && h[i].length >= 2) { headers[String(h[i][0])] = String(h[i][1]); }
              }
            } else {
              for (var k in h) {
                if (Object.prototype.hasOwnProperty.call(h, k)) {
                  headers[k] = String(h[k]);
                }
              }
            }
          }
          var body = null;
          var bodyIsBase64 = false;
          if (typeof options.body === "string") {
            body = options.body;
          } else if (options.body != null) {
            body = __sbBytesToBase64(options.body);
            bodyIsBase64 = true;
          }
          if (options.signal && options.signal.aborted === true) {
            var abortError = new Error("The operation was aborted");
            abortError.name = "AbortError";
            return Promise.reject(abortError);
          }
          var call = {
            url: String(url),
            method: options.method || "GET",
            headers: headers,
            body: body,
            bodyBase64: bodyIsBase64,
            timeoutMs: (typeof options.timeoutMs === "number") ? options.timeoutMs : null
          };
          return __sbHttp(call).then(function(envelope) {
            var r = {};
            try { r = JSON.parse(envelope); } catch (e) { r = {}; }
            return __sbMakeResponse(r);
          });
        };

        function __sbMakeResponse(r) {
          var resp = {
            ok: !!r.ok,
            status: r.status || 0,
            statusText: r.statusText || "",
            url: r.url || "",
            redirected: !!r.redirected,
            headers: __sbHeaders(r.headers || {}),
            text: function() {
              if (!r.binary) { return Promise.resolve(r.bodyText || ""); }
              var bytes = new Uint8Array(__sbBase64ToArrayBuffer(r.bodyBase64 || ""));
              return Promise.resolve(new TextDecoder("utf-8").decode(bytes));
            },
            json: function() {
              return resp.text().then(function(t) { return JSON.parse(t); });
            },
            arrayBuffer: function() {
              if (r.binary) { return Promise.resolve(__sbBase64ToArrayBuffer(r.bodyBase64 || "")); }
              return Promise.resolve(__sbUtf8Encode(r.bodyText || ""));
            },
            bytes: function() {
              return resp.arrayBuffer().then(function(buffer) {
                return new Uint8Array(buffer);
              });
            }
          };
          return resp;
        }
        globalThis.atob = function(s) { return __sbAtob(String(s)); };
        globalThis.btoa = function(s) { return __sbBtoa(String(s)); };
        var URLSearchParams = function(init) {
          this._pairs = [];
          if (init && init instanceof URLSearchParams) {
            for (var i = 0; i < init._pairs.length; i++) {
              this._pairs.push([init._pairs[i][0], init._pairs[i][1]]);
            }
          } else if (init && typeof init === "object") {
            for (var k in init) { this._pairs.push([k, String(init[k])]); }
          } else if (typeof init === "string" && init.length > 0) {
            var parts = init.split("&");
            for (var i = 0; i < parts.length; i++) {
              var kv = parts[i].split("=");
              // application/x-www-form-urlencoded: '+' means space.
              this._pairs.push([
                decodeURIComponent(kv[0].replace(/\+/g, " ")),
                decodeURIComponent((kv[1] || "").replace(/\+/g, " "))
              ]);
            }
          }
        };
        URLSearchParams.prototype.append = function(k, v) { this._pairs.push([k, String(v)]); };
        URLSearchParams.prototype.set = function(k, v) {
          for (var i = 0; i < this._pairs.length; i++) {
            if (this._pairs[i][0] === k) { this._pairs[i][1] = String(v); return; }
          }
          this.append(k, v);
        };
        URLSearchParams.prototype.has = function(k) {
          for (var i = 0; i < this._pairs.length; i++) {
            if (this._pairs[i][0] === k) { return true; }
          }
          return false;
        };
        URLSearchParams.prototype.get = function(k) {
          for (var i = 0; i < this._pairs.length; i++) {
            if (this._pairs[i][0] === k) { return this._pairs[i][1]; }
          }
          return null;
        };
        URLSearchParams.prototype.getAll = function(k) {
          var out = [];
          for (var i = 0; i < this._pairs.length; i++) {
            if (this._pairs[i][0] === k) { out.push(this._pairs[i][1]); }
          }
          return out;
        };
        URLSearchParams.prototype.delete = function(k) {
          var kept = [];
          for (var i = 0; i < this._pairs.length; i++) {
            if (this._pairs[i][0] !== k) { kept.push(this._pairs[i]); }
          }
          this._pairs = kept;
        };
        URLSearchParams.prototype.forEach = function(cb) {
          for (var i = 0; i < this._pairs.length; i++) {
            cb(this._pairs[i][1], this._pairs[i][0], this);
          }
        };
        URLSearchParams.prototype.entries = function() {
          var pairs = this._pairs, index = 0;
          return { next: function() {
            if (index < pairs.length) {
              return { value: pairs[index++], done: false };
            }
            return { value: undefined, done: true };
          } };
        };
        URLSearchParams.prototype.keys = function() {
          var entries = this.entries();
          return { next: function() {
            var r = entries.next();
            return r.done ? r : { value: r.value[0], done: false };
          } };
        };
        URLSearchParams.prototype.values = function() {
          var entries = this.entries();
          return { next: function() {
            var r = entries.next();
            return r.done ? r : { value: r.value[1], done: false };
          } };
        };
        URLSearchParams.prototype[Symbol.iterator] = URLSearchParams.prototype.entries;
        URLSearchParams.prototype.toString = function() {
          var out = [];
          for (var i = 0; i < this._pairs.length; i++) {
            out.push(encodeURIComponent(this._pairs[i][0]) + "=" + encodeURIComponent(this._pairs[i][1]));
          }
          return out.join("&");
        };
        globalThis.URLSearchParams = URLSearchParams;

        // ---- Web API shims providers rely on -------------------------

        // Headers: plain lowercase map plus case-insensitive get()/has().
        function __sbHeaders(raw) {
          var h = {};
          for (var k in raw) {
            if (Object.prototype.hasOwnProperty.call(raw, k)) { h[k] = raw[k]; }
          }
          h.get = function(name) {
            var key = String(name).toLowerCase();
            return Object.prototype.hasOwnProperty.call(this, key) ? this[key] : null;
          };
          h.has = function(name) {
            return this.get(name) !== null;
          };
          return h;
        }

        // URL: pragmatic RFC-3986-style parser with relative resolution.
        function __sbNormalizePath(path) {
          var segs = String(path).split("/");
          var out = [];
          for (var i = 0; i < segs.length; i++) {
            var s = segs[i];
            if (s === ".") {
              if (i === segs.length - 1) { out.push(""); }
              continue;
            }
            if (s === "..") {
              if (out.length > 0 && out[out.length - 1] !== "") { out.pop(); }
              if (i === segs.length - 1) { out.push(""); }
              continue;
            }
            out.push(s);
          }
          return out.join("/");
        }

        function __sbParseUrl(url) {
          var rest = String(url);
          var hash = "";
          var hashIndex = rest.indexOf("#");
          if (hashIndex !== -1) { hash = rest.slice(hashIndex); rest = rest.slice(0, hashIndex); }
          var search = "";
          var searchIndex = rest.indexOf("?");
          if (searchIndex !== -1) { search = rest.slice(searchIndex); rest = rest.slice(0, searchIndex); }
          var m = /^([a-zA-Z][a-zA-Z0-9+.-]*):/.exec(rest);
          if (!m) { return null; }
          var protocol = m[1].toLowerCase();
          rest = rest.slice(m[0].length);
          var hadAuthority = rest.slice(0, 2) === "//";
          var host = "", port = "", pathname = "";
          if (hadAuthority) {
            var slash = rest.indexOf("/", 2);
            var authority = slash === -1 ? rest.slice(2) : rest.slice(2, slash);
            pathname = slash === -1 ? "/" : rest.slice(slash);
            var at = authority.lastIndexOf("@");
            if (at !== -1) { authority = authority.slice(at + 1); }
            var colon = authority.lastIndexOf(":");
            if (colon !== -1 && authority.indexOf("]") === -1) {
              port = authority.slice(colon + 1);
              host = authority.slice(0, colon);
            } else {
              host = authority;
            }
          } else {
            pathname = rest;
          }
          if (pathname === "" && hadAuthority) { pathname = "/"; }
          // Hierarchical paths are normalized (dot segments removed),
          // opaque paths (mailto:, data:) are left untouched.
          if (hadAuthority || pathname.slice(0, 1) === "/") {
            pathname = __sbNormalizePath(pathname);
          }
          return {
            protocol: protocol, host: host, port: port, pathname: pathname,
            search: search, hash: hash, hasAuthority: hadAuthority
          };
        }

        function __sbResolveUrl(base, ref) {
          var b = __sbParseUrl(base);
          if (!b) { return ref; }
          var r = String(ref);
          if (/^[a-zA-Z][a-zA-Z0-9+.-]*:/.test(r)) { return r; }
          var rHash = "", rSearch = "";
          var hi = r.indexOf("#");
          if (hi !== -1) { rHash = r.slice(hi); r = r.slice(0, hi); }
          var si = r.indexOf("?");
          if (si !== -1) { rSearch = r.slice(si); r = r.slice(0, si); }
          var origin = b.protocol + "://" + b.host + (b.port ? ":" + b.port : "");
          if (r === "") {
            // Empty or query/fragment-only reference: keep the base path,
            // replace the query when the reference carries one.
            return origin + b.pathname + (rSearch !== "" ? rSearch : b.search) + rHash;
          }
          if (r.slice(0, 2) === "//") { return b.protocol + ":" + r + rSearch + rHash; }
          if (r.slice(0, 1) === "/") { return origin + __sbNormalizePath(r) + rSearch + rHash; }
          var dir = b.pathname.slice(0, b.pathname.lastIndexOf("/") + 1);
          return origin + __sbNormalizePath(dir + r) + rSearch + rHash;
        }

        function URL(input, base) {
          if (!(this instanceof URL)) { return new URL(input, base); }
          var href = String(input == null ? "" : input);
          if (base !== undefined && base !== null) {
            href = __sbResolveUrl(String(base instanceof URL ? base.href : base), href);
          }
          var parts = __sbParseUrl(href);
          if (!parts) { throw new Error("Invalid URL: " + href); }
          this._u = parts;
          this._searchParams = new URLSearchParams(
            parts.search.indexOf("?") === 0 ? parts.search.slice(1) : "");
        }
        URL.prototype.toString = function() { return this.href; };
        URL.prototype.toJSON = function() { return this.href; };
        Object.defineProperty(URL.prototype, "href", { get: function() {
          var u = this._u;
          var auth = u.hasAuthority ? "//" + u.host + (u.port ? ":" + u.port : "") : "";
          return u.protocol + ":" + auth + u.pathname + u.search + u.hash;
        } });
        Object.defineProperty(URL.prototype, "protocol", { get: function() { return this._u.protocol + ":"; } });
        Object.defineProperty(URL.prototype, "host", { get: function() {
          return this._u.host + (this._u.port ? ":" + this._u.port : "");
        } });
        Object.defineProperty(URL.prototype, "hostname", { get: function() { return this._u.host; } });
        Object.defineProperty(URL.prototype, "port", { get: function() { return this._u.port; } });
        Object.defineProperty(URL.prototype, "pathname", { get: function() { return this._u.pathname; } });
        Object.defineProperty(URL.prototype, "search", { get: function() { return this._u.search; } });
        Object.defineProperty(URL.prototype, "hash", { get: function() { return this._u.hash; } });
        Object.defineProperty(URL.prototype, "origin", { get: function() {
          var u = this._u;
          if (!u.hasAuthority || (u.protocol !== "http" && u.protocol !== "https")) { return "null"; }
          return u.protocol + "://" + u.host + (u.port ? ":" + u.port : "");
        } });
        Object.defineProperty(URL.prototype, "searchParams", { get: function() { return this._searchParams; } });
        globalThis.URL = URL;

        // TextDecoder: UTF-8 (default) with a latin-1 best effort.
        function __sbCodePointToString(cp) {
          if (cp < 0 || cp > 0x10ffff || (cp >= 0xd800 && cp <= 0xdfff)) { cp = 0xfffd; }
          if (cp <= 0xffff) { return String.fromCharCode(cp); }
          cp -= 0x10000;
          return String.fromCharCode(0xd800 + (cp >> 10), 0xdc00 + (cp & 0x3ff));
        }
        function TextDecoder(label) {
          this._label = String(label == null ? "utf-8" : label).toLowerCase();
          this.encoding = "utf-8";
        }
        TextDecoder.prototype.decode = function(data) {
          var bytes = data || [];
          var n = bytes.length;
          var out = "";
          var utf8 = this._label === "" || this._label === "utf-8" || this._label === "utf8";
          if (utf8) {
            var i = 0;
            while (i < n) {
              var b = bytes[i] & 0xff;
              var cp = 0xfffd, extra = 0;
              if (b < 0x80) { cp = b; }
              else if ((b & 0xe0) === 0xc0) { cp = b & 0x1f; extra = 1; }
              else if ((b & 0xf0) === 0xe0) { cp = b & 0x0f; extra = 2; }
              else if ((b & 0xf8) === 0xf0) { cp = b & 0x07; extra = 3; }
              i++;
              var ok = true;
              for (var j = 0; j < extra && i < n; j++) {
                var cb = bytes[i] & 0xff;
                if ((cb & 0xc0) !== 0x80) { ok = false; break; }
                cp = (cp << 6) | (cb & 0x3f);
                i++;
              }
              if (!ok || (extra > 0 && cp < 0x80)) { cp = 0xfffd; }
              out += __sbCodePointToString(cp);
            }
            return out;
          }
          for (var k = 0; k < n; k++) { out += String.fromCharCode(bytes[k] & 0xff); }
          return out;
        };
        globalThis.TextDecoder = TextDecoder;
    """.trimIndent()

    private fun escapeJs(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")

    /** Reads at most [max] bytes (bounds memory for untrusted bodies). */
    private fun java.io.InputStream.readAtMost(max: Int): ByteArray {
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

    companion object {
        private val UA = ProviderHttpEngine.PROVIDER_UA
    }
}

/** Controlled plugin failure — always reported, never a crash. */
class NuvioPluginException(message: String) : Exception(message)

/** Logs without crashing when android.util.Log is unavailable (JVM tests). */
internal fun logPlugin(level: String, message: String) {
    try {
        android.util.Log.d("NuvioPlugin", "[${'$'}level] ${'$'}message")
    } catch (_: Throwable) {
        // Unit tests / non-Android environments: Log is not available.
        println("[NuvioPlugin:${'$'}level] ${'$'}message")
    }
}
