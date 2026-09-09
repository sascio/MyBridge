package com.streambridge.app

import com.streambridge.app.addon.plugin.compat.ProviderAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Static provider analysis: module-system detection, dependency and
 * global scanning, and the structured compatibility diagnostics that
 * turn cryptic provider failures into actionable reports.
 */
class ProviderAnalyzerTest {

    @Test
    fun `detects plain CommonJS providers`() {
        val profile = ProviderAnalyzer.analyze(
            """
            const axios = require("axios");
            module.exports = { getStreams: async () => [] };
            """.trimIndent()
        )
        assertTrue(profile.isCommonJS)
        assertFalse(profile.isESM)
        assertEquals(listOf("axios"), profile.requiredModules)
    }

    @Test
    fun `detects obfuscated esbuild bundles like the real Nuvio providers`() {
        // Faithful shape of animepahe.js: __toESM(require(...)) interop.
        val profile = ProviderAnalyzer.analyze(
            """
            var __toESM = (mod, isNodeMode) => { ... };
            var import_cheerio = __toESM(require("cheerio-without-node-native"));
            async function getStreams(tmdbId, mediaType, season, episode) {
              const $ = import_cheerio.default.load(html);
              $("a").each((i, el) => { $(el).attr("data-src"); });
              return [];
            }
            module.exports = { getStreams };
            """.trimIndent()
        )
        assertTrue(profile.isCommonJS)
        assertTrue(profile.usesCheerio)
        assertTrue(profile.requiredModules.contains("cheerio-without-node-native"))
        assertFalse(profile.hasUnsupportedRequires)
    }

    @Test
    fun `detects ESM providers`() {
        val profile = ProviderAnalyzer.analyze(
            """
            import { load } from "cheerio";
            export async function getStreams(id) { return []; }
            """.trimIndent()
        )
        assertTrue(profile.isESM)
        assertTrue(profile.usesCheerio)
        assertTrue(profile.requiredModules.contains("cheerio"))
    }

    @Test
    fun `detects browser and Node globals`() {
        val profile = ProviderAnalyzer.analyze(
            """
            if (typeof window !== "undefined" && window.location.href) { }
            var size = Buffer.byteLength("x");
            if (typeof process !== "undefined" && process.env.DEBUG) { }
            module.exports = {};
            """.trimIndent()
        )
        assertTrue(profile.browserGlobals.containsAll(listOf("window", "location")))
        assertTrue(profile.nodeGlobals.containsAll(listOf("Buffer", "process")))
    }

    @Test
    fun `detects relative modules for pre-fetching`() {
        val profile = ProviderAnalyzer.analyze(
            """
            var helper = require("./helper.js");
            var util2 = require("../shared/util.js");
            var fs = require("fs");
            module.exports = {};
            """.trimIndent()
        )
        assertEquals(listOf("./helper.js", "../shared/util.js"), profile.relativeModules)
        assertTrue(profile.hasUnsupportedRequires)
        assertEquals("fs", profile.firstUnsupportedModule)
    }

    @Test
    fun `node-prefixed specifiers map to the sandbox built-ins`() {
        val profile = ProviderAnalyzer.analyze(
            """
            var crypto = require("node:crypto");
            var url = require("node:url");
            module.exports = {};
            """.trimIndent()
        )
        assertFalse(profile.hasUnsupportedRequires)
        assertNull(profile.firstUnsupportedModule)
    }

    @Test
    fun `blocked modules are named as a security boundary`() {
        val profile = ProviderAnalyzer.analyze(
            """var fs = require("fs"); module.exports = {};"""
        )
        val text = ProviderAnalyzer.diagnostic(
            profile,
            "Cannot find module 'fs': require('fs') is not available in the StreamBridge provider sandbox (MODULE_NOT_FOUND)"
        )
        assertTrue(text.contains("Status: Unsupported Dependency"))
        assertTrue(text.contains("Dependency: fs"))
        assertTrue(text.contains("security boundary"))
    }

    @Test
    fun `unsupported dependencies are named in the diagnostic`() {
        val profile = ProviderAnalyzer.analyze(
            """var sdk = require("some-vendor-sdk"); module.exports = {};"""
        )
        val text = ProviderAnalyzer.diagnostic(
            profile,
            "Cannot find module 'some-vendor-sdk': require('some-vendor-sdk') is not available in the StreamBridge provider sandbox (MODULE_NOT_FOUND)"
        )
        assertTrue(text.contains("Dependency: some-vendor-sdk"))
        assertTrue(text.contains("Status:"))
        assertTrue(text.contains("Action:"))
    }

    @Test
    fun `is not defined errors name the missing global`() {
        val profile = ProviderAnalyzer.analyze("module.exports = {};")
        val text = ProviderAnalyzer.diagnostic(
            profile, "XMLHttpRequest is not defined"
        )
        assertTrue(text.contains("Runtime global: XMLHttpRequest"))
        assertTrue(text.contains("Status: Incompatible Runtime API"))
    }

    @Test
    fun `diagnostic attaches only when it adds context`() {
        val cheerioProfile = ProviderAnalyzer.analyze(
            """var c = require("cheerio"); module.exports = {};"""
        )
        // A plain provider bug needs no compat context.
        assertFalse(
            ProviderAnalyzer.shouldAttachDiagnostic("boom: HTTP error 500", cheerioProfile)
        )
        // Module-not-found does.
        assertTrue(
            ProviderAnalyzer.shouldAttachDiagnostic("Cannot find module 'x'", cheerioProfile)
        )
        // Providers that reference blocked/unknown modules always get one.
        val fsProfile = ProviderAnalyzer.analyze(
            """var fs = require("fs"); module.exports = {};"""
        )
        assertTrue(ProviderAnalyzer.shouldAttachDiagnostic("boom", fsProfile))
    }

    @Test
    fun `supported module list covers the compat surface`() {
        for (module in listOf(
            "cheerio", "cheerio-without-node-native", "node-forge", "crypto",
            "buffer", "events", "path", "url", "querystring", "util", "assert",
            "string_decoder", "stream", "process", "timers"
        )) {
            assertTrue("expected $module to be supported", module in ProviderAnalyzer.SUPPORTED_MODULES)
        }
    }
}
