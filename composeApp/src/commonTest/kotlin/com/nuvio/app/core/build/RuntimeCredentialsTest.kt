package com.nuvio.app.core.build

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Credential-detection rules used by TraktAuthRepository.hasRequiredCredentials()
 * and SimklAuthRepository.hasRequiredCredentials().
 *
 * All values here are obviously fake test placeholders — never real credentials.
 */
class RuntimeCredentialsTest {

    private val fakeTraktClientId = "test-trakt-client-id"
    private val fakeTraktClientSecret = "test-trakt-client-secret"
    private val fakeSimklClientId = "test-simkl-client-id"

    // ── Configured ────────────────────────────────────────────────────────────

    @Test
    fun configuredTraktCredentialsAreDetected() {
        assertTrue(RuntimeCredentials.allConfigured(fakeTraktClientId, fakeTraktClientSecret))
    }

    @Test
    fun configuredSimklCredentialIsDetected() {
        assertTrue(RuntimeCredentials.isConfigured(fakeSimklClientId))
    }

    // ── Missing ───────────────────────────────────────────────────────────────

    @Test
    fun missingTraktCredentialsAreReportedMissing() {
        // Nothing configured at all — the generated config holds empty strings.
        assertFalse(RuntimeCredentials.allConfigured("", ""))
        // Partially configured must still be treated as missing: Trakt OAuth needs both.
        assertFalse(RuntimeCredentials.allConfigured(fakeTraktClientId, ""))
        assertFalse(RuntimeCredentials.allConfigured("", fakeTraktClientSecret))
    }

    @Test
    fun missingSimklCredentialIsReportedMissing() {
        assertFalse(RuntimeCredentials.isConfigured(""))
        assertFalse(RuntimeCredentials.isConfigured(null))
    }

    // ── Whitespace ────────────────────────────────────────────────────────────

    @Test
    fun whitespaceOnlyValuesCountAsMissing() {
        assertFalse(RuntimeCredentials.isConfigured("   "))
        assertFalse(RuntimeCredentials.isConfigured("\n"))
        assertFalse(RuntimeCredentials.isConfigured("\t \n"))
        assertFalse(RuntimeCredentials.allConfigured(fakeTraktClientId, "  \n "))
    }

    @Test
    fun valuesSurroundedByWhitespaceStillCountAsConfigured() {
        // A secret pasted into CI with a trailing newline is still a real credential.
        assertTrue(RuntimeCredentials.isConfigured("  $fakeSimklClientId\n"))
    }

    // ── Safety ────────────────────────────────────────────────────────────────

    @Test
    fun emptyArgumentListIsNotConsideredConfigured() {
        assertFalse(RuntimeCredentials.allConfigured())
    }

    @Test
    fun presenceReportNeverLeaksValues() {
        val report = RuntimeCredentials.presenceReport(
            "TRAKT_CLIENT_ID" to fakeTraktClientId,
            "TRAKT_CLIENT_SECRET" to "",
            "SIMKL_CLIENT_ID" to fakeSimklClientId,
        )
        assertEquals(
            "TRAKT_CLIENT_ID present=true TRAKT_CLIENT_SECRET present=false SIMKL_CLIENT_ID present=true",
            report,
        )
        assertFalse(report.contains(fakeTraktClientId))
        assertFalse(report.contains(fakeSimklClientId))
    }
}
