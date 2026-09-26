package com.nuvio.app.features.cloudstream

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression guard for the reported AllMovieLand failure.
 *
 * The user saw `Failed resolution of: Lkotlin/collections/SetsKt;` and it read
 * as a provider bug. It is a host build defect: R8 had removed the multifile
 * facade `kotlin.collections.SetsKt` that `AllMovieLandProvider.cs3` calls
 * `setOf()` through. Misattributing it cost real debugging time, so the
 * classification is pinned here.
 */
class CloudStreamRuntimeFailureTest {

    @Test
    fun `the exact AllMovieLand failure is identified as a host runtime gap`() {
        val diagnosis = CloudStreamRuntimeFailure.describe(
            throwableTypeName = "NoClassDefFoundError",
            rawMessage = "Failed resolution of: Lkotlin/collections/SetsKt;",
            providerName = "AllMovieLandProvider",
        )
        assertEquals(CloudStreamRuntimeFailure.Kind.MISSING_CLASS, diagnosis.kind)
        assertEquals("kotlin.collections.SetsKt", diagnosis.symbol)
        assertTrue(diagnosis.isHostRuntimeGap)
        assertTrue("AllMovieLandProvider" in diagnosis.message)
        assertTrue("kotlin.collections.SetsKt" in diagnosis.message)
        assertTrue("not a provider error" in diagnosis.message, diagnosis.message)
    }

    @Test
    fun `other kotlin stdlib facades are identified the same way`() {
        // The fix must not be specific to SetsKt: any stdlib class a provider
        // resolves by name can be stripped the same way.
        listOf(
            "Lkotlin/collections/CollectionsKt;" to "kotlin.collections.CollectionsKt",
            "Lkotlin/collections/MapsKt;" to "kotlin.collections.MapsKt",
            "Lkotlin/text/StringsKt;" to "kotlin.text.StringsKt",
            "Lkotlin/jvm/internal/Intrinsics;" to "kotlin.jvm.internal.Intrinsics",
            "Lkotlinx/coroutines/BuildersKt;" to "kotlinx.coroutines.BuildersKt",
            "Lokhttp3/OkHttpClient;" to "okhttp3.OkHttpClient",
        ).forEach { (descriptor, expected) ->
            val diagnosis = CloudStreamRuntimeFailure.describe(
                throwableTypeName = "NoClassDefFoundError",
                rawMessage = "Failed resolution of: $descriptor",
            )
            assertEquals(expected, diagnosis.symbol, "for $descriptor")
            assertTrue(diagnosis.isHostRuntimeGap)
        }
    }

    @Test
    fun `a CloudStream api class is identified`() {
        val diagnosis = CloudStreamRuntimeFailure.describe(
            throwableTypeName = "java.lang.NoClassDefFoundError",
            rawMessage = "Failed resolution of: Lcom/lagradost/cloudstream3/utils/ExtractorLink;",
        )
        assertEquals("com.lagradost.cloudstream3.utils.ExtractorLink", diagnosis.symbol)
    }

    @Test
    fun `a JVM style ClassNotFoundException is handled too`() {
        val diagnosis = CloudStreamRuntimeFailure.describe(
            throwableTypeName = "ClassNotFoundException",
            rawMessage = "kotlin.collections.SetsKt",
        )
        assertEquals(CloudStreamRuntimeFailure.Kind.MISSING_CLASS, diagnosis.kind)
        assertEquals("kotlin.collections.SetsKt", diagnosis.symbol)
    }

    @Test
    fun `an ABI version mismatch is reported as a member, not a missing class`() {
        val diagnosis = CloudStreamRuntimeFailure.describe(
            throwableTypeName = "NoSuchMethodError",
            rawMessage = "No virtual method loadLinks(...) in class Lcom/lagradost/cloudstream3/MainAPI;",
            providerName = "SomeProvider",
        )
        assertEquals(CloudStreamRuntimeFailure.Kind.MISSING_MEMBER, diagnosis.kind)
        assertTrue(diagnosis.isHostRuntimeGap)
        assertTrue("different version" in diagnosis.message, diagnosis.message)
    }

    @Test
    fun `a static initialiser failure is reported as a linkage problem`() {
        val diagnosis = CloudStreamRuntimeFailure.describe(
            throwableTypeName = "ExceptionInInitializerError",
            rawMessage = null,
            providerName = "SomeProvider",
        )
        assertEquals(CloudStreamRuntimeFailure.Kind.LINKAGE, diagnosis.kind)
        assertTrue(diagnosis.isHostRuntimeGap)
        assertTrue("SomeProvider" in diagnosis.message)
    }

    @Test
    fun `a genuine provider failure is never relabelled as a host gap`() {
        // The whole point of classifying is that it must not lie in either
        // direction: a network or scraping failure stays the provider's.
        listOf(
            "IOException" to "Unable to resolve host \"example.com\"",
            "IllegalStateException" to "Provider has no S1E3",
            "JsonDecodingException" to "Unexpected JSON token at offset 12",
            "SocketTimeoutException" to "timeout",
        ).forEach { (type, message) ->
            val diagnosis = CloudStreamRuntimeFailure.describe(type, message)
            assertEquals(CloudStreamRuntimeFailure.Kind.PROVIDER, diagnosis.kind, "for $type")
            assertFalse(diagnosis.isHostRuntimeGap, "for $type")
            assertEquals(message, diagnosis.message)
            assertNull(diagnosis.symbol)
        }
    }

    @Test
    fun `a failure with no message still produces something reportable`() {
        val diagnosis = CloudStreamRuntimeFailure.describe("NullPointerException", null)
        assertEquals("NullPointerException", diagnosis.message)
    }
}
