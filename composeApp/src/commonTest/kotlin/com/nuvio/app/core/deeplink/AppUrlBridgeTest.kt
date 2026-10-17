package com.nuvio.app.core.deeplink

import com.nuvio.app.features.party.canonicalPartyCode
import com.nuvio.app.features.party.formatPartyCode
import com.nuvio.app.features.party.partyInviteLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppUrlBridgeTest {

    @Test
    fun `parses party invites from StreamBridge and keeps legacy links compatible`() {
        assertEquals(AppDeepLink.Party("K7M2QX"), parseAppDeepLink("streambridge://party/k7m-2qx"))
        assertEquals(AppDeepLink.Party("K7M2QX"), parseAppDeepLink("nuvio://party/k7m-2qx"))
        assertEquals(AppDeepLink.Party("K7M2QX"), parseAppDeepLink("https://nuvio.tv/party/K7M2QX"))
        assertEquals(AppDeepLink.Party("K7M2QX"), parseAppDeepLink("https://NUVIO.tv/party/K7M2QX/"))
        assertNull(parseAppDeepLink("https://nuvio.tv/party/K7M2Q"))
        assertNull(parseAppDeepLink("https://nuvio.tv/link?code=K7M2QX"))
        assertNull(parseAppDeepLink("https://evil.example/party/K7M2QX"))
        assertNull(parseAppDeepLink("http://nuvio.tv/party/K7M2QX"))
    }

    @Test
    fun `generated party invitations keep the canonical code through link parsing`() {
        val invitationUrl = partyInviteLink("8M6 65W")

        assertEquals("8M6 65W", formatPartyCode("8M665W"))
        assertEquals("streambridge://party/8M665W", invitationUrl)
        assertEquals(AppDeepLink.Party("8M665W"), invitationUrl?.let(::parseAppDeepLink))
        assertNull(partyInviteLink("8M665W!"))
    }

    @Test
    fun `rejects malformed and incomplete party routes`() {
        assertEquals("K7M2QX", canonicalPartyCode("k7m-2qx"))
        assertNull(canonicalPartyCode("K7M2QX!"))
        assertNull(canonicalPartyCode("K7M2Q"))
        assertNull(parseAppDeepLink("streambridge://party"))
        assertNull(parseAppDeepLink("streambridge://party/K7M2Q"))
        assertNull(parseAppDeepLink("streambridge://party/K7M2QX!"))
        assertNull(parseAppDeepLink("streambridge://party/K7M2QX/extra"))
        assertNull(parseAppDeepLink("nuvio://party/K7M2QX!"))
    }

    @Test
    fun `parses existing notification meta deeplink`() {
        assertEquals(
            AppDeepLink.Meta(type = "series", id = "tt0944947"),
            parseAppDeepLink("nuvio://meta?type=series&id=tt0944947"),
        )
    }

    @Test
    fun `parses direct nuvio addon install deeplink`() {
        assertEquals(
            AppDeepLink.AddonInstall("https://free.nebulapro.xyz/sports/i/free/manifest.json"),
            parseAppDeepLink("nuvio://free.nebulapro.xyz/sports/i/free/manifest.json"),
        )
    }

    @Test
    fun `parses stremio addon install deeplink`() {
        assertEquals(
            AppDeepLink.AddonInstall("https://free.nebulapro.xyz/sports/i/free/manifest.json"),
            parseAppDeepLink("stremio://free.nebulapro.xyz/sports/i/free/manifest.json"),
        )
    }

    @Test
    fun `parses direct imdb detail deeplink`() {
        assertEquals(
            AppDeepLink.Meta(type = "series", id = "tt0944947"),
            parseAppDeepLink("nuvio://series/tt0944947"),
        )
    }

    @Test
    fun `parses provider imdb detail deeplink`() {
        assertEquals(
            AppDeepLink.Meta(type = "series", id = "tt0944947"),
            parseAppDeepLink("nuvio://imdb/series/tt0944947"),
        )
    }

    @Test
    fun `parses provider tmdb detail deeplink`() {
        assertEquals(
            AppDeepLink.Meta(type = "series", id = "tmdb:1399"),
            parseAppDeepLink("nuvio://tmdb/tv/1399"),
        )
    }

    @Test
    fun `does not treat reserved auth link as addon install`() {
        assertNull(parseAppDeepLink("nuvio://auth/trakt?code=abc"))
    }

    @Test
    fun `does not treat non-host stremio link as addon install`() {
        assertNull(parseAppDeepLink("stremio://detail/series/tt0944947"))
    }
}
