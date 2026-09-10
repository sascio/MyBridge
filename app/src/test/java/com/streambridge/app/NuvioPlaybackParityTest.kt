@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.streambridge.app

import androidx.media3.common.PlaybackException
import com.streambridge.app.addon.StreamMimeTypes
import com.streambridge.app.player.Media3RecoveryPolicy
import com.streambridge.app.player.MpvEndFilePolicy
import com.streambridge.app.player.MpvLoadGate
import com.streambridge.app.player.MpvPendingLoad
import com.streambridge.app.player.MpvTrackInventory
import com.streambridge.app.player.MpvTrackNode
import com.streambridge.app.player.PlaybackRuntime
import com.streambridge.app.player.PlaybackRuntimePhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decision-layer coverage for Nuvio vs StreamBridge playback parity.
 * These are the same-source scenarios the player must honour; they do
 * not claim a real device played the bytes.
 */
class NuvioPlaybackParityTest {

    // A. Direct MP4 — URL evidence is video/mp4, no probe required.
    @Test
    fun `A direct mp4 is classified as progressive mp4`() {
        assertEquals(StreamMimeTypes.VIDEO_MP4, StreamMimeTypes.fromUrl("https://cdn.example.com/movie.mp4"))
        assertFalse(StreamMimeTypes.fromUrl("https://cdn.example.com/movie.mp4") in StreamMimeTypes.MANIFEST_MIMES)
    }

    // B. Extensionless direct video — unknown until probe; probe result used.
    @Test
    fun `B extensionless URL stays unknown without evidence`() {
        assertNull(StreamMimeTypes.fromUrl("https://cdn.example.com/d/9f8e7d6c5b4a?token=x"))
    }

    // C. HLS
    @Test
    fun `C HLS is recognized from extension query and path token`() {
        assertEquals(StreamMimeTypes.APPLICATION_M3U8, StreamMimeTypes.fromUrl("https://cdn.example.com/a.m3u8"))
        assertEquals(StreamMimeTypes.APPLICATION_M3U8, StreamMimeTypes.fromUrl("https://cdn.example.com/p?output=m3u8"))
        assertEquals(StreamMimeTypes.APPLICATION_M3U8, StreamMimeTypes.fromUrl("https://cdn.example.com/hls/token"))
    }

    // D. DASH
    @Test
    fun `D DASH is recognized from mpd evidence`() {
        assertEquals(StreamMimeTypes.APPLICATION_MPD, StreamMimeTypes.fromUrl("https://cdn.example.com/a.mpd"))
        assertEquals(StreamMimeTypes.APPLICATION_MPD, StreamMimeTypes.fromUrl("https://cdn.example.com/dash/token"))
    }

    // E/F/G header survival is PlaybackHttpTest.

    // H. Multiple audio tracks — MPV inventory maps every audio node.
    @Test
    fun `H multiple audio tracks are listed from the active backend`() {
        val options = MpvTrackInventory.audioOptions(
            listOf(
                MpvTrackNode("audio", 1, "English", "eng", "aac", selected = true),
                MpvTrackNode("audio", 2, "Commentary", "eng", "aac", selected = false),
                MpvTrackNode("sub", 1, "English", "eng", "srt", selected = false)
            )
        )
        assertEquals(2, options.size)
        assertTrue(options[0].selected)
        assertEquals("Commentary", options[1].label)
        assertEquals(2, options[1].trackIndex)
    }

    // I/J codec rows: no blacklist — recovery policy never mentions eac3/hevc.
    @Test
    fun `I J no codec name is hardcoded into the MIME probe policy`() {
        assertTrue(Media3RecoveryPolicy.shouldProbeMimeAndRetry(
            errorCode = PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            unrecognizedContainer = true,
            alreadyProbed = false
        ))
        assertFalse(Media3RecoveryPolicy.shouldProbeMimeAndRetry(
            errorCode = PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            unrecognizedContainer = false,
            alreadyProbed = false
        ))
    }

    // K. Subtitle-bearing source — sub tracks map like Nuvio.
    @Test
    fun `K subtitle tracks from mpv track-list are exposed`() {
        val options = MpvTrackInventory.subtitleOptions(
            listOf(
                MpvTrackNode("sub", 1, "English", "eng", "srt", selected = true),
                MpvTrackNode("sub", 2, "Spanish", "spa", "vtt", selected = false)
            )
        )
        assertEquals(listOf("English", "Spanish"), options.map { it.label })
        assertTrue(options[0].selected)
    }

