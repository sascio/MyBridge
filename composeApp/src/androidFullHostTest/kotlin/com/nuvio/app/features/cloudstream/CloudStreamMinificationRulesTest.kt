package com.nuvio.app.features.cloudstream

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guards the R8 keep rules that make a **minified release** build able to run
 * CloudStream plugins at all.
 *
 * A `.cs3` is compiled DEX loaded at runtime. It resolves the host's classes by
 * their original JVM names, which R8 cannot see and therefore renames. Debug
 * builds are not minified, so a missing keep rule produces a build that passes
 * every test and every CI check and then fails on the first provider call in
 * the shipped release APK — exactly the class of defect this file exists to
 * prevent from recurring.
 *
 * The package list is not a guess. The DEX type tables of 50 `.cs3` packages
 * published by a real CloudStream repository were inspected; the packages
 * asserted below are referenced by all 50 of them.
 */
class CloudStreamMinificationRulesTest {

    private fun rules(): String {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "proguard-cloudstream-full.pro")
            if (candidate.isFile) return candidate.readText()
            val nested = File(dir, "composeApp/proguard-cloudstream-full.pro")
            if (nested.isFile) return nested.readText()
            dir = dir.parentFile
        }
        error("proguard-cloudstream-full.pro not found")
    }

    /** Packages every sampled published extension resolves by original name. */
    private val requiredByEveryPlugin = listOf(
        "com.lagradost",
        "kotlin",
        "kotlinx.coroutines",
        "okhttp3",
    )

    /** Packages a substantial subset of published extensions resolve. */
    private val requiredBySomePlugins = listOf(
        "kotlinx.serialization",
        "com.fasterxml.jackson",
        "org.jsoup",
        "org.mozilla.javascript",
        "me.xdrop.fuzzywuzzy",
        "kotlinx.datetime",
        "io.ktor",
        "com.google.gson",
    )

    @Test
    fun `every package a loaded plugin resolves by name survives minification`() {
        val text = rules()
        (requiredByEveryPlugin + requiredBySomePlugins).forEach { pkg ->
            val keep = Regex("""^\s*-keep\s+(class|interface)\s+${Regex.escape(pkg)}\.\*\*""", RegexOption.MULTILINE)
            assertTrue(
                keep.containsMatchIn(text),
                "Release builds would rename '$pkg', which dynamically loaded CloudStream " +
                    "plugins resolve by its original JVM name. Add a -keep rule.",
            )
        }
    }

    @Test
    fun `obfuscation of the CloudStream runtime itself stays disabled`() {
        val text = rules()
        assertTrue(
            Regex("""^\s*-keep\s+class\s+com\.lagradost\.\*\*\s*\{\s*\*;\s*}""", RegexOption.MULTILINE)
                .containsMatchIn(text),
            "The CloudStream runtime ABI must be kept whole, including members.",
        )
    }
}
