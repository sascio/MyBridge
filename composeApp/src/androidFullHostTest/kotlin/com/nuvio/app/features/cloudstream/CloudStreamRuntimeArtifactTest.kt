package com.nuvio.app.features.cloudstream

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integrity and content checks for the embedded CloudStream runtime.
 *
 * The runtime AAR is what makes controlled `.cs3` execution possible, so an
 * accidental (or malicious) replacement must fail loudly rather than silently
 * changing what StreamBridge executes. The Gradle `verifyCloudStreamRuntime`
 * task enforces the hash at build time; this test enforces it again at test
 * time and additionally asserts that the APIs the executor binds against are
 * really present, rather than trusting the filename.
 */
class CloudStreamRuntimeArtifactTest {

    private val expectedSha256 =
        "b67a4384bea1f4072123b86c5f164471422d9c6c12845d5067f12db44674d427"

    private fun artifact(): File {
        // Tests run with a working directory inside the module; walk up until
        // the libs directory is found so this is robust to the exact CWD.
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "libs/cloudstream-runtime-api-4.8.0-3496e5f.aar")
            if (candidate.isFile) return candidate
            val nested = File(dir, "composeApp/libs/cloudstream-runtime-api-4.8.0-3496e5f.aar")
            if (nested.isFile) return nested
            dir = dir.parentFile
        }
        error("CloudStream runtime AAR not found")
    }

    @Test
    fun runtimeArtifactMatchesItsPinnedHash() {
        val bytes = artifact().readBytes()
        val actual = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
        assertEquals(
            expectedSha256,
            actual,
            "CloudStream runtime AAR does not match the pinned SHA-256. " +
                "Refusing to trust an unverified execution runtime.",
        )
    }

    @Test
    fun runtimeArtifactExposesTheApisTheExecutorBindsAgainst() {
        val classNames = ZipFile(artifact()).use { aar ->
            val classesEntry = aar.getEntry("classes.jar")
                ?: error("AAR has no classes.jar")
            aar.getInputStream(classesEntry).use { stream ->
                val temp = File.createTempFile("cs-classes", ".jar").apply { deleteOnExit() }
                temp.outputStream().use(stream::copyTo)
                ZipFile(temp).use { jar -> jar.entries().toList().map { it.name } }
            }
        }

        listOf(
            "com/lagradost/cloudstream3/MainAPI.class",
            "com/lagradost/cloudstream3/APIHolder.class",
            "com/lagradost/cloudstream3/SubtitleFile.class",
            "com/lagradost/cloudstream3/plugins/BasePlugin.class",
            "com/lagradost/cloudstream3/utils/ExtractorLink.class",
            "com/lagradost/cloudstream3/utils/ExtractorApi.class",
        ).forEach { required ->
            assertTrue(required in classNames, "Runtime AAR is missing $required")
        }
    }

    @Test
    fun runtimeArtifactShipsTheSharedExtractorLibrary() {
        // Broad extension compatibility depends on CloudStream's own shared
        // extractors rather than hand-written per-site code, so their presence
        // is part of the contract this artifact must satisfy.
        val extractorCount = ZipFile(artifact()).use { aar ->
            val classesEntry = aar.getEntry("classes.jar") ?: error("AAR has no classes.jar")
            aar.getInputStream(classesEntry).use { stream ->
                val temp = File.createTempFile("cs-classes", ".jar").apply { deleteOnExit() }
                temp.outputStream().use(stream::copyTo)
                ZipFile(temp).use { jar ->
                    jar.entries().toList().count { entry ->
                        entry.name.startsWith("com/lagradost/cloudstream3/extractors/") &&
                            entry.name.endsWith(".class")
                    }
                }
            }
        }
        assertTrue(
            extractorCount > 100,
            "Expected CloudStream's bundled extractor library, found $extractorCount classes",
        )
    }

    @Test
    fun executionIsEnabledOnlyOnThisDistribution() {
        // This test source set compiles only for the full distribution, which
        // is precisely the distribution permitted to execute extensions.
        assertTrue(CloudStreamPlatformRuntime.supportsExecution)
    }
}
