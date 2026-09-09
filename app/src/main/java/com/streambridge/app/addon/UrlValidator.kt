package com.streambridge.app.addon

/**
 * Validates and normalizes extension URLs entered by the user.
 * Pure Kotlin, unit tested.
 */
object UrlValidator {

    private const val MAX_LENGTH = 2048

    sealed class Result {
        /** The normalized URL (http/https only). */
        data class Valid(val url: String) : Result()

        /** The input cannot be used as an addon URL. */
        data class Invalid(val reason: String) : Result()
    }

    /**
     * Validates an extension URL entered by the user.
     *
     * @param allowPublicHttp set to true for plain *content* URLs (e.g. a
     *   playable stream returned by an already-installed source), where
     *   the app only fetches/plays. Install paths (addon and plugin
     *   manifests) keep the default and accept plain http only for
     *   local-network hosts, matching the documented security policy.
     */
    fun validate(rawInput: String, allowPublicHttp: Boolean = false): Result {
        val input = rawInput.trim()
        if (input.isEmpty()) {
            return Result.Invalid("Enter an extension URL")
        }
        if (input.length > MAX_LENGTH) {
            return Result.Invalid("URL is too long")
        }
        if (input.contains(' ') || input.contains('\n') || input.contains('\t')) {
            return Result.Invalid("URL must not contain spaces")
        }

        val schemeSeparator = input.indexOf("://")
        if (schemeSeparator > 0) {
            // An explicit scheme must be http(s); anything else (ftp, file,
            // javascript, …) is rejected instead of being mangled by the
            // https fallback below.
            val explicitScheme = input.substring(0, schemeSeparator)
                .lowercase(java.util.Locale.US)
            if (explicitScheme != "http" && explicitScheme != "https") {
                return Result.Invalid("Only http and https extensions are supported")
            }
        }

        val withScheme = if (schemeSeparator > 0) {
            input
        } else {
            "https://$input"
        }

        val uri = try {
            java.net.URI(withScheme)
        } catch (_: Exception) {
            return Result.Invalid("This is not a valid URL")
        }

        val scheme = uri.scheme?.lowercase(java.util.Locale.US)
        if (scheme != "http" && scheme != "https") {
            return Result.Invalid("Only http and https extensions are supported")
        }

        val host = uri.host
        if (host.isNullOrBlank()) {
            return Result.Invalid("URL is missing a host name")
        }
        if (uri.userInfo != null) {
            return Result.Invalid("URLs with embedded credentials are not allowed")
        }
        if (uri.rawQuery?.contains(' ') == true || uri.rawFragment?.contains(' ') == true) {
            return Result.Invalid("URL is malformed")
        }
        if (uri.path?.contains("..") == true) {
            return Result.Invalid("URL path is not allowed")
        }

        // Security policy: plain http is only accepted for local-network
        // hosts (LAN-hosted addons, the local bridge, mDNS names). Public
        // hosts must use https so TLS verification always applies.
        if (!allowPublicHttp && scheme == "http" && !isLocalNetworkHost(host)) {
            return Result.Invalid(
                "Plain http is only allowed for local network addresses — use https for public hosts"
            )
        }

        // Rebuild from parts so the final string is well-formed.
        val normalized = buildString {
            append(scheme)
            append("://")
            if (host.startsWith("[")) {
                // IPv6 literal that already carries its brackets.
                append(host.lowercase(java.util.Locale.US))
            } else if (host.contains(':')) {
                // Bare IPv6 literal: (re-)add the brackets.
                append('[')
                append(host.lowercase(java.util.Locale.US))
                append(']')
            } else {
                append(host.lowercase(java.util.Locale.US))
            }
            if (uri.port != -1) {
                append(':')
                append(uri.port)
            }
            uri.rawPath?.let { path ->
                if (path.isNotBlank()) append(path) else append('/')
            }
            uri.rawQuery?.let { query -> append('?'); append(query) }
        }

        if (normalized.isBlank()) {
            return Result.Invalid("This is not a valid URL")
        }
        return Result.Valid(normalized)
    }

    /**
     * True for hosts that are safe to reach over plain http: loopback,
     * RFC-1918/CGNAT/link-local IPv4, IPv6 loopback/link-local/ULA, and
     * the local-only hostnames "localhost" and "*.local" (mDNS).
     * Everything else is considered public.
     */
    internal fun isLocalNetworkHost(host: String): Boolean {
        val lower = host.lowercase(java.util.Locale.US)
        if (lower == "localhost" || lower.endsWith(".local") || lower.endsWith(".internal")) {
            return true
        }
        // IPv6 (java.net.URI keeps the brackets; possibly with a zone
        // index, e.g. "[fe80::1%wlan0]").
        val bare = lower.removePrefix("[").removeSuffix("]").substringBefore('%')
        if (bare.contains(':')) {
            if (bare == "::1" || bare == "::") return true
            val mapped = bare.removePrefix("::ffff:")
            if (mapped != bare) return isPrivateIPv4(mapped)
            if (lower.startsWith("fe8") || lower.startsWith("fe9") ||
                lower.startsWith("fea") || lower.startsWith("feb")
            ) {
                return true // fe80::/10 link-local
            }
            return lower.startsWith("fc") || lower.startsWith("fd") // fc00::/7 ULA
        }
        return isPrivateIPv4(lower)
    }

    private fun isPrivateIPv4(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        val octets = parts.mapNotNull { it.toIntOrNull()?.takeIf { v -> v in 0..255 } }
        if (octets.size != 4) return false
        val a = octets[0]
        val b = octets[1]
        return when {
            a == 127 -> true                    // loopback
            a == 10 -> true                     // RFC-1918
            a == 192 && b == 168 -> true        // RFC-1918
            a == 172 && b in 16..31 -> true     // RFC-1918
            a == 169 && b == 254 -> true        // link-local
            a == 100 && b in 64..127 -> true    // CGNAT 100.64/10 (hotspots)
            else -> false
        }
    }
}
