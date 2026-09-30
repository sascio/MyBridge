package com.nuvio.app.features.cloudstream

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the representative plugin-facing ABI through the probe used by the
 * production PathClassLoader path. The full APK also runs the same probe with
 * the actual per-plugin PathClassLoader and records its defining loaders.
 */
class CloudStreamRuntimeDependencyProbeTest {
    @Test
    fun `parent delegation exposes the AndroidX Material Kotlin and CloudStream ABI`() {
        val results = CloudStreamRuntimeDependencyProbe.inspect(
            CloudStreamRuntimeDependencyProbeTest::class.java.classLoader,
        )

        assertTrue(results.isNotEmpty())
        results.forEach { result ->
                assertTrue(
                    result.resolved,
                    "${result.binaryName} was not resolvable through the runtime parent: " +
                        "${result.errorType}: ${result.errorMessage}",
                )
            }
    }

    @Test
    fun `the Material settings fragment is a generic UI ABI probe`() {
        val material = CloudStreamRuntimeDependencyProbe.resolve(
            "com.google.android.material.bottomsheet.BottomSheetDialogFragment",
            CloudStreamRuntimeDependencyProbeTest::class.java.classLoader,
        )

        assertTrue(material.resolved, "Material UI dependency is not on the full runtime classpath")
        assertEquals(
            "com.google.android.material.bottomsheet.BottomSheetDialogFragment",
            material.binaryName,
        )
    }
}
