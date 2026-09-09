package com.streambridge.app.addon.plugin.compat

/**
 * Static, best-effort analysis of a provider's source BEFORE execution.
 *
 * Obfuscated providers call require() with computed arguments, so the
 * analyzer never claims to be complete: it detects what it can (module
 * system, browser globals, known dependency names, Node globals) so the
 * runtime can (a) pre-fetch relative modules and (b) turn cryptic
 * runtime errors into useful compatibility diagnostics.
 */
data class ProviderProfile(
    val isCommonJS: Boolean = false,
    val isESM: Boolean = false,
    /** Browser globals referenced by identifier (window, document, …). */
    val browserGlobals: List<String> = emptyList(),
    /** Node globals referenced by identifier (Buffer, process, global). */
    val nodeGlobals: List<String> = emptyList(),
    /** require()/import specifiers that appear as string literals. */
    val requiredModules: List<String> = emptyList(),
    /** Relative specifiers (./x, ../y) — candidates for pre-fetching. */
    val relativeModules: List<String> = emptyList(),
    val usesCheerio: Boolean = false,
    val usesNodeForge: Boolean = false,
    val usesCrypto: Boolean = false,
    val usesTimers: Boolean = false
) {
    val hasUnsupportedRequires: Boolean
        get() = requiredModules.any {
            !ProviderAnalyzer.isRelativeModule(it) &&
                ProviderAnalyzer.normalizeSpecifier(it) !in ProviderAnalyzer.SUPPORTED_MODULES
        }

    /** The first require()/import the sandbox cannot provide, if any. */
    val firstUnsupportedModule: String?
        get() = requiredModules.firstOrNull {
            !ProviderAnalyzer.isRelativeModule(it) &&
                ProviderAnalyzer.normalizeSpecifier(it) !in ProviderAnalyzer.SUPPORTED_MODULES
        }
}

object ProviderAnalyzer {

    /** Modules the compatibility layer can resolve inside the sandbox. */
    val SUPPORTED_MODULES: Set<String> = setOf(
        // HTML engine
        "cheerio", "cheerio-without-node-native",
        // crypto capability
        "crypto", "node-forge",
        // Node-compatible pure-JS adapters
        "url", "querystring", "path", "util", "buffer", "events",
        "string_decoder", "assert", "stream", "process", "timers",
        "util-deprecate", "inherits"
    )

    /** Modules that must NEVER resolve — security boundary, not a TODO. */
    val BLOCKED_MODULES: Set<String> = setOf(
        "fs", "child_process", "worker_threads", "vm", "net", "tls",
        "dns", "os", "cluster", "repl", "v8", "ffi-napi"
    )

    private val BROWSER_GLOBALS = listOf(
        "window", "document", "navigator", "location", "localStorage",
        "sessionStorage", "screen", "history", "DOMParser", "XMLHttpRequest",
        "requestAnimationFrame", "matchMedia", "addEventListener"
    )

    private val NODE_GLOBALS = listOf(
        "Buffer", "process", "global", "setImmediate", "clearImmediate"
    )

    private val UNDEFINED_IDENTIFIER = Regex("""([A-Za-z_$][A-Za-z0-9_$]*) is not defined""")

    /**
     * Whether a structured compatibility diagnostic adds information for
     * this failure (vs. a plain provider bug that needs no context).
     */
    fun shouldAttachDiagnostic(message: String, profile: ProviderProfile): Boolean =
        message.contains("Cannot find module") ||
            message.contains("MODULE_NOT_FOUND") ||
            message.contains("is not defined") ||
            message.contains("not available in the") ||
            message.contains("not supported in the") ||
            profile.firstUnsupportedModule != null ||
            profile.browserGlobals.isNotEmpty()

    private val REQUIRE_LITERAL = Regex(
        """\brequire\s*\(\s*['"]([^'"]+)['"]\s*\)"""
    )
    private val IMPORT_LITERAL = Regex(
        """\bfrom\s*['"]([^'"]+)['"]"""
    )
    private val IMPORT_BARE = Regex(
        """\bimport\s*['"]([^'"]+)['"]"""
    )
    private val ESM_IMPORT = Regex(
        """(?m)^\s*import\s+[\w{},*\s]+?\s+from\s*['"]|^\s*import\s*['"]|^\s*import\s*[\w{},*\s]+from"""
    )
    private val ESM_EXPORT = Regex(
        """(?m)^\s*export\s+(default|const|let|var|function|class|async|\{|\*)"""
    )
    private val CJS_MARKERS = listOf("module.exports", "exports.", "require(")

    fun isRelativeModule(spec: String): Boolean =
        spec.startsWith("./") || spec.startsWith("../") || spec.startsWith("/")