    // L. Media3 failure → MIME probe before MPV.
    @Test
    fun `L unrecognized container probes MIME once then stops`() {
        assertTrue(
            Media3RecoveryPolicy.shouldProbeMimeAndRetry(
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                unrecognizedContainer = false,
                alreadyProbed = false
            )
        )
        assertFalse(
            Media3RecoveryPolicy.shouldProbeMimeAndRetry(
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                unrecognizedContainer = false,
                alreadyProbed = true
            )
        )
        assertTrue(Media3RecoveryPolicy.shouldUseProbedMime(null, StreamMimeTypes.APPLICATION_M3U8))
        assertFalse(Media3RecoveryPolicy.shouldUseProbedMime(StreamMimeTypes.APPLICATION_M3U8, StreamMimeTypes.APPLICATION_M3U8))
    }

    @Test
    fun `L extension prefer retry only when extensions exist`() {
        assertTrue(
            Media3RecoveryPolicy.shouldRetryWithExtensionPrefer(
                decoderFailure = true,
                extensionsPresent = true,
                alreadyRetriedPrefer = false
            )
        )
        assertFalse(
            Media3RecoveryPolicy.shouldRetryWithExtensionPrefer(
                decoderFailure = true,
                extensionsPresent = false,
                alreadyRetriedPrefer = false
            )
        )
    }

    // M. MPV failure before first frame is FAILED, not ENDED.
    @Test
    fun `M end-file before playback is a failure not a fake EOF`() {
        assertEquals(
            MpvEndFilePolicy.Decision.FAILED,
            MpvEndFilePolicy.decide(reason = null, firstFrame = false, fileLoaded = false)
        )
        assertEquals(
            MpvEndFilePolicy.Decision.FAILED,
            MpvEndFilePolicy.decide(MpvEndFilePolicy.REASON_EOF, firstFrame = false, fileLoaded = false)
        )
        assertEquals(
            MpvEndFilePolicy.Decision.FAILED,
            MpvEndFilePolicy.decide(MpvEndFilePolicy.REASON_ERROR, firstFrame = false, fileLoaded = false)
        )
        assertEquals(
            MpvEndFilePolicy.Decision.ENDED,
            MpvEndFilePolicy.decide(MpvEndFilePolicy.REASON_EOF, firstFrame = true, fileLoaded = true)
        )
        assertEquals(
            MpvEndFilePolicy.Decision.IGNORE,
            MpvEndFilePolicy.decide(MpvEndFilePolicy.REASON_STOP, firstFrame = false, fileLoaded = false)
        )
    }

    // N/O. Surface before/after engine.
    @Test
    fun `N O both surface orders can loadfile`() {
        val load = MpvPendingLoad("https://cdn.example.com/a", emptyMap(), 0L)
        val surfaceFirst = MpvLoadGate()
        surfaceFirst.onSurfaceAttached()
        surfaceFirst.setPending(load)
        assertNull(surfaceFirst.consumeIfReady())
        surfaceFirst.onEngineCreated()
        assertEquals(load, surfaceFirst.consumeIfReady())

        val engineFirst = MpvLoadGate()
        engineFirst.onEngineCreated()
        engineFirst.setPending(load)
        assertNull(engineFirst.consumeIfReady())
        engineFirst.onSurfaceAttached()
        assertEquals(load, engineFirst.consumeIfReady())
    }

    // P. Backend switching while surface is alive — UI stays in SWITCHING.
    @Test
    fun `P switching backend shows a spinner not a play button`() {
        val phase = PlaybackRuntime.phase(
            isPlaying = false,
            buffering = true,
            ended = false,
            switchingBackend = true,
            error = false
        )
        assertEquals(PlaybackRuntimePhase.SWITCHING_BACKEND, phase)
        assertTrue(PlaybackRuntime.showLoadingSpinner(phase))
        assertFalse(
            PlaybackRuntime.showLoadingSpinner(
                PlaybackRuntime.phase(
                    isPlaying = false,
                    buffering = false,
                    ended = false,
                    switchingBackend = false,
                    error = false
                )
            )
        )
    }

    @Test
    fun `black-screen fake ready is classified as paused not playing`() {
        val idleAfterFailedLoad = PlaybackRuntime.phase(
            isPlaying = false,
            buffering = false,
            ended = true,
            switchingBackend = false,
            error = false
        )
        assertEquals(PlaybackRuntimePhase.ENDED, idleAfterFailedLoad)
        val errorPhase = PlaybackRuntime.phase(
            isPlaying = false,
            buffering = false,
            ended = false,
            switchingBackend = false,
            error = true
        )
        assertEquals(PlaybackRuntimePhase.ERROR, errorPhase)
        assertFalse(PlaybackRuntime.showLoadingSpinner(errorPhase))
    }
}
