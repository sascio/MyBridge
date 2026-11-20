package com.nuvio.app.features.cloudstream

import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertFalse

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CloudStreamPackageStorageTest {
    private fun plugin(bytes: ByteArray, url: String = "https://example.com/plugin.cs3") = CloudStreamPlugin(
        id = "storage-test", displayName = "Storage test", version = 1, description = null,
        authors = emptyList(), language = null, tvTypes = emptyList(), iconUrl = null,
        artifactUrl = url, repositoryUrl = "https://example.com/repo", fileSize = null,
        fileHash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
        apiVersion = 1, compatibility = CloudStreamCompatibility.COMPATIBLE,
        compatibilityReason = CloudStreamCompatibilityReason.NONE,
    )

    @Test fun failedCommitKeepsPreviousPackageAndCleansStage() {
        val context = RuntimeEnvironment.getApplication()
        val old = "previous package".encodeToByteArray()
        val replacement = "replacement package".encodeToByteArray()
        val original = plugin(old)
        CloudStreamPackageStorage.remove(context, original)
        try {
            CloudStreamPackageStorage.install(context, original, old) { stage, target ->
                check(stage.renameTo(target))
            }
            assertFailsWith<IOException> {
                CloudStreamPackageStorage.install(context, plugin(replacement), replacement) { _, _ ->
                    throw IOException("simulated rename failure")
                }
            }
            val installed = assertNotNull(CloudStreamPackageStorage.packageFile(context, original))
            assertContentEquals(old, installed.readBytes())
            assertFalse(java.io.File(installed.parentFile, "${installed.name}.part").exists())
        } finally {
            CloudStreamPackageStorage.remove(context, original)
        }
    }

    @Test fun invalidDownloadUrlFailsBeforeRequestIsBuilt(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        CloudStreamPackageInstaller.initialize(context)
        val invalid = plugin("x".encodeToByteArray(), "javascript:alert(1)")
        val result = CloudStreamPackageInstaller.install(invalid) as CloudStreamInstallResult.Failure
        assertEquals(CloudStreamInstallError.INVALID_RESPONSE, result.error)
    }
}
