package com.streambridge.app.addon

/**
 * Sanitizes HTTP headers supplied by addons (e.g. Stremio
 * behaviorHints.proxyHeaders) before they are attached to a player
 * request. Only well-formed name/value pairs survive; anything that
 * could corrupt the request (CRLF injection, control characters,
 * oversized values, header flooding) is dropped.
 *
 * Headers are applied per-stream only — they are never attached to
 * unrelated addon/catalog requests.
 *
 * Pure logic — unit tested.
 */
object StreamHeaders {

    private val HEADER_NAME = Regex("^[A-Za-z0-9-]{1,64}$")
    /**
     * Cookie / Referer values on real CDNs regularly exceed 512 bytes.
     * Dropping them silently made StreamBridge omit headers Nuvio sends.
     * Still bounded against header flooding.
     */
    private const val MAX_VALUE_LENGTH = 8192
    /**
     * Nuvio does not cap header count. 12 was dropping legitimate extra
     * CDN headers (Accept, Origin, custom tokens). 32 is a flood cap
     * only.
     */
    private const val MAX_HEADERS = 32

    fun sanitize(raw: Map<String, String>?): Map<String, String> {
        if (raw.isNullOrEmpty()) return emptyMap()
        val safe = LinkedHashMap<String, String>()
        for ((name, value) in raw) {
            if (safe.size >= MAX_HEADERS) break
            val cleanName = name.trim()
            val cleanValue = value.trim()
            if (!HEADER_NAME.matches(cleanName)) continue
            if (cleanValue.isEmpty() || cleanValue.length > MAX_VALUE_LENGTH) continue
            // Reject any control characters, including CR/LF injection.
            if (cleanValue.any { it.code < 0x20 || it.code == 0x7F }) continue
            // Nuvio never puts Range on the session-wide playback client:
            // Media3/mpv issue Range per request. A leftover Range on
            // every segment request breaks HLS/DASH.
            if (cleanName.equals("Range", ignoreCase = true)) continue
            safe[cleanName] = cleanValue
        }
        return safe
    }

    /** Merge that never returns null and always sanitizes the additions. */
    fun merge(base: Map<String, String>?, additions: Map<String, String>?): Map<String, String> {
        val merged = LinkedHashMap<String, String>()
        for ((k, v) in sanitize(base)) merged[k] = v
        for ((k, v) in sanitize(additions)) merged[k] = v
        return merged
    }
}
