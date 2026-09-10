package com.streambridge.app

import com.streambridge.app.player.DecoderQuery
import com.streambridge.app.player.DecoderRegistry
import com.streambridge.app.player.DecoderDescriptor
import com.streambridge.app.player.DecoderSupport
import com.streambridge.app.player.Media3PlaybackBackend
import com.streambridge.app.player.PlaybackBackend
import com.streambridge.app.player.PlaybackBackendSelector
import com.streambridge.app.player.PlaybackPreparation
import com.streambridge.app.player.SoftwareDecoderExtensions

import androidx.media3.common.MimeTypes
import com.streambridge.app.addon.StreamMimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Backend selection is capability-based: the container/codec decides,
 * never the provider or URL. The same stream from two different
 * providers must get the identical decision.
 */
class PlaybackBackendSelectorTest {

    private val media3 = Media3PlaybackBackend(FakeRegistry(emptyMap()))

    private class FakeRegistry(private val supported: Set<String>) : DecoderRegistry {
        override fun query(mimeType: String): DecoderQuery =
            if (mimeType in supported) {
                DecoderQuery.Decoders(listOf(DecoderDescriptor(mimeType, "fake", false, true)))
            } else {
                DecoderQuery.Decoders(emptyList())
            }
    }

    /** A backend whose support map we control (stands in for a future software backend). */
    private class FakeBackend(
        override val id: String,
        private val containers: Set<String?>
    ) : PlaybackBackend {
        override fun supportsContainer(mimeType: String?): DecoderSupport =
            if (mimeType in containers) DecoderSupport.SUPPORTED else DecoderSupport.UNKNOWN

        override fun supportsCodec(mimeType: String?): DecoderSupport = DecoderSupport.SUPPORTED
    }

    private fun preparation(mime: String?) = PlaybackPreparation(
        url = "https://provider.example.com/stream",
        headers = emptyMap(),
        mimeType = mime
    )

    @Test
    fun `manifest containers are supported by the media3 backend`() {
        StreamMimeTypes.MANIFEST_MIMES.forEach { mime ->
            assertEquals("expected SUPPORTED for $mime", DecoderSupport.SUPPORTED, media3.supportsContainer(mime))
        }
        assertEquals(DecoderSupport.SUPPORTED, media3.supportsContainer(MimeTypes.APPLICATION_M3U8))
        assertEquals(DecoderSupport.SUPPORTED, media3.supportsContainer(MimeTypes.APPLICATION_MPD))
    }

    @Test
    fun `progressive containers are supported by the media3 backend`() {
        listOf(
            MimeTypes.APPLICATION_MP4,
            MimeTypes.VIDEO_MP4,
            MimeTypes.VIDEO_MP2T,
            MimeTypes.APPLICATION_MATROSKA,
            MimeTypes.VIDEO_WEBM,
            MimeTypes.AUDIO_MPEG,
            MimeTypes.AUDIO_FLAC
        ).forEach { mime ->
            assertEquals("expected SUPPORTED for $mime", DecoderSupport.SUPPORTED, media3.supportsContainer(mime))
        }
    }

    @Test
    fun `unknown mime stays UNKNOWN — sniffing decides, never a guess`() {
        assertEquals(DecoderSupport.UNKNOWN, media3.supportsContainer(null))
        assertEquals(DecoderSupport.UNKNOWN, media3.supportsContainer("application/x-something-new"))
    }

    @Test
    fun `codec support mirrors the device decoder registry`() {
        val capable = Media3PlaybackBackend(FakeRegistry(setOf(MimeTypes.AUDIO_E_AC3)))
        assertEquals(DecoderSupport.SUPPORTED, capable.supportsCodec(MimeTypes.AUDIO_E_AC3))

        val incapable = Media3PlaybackBackend(FakeRegistry(emptySet()))
        assertEquals(DecoderSupport.UNSUPPORTED, incapable.supportsCodec(MimeTypes.AUDIO_E_AC3))
    }

    @Test
    fun `selector picks media3 when it supports the container`() {
        val decision = PlaybackBackendSelector.select(preparation(MimeTypes.APPLICATION_M3U8), listOf(media3))
        assertEquals(Media3PlaybackBackend.ID, decision.backend.id)
        assertEquals(DecoderSupport.SUPPORTED, decision.containerSupport)
        assertEquals(listOf(Media3PlaybackBackend.ID), decision.considered)
    }

    @Test
    fun `selector is provider agnostic — same format, same decision`() {
        val hlsFromProviderA = PlaybackPreparation(
            url = "https://cdn-a.example.com/x?token=a",
            headers = emptyMap(),
            mimeType = MimeTypes.APPLICATION_M3U8
        )
        val hlsFromProviderB = PlaybackPreparation(
            url = "https://completely-different.provider.org/y",
            headers = mapOf("Referer" to "https://b.example.com"),
            mimeType = MimeTypes.APPLICATION_M3U8
        )
        val decisionA = PlaybackBackendSelector.select(hlsFromProviderA, listOf(media3))
        val decisionB = PlaybackBackendSelector.select(hlsFromProviderB, listOf(media3))
        assertEquals(decisionA.backend.id, decisionB.backend.id)
        assertEquals(decisionA.containerSupport, decisionB.containerSupport)
    }

    @Test
    fun `a secondary backend wins exactly when the primary cannot`() {
        val strictPrimary = FakeBackend("strict-primary", containers = setOf(MimeTypes.APPLICATION_M3U8))
        val softwareSecondary = FakeBackend("software", containers = setOf(null, MimeTypes.APPLICATION_MP4, "video/x-matroska"))
        val backends = listOf(strictPrimary, softwareSecondary)

        // Primary handles its formats…
        assertEquals(
            "strict-primary",
            PlaybackBackendSelector.select(preparation(MimeTypes.APPLICATION_M3U8), backends).backend.id
        )
        // …and only falls through to the secondary for what it cannot.
        assertEquals(
            "software",
            PlaybackBackendSelector.select(preparation("video/x-matroska"), backends).backend.id
        )
    }

    @Test
    fun `no backends is a programming error, not a silent default`() {
        var thrown = false
        try {
            PlaybackBackendSelector.select(preparation(null), emptyList())
        } catch (_: IllegalArgumentException) {
            thrown = true
        }
        assertTrue(thrown)
    }

    @Test
    fun `software decoder extensions are absent in the current build`() {
        // Documents today's honest state: no ffmpeg/av1/mpegh extension
        // AARs are bundled, so the software tier reports empty.
        assertTrue(SoftwareDecoderExtensions.present().isEmpty())
    }

    @Test
    fun `extension detection is classpath based`() {
        // Any class actually present is detected (java.lang.String
        // stands in for a bundled extension renderer class).
        assertEquals(
            listOf("java.lang.String"),
            SoftwareDecoderExtensions.present(candidateClasses = listOf("java.lang.String", "no.such.Class"))
        )
    }
}
