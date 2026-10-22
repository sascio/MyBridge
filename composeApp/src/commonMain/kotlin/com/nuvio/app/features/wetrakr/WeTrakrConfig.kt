package com.nuvio.app.features.wetrakr

import com.nuvio.app.core.build.TrackingClientOverrides

object WeTrakrConfig {
    /**
     * The WeTrakr OAuth client this build uses when no override is configured.
     *
     * This is a client id, not a secret. WeTrakr renders the application name on
     * its activation page (`/activate`) from the registration this id belongs to,
     * so the name shown to the user ("Nuvio") is stored on WeTrakr's side and is
     * not controlled by this file. See docs/OAUTH-BRANDING.md.
     */
    private const val DEFAULT_CLIENT_ID = "193cadea493eaf3f7f4cdcbba020f577"

    /**
     * Effective WeTrakr client id: `WETRAKR_CLIENT_ID` from `local.properties` /
     * environment / `-P` when supplied at build time, otherwise the compiled-in
     * client above. Switching clients does not change the device-authorization
     * grant, the `wetrakr-api-key` header or the token handling.
     */
    val CLIENT_ID: String =
        TrackingClientOverrides.WETRAKR_CLIENT_ID.ifBlank { DEFAULT_CLIENT_ID }

    const val API_BASE_URL = "https://api.wetrakr.com"
    const val API_VERSION = "1"
    const val WEBSITE_URL = "https://wetrakr.com"
    const val DEFAULT_VERIFICATION_URL = "https://wetrakr.com/activate"

    // Sent as the HTTP User-Agent on API calls. Deliberately unchanged: it is not
    // user-visible branding, and WeTrakr identifies API clients by
    // `wetrakr-api-key` (the client id). Renaming it cannot change the name shown
    // on the activation page and could affect provider-side allowlisting.
    const val USER_AGENT = "NuvioMobile"

    const val IMAGE_BASE_URL = "https://image.tmdb.org/t/p"

    val isConfigured: Boolean
        get() = CLIENT_ID.isNotBlank()
}
