package com.streambridge.app.player

import okhttp3.Dns
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Nuvio-parity HTTP request-context helpers for Media3 playback.
 *
 * Read from NuvioStreaming `PlayerPlaybackNetworking.kt` +
 * `PlatformPlaybackDataSourceFactory`:
 *  - source headers are applied as **default request properties** so
 *    they survive the initial URL, redirects, HLS/DASH segments, audio
 *    segments, keys and nested manifests;
 *  - `Range` is never a default (Media3 adds Range per request; a
 *    provider Range on every request breaks adaptive streams);
 *  - a missing User-Agent falls back to Nuvio's browser UA;
 *  - lookup prefers IPv4 (Nuvio `IPv4FirstDns`) but keeps IPv6 so
 *    IPv6-only hosts still resolve.
 *
 * Pure / unit-testable. Never logs header values.
 */
object PlaybackHttp {

    /**
     * Drop hop-by-hop / per-request headers that must not become
     * session-wide defaults. Nuvio strips `Range` only.
     */
    fun forDefaultRequestProperties(headers: Map<String, String>): Map<String, String> {
        if (headers.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>(headers.size)
        for ((name, value) in headers) {
            if (name.equals("Range", ignoreCase = true)) continue
            out[name] = value
        }
        return out
    }

    /**
     * Merge one DataSpec's headers with the active source session.
     *
     * DataSpec first (Media3-generated Range, etc.), then session
     * headers overwrite so a provider User-Agent/Referer/Cookie/Origin
     * cannot be replaced by the factory default UA.
     */
    fun mergeRequestHeaders(
        sessionHeaders: Map<String, String>,
        dataSpecHeaders: Map<String, String>
    ): Map<String, String> {
        val session = forDefaultRequestProperties(sessionHeaders)
        if (session.isEmpty() && dataSpecHeaders.isEmpty()) return emptyMap()
        val merged = LinkedHashMap<String, String>(dataSpecHeaders.size + session.size)
        merged.putAll(dataSpecHeaders)
        session.forEach { (name, value) -> merged[name] = value }
        return merged
    }

    fun effectiveUserAgent(headers: Map<String, String>): String =
        headers.entries
            .firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
            ?.value
            ?.takeIf { it.isNotBlank() }
            ?: PlaybackUserAgent.DEFAULT
}

/**
 * Nuvio `IPv4FirstDns`: return A records before AAAA. IPv6-only hosts
 * still resolve because AAAA are appended, not dropped.
 */
class Ipv4FirstDns(
    private val delegate: Dns = Dns.SYSTEM
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val all = delegate.lookup(hostname)
        if (all.size <= 1) return all
        val v4 = ArrayList<InetAddress>(all.size)
        val v6 = ArrayList<InetAddress>(all.size)
        for (address in all) {
            when (address) {
                is Inet4Address -> v4 += address
                is Inet6Address -> v6 += address
                else -> v4 += address
            }
        }
        if (v4.isEmpty() || v6.isEmpty()) return all
        return v4 + v6
    }
}
