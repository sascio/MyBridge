package com.nuvio.app.features.cloudstream

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertNotEquals

class CloudStreamRepositoryTrustTest {
    @Test fun pinKeyIsPerRepositoryAndExtensionNotPerSource() {
        assertTrue(cloudStreamPinKey(" https://example.com/Repo ", "plugin") == cloudStreamPinKey("https://example.com/Repo", "plugin"))
        assertNotEquals(cloudStreamPinKey("https://example.com/Repo", "plugin"), cloudStreamPinKey("https://example.com/repo", "plugin"))
        assertNotEquals(cloudStreamPinKey("https://example.com/Repo", "plugin"), cloudStreamPinKey("https://example.com/Repo", "other"))
    }

    @Test fun repositoryMatchingTrimsButDoesNotFoldPathsOrQueries() {
        assertTrue(sameCloudStreamRepositoryUrl("  https://example.com/Repo?q=A  ", "https://example.com/Repo?q=A"))
        assertFalse(sameCloudStreamRepositoryUrl("https://example.com/Repo", "https://example.com/repo"))
        assertFalse(sameCloudStreamRepositoryUrl("https://example.com/Repo?q=A", "https://example.com/Repo?q=a"))
    }

    // --- authorization -----------------------------------------------------

    private val configured = listOf("https://repo.example/repo.json", "https://other.example/repo.json")

    private fun authorized(setting: Map<String, String>, url: String?) =
        cloudStreamRepositoryAuthorized(configured, setting, url)

    @Test fun addingARepositoryDoesNotAuthorizeIt() {
        assertFalse(authorized(emptyMap(), "https://repo.example/repo.json"))
    }

    @Test fun explicitAuthorizationIsRecognisedForTheConfiguredUrl() {
        val setting = mapOf(cloudStreamTrustKey("https://repo.example/repo.json") to "true")
        assertTrue(authorized(setting, "https://repo.example/repo.json"))
        // Trimming is allowed; the URL itself is otherwise compared verbatim.
        assertTrue(authorized(setting, "  https://repo.example/repo.json  "))
    }

    @Test fun authorizationCoversEveryExtensionOfTheSameRepository() {
        // Every extension carries its repository URL, and the check is on the
        // URL alone, so one authorization covers the whole repository (CNC
        // Verse providers included) without per-extension grants.
        val setting = mapOf(cloudStreamTrustKey("https://repo.example/repo.json") to "true")
        val pluginRepositories = listOf(
            "https://repo.example/repo.json",
            "https://repo.example/repo.json",
            "https://repo.example/repo.json",
        )
        assertTrue(pluginRepositories.all { authorized(setting, it) })
    }

    @Test fun aDifferentRepositoryStaysUnauthorized() {
        val setting = mapOf(cloudStreamTrustKey("https://repo.example/repo.json") to "true")
        assertFalse(authorized(setting, "https://other.example/repo.json"))
        assertFalse(authorized(setting, "https://repo.example/repo.json?mirror=1"))
        assertFalse(authorized(setting, "https://repo.example/REPO.json"))
    }

    @Test fun anAuthorizationKeyForAnUnconfiguredRepositoryGrantsNothing() {
        // A stale key (e.g. left over after the URL was edited) must never
        // authorize anything, even though the stored value is "true".
        val setting = mapOf(cloudStreamTrustKey("https://removed.example/repo.json") to "true")
        assertFalse(authorized(setting, "https://removed.example/repo.json"))
        assertFalse(authorized(setting, null))
    }

    @Test fun trustKeyIsStableAndRepositoryScoped() {
        assertEquals(
            cloudStreamTrustKey(" https://repo.example/repo.json "),
            cloudStreamTrustKey("https://repo.example/repo.json"),
        )
        assertNotEquals(
            cloudStreamTrustKey("https://repo.example/repo.json"),
            cloudStreamTrustKey("https://repo.example/other.json"),
        )
    }

    // --- blocked-state surfacing -------------------------------------------

    private fun state(error: String?, trusted: Set<String> = emptySet()) = CloudStreamUiState(
        hasLoadedOnce = true,
        repositories = configured.map { CloudStreamRepository(url = it, name = it) },
        trustedRepositories = trusted,
        errorMessage = error,
    )

    @Test fun installRefusalNamesAnUnauthorizedRepositoryAndCarriesTheAction() {
        val blocked = state(unauthorizedInstallMessage)
        assertEquals("https://repo.example/repo.json", blocked.unauthorizedRepositoryUrl)
    }

    @Test fun enableRefusalIsSurfacedTheSameWay() {
        val blocked = state(unauthorizedEnableMessage)
        assertEquals("https://repo.example/repo.json", blocked.unauthorizedRepositoryUrl)
    }

    @Test fun unrelatedErrorsDoNotShowTheAuthorizationPrompt() {
        assertNull(state("The repository could not be read.").unauthorizedRepositoryUrl)
    }

    @Test fun authorizingEveryRepositoryClearsThePrompt() {
        val blocked = state(unauthorizedInstallMessage)
        assertEquals("https://repo.example/repo.json", blocked.unauthorizedRepositoryUrl)

        // Authorizing the repository that was refused removes it from the
        // prompt; the next still-unauthorized repository is what remains.
        val partlyAuthorized = state(
            error = unauthorizedInstallMessage,
            trusted = setOf("https://repo.example/repo.json"),
        )
        assertEquals("https://other.example/repo.json", partlyAuthorized.unauthorizedRepositoryUrl)

        val fullyAuthorized = state(
            error = unauthorizedInstallMessage,
            trusted = configured.toSet(),
        )
        assertNull(fullyAuthorized.unauthorizedRepositoryUrl)
        // setRepositoryTrusted also drops the refusal message itself, so the
        // screen returns to its normal state after consent.
        assertNull(state(error = null).unauthorizedRepositoryUrl)
    }
}
