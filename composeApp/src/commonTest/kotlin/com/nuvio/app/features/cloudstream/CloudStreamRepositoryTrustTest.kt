package com.nuvio.app.features.cloudstream

import kotlin.test.Test
import kotlin.test.assertFalse
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
}
