package com.nuvio.app.features.cloudstream

import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
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
    @Test fun simultaneousInstallsUseDistinctStagingFiles() {
        val context = RuntimeEnvironment.getApplication()
        val one = "first package".encodeToByteArray()
        val two = "second package".encodeToByteArray()
        val original = plugin(one)
        val pool = Executors.newFixedThreadPool(2)
        val bothStaged = CountDownLatch(2)
        val names = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        CloudStreamPackageStorage.remove(context, original)
        try {
            val tasks = listOf(one, two).map { bytes ->
                pool.submit {
                    CloudStreamPackageStorage.install(context, plugin(bytes), bytes) { stage, target ->
                        names.add(stage.name)
                        bothStaged.countDown()
                        check(bothStaged.await(10, TimeUnit.SECONDS))
                        check(stage.renameTo(target))
                    }
                }
            }
            tasks.forEach { it.get(15, TimeUnit.SECONDS) }
            assertEquals(2, names.size)
            val result = assertNotNull(CloudStreamPackageStorage.packageFile(context, original)).readBytes()
            kotlin.test.assertTrue(result.contentEquals(one) || result.contentEquals(two))
        } finally {
            pool.shutdownNow()
            CloudStreamPackageStorage.remove(context, original)
        }
    }

    @Test fun cancellingDownloadStopsTheCallWithoutPublishing(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        CloudStreamPackageInstaller.initialize(context)
        val bytes = "unused".encodeToByteArray()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.start()
            val extension = plugin(bytes, server.url("/plugin.cs3").toString())
            CloudStreamPackageStorage.remove(context, extension)
            try {
                val job = launch(Dispatchers.IO) { CloudStreamPackageInstaller.install(extension) }
                assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })
                withTimeout(3000) { job.cancelAndJoin() }
                assertFalse(CloudStreamPackageStorage.isInstalled(context, extension))
            } finally {
                CloudStreamPackageStorage.remove(context, extension)
            }
        }
    }

}
