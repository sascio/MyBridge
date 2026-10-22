package com.nuvio.app.features.party

import com.nuvio.app.core.deeplink.AppDeepLink
import com.nuvio.app.core.deeplink.parseAppDeepLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The watch-party invite used to be shared as `https://nuvio.tv/party/<code>`: Nuvio
 * branding and a NuvioMedia-owned address that StreamBridge neither controls nor can
 * redirect. These tests pin the StreamBridge-owned replacement and prove that joining
 * still works for every link shape users may already have.
 */
class PartyInviteLinkTest {

    @Test
    fun inviteLinkIsStreamBridgeOwned() {
        val link = partyInviteLink("FXRXFV")

        assertEquals("streambridge://party/FXRXFV", link)
        assertFalse(link.contains("nuvio", ignoreCase = true), "The invite link still mentions Nuvio: $link")
        assertTrue(link.startsWith(STREAMBRIDGE_URL_SCHEME), "The invite link is not on the StreamBridge scheme: $link")
    }

    @Test
    fun inviteLinkPreservesThePartyCode() {
        // The code is uppercased and stripped to the party alphabet, never mangled:
        // "FXR XFV" is how the code is displayed, "FXRXFV" is how it is shared.
        listOf("FXRXFV", "fxrxfv", "FXR XFV", " fxr-xfv ", "FxRxFv").forEach { input ->
            assertEquals("streambridge://party/FXRXFV", partyInviteLink(input), "party code input: \"$input\"")
        }
    }

    @Test
    fun inviteLinkSurvivesTheDeepLinkRoundTrip() {
        listOf("FXRXFV", "K7M2QX", "ABCD23").forEach { code ->
            assertEquals(
                AppDeepLink.Party(code),
                parseAppDeepLink(partyInviteLink(code)),
                "sharing then opening $code must resolve to that party",
            )
        }
    }

    @Test
    fun existingJoiningBehaviourStillWorks() {
        // Links shared by earlier StreamBridge builds, and the nuvio:// scheme the
        // app still registers for OAuth callbacks and older notifications.
        assertEquals(AppDeepLink.Party("K7M2QX"), parseAppDeepLink("nuvio://party/K7M2QX"))
        assertEquals(AppDeepLink.Party("K7M2QX"), parseAppDeepLink("nuvio://party/k7m-2qx"))
        assertEquals(AppDeepLink.Party("K7M2QX"), parseAppDeepLink("https://nuvio.tv/party/K7M2QX"))
        assertEquals(AppDeepLink.Party("K7M2QX"), parseAppDeepLink("https://NUVIO.tv/party/K7M2QX/"))
    }

    @Test
    fun inviteRedirectPageResolvesToTheParty() {
        // The shapes the StreamBridge invite page hands over (docs/WATCH-PARTY-LINKS.md).
        assertEquals(
            AppDeepLink.Party("K7M2QX"),
            parseAppDeepLink("${STREAMBRIDGE_INVITE_REDIRECT_BASE_URL}?code=K7M2QX"),
        )
        assertEquals(
            AppDeepLink.Party("K7M2QX"),
            parseAppDeepLink("https://sascio.github.io/MyBridge/party/K7M2QX"),
        )
        assertEquals(
            AppDeepLink.Party("K7M2QX"),
            parseAppDeepLink("https://sascio.github.io/MyBridge/party/?code=k7m2qx"),
        )
        assertEquals(
            AppDeepLink.Party("K7M2QX"),
            parseAppDeepLink("streambridge://party?code=K7M2QX"),
        )
    }

    @Test
    fun inviteRedirectPageRejectsLookalikeLinks() {
        assertNull(parseAppDeepLink("https://sascio.github.io/MyBridge/party/K7M2Q"))
        assertNull(parseAppDeepLink("https://sascio.github.io/MyBridge/other/K7M2QX"))
        assertNull(parseAppDeepLink("https://sascio.github.io/party/K7M2QX"))
        assertNull(parseAppDeepLink("https://evil.example/MyBridge/party/K7M2QX"))
        assertNull(parseAppDeepLink("http://sascio.github.io/MyBridge/party/K7M2QX"))
        assertNull(parseAppDeepLink("https://nuvio.tv/party/K7M2Q"))
    }

    @Test
    fun installFallbackPointsAtTheRealReleasePage() {
        assertTrue(
            STREAMBRIDGE_INSTALL_URL.startsWith("https://github.com/sascio/MyBridge/releases"),
            "The invite fallback must be a real, published download page: $STREAMBRIDGE_INSTALL_URL",
        )
    }
}
