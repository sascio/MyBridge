package com.nuvio.app.features.tracking

import com.nuvio.app.features.anilist.AniListConfig
import com.nuvio.app.features.wetrakr.WeTrakrConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guard rails for the AniList / WeTrakr OAuth registrations.
 *
 * Both providers take an optional build-time client-id override
 * (`ANILIST_CLIENT_ID` / `WETRAKR_CLIENT_ID`, see docs/OAUTH-BRANDING.md). These
 * assertions make sure that switching the registration can never silently remove
 * the client id or change the callback the flow depends on: a build without an
 * override must still be configured, and the redirect URI the intent filters and
 * the OAuth servers agree on must stay exactly as it is.
 */
class TrackingClientRegistrationTest {

    @Test
    fun `anilist keeps a configured client id and its callback`() {
        assertTrue(AniListConfig.CLIENT_ID.isNotBlank(), "AniList client id must never be blank")
        // AniList issues numeric client ids; isConfigured relies on that.
        assertTrue(
            AniListConfig.CLIENT_ID.all(Char::isDigit),
            "AniList client id must be numeric",
        )
        assertTrue(AniListConfig.isConfigured, "AniList must be configured out of the box")
        assertEquals("nuvio://auth/anilist", AniListConfig.REDIRECT_URI)
        assertEquals("https://anilist.co/api/v2/oauth/authorize", AniListConfig.AUTHORIZE_URL)
    }

    @Test
    fun `wetrakr keeps a configured client id and its verification page`() {
        assertTrue(WeTrakrConfig.CLIENT_ID.isNotBlank(), "WeTrakr client id must never be blank")
        assertTrue(WeTrakrConfig.isConfigured, "WeTrakr must be configured out of the box")
        assertEquals("https://wetrakr.com/activate", WeTrakrConfig.DEFAULT_VERIFICATION_URL)
        assertEquals("1", WeTrakrConfig.API_VERSION)
    }
}