    /** 'node:crypto' and 'crypto' name the same module in this sandbox. */
    fun normalizeSpecifier(spec: String): String =
        if (spec.startsWith("node:")) spec.removePrefix("node:") else spec

    fun analyze(code: String): ProviderProfile {
        val required = (REQUIRE_LITERAL.findAll(code).map { it.groupValues[1] }.toList() +
            IMPORT_LITERAL.findAll(code).map { it.groupValues[1] }.toList() +
            IMPORT_BARE.findAll(code).map { it.groupValues[1] }.toList()).distinct()

        val isEsm = ESM_IMPORT.containsMatchIn(code) || ESM_EXPORT.containsMatchIn(code)
        val isCjs = !isEsm && CJS_MARKERS.any { code.contains(it) }

        return ProviderProfile(
            isCommonJS = isCjs,
            isESM = isEsm,
            browserGlobals = BROWSER_GLOBALS.filter { Regex("""\b$it\b""").containsMatchIn(code) },
            nodeGlobals = NODE_GLOBALS.filter { Regex("""\b$it\b""").containsMatchIn(code) },
            requiredModules = required,
            relativeModules = required.filter { isRelativeModule(it) }.distinct(),
            usesCheerio = required.any { it.startsWith("cheerio") } ||
                Regex("""\bcheerio\b""").containsMatchIn(code),
            usesNodeForge = required.contains("node-forge") ||
                Regex("""\bforge\.""").containsMatchIn(code) ||
                code.contains("node-forge"),
            usesCrypto = code.contains("createHash") || code.contains("createHmac") ||
                code.contains("crypto.subtle") || code.contains("createCipheriv") ||
                code.contains("createDecipheriv") || code.contains("pbkdf2") ||
                code.contains("getRandomValues"),
            usesTimers = Regex("""\b(setTimeout|setInterval)\s*\(""").containsMatchIn(code)
        )
    }

    /**
     * Human-readable compatibility diagnostic for a failed provider,
     * following the structured format the picker shows:
     * Status / Detected / Missing / Action. Never provider-specific.
     */
    fun diagnostic(profile: ProviderProfile, error: String): String {
        val detected = buildList {
            if (profile.isCommonJS) add("CommonJS")
            if (profile.isESM) add("ESM")
            if (profile.usesCheerio) add("Cheerio")
            if (profile.usesNodeForge) add("node-forge")
            if (profile.usesCrypto) add("crypto")
            if (profile.browserGlobals.isNotEmpty()) {
                add("browser globals (" + profile.browserGlobals.joinToString(", ") + ")")
            }
            if (profile.nodeGlobals.isNotEmpty()) {
                add("Node globals (" + profile.nodeGlobals.joinToString(", ") + ")")
            }
        }
        val detectedLine = if (detected.isEmpty()) "unknown" else detected.joinToString(", ")

        val unsupported = profile.requiredModules
            .filter { !isRelativeModule(it) && it !in SUPPORTED_MODULES }
        val blocked = unsupported.filter { it in BLOCKED_MODULES }
        val missingBrowser = profile.browserGlobals.filter { it !in SHIMMED_BROWSER_GLOBALS }

        return if (unsupported.isNotEmpty() &&
            (error.contains("require(") || error.contains("Cannot find module") ||
                error.contains("MODULE_NOT_FOUND"))
        ) {
            """
Status: Unsupported Dependency
Dependency: ${unsupported.first()}
Reason: The provider requires a Node dependency that cannot be executed inside the secure provider sandbox${if (blocked.isNotEmpty()) " (this module is a security boundary and is never available)" else ""}.
Action: The provider was isolated safely and did not affect other providers.
""".trimIndent()
        } else {
            // "window is not defined" — name the exact identifier.
            val undefined = UNDEFINED_IDENTIFIER.find(error)?.groupValues?.get(1)
            val missing = buildList {
                addAll(missingBrowser.map { "Browser API: $it" })
                addAll(blocked.map { "Node module: $it (never available in the sandbox)" })
                addAll(unsupported.filter { it !in blocked }.map { "Dependency: $it" })
                if (undefined != null && missingBrowser.none { it == undefined }) {
                    add("Runtime global: $undefined")
                }
            }
            val missingLine = when {
                missing.isNotEmpty() -> missing.joinToString(", ")
                error.isNotBlank() -> error.take(160)
                else -> "unknown"
            }
            """
Status: Incompatible Runtime API
Detected: $detectedLine
Missing: $missingLine
Action: The provider requires functionality that is not currently available in the StreamBridge sandbox. It was isolated safely and did not affect other providers.
""".trimIndent()
        }
    }

    /** Browser globals the compatibility layer actually provides. */
    val SHIMMED_BROWSER_GLOBALS = setOf(
        "window", "document", "navigator", "location", "localStorage",
        "sessionStorage", "DOMParser", "requestAnimationFrame", "matchMedia"
    )
}
