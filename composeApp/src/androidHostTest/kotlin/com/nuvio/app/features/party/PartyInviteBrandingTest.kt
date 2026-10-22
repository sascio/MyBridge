package com.nuvio.app.features.party

import com.nuvio.app.core.deeplink.parseAppDeepLink
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Static guards for the watch-party invitation.
 *
 * The v0.1.13 release shipped invites that read "Join my Nuvio watch party" and
 * linked to `https://nuvio.tv/party/<code>`, a NuvioMedia-owned address. These
 * checks fail `:composeApp:check` if Nuvio branding or the Nuvio website link come
 * back into the invite, or if a platform stops registering the StreamBridge scheme.
 */
class PartyInviteBrandingTest {

    private val repositoryRoot: File by lazy {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile &&
                File(dir, "streambridge.version.properties").isFile
            ) {
                return@lazy dir
            }
            dir = dir.parentFile
        }
        error("Could not locate the StreamBridge repository root from ${File(".").absolutePath}")
    }

    private fun partyStringFiles(): List<File> {
        val resources = File(repositoryRoot, "composeApp/src/commonMain/composeResources")
        return resources.listFiles()
            .orEmpty()
            .filter { it.isDirectory }
            .map { File(it, "strings_party.xml") }
            .filter { it.isFile }
    }

    private fun stringValue(file: File, name: String): String {
        val match = Regex("<string name=\"${name}\">([^<]*)</string>").find(file.readText())
        assertTrue(match != null, "${file.name} does not declare $name")
        return match!!.groupValues[1]
    }

    @Test
    fun everyLocaleInvitesToAStreamBridgeParty() {
        val files = partyStringFiles()
        assertTrue(files.isNotEmpty(), "No party string resources were found.")

        files.forEach { file ->
            val message = stringValue(file, "party_invite_message")
            val locale = file.parentFile.name

            assertTrue(
                message.contains("StreamBridge"),
                "$locale: the invite message does not mention StreamBridge: \"$message\"",
            )
            listOf("Nuvio", "nuvio", "nuvio.tv").forEach { forbidden ->
                assertFalse(
                    message.contains(forbidden),
                    "$locale: the invite message still carries \"$forbidden\": \"$message\"",
                )
            }
            // %1$s = formatted code, %2$s = deep link, %3$s = install fallback.
            listOf("%1\$s", "%2\$s", "%3\$s").forEach { placeholder ->
                assertTrue(
                    message.contains(placeholder),
                    "$locale: the invite message lost the $placeholder parameter: \"$message\"",
                )
            }
        }
    }

    @Test
    fun everyLocaleDropsNuvioBrandingFromTheSignInPrompt() {
        partyStringFiles().forEach { file ->
            val message = stringValue(file, "party_sign_in_required")
            val locale = file.parentFile.name
            assertTrue(message.contains("StreamBridge"), "$locale: the sign-in prompt does not mention StreamBridge")
            assertFalse(message.contains("Nuvio"), "$locale: the sign-in prompt still mentions Nuvio: \"$message\"")
        }
    }

    @Test
    fun invitesOnlyShipWorkingLinks() {
        // The generator must not fall back to the Nuvio website for any server
        // configuration: the deep link works with the public backend and with a
        // custom one because the scheme is resolved on the device.
        val repository = File(repositoryRoot, "composeApp/src/commonMain/kotlin/com/nuvio/app/features/party/PartyRepository.kt")
            .readText()
        assertFalse(repository.contains("nuvio.tv"), "PartyRepository still points invites at nuvio.tv")
        assertFalse(repository.contains("nuvio://party"), "PartyRepository still builds nuvio:// party invites")

        listOf("FXRXFV", "K7M2QX").forEach { code ->
            assertEquals("$STREAMBRIDGE_URL_SCHEME://party/$code", partyInviteLink(code))
            assertTrue(
                parseAppDeepLink(partyInviteLink(code)) != null,
                "The generated invite link is not one this app can open: ${partyInviteLink(code)}",
            )
        }
    }

    @Test
    fun bothPlatformsRegisterTheStreamBridgeScheme() {
        val manifest = File(repositoryRoot, "androidApp/src/main/AndroidManifest.xml").readText()
        assertTrue(
            manifest.contains("android:scheme=\"" + STREAMBRIDGE_URL_SCHEME + "\""),
            "AndroidManifest.xml does not register the $STREAMBRIDGE_URL_SCHEME scheme.",
        )
        assertTrue(
            manifest.contains("android:host=\"sascio.github.io\"") &&
                manifest.contains("android:pathPrefix=\"/MyBridge/party\""),
            "AndroidManifest.xml does not accept the StreamBridge invite redirect URL.",
        )

        val plist = File(repositoryRoot, "iosApp/iosApp/Info.plist").readText()
        assertTrue(
            plist.contains("<string>$STREAMBRIDGE_URL_SCHEME</string>"),
            "Info.plist does not register the $STREAMBRIDGE_URL_SCHEME URL scheme.",
        )
    }

    @Test
    fun invitePageUsesTheSameSchemeAndFallback() {
        val page = File(repositoryRoot, "site/party/index.html")
        assertTrue(page.isFile, "The invite redirect page is missing from site/party/index.html.")
        val html = page.readText()

        assertTrue(
            html.contains("$STREAMBRIDGE_URL_SCHEME://party/"),
            "The invite page does not hand over the StreamBridge deep link.",
        )
        assertTrue(html.contains(STREAMBRIDGE_INSTALL_URL), "The invite page lost its install fallback.")
        assertFalse(html.contains("nuvio", ignoreCase = true), "The invite page still mentions Nuvio.")
    }
}
