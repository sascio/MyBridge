package com.nuvio.app.features.cloudstream

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guards the R8 keep rules that make a **minified release** build able to run
 * CloudStream plugins at all.
 *
 * A `.cs3` is compiled DEX loaded at runtime. It resolves the host's classes by
 * their original JVM names, which R8 cannot see and therefore shrinks or
 * renames. Debug builds are not minified, so a missing keep rule produces a
 * build that passes every test and every CI check and then fails on the first
 * provider call in the shipped release APK.
 *
 * That is precisely how `Failed resolution of: Lkotlin/collections/SetsKt;`
 * reached users: `AllMovieLandProvider.cs3` calls
 * `kotlin.collections.SetsKt.setOf`, and R8 had dropped that multifile facade.
 *
 * The package list lives in `composeApp/cloudstream-plugin-abi.txt` and is
 * shared with `.github/verify-cloudstream-apk.py`, which asserts the same
 * symbols are genuinely present in the built APK's dex. This test is the fast
 * guard; that script is the proof on the real artifact.
 */
class CloudStreamMinificationRulesTest {

    private fun repoFile(vararg candidates: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            candidates.forEach { relative ->
                val direct = File(dir, relative)
                if (direct.isFile) return direct
                val nested = File(dir, "composeApp/$relative")
                if (nested.isFile) return nested
            }
            dir = dir.parentFile
        }
        error("None of ${candidates.toList()} found walking up from ${File(".").absolutePath}")
    }

    private fun abiManifest(): List<Pair<String, String>> =
        repoFile("cloudstream-plugin-abi.txt").readLines().mapNotNull { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@mapNotNull null
            val parts = line.split(Regex("\\s+"), limit = 2)
            if (parts.size != 2) null else parts[0] to parts[1].trim()
        }

    private fun keeps(): List<String> = abiManifest().filter { it.first == "keep" }.map { it.second }

    private fun probes(): List<String> = abiManifest().filter { it.first == "probe" }.map { it.second }

    @Test
    fun `the abi manifest is non trivial and well formed`() {
        val keeps = keeps()
        val probes = probes()
        assertTrue(keeps.size >= 10, "Expected the declared plugin ABI, found ${keeps.size} keeps")
        assertTrue(probes.size >= 15, "Expected probe symbols, found ${probes.size}")
        probes.forEach { descriptor ->
            assertTrue(
                descriptor.startsWith("L") && descriptor.endsWith(";"),
                "Probe '$descriptor' is not a JVM type descriptor",
            )
        }
        keeps.forEach { pkg ->
            assertTrue(
                pkg.none { it == '/' || it == ';' },
                "Keep entry '$pkg' should be a dotted java package, not a descriptor",
            )
        }
    }

    @Test
    fun `every package a loaded plugin resolves by name survives minification`() {
        val rules = repoFile("proguard-cloudstream-full.pro").readText()
        keeps().forEach { pkg ->
            val keep = Regex(
                """^\s*-keep\s+(class|interface)\s+${Regex.escape(pkg)}\.\*\*""",
                RegexOption.MULTILINE,
            )
            assertTrue(
                keep.containsMatchIn(rules),
                "Release builds would shrink or rename '$pkg', which dynamically loaded " +
                    "CloudStream plugins resolve by its original JVM name. Add a -keep rule to " +
                    "proguard-cloudstream-full.pro, or drop '$pkg' from cloudstream-plugin-abi.txt.",
            )
        }
    }

    @Test
    fun `the class behind the reported AllMovieLand failure is declared`() {
        // Named explicitly so removing it from the manifest is a deliberate,
        // visible act rather than an accident.
        assertTrue(
            "Lkotlin/collections/SetsKt;" in probes(),
            "kotlin.collections.SetsKt is the multifile facade AllMovieLandProvider.cs3 calls " +
                "setOf() through. It must stay in the declared plugin ABI.",
        )
    }

    @Test
    fun `every probe lives inside a kept package`() {
        // A probe outside a kept package could only pass by luck (some other
        // reference happened to retain it), which is not a guarantee.
        val keepPrefixes = keeps().map { it.replace('.', '/') + "/" }
        probes().forEach { descriptor ->
            val binary = descriptor.removePrefix("L").removeSuffix(";")
            assertTrue(
                keepPrefixes.any { binary.startsWith(it) },
                "Probe '$descriptor' is not covered by any keep rule, so its presence in a " +
                    "minified APK would be accidental rather than guaranteed.",
            )
        }
    }

    @Test
    fun `the Activity boundary stays named for runtime hierarchy diagnostics`() {
        val rules = repoFile("proguard-cloudstream-full.pro").readText()
        assertTrue(
            "-keep class com.nuvio.app.MainActivity { *; }" in rules,
            "The AppCompat host Activity must remain identifiable across the dynamic CloudStream boundary.",
        )
        assertTrue(
            "-keep class com.nuvio.app.launcher.** extends com.nuvio.app.MainActivity { *; }" in rules,
            "Launcher Activity subclasses must retain the AppCompat host hierarchy.",
        )
    }

    @Test
    fun `obfuscation of the CloudStream runtime itself stays disabled`() {
        assertTrue(
            Regex("""^\s*-keep\s+class\s+com\.lagradost\.\*\*\s*\{\s*\*;\s*}""", RegexOption.MULTILINE)
                .containsMatchIn(repoFile("proguard-cloudstream-full.pro").readText()),
            "The CloudStream runtime ABI must be kept whole, including members.",
        )
    }

    @Test
    fun `the kotlin runtime is kept with its members`() {
        // `-keep class kotlin.**` without `{ *; }` would preserve the class name
        // but let R8 rename setOf(), which fails just as hard at runtime.
        assertTrue(
            Regex("""^\s*-keep\s+class\s+kotlin\.\*\*\s*\{\s*\*;\s*}""", RegexOption.MULTILINE)
                .containsMatchIn(repoFile("proguard-cloudstream-full.pro").readText()),
            "kotlin.** must be kept including members.",
        )
    }
}
