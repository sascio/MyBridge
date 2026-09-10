package com.streambridge.app

import com.streambridge.app.player.PlaybackDiagnostics
import com.streambridge.app.player.PlaybackFailureCategory

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Diagnostics must preserve the REAL cause of a failure while NEVER
 * leaking credentials: no cookies, no authorization values, no signed
 * URL paths or query strings. These tests attack exactly that.
 */
class PlaybackDiagnosticsTest {

    private val signedUrl = "https://cdn.example.com/h/abc123/master.m3u8?token=SECRET_TOKEN&exp=1999"

    @Test
    fun `hostOf keeps only the host — path and token are dropped`() {
        assertEquals("cdn.example.com", PlaybackDiagnostics.hostOf(signedUrl))
    }

    @Test
    fun `hostOf of null blank or malformed urls is null`() {
        assertNull(PlaybackDiagnostics.hostOf(null))
        assertNull(PlaybackDiagnostics.hostOf(""))
        assertNull(PlaybackDiagnostics.hostOf("   "))
        assertNull(PlaybackDiagnostics.hostOf("not a url"))
    }

    @Test
    fun `log line never contains the signed url token or cookie value`() {
        val diagnostics = PlaybackDiagnostics(
            backendId = "media3",
            sourceName = "ExampleAddon",
            containerMime = MimeTypes.APPLICATION_M3U8,
            streamHost = PlaybackDiagnostics.hostOf(signedUrl),
            headerNames = listOf("Cookie", "Referer", "User-Agent").sorted(),
            audioMimeType = MimeTypes.AUDIO_E_AC3,
            errorCategory = PlaybackFailureCategory.AUDIO_DECODER_UNSUPPORTED,
            errorCause = "DecoderInitializationException",
            fallbackAttempts = listOf("audio switch -> AAC 2ch", "audio disabled (no decodable track)")
        )
        val log = diagnostics.toLogString()
        assertFalse(log.contains("SECRET_TOKEN"))
        assertFalse(log.contains("master.m3u8"))
        assertFalse(log.contains("sessionid=supersecret"))
        // The facts that explain the failure ARE there.
        assertTrue(log.contains("cdn.example.com"))
        assertTrue(log.contains("Cookie"))
        assertTrue(log.contains("AUDIO_DECODER_UNSUPPORTED"))
        assertTrue(log.contains("audio/eac3"))
        assertTrue(log.contains("audio switch -> AAC 2ch"))
    }

    @Test
    fun `header values are structurally impossible to store`() {
        // The type only carries header NAMES — this is the redaction
        // guarantee: there is no field a value could hide in.
        val diagnostics = PlaybackDiagnostics(
            backendId = "media3",
            headerNames = listOf("Authorization")
        )
        assertEquals(listOf("Authorization"), diagnostics.headerNames)
        assertFalse(diagnostics.toLogString().contains("Bearer"))
    }

    @Test
    fun `every structured fact survives into the log line`() {
        val diagnostics = PlaybackDiagnostics(
            backendId = "media3",
            sourceName = "SomeAddon",
            containerMime = MimeTypes.APPLICATION_MPD,
            streamHost = "cdn.example.org",
            headerNames = listOf("Referer"),
            videoMimeType = MimeTypes.VIDEO_H265,
            videoCodec = "hvc1.2.4.L153.B0",
            selectedVideo = "HEVC 1920x1080",
            audioMimeType = MimeTypes.AUDIO_AAC,
            audioCodec = "mp4a.40.2",
            selectedAudio = "English (AAC 2ch)",
            availableAudioTracks = listOf("English (AAC 2ch)", "E-AC-3 6ch"),
            httpStatus = 403,
            errorCategory = PlaybackFailureCategory.HTTP_403,
            errorCause = "InvalidResponseCodeException -> HttpDataSource",
            fallbackAttempts = listOf("tried alternate #2"),
            notes = listOf("no MediaCodec decoder for audio/eac3 on this device")
        )
        val log = diagnostics.toLogString()
        listOf(
            "backend=media3",
            "source=SomeAddon",
            "container=application/dash+xml",
            "host=cdn.example.org",
            "video=video/hevc",
            "vcodec=hvc1.2.4.L153.B0",
            "selectedVideo=HEVC 1920x1080",
            "audio=audio/mp4a-latm",
            "selectedAudio=English (AAC 2ch)",
            "http=403",
            "category=HTTP_403",
            "fallbacks=tried alternate #2",
            "notes=no MediaCodec decoder for audio/eac3 on this device"
        ).forEach { fact ->
            assertTrue("log must contain: $fact", log.contains(fact))
        }
    }

    @Test
    fun `mpv stage is logged without carrying a url`() {
        val diagnostics = PlaybackDiagnostics(
            backendId = "libmpv",
            streamHost = "cdn.example.com",
            mpvStage = "SURFACE_WAIT_TIMEOUT",
            errorCause = "SURFACE_WAIT_TIMEOUT: no Surface arrived",
            notes = listOf("lastSuccessfulStage=WAITING_FOR_SURFACE")
        )
        val log = diagnostics.toLogString()
        assertTrue(log.contains("mpvStage=SURFACE_WAIT_TIMEOUT"))
        assertTrue(log.contains("WAITING_FOR_SURFACE"))
        assertFalse(log.contains("http"))
        assertFalse(log.contains("token"))
    }

    @Test
    fun `copy preserves unmodified fields across updates`() {
        val base = PlaybackDiagnostics(
            backendId = "media3",
            sourceName = "Addon",
            headerNames = listOf("Cookie")
        )
        val updated = base.copy(httpStatus = 403, errorCategory = PlaybackFailureCategory.HTTP_403)
        assertEquals("Addon", updated.sourceName)
        assertEquals(listOf("Cookie"), updated.headerNames)
        assertEquals(403, updated.httpStatus)
    }
}
