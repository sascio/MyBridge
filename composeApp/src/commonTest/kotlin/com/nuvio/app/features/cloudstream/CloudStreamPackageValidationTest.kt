package com.nuvio.app.features.cloudstream

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Security gates applied to a `.cs3` before any of its code can be loaded.
 *
 * These are the rules that stand between a user-added repository and executing
 * third-party bytecode, so each one is pinned by an explicit test.
 */
class CloudStreamPackageValidationTest {

    private val validEntries = listOf("manifest.json", "classes.dex")
    private val digest = "a".repeat(64)

    private fun validate(
        entries: List<String> = validEntries,
        pluginClass: String? = "com.example.MyPlugin",
        expectedHash: String? = "sha256-${"a".repeat(64)}",
        actual: String = digest,
    ) = CloudStreamPackageValidation.validate(entries, pluginClass, expectedHash, actual)

    // ---- hash verification ----------------------------------------------

    @Test
    fun matchingHashIsAccepted() {
        assertTrue(CloudStreamPackageValidation.matchesExpectedHash("sha256-$digest", digest))
    }

    @Test
    fun hashComparisonIsCaseInsensitiveAndPrefixOptional() {
        assertTrue(CloudStreamPackageValidation.matchesExpectedHash(digest.uppercase(), digest))
        assertTrue(CloudStreamPackageValidation.matchesExpectedHash(digest, digest))
    }

    @Test
    fun mismatchedHashIsRejected() {
        assertFalse(CloudStreamPackageValidation.matchesExpectedHash("sha256-${"b".repeat(64)}", digest))
    }

    @Test
    fun tamperedPackageIsRejectedByValidate() {
        val result = validate(expectedHash = "sha256-${"b".repeat(64)}")
        val invalid = assertIs<CloudStreamPackageValidation.Result.Invalid>(result)
        assertEquals(CloudStreamPackageValidation.Rejection.HASH_MISMATCH, invalid.reason)
    }

    @Test
    fun absentHashIsNotTreatedAsAMismatchButIsReportedAsUnpinned() {
        // An absent hash cannot be a mismatch, but callers must be able to tell
        // that integrity was not actually pinned by the repository.
        assertTrue(CloudStreamPackageValidation.matchesExpectedHash(null, digest))
        assertTrue(CloudStreamPackageValidation.matchesExpectedHash("", digest))
        assertFalse(CloudStreamPackageValidation.hasVerifiableHash(null))
        assertFalse(CloudStreamPackageValidation.hasVerifiableHash("sha256-"))
        assertTrue(CloudStreamPackageValidation.hasVerifiableHash("sha256-$digest"))
    }

    // ---- archive safety (Zip Slip) --------------------------------------

    @Test
    fun pathTraversalEntriesAreRejected() {
        assertFalse(CloudStreamPackageValidation.isSafeArchiveEntry("../evil.dex"))
        assertFalse(CloudStreamPackageValidation.isSafeArchiveEntry("a/../../evil.dex"))
        assertFalse(CloudStreamPackageValidation.isSafeArchiveEntry("/etc/passwd"))
        assertFalse(CloudStreamPackageValidation.isSafeArchiveEntry("C:/windows/system32"))
        assertFalse(CloudStreamPackageValidation.isSafeArchiveEntry("..\\evil.dex"))
        assertFalse(CloudStreamPackageValidation.isSafeArchiveEntry(""))
    }

    @Test
    fun ordinaryEntriesAreAccepted() {
        assertTrue(CloudStreamPackageValidation.isSafeArchiveEntry("manifest.json"))
        assertTrue(CloudStreamPackageValidation.isSafeArchiveEntry("res/drawable/icon.png"))
    }

    @Test
    fun anUnsafeEntryRejectsTheWholePackage() {
        val result = validate(entries = validEntries + "../../evil.so")
        val invalid = assertIs<CloudStreamPackageValidation.Result.Invalid>(result)
        assertEquals(CloudStreamPackageValidation.Rejection.UNSAFE_ARCHIVE_ENTRY, invalid.reason)
    }

    // ---- archive layout --------------------------------------------------

    @Test
    fun packageWithoutManifestIsRejected() {
        val invalid = assertIs<CloudStreamPackageValidation.Result.Invalid>(
            validate(entries = listOf("classes.dex")),
        )
        assertEquals(CloudStreamPackageValidation.Rejection.MISSING_MANIFEST, invalid.reason)
    }

    @Test
    fun packageWithoutDexIsRejected() {
        val invalid = assertIs<CloudStreamPackageValidation.Result.Invalid>(
            validate(entries = listOf("manifest.json")),
        )
        assertEquals(CloudStreamPackageValidation.Rejection.MISSING_DEX, invalid.reason)
    }

    // ---- plugin class ----------------------------------------------------

    @Test
    fun missingPluginClassIsRejected() {
        val invalid = assertIs<CloudStreamPackageValidation.Result.Invalid>(
            validate(pluginClass = null),
        )
        assertEquals(CloudStreamPackageValidation.Rejection.MISSING_PLUGIN_CLASS, invalid.reason)
    }

    @Test
    fun malformedPluginClassNamesAreRejected() {
        // Anything path-, descriptor- or whitespace-shaped must never reach loadClass.
        listOf(
            "com/example/Plugin",
            "com.example.Plugin;",
            "..com.example.Plugin",
            "com.example.Plugin.",
            "com example Plugin",
            "com.example.Plugin\\x",
        ).forEach { candidate ->
            assertFalse(
                CloudStreamPackageValidation.isValidPluginClassName(candidate),
                "expected '$candidate' to be rejected",
            )
        }
    }

    @Test
    fun realisticPluginClassNamesAreAccepted() {
        listOf(
            "com.example.MyPlugin",
            "com.megix.CineStream",
            "recloudstream.Provider\$Inner",
            "Plugin_1",
        ).forEach { candidate ->
            assertTrue(
                CloudStreamPackageValidation.isValidPluginClassName(candidate),
                "expected '$candidate' to be accepted",
            )
        }
    }

    // ---- happy path ------------------------------------------------------

    @Test
    fun wellFormedVerifiedPackageIsAcceptedAndYieldsItsEntryClass() {
        val valid = assertIs<CloudStreamPackageValidation.Result.Valid>(validate())
        assertEquals("com.example.MyPlugin", valid.pluginClassName)
    }

    @Test
    fun hashIsCheckedBeforeStructureSoTamperingIsTheReportedReason() {
        // A tampered archive is reported as a hash failure rather than a
        // structural one, which is the more accurate and actionable message.
        val result = validate(
            entries = listOf("classes.dex"),
            expectedHash = "sha256-${"c".repeat(64)}",
        )
        val invalid = assertIs<CloudStreamPackageValidation.Result.Invalid>(result)
        assertEquals(CloudStreamPackageValidation.Rejection.HASH_MISMATCH, invalid.reason)
    }
}
