package com.streambridge.app

import com.streambridge.app.player.StreamValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stream URL validation is the crash boundary: a URL that reaches
 * ExoPlayer unvalidated can throw synchronously out of setMediaItem.
 */
class StreamValidatorTest {

    private fun invalid(url: String?): StreamValidator.Result.Invalid {
        val result = StreamValidator.validate(url)
        assertTrue("expected Invalid for $url, got $result", result is StreamValidator.Result.Invalid)
        return result as StreamValidator.Result.Invalid
    }

    private fun valid(url: String, type: StreamValidator.ContentType? = null): StreamValidator.Result.Valid {
        val result = StreamValidator.validate(url)
        assertTrue("expected Valid for $url, got $result", result is StreamValidator.Result.Valid)
        return result as StreamValidator.Result.Valid
    }

    @Test
    fun `empty and blank urls are rejected`() {
        assertTrue(invalid("").reason.isNotBlank())
        assertTrue(invalid("   ").reason.isNotBlank())
        assertTrue(invalid(null).reason.isNotBlank())
    }

    @Test
    fun `urls without a scheme are rejected`() {
        invalid("example.com/video.mp4")
        invalid("/local/path.mp4")
        invalid("just-a-string")
    }

    @Test
    fun `unsupported schemes are rejected with a readable reason`() {
        val magnet = invalid("magnet:?xt=urn:btih:abcdef")
        assertTrue(magnet.reason.contains("magnet"))
        invalid("file:///sdcard/movie.mp4")
        invalid("content://media/external/video/1")
        invalid("javascript:alert(1)")
        invalid("ftp://example.com/video.mp4")
    }

    @Test
    fun `http urls without a host are rejected`() {
        invalid("http://")
        invalid("http:///path")
        invalid("https://:8080/x")
    }

    @Test
    fun `whitespace and control characters are rejected`() {
        invalid("https://example.com/video file.mp4")
        invalid("https://example.com/a\u0000b.mp4")
        invalid("https://example.com/a\r\nHeader: injected")
    }

    @Test
    fun `overlong urls are rejected`() {
        invalid("https://example.com/" + "a".repeat(4000))
    }

    @Test
    fun `valid http and https urls pass`() {
        valid("https://cdn.example.com/movie.mkv")
        valid("http://192.168.1.4:8080/stream.mp4")
        val withQuery = valid("https://cdn.example.com/hls/index.m3u8?token=abc&expires=1")
        assertEquals(StreamValidator.ContentType.HLS, withQuery.contentType)
    }

    @Test
    fun `rtsp and rtp are accepted`() {
        assertEquals(StreamValidator.ContentType.RTSP, valid("rtsp://cam.example.com/stream").contentType)
        assertEquals(StreamValidator.ContentType.RTSP, valid("rtp://example.com:5004").contentType)
    }

    @Test
    fun `content type inference follows extensions`() {
        assertEquals(StreamValidator.ContentType.HLS, valid("https://x.example.com/index.m3u8").contentType)
        assertEquals(StreamValidator.ContentType.DASH, valid("https://x.example.com/manifest.mpd").contentType)
        assertEquals(StreamValidator.ContentType.SMOOTH_STREAMING, valid("https://x.example.com/stream.ism/manifest").contentType)
        assertEquals(StreamValidator.ContentType.PROGRESSIVE, valid("https://x.example.com/movie.mp4").contentType)
        assertEquals(StreamValidator.ContentType.PROGRESSIVE, valid("https://x.example.com/video").contentType)
    }

    @Test
    fun `mime type overrides the extension`() {
        assertEquals(
            StreamValidator.ContentType.HLS,
            StreamValidator.inferContentType("https://x.example.com/video", "application/x-mpegURL")
        )
        assertEquals(
            StreamValidator.ContentType.DASH,
            StreamValidator.inferContentType("https://x.example.com/video.mp4", "application/dash+xml")
        )
        assertEquals(
            StreamValidator.ContentType.PROGRESSIVE,
            StreamValidator.inferContentType("https://x.example.com/index.m3u8", null)
        )
        // Unknown MIME falls through to extension detection.
        assertEquals(
            StreamValidator.ContentType.HLS,
            StreamValidator.inferContentType("https://x.example.com/index.m3u8", "text/plain")
        )
    }

    @Test
    fun `urls are trimmed before validation`() {
        val result = valid("  https://example.com/movie.mp4  ")
        assertEquals("https://example.com/movie.mp4", result.url)
    }
}
