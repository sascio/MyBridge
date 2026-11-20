package com.nuvio.app.features.cloudstream

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CloudStreamRepositoryTrustTest {
    @Test fun repositoryMatchingTrimsButDoesNotFoldPathsOrQueries() {
        assertTrue(sameCloudStreamRepositoryUrl("  https://example.com/Repo?q=A  ", "https://example.com/Repo?q=A"))
        assertFalse(sameCloudStreamRepositoryUrl("https://example.com/Repo", "https://example.com/repo"))
        assertFalse(sameCloudStreamRepositoryUrl("https://example.com/Repo?q=A", "https://example.com/Repo?q=a"))
    }
}
