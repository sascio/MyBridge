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

    fun validate(rawInput: String): Result {
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

        val withScheme = if (input.startsWith("http://", ignoreCase = true) ||
            input.startsWith("https://", ignoreCase = true)
        ) {
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

        // Rebuild from parts so the final string is well-formed.
        val normalized = buildString {
            append(scheme)
            append("://")
            append(host.lowercase(java.util.Locale.US))
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
}
