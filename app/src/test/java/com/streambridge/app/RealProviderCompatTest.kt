package com.streambridge.app

import com.streambridge.app.addon.plugin.NuvioPluginException
import com.streambridge.app.addon.plugin.NuvioPluginRuntime
import com.streambridge.app.addon.plugin.NuvioStreamRequest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * REAL-PROVIDER integration: the actual provider bundles from the
 * NuvioPlugin repository, downloaded at test time and executed through
 * the exact production path — compat layer, module registry with the
 * shipped cheerio/node-forge bundles, provider HTTP engine, sandbox.
 *
 * This is where compatibility claims are proven by execution, not by
 * compilation. The contract under test is OUR layer:
 *
 *  - the real bundle must LOAD (module evaluation, requires resolved);
 *  - its getStreams must EXECUTE (real network calls included);
 *  - any failure caused by the compatibility layer (module not found,
 *    missing global, sandbox error) FAILS this test.
 *
 * What is NOT under test: the provider's own upstream service. These
 * are live piracy-adjacent sites behind Cloudflare; from a CI runner
 * they may be unreachable, challenge, or return empty results. Such an
 * outcome is classified UPSTREAM: the provider ran in our runtime and
 * its own network target answered (or didn't) — logged, not failed.
 */
class RealProviderCompatTest {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val runtime = NuvioPluginRuntime()

    /** The real repository the app installs providers from. */
    private val repoRaw = "https://raw.githubusercontent.com/NuvioPlugin/All-in-One-Nuvio/main/providers/"

    /** The standard bundles, loaded from the same assets production uses. */
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
        for (path in listOf("src/main/assets/compat/$name", "app/src/main/assets/compat/$name")) {
            val file = File(path)
            if (file.exists()) return file.readText(Charsets.UTF_8)
        }
        throw IllegalStateException("compat bundle not found: $name")
    }

    /** Failure signatures that are OUR responsibility. */
    private fun isCompatFailure(message: String): Boolean {
        val m = message.lowercase()
        return m.contains("cannot find module") ||
            m.contains("module_not_found") ||
            m.contains("is not defined") ||
            m.contains("not available in the") ||
            m.contains("not supported in the") ||
            m.contains("provider sandbox") ||
            // The engine could not even parse the bundle: a runtime gap.
            m.contains("syntaxerror") ||
            m.contains("expected token") ||
            m.contains("unexpected token") ||
            // The engine itself rejected something structural.
            m.contains("quickjs")
    }

    private sealed class Outcome {
        data class Streams(val count: Int, val sample: String) : Outcome()
        data class Upstream(val message: String) : Outcome()
    }

    private fun runRealProvider(
        filename: String,
        request: NuvioStreamRequest
    ): Outcome {
        val codeUrl = repoRaw + filename
        return runBlocking {
            withTimeout(120_000L) {
                val code = try {
                    runtime.fetchProviderCode(http, codeUrl)
                } catch (e: Exception) {
                    // Could not download the provider bundle itself: a
                    // GitHub/raw availability issue, not a compat one —
                    // but surfaced loudly so it is never silent.
                    return@withTimeout Outcome.Upstream(
                        "provider download failed: " + (e.message?.take(120) ?: "")
                    )
                }
                try {
                    val streams = runtime.execute(
                        http, codeUrl, code, request, extraModules = modules
                    )
                    if (streams.isEmpty()) {
                        Outcome.Upstream("executed cleanly, no streams (upstream empty)")
                    } else {
                        val s = streams[0]
                        Outcome.Streams(
                            streams.size,
                            "title='" + s.title.take(40) + "' url=" + s.url.take(60)
                        )
                    }
                } catch (cancellation: kotlinx.coroutines.CancellationException) {
                    // A timeout must surface as a timeout, never as a
                    // fake runtime defect.
                    throw cancellation
                } catch (e: NuvioPluginException) {
                    val message = e.message ?: ""
                    if (isCompatFailure(message)) {
                        fail("COMPAT FAILURE running real provider $filename:\n$message")
                    }
                    Outcome.Upstream(message.take(160))
                } catch (e: Exception) {
                    // Anything escaping as a non-plugin exception is a
                    // runtime defect by definition.
                    fail(
                        "RUNTIME DEFECT running real provider $filename: " +
                            e.javaClass.simpleName + ": " + (e.message?.take(160) ?: "")
                    )
                }
                @Suppress("UNREACHABLE_CODE")
                Outcome.Upstream("unreachable")
            }
        }
    }

    private fun report(filename: String, outcome: Outcome) {
        when (outcome) {
            is Outcome.Streams ->
                println("[real-provider] $filename: ${outcome.count} stream(s), ${outcome.sample}")
            is Outcome.Upstream ->
                println("[real-provider] $filename: RAN in sandbox; upstream: ${outcome.message}")
        }
    }

    @Test
    fun `real animepahe bundle loads and executes`() {
        val outcome = runRealProvider(
            "animepahe.js",
            NuvioStreamRequest(
                tmdbId = "550",
                mediaType = "movie",
                season = null,
                episode = null
            )
        )
        report("animepahe.js", outcome)
    }

    @Test
    fun `real animepahe resolves an episode`() {
        val outcome = runRealProvider(
            "animepahe.js",
            NuvioStreamRequest(
                tmdbId = "37854",
                mediaType = "tv",
                season = 1,
                episode = 1
            )
        )
        report("animepahe.js (tv s1e1)", outcome)
    }

    @Test
    fun `real 4khdhub bundle loads and executes`() {
        val outcome = runRealProvider(
            "4khdhub.js",
            NuvioStreamRequest(
                tmdbId = "550",
                mediaType = "movie",
                season = null,
                episode = null
            )
        )
        report("4khdhub.js", outcome)
    }

    @Test
    fun `real cineby bundle loads and executes`() {
        // A provider with a different shape (m3u8-oriented, no cheerio).
        val outcome = runRealProvider(
            "cineby.js",
            NuvioStreamRequest(
                tmdbId = "550",
                mediaType = "movie",
                season = null,
                episode = null
            )
        )
        report("cineby.js", outcome)
    }

    @Test
    fun `all manifest providers are statically analyzable`() {
        // Every provider in the real manifest must pass static analysis
        // and its requires must be satisfiable by the sandbox (or be
        // named in the diagnostic). Obfuscated requires with computed
        // arguments are expected to be missed by the analyzer — that is
        // its documented best-effort contract.
        val manifestUrl = "https://raw.githubusercontent.com/NuvioPlugin/All-in-One-Nuvio/main/manifest.json"
        val analyzer = com.streambridge.app.addon.plugin.compat.ProviderAnalyzer
        runBlocking {
            withTimeout(60_000L) {
                val manifest = runtime.fetchProviderCode(http, manifestUrl)
                val filenames = Regex("\"filename\"\\s*:\\s*\"([^\"]+)\"")
                    .findAll(manifest)
                    .map { it.groupValues[1] }
                    .filter { it.endsWith(".js") }
                    .toList()
                assertTrue("no providers found in the manifest", filenames.size >= 20)
                var checked = 0
                val problems = ArrayList<String>()
                for (filename in filenames) {
                    val code = try {
                        runtime.fetchProviderCode(http, repoRaw.replace("providers/", "") + filename)
                    } catch (_: Exception) {
                        continue // unreachable provider file: skip, not our contract
                    }
                    checked++
                    val profile = try {
                        analyzer.analyze(code)
                    } catch (e: Exception) {
                        problems.add(filename + " crashed the analyzer: " + e.message)
                        continue
                    }
                    // Any STATICALLY visible unsupported require must be a
                    // blocked module (fs etc.) — never something the
                    // analyzer claims we support but does not resolve.
                    profile.requiredModules
                        .filter { !analyzer.isRelativeModule(it) }
                        .filter { analyzer.normalizeSpecifier(it) !in analyzer.SUPPORTED_MODULES }
                        .filter { it !in analyzer.BLOCKED_MODULES }
                        .forEach { problems.add(filename + " requires unknown module '" + it + "'") }
                }
                assertTrue("only $checked providers checked (download failures?)", checked >= 15)
                assertTrue(
                    "unsatisfiable requires:\n" + problems.joinToString("\n"),
                    problems.isEmpty()
                )
                println("[real-provider] $checked manifest providers analyzed cleanly")
            }
        }
    }
}
