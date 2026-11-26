package com.nuvio.app.features.media.service

internal data class WebDavListingItem(val href: String, val name: String, val directory: Boolean)

/** Deliberately bounded DAV XML subset. No DTD, CDATA, processing instructions inside
 * the body, numeric entities or entity expansion. Unsupported XML fails explicitly.
 * This parser is only metadata; URL scope is independently checked by WebDavService. */
internal fun parseWebDavListing(xml: String): List<WebDavListingItem> {
    fun invalid(): Nothing = throw MediaServiceException(MediaFailure.InvalidResponse)
    if (xml.length > 2 * 1024 * 1024 || "<!" in xml) invalid()
    val body = xml.trim().replaceFirst(Regex("^<\\?xml\\s[^?]*\\?>"), "").trim()
    if ("<?" in body || !Regex("^<(?:[\\w.-]+:)?multistatus(?:\\s|>)").containsMatchIn(body) || !Regex("</(?:[\\w.-]+:)?multistatus\\s*>$").containsMatchIn(body)) invalid()
    val response = Regex("<(?:[\\w.-]+:)?response(?:\\s[^>]*)?>(.*?)</(?:[\\w.-]+:)?response\\s*>", RegexOption.DOT_MATCHES_ALL)
    val tagPattern = "(?:[\\w.-]+:)?"
    fun text(block: String, tag: String): String? = Regex("<$tagPattern$tag(?:\\s[^>]*)?>([^<]*)</$tagPattern$tag\\s*>").find(block)?.groupValues?.get(1)
    fun decode(value: String): String {
        if (Regex("&(?!amp;|lt;|gt;|quot;|apos;)").containsMatchIn(value)) invalid()
        return value.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
    }
    val matches = response.findAll(body).toList()
    if (matches.size > 3000) invalid()
    if (matches.isEmpty() && Regex("<(?:[\\w.-]+:)?response\\b").containsMatchIn(body)) invalid()
    return matches.mapNotNull { match ->
        val block = match.groupValues[1]
        // Only use properties from a successful propstat, never a 404/403 property set.
        val properties = Regex("<(?:[\\w.-]+:)?propstat(?:\\s[^>]*)?>(.*?)</(?:[\\w.-]+:)?propstat\\s*>", RegexOption.DOT_MATCHES_ALL)
            .findAll(block).map { it.groupValues[1] }.firstOrNull { text(it, "status")?.contains(Regex("\\s200(?:\\s|$)")) == true } ?: return@mapNotNull null
        val href = text(block, "href")?.let(::decode)?.takeIf { it.isNotBlank() } ?: invalid()
        WebDavListingItem(href, text(properties, "displayname")?.let(::decode).orEmpty(), Regex("<(?:[\\w.-]+:)?collection(?:\\s|/|>)").containsMatchIn(properties))
    }
}
