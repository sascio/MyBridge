package com.nuvio.app.features.cloudstream

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** UX states rendered by the Extensions screen. */
class CloudStreamUiStateTest {

    private fun plugin(id: String, tvTypes: List<String> = listOf("Movie")) = CloudStreamPlugin(
        id = id,
        displayName = id,
        version = 1,
        description = null,
        authors = emptyList(),
        language = null,
        tvTypes = tvTypes,
        iconUrl = null,
        artifactUrl = "https://e.com/$id.cs3",
        repositoryUrl = null,
        fileSize = null,
        fileHash = null,
        apiVersion = 1,
        compatibility = CloudStreamCompatibility.UNSUPPORTED,
        compatibilityReason = CloudStreamCompatibilityReason.REQUIRES_NATIVE_EXECUTION,
    )

    private fun extension(id: String, tvTypes: List<String> = listOf("Movie")) =
        CloudStreamExtensionMapping.toExtension(plugin(id, tvTypes), "https://e.com/repo.json")

    @Test
    fun `initial state is empty and has not loaded`() {
        val state = CloudStreamUiState()
        assertTrue(state.isEmpty)
        assertTrue(!state.hasLoadedOnce)
        assertTrue(!state.hasRepositories)
        assertEquals(0, state.overview.extensionCount)
    }

    @Test
    fun `overview is derived from the discovered extensions`() {
        val state = CloudStreamUiState(
            hasLoadedOnce = true,
            extensions = listOf(
                extension("A", listOf("Movie", "TvSeries")),
                extension("B", listOf("Anime")),
            ),
        )
        assertEquals(2, state.overview.extensionCount)
        assertEquals(3, state.overview.catalogCount)
        assertEquals(0, state.overview.activeCount)
        assertTrue(!state.isEmpty)
    }

    @Test
    fun `failed repositories are exposed for the problem banner`() {
        val state = CloudStreamUiState(
            hasLoadedOnce = true,
            repositories = listOf(
                CloudStreamRepository(url = "https://ok.com", name = "OK"),
                CloudStreamRepository(
                    url = "https://bad.com",
                    name = "Bad",
                    compatibility = CloudStreamCompatibility.FAILED,
                    compatibilityReason = CloudStreamCompatibilityReason.MALFORMED_OR_UNREACHABLE,
                    errorMessage = "Could not reach the repository.",
                ),
            ),
        )
        assertTrue(state.hasRepositories)
        val failed = state.failedRepositories.single()
        assertEquals("Bad", failed.name)
        assertNotNull(failed.errorMessage)
    }

    @Test
    fun `a repository with no extensions yields the empty state`() {
        val state = CloudStreamUiState(
            hasLoadedOnce = true,
            repositories = listOf(CloudStreamRepository(url = "https://ok.com", name = "OK")),
            extensions = emptyList(),
        )
        assertTrue(state.hasRepositories)
        assertTrue(state.isEmpty)
    }

    @Test
    fun `loading and refreshing are distinct states`() {
        val loading = CloudStreamUiState(isLoading = true)
        val refreshing = CloudStreamUiState(isRefreshing = true, hasLoadedOnce = true)
        assertTrue(loading.isLoading && !loading.isRefreshing)
        assertTrue(refreshing.isRefreshing && !refreshing.isLoading)
        assertTrue(refreshing.hasLoadedOnce)
    }

    @Test
    fun `an extension can be selected by id for the detail screen`() {
        val extensions = listOf(extension("Alpha"), extension("Beta"))
        val selected = extensions.firstOrNull { it.id == "Beta" }
        assertNotNull(selected)
        assertEquals("Beta", selected.name)
        assertEquals(1, selected.sourceCount)
    }

    @Test
    fun `selecting an unknown id yields no extension rather than crashing`() {
        val extensions = listOf(extension("Alpha"))
        assertEquals(null, extensions.firstOrNull { it.id == "Missing" })
    }

    @Test
    fun `an error message is surfaced without discarding discovered extensions`() {
        val state = CloudStreamUiState(
            hasLoadedOnce = true,
            extensions = listOf(extension("A")),
            errorMessage = "That repository has already been added.",
        )
        assertEquals(1, state.overview.extensionCount)
        assertNotNull(state.errorMessage)
    }
}
