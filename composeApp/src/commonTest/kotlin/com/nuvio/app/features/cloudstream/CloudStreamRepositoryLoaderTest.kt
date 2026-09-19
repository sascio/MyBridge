package com.nuvio.app.features.cloudstream

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Deterministic: the loader's network dependency is injected, never real. */
class CloudStreamRepositoryLoaderTest {

    private val repoUrl = "https://example.com/repo.json"
    private val listUrl = "https://example.com/plugins.json"

    private val repoJson = """
        {"name":"Example","description":"d","manifestVersion":1,"pluginLists":["$listUrl"]}
    """.trimIndent()

    private val pluginsJson = """
        [{"name":"Alpha","internalName":"Alpha","url":"https://e.com/a.cs3","apiVersion":1,"version":3},
         {"name":"Beta","internalName":"Beta","url":"https://e.com/b.cs3","apiVersion":1,"version":1}]
    """.trimIndent()

    /**
     * Execution capability is pinned explicitly so results never depend on
     * which distribution the test host happens to be configured for.
     */
    private fun loader(
        responses: Map<String, String>,
        canExecute: Boolean = true,
    ) = CloudStreamRepositoryLoader(
        fetch = { url -> responses[url] ?: throw IllegalStateException("404 $url") },
        canExecute = canExecute,
    )

    @Test
    fun `loads a repository and its plugins`() = runBlocking {
        val repo = loader(mapOf(repoUrl to repoJson, listUrl to pluginsJson)).load(repoUrl)
        assertEquals(CloudStreamCompatibility.COMPATIBLE, repo.compatibility)
        assertEquals("Example", repo.name)
        assertEquals(2, repo.plugins.size)
        assertTrue(repo.isUsable)
    }

    @Test
    fun `plugins are sorted by display name`() = runBlocking {
        val repo = loader(mapOf(repoUrl to repoJson, listUrl to pluginsJson)).load(repoUrl)
        assertEquals(listOf("Alpha", "Beta"), repo.plugins.map { it.displayName })
    }

    @Test
    fun `an unreachable repository fails gracefully with a message`() = runBlocking {
        val repo = loader(emptyMap()).load(repoUrl)
        assertEquals(CloudStreamCompatibility.FAILED, repo.compatibility)
        assertEquals(
            CloudStreamCompatibilityReason.MALFORMED_OR_UNREACHABLE,
            repo.compatibilityReason,
        )
        assertNotNull(repo.errorMessage)
        assertTrue(!repo.isUsable)
    }

    @Test
    fun `a malformed repository manifest fails gracefully`() = runBlocking {
        val repo = loader(mapOf(repoUrl to "{ broken")).load(repoUrl)
        assertEquals(CloudStreamCompatibility.FAILED, repo.compatibility)
        assertNotNull(repo.errorMessage)
    }

    @Test
    fun `an unreachable plugin list fails the repository without throwing`() = runBlocking {
        val repo = loader(mapOf(repoUrl to repoJson)).load(repoUrl)
        assertEquals(CloudStreamCompatibility.FAILED, repo.compatibility)
        assertNotNull(repo.errorMessage)
    }

    @Test
    fun `a repository with no plugin lists is partially compatible`() = runBlocking {
        val repo = loader(
            mapOf(repoUrl to """{"name":"Empty","manifestVersion":1,"pluginLists":[]}"""),
        ).load(repoUrl)
        assertEquals(CloudStreamCompatibility.PARTIALLY_COMPATIBLE, repo.compatibility)
    }

    @Test
    fun `a newer manifest version is reported unsupported not failed`() = runBlocking {
        val repo = loader(
            mapOf(repoUrl to """{"name":"Future","manifestVersion":99,"pluginLists":["$listUrl"]}"""),
        ).load(repoUrl)
        assertEquals(CloudStreamCompatibility.UNSUPPORTED, repo.compatibility)
        assertEquals(
            CloudStreamCompatibilityReason.UNSUPPORTED_API_VERSION,
            repo.compatibilityReason,
        )
    }

    @Test
    fun `non http urls are rejected up front`() = runBlocking {
        listOf("", "   ", "ftp://x/repo.json", "javascript:alert(1)").forEach { bad ->
            val repo = loader(emptyMap()).load(bad)
            assertEquals(CloudStreamCompatibility.FAILED, repo.compatibility, "for '$bad'")
        }
    }

    @Test
    fun `duplicate plugin ids collapse to the highest version`() = runBlocking {
        val dupes = """
            [{"name":"A","internalName":"A","url":"https://e.com/a1.cs3","apiVersion":1,"version":1},
             {"name":"A","internalName":"A","url":"https://e.com/a2.cs3","apiVersion":1,"version":7}]
        """.trimIndent()
        val repo = loader(mapOf(repoUrl to repoJson, listUrl to dupes)).load(repoUrl)
        assertEquals(1, repo.plugins.size)
        assertEquals(7, repo.plugins.single().version)
    }

    @Test
    fun `discovery never marks a plugin installed even when it is executable`() = runBlocking {
        // Executable describes capability; installed describes disk state. A
        // discovered plugin is never installed, whatever the build can run.
        val repo = loader(mapOf(repoUrl to repoJson, listUrl to pluginsJson)).load(repoUrl)
        assertTrue(repo.plugins.none { it.installed })
        assertTrue(repo.plugins.all { it.isExecutable })
    }

    @Test
    fun `plugins are not executable where the build has no runtime`() = runBlocking {
        val repo = loader(
            mapOf(repoUrl to repoJson, listUrl to pluginsJson),
            canExecute = false,
        ).load(repoUrl)
        assertTrue(repo.plugins.none { it.isExecutable })
        assertTrue(repo.plugins.none { it.installed })
    }
}
