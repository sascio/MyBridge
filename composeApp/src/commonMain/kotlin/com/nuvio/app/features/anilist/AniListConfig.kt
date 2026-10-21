package com.nuvio.app.features.anilist

import com.nuvio.app.core.build.TrackingClientOverrides

object AniListConfig {
    /**
     * The AniList API client this build uses when no override is configured.
     *
     * This is a client id, not a secret. AniList renders the application name on
     * its authorization page from the registration this id belongs to, so the
     * name shown to the user ("Nuvio") is stored in AniList's developer settings
     * and is not controlled by this file. See docs/OAUTH-BRANDING.md.
     */
    private const val DEFAULT_CLIENT_ID = "53329"

    /**
     * Effective AniList client id: `ANILIST_CLIENT_ID` from `local.properties` /
     * environment / `-P` when supplied at build time, otherwise the compiled-in
     * client above. Switching clients does not change the redirect URI, the
     * implicit-token grant or the token handling below.
     */
    val CLIENT_ID: String =
        TrackingClientOverrides.ANILIST_CLIENT_ID.ifBlank { DEFAULT_CLIENT_ID }

    const val REDIRECT_URI = "nuvio://auth/anilist"
    const val AUTHORIZE_URL = "https://anilist.co/api/v2/oauth/authorize"
    const val GRAPHQL_URL = "https://graphql.anilist.co"
    const val WEBSITE_URL = "https://anilist.co"
    const val ARM_BASE_URL = "https://arm.haglund.dev/api/v2"

    val isConfigured: Boolean
        get() = CLIENT_ID.isNotBlank() && CLIENT_ID.all(Char::isDigit)
}
