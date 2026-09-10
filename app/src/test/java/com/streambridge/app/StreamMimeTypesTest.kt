package com.streambridge.app

import com.streambridge.app.addon.StreamMimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Container/manifest detection must survive the URLs real providers
 * emit: extension-less manifests, manifests typed in query parameters,
 * manifest path tokens, honest Content-Type headers and
 * Content-Disposition file names — while NEVER inventing a MIME.
 */
class StreamMimeTypesTest {

    // -----------------------------------------------------------------
    // HLS detection
    // -----------------------------------------------------------------

    @Test
    fun `plain m3u8 extension is HLS`() {
        assertEquals(
            StreamMimeTypes.APPLICATION_M3U8,
            StreamMimeTypes.fromUrl("https://cdn.example.com/master.m3u8")
        )
    }

    @Test
    fun `m3u8 extension with query token is HLS`() {
        assertEquals(
            StreamMimeTypes.APPLICATION_M3U8,
            StreamMimeTypes.fromUrl("https://cdn.example.com/index.m3u8?token=abc&expires=1")
        )
    }

    @Test
    fun `extension-less manifest url with hls path token is HLS`() {
        assertEquals(
            StreamMimeTypes.APPLICATION_M3U8,
            StreamMimeTypes.fromUrl("https://cdn.example.com/hls/d6f2b8a1c9e4/token123")
        )
    }

    @Test
    fun `m3u8 in a query parameter is HLS`() {
        assertEquals(
            StreamMimeTypes.APPLICATION_M3U8,
            StreamMimeTypes.fromUrl("https://proxy.example.com/manifest?output=m3u8&token=x")
        )
        assertEquals(
            StreamMimeTypes.APPLICATION_M3U8,
            StreamMimeTypes.fromUrl("https://proxy.example.com/get?type=hls")
        )
    }

    @Test
    fun `hls content type maps to HLS`() {
        assertEquals(
            StreamMimeTypes.APPLICATION_M3U8,
            StreamMimeTypes.fromContentType("application/vnd.apple.mpegurl")
        )
        assertEquals(
            StreamMimeTypes.APPLICATION_M3U8,
            StreamMimeTypes.fromContentType("application/x-mpegurl; charset=utf-8")
        )
    }

    // -----------------------------------------------------------------
    // DASH detection
    // -----------------------------------------------------------------

    @Test
    fun `mpd extension and dash query are DASH`() {
        assertEquals(
            StreamMimeTypes.APPLICATION_MPD,
            StreamMimeTypes.fromUrl("https://cdn.example.com/stream.mpd")
        )
        assertEquals(
            StreamMimeTypes.APPLICATION_MPD,
            StreamMimeTypes.fromUrl("https://cdn.example.com/manifest?type=mpd")
        )
        assertEquals(
            StreamMimeTypes.APPLICATION_MPD,
            StreamMimeTypes.fromUrl("https://cdn.example.com/dash/abc123/token")
        )
    }

    @Test
    fun `dash content type maps to DASH`() {
        assertEquals(
            StreamMimeTypes.APPLICATION_MPD,
            StreamMimeTypes.fromContentType("application/dash+xml")
        )
    }

    // -----------------------------------------------------------------
    // Progressive containers
    // -----------------------------------------------------------------

    @Test
    fun `progressive extensions map to their containers`() {
        assertEquals(
            StreamMimeTypes.VIDEO_MATROSKA,
            StreamMimeTypes.fromUrl("https://cdn.example.com/movie.mkv?token=1")
        )
        assertEquals(
            StreamMimeTypes.VIDEO_MP4,
            StreamMimeTypes.fromUrl("https://cdn.example.com/video.mp4")
        )
        assertEquals(
            StreamMimeTypes.VIDEO_MP2T,
            StreamMimeTypes.fromUrl("https://cdn.example.com/stream.ts")
        )
        assertEquals(
            StreamMimeTypes.VIDEO_WEBM,
            StreamMimeTypes.fromUrl("https://cdn.example.com/v.webm")
        )
    }

    @Test
    fun `progressive container from query parameter`() {
        assertEquals(
            StreamMimeTypes.VIDEO_MATROSKA,
            StreamMimeTypes.fromUrl("https://cdn.example.com/dl/abc123?ext=mkv")
        )
    }

    // -----------------------------------------------------------------
    // Honesty: never invent a MIME
    // -----------------------------------------------------------------

    @Test
    fun `urls with no media evidence stay unknown`() {
        assertNull(StreamMimeTypes.fromUrl("https://cdn.example.com/d/abcdef123456?token=zzz"))
        assertNull(StreamMimeTypes.fromUrl("https://cdn.example.com/file/0192837465"))
    }

    @Test
    fun `non-media content types are not forced into a mime`() {
        assertNull(StreamMimeTypes.fromContentType("text/html"))
        assertNull(StreamMimeTypes.fromContentType("application/json"))
        assertNull(StreamMimeTypes.fromContentType(""))
        assertNull(StreamMimeTypes.fromContentType(null))
    }

    @Test
    fun `partial tokens do not trigger detection`() {
        // "m3u8playlist" is one token, not a delimited m3u8.
        assertNull(
            StreamMimeTypes.fromUrl("https://x.example.com/files/m3u8playlistdata/file")
        )
    }

    // -----------------------------------------------------------------
    // Provider hints and Content-Disposition
    // -----------------------------------------------------------------

    @Test
    fun `provider format hints map cleanly or not at all`() {
        assertEquals(StreamMimeTypes.APPLICATION_M3U8, StreamMimeTypes.fromProviderHint("m3u8"))
        assertEquals(StreamMimeTypes.APPLICATION_M3U8, StreamMimeTypes.fromProviderHint("HLS"))
        assertEquals(StreamMimeTypes.APPLICATION_MPD, StreamMimeTypes.fromProviderHint("dash"))
        assertEquals(StreamMimeTypes.VIDEO_MP4, StreamMimeTypes.fromProviderHint("mp4"))
        assertEquals(StreamMimeTypes.VIDEO_MATROSKA, StreamMimeTypes.fromProviderHint("mkv"))
        assertEquals(StreamMimeTypes.VIDEO_MP2T, StreamMimeTypes.fromProviderHint("ts"))
        assertNull(StreamMimeTypes.fromProviderHint("something-exotic"))
        assertNull(StreamMimeTypes.fromProviderHint(null))
        assertNull(StreamMimeTypes.fromProviderHint("  "))
    }

    @Test
    fun `content disposition filename gives the container`() {
        assertEquals(
            StreamMimeTypes.VIDEO_MATROSKA,
            StreamMimeTypes.fromContentDisposition("attachment; filename=\"movie.2023.mkv\"")
        )
        assertEquals(
            StreamMimeTypes.APPLICATION_M3U8,
            StreamMimeTypes.fromContentDisposition("inline; filename*=UTF-8''master%2Em3u8")
        )
        assertNull(StreamMimeTypes.fromContentDisposition("attachment; filename=\"data.bin\""))
        assertNull(StreamMimeTypes.fromContentDisposition(null))
    }

    @Test
    fun `manifest mime set identifies manifests`() {
        assertEquals(3, StreamMimeTypes.MANIFEST_MIMES.size)
        org.junit.Assert.assertTrue(StreamMimeTypes.APPLICATION_M3U8 in StreamMimeTypes.MANIFEST_MIMES)
        org.junit.Assert.assertTrue(StreamMimeTypes.VIDEO_MP4 !in StreamMimeTypes.MANIFEST_MIMES)
    }
}
