package com.nuvio.app.features.simkl

import com.nuvio.app.core.build.AppVersionConfig
import io.ktor.http.encodeURLParameter

internal const val SIMKL_API_BASE_URL = "https://api.simkl.com"

// Simkl AUTH V2. The consent page lives on simkl.com while every other V2 endpoint
// lives on api.simkl.com. The V1 authorize URL (/oauth/authorize) rejects V2 clients
// with {"error":"unauthorized_client"}, which is what a V2-registered client_id hit.
internal const val SIMKL_AUTHORIZE_URL = "https://simkl.com/oauth2/authorize"

// POSTed to api.simkl.com as application/x-www-form-urlencoded. V1's /oauth/token
// took a JSON body and is rejected for V2 clients.
internal const val SIMKL_TOKEN_PATH = "/oauth2/token"

// Omitting scope yields a read-only token, which would break scrobbling and
// watched-status writes. A misspelled scope is silently downgraded to read-only
// rather than rejected, so keep this exactly as Simkl documents it.
internal const val SIMKL_AUTH_SCOPE = "media:read media:write"

// Returned in the callback as `iss`. V2 sends it so a client talking to several
// authorization servers can tell whose response it is.
internal const val SIMKL_ISSUER = "https://simkl.com"

internal val simklAppVersion: String
    get() = AppVersionConfig.VERSION_NAME.ifBlank { "dev" }

internal fun buildSimklApiUrl(
    path: String,
    query: Map<String, String> = emptyMap(),
): String {
    val normalizedPath = path.trim().let { value ->
        if (value.startsWith('/')) value else "/$value"
    }
    val parameters = linkedMapOf<String, String>().apply {
        putAll(query)
        put("client_id", SimklConfig.CLIENT_ID)
        put("app-name", SimklConfig.APP_NAME)
        put("app-version", simklAppVersion)
    }
    return buildString {
        append(SIMKL_API_BASE_URL)
        append(normalizedPath)
        append('?')
        append(
            parameters.entries.joinToString("&") { (key, value) ->
                "${key.encodeURLParameter()}=${value.encodeURLParameter()}"
            },
        )
    }
}

internal fun simklRequestHeaders(
    accessToken: String? = null,
    contentTypeJson: Boolean = false,
    formEncoded: Boolean = false,
): Map<String, String> = buildMap {
    put("User-Agent", "${SimklConfig.APP_NAME}/$simklAppVersion")
    put("Accept", "application/json")
    accessToken?.trim()?.takeIf(String::isNotBlank)?.let { token ->
        put("Authorization", "Bearer $token")
    }
    // The AUTH V2 token endpoint requires form encoding; every other POST keeps JSON.
    if (formEncoded) {
        put("Content-Type", "application/x-www-form-urlencoded")
    } else if (contentTypeJson) {
        put("Content-Type", "application/json")
    }
}
