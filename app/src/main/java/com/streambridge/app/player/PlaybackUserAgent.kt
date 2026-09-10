package com.streambridge.app.player

/**
 * The default User-Agent for STREAM PLAYBACK requests.
 *
 * Many stream CDNs answer with HTTP 403 for non-browser user agents.
 * Playback requests therefore identify as a normal browser; when a
 * provider supplies its own User-Agent for a source, that source's
 * header wins (it is applied on top of this default).
 *
 * This is the default for the playback data source only — addon API
 * and catalog traffic keep the app identity.
 */
internal object PlaybackUserAgent {
    const val DEFAULT =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
}
