package com.streambridge.app

import com.streambridge.app.player.DecoderQuery
import com.streambridge.app.player.DecoderRegistry
import com.streambridge.app.player.DecoderDescriptor
import com.streambridge.app.player.DecoderSupport
import com.streambridge.app.player.PlaybackCapabilities

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Capability queries drive track selection and backend choice, so their
 * TRI-STATE contract is tested directly: decoders present → SUPPORTED,
 * definitively none → UNSUPPORTED (may deselect a track), query
 * failed/missing mime → UNKNOWN (must never deselect anything).
 */
class PlaybackCapabilitiesTest {

    private class FakeRegistry(private val answers: Map<String, DecoderQuery>) : DecoderRegistry {
        override fun query(mimeType: String): DecoderQuery =
            answers[mimeType] ?: DecoderQuery.Decoders(emptyList())
    }

    @Test
    fun `decoders present means supported`() {
        val registry = FakeRegistry(
            mapOf(
                MimeTypes.AUDIO_AAC to DecoderQuery.Decoders(
                    listOf(
                        DecoderDescriptor(MimeTypes.AUDIO_AAC, "OMX.google.aac.decoder", false, true),
                        DecoderDescriptor(MimeTypes.AUDIO_AAC, "c2.qti.aac.decoder", true, false)
                    )
                )
            )
        )
        assertEquals(DecoderSupport.SUPPORTED, PlaybackCapabilities.supportFor(registry, MimeTypes.AUDIO_AAC))
        assertTrue(PlaybackCapabilities.canDecode(registry, MimeTypes.AUDIO_AAC))
    }

    @Test
    fun `empty decoder list means unsupported`() {
        val registry = FakeRegistry(
            mapOf(MimeTypes.AUDIO_E_AC3 to DecoderQuery.Decoders(emptyList()))
        )
        assertEquals(
            DecoderSupport.UNSUPPORTED,
            PlaybackCapabilities.supportFor(registry, MimeTypes.AUDIO_E_AC3)
        )
    }

    @Test
    fun `a failed device query means unknown — never unsupported`() {
        val registry = FakeRegistry(mapOf(MimeTypes.AUDIO_E_AC3 to DecoderQuery.QueryFailed))
        assertEquals(
            DecoderSupport.UNKNOWN,
            PlaybackCapabilities.supportFor(registry, MimeTypes.AUDIO_E_AC3)
        )
    }

    @Test
    fun `missing or blank mime means unknown`() {
        val registry = FakeRegistry(emptyMap())
        assertEquals(DecoderSupport.UNKNOWN, PlaybackCapabilities.supportFor(registry, null))
        assertEquals(DecoderSupport.UNKNOWN, PlaybackCapabilities.supportFor(registry, ""))
        assertEquals(DecoderSupport.UNKNOWN, PlaybackCapabilities.supportFor(registry, "   "))
    }

    @Test
    fun `a registry that throws means unknown`() {
        val registry = object : DecoderRegistry {
            override fun query(mimeType: String): DecoderQuery = error("codec service down")
        }
        assertEquals(DecoderSupport.UNKNOWN, PlaybackCapabilities.supportFor(registry, MimeTypes.AUDIO_AAC))
    }

    @Test
    fun `mime lookup is exact — one codec's answer never affects another`() {
        val registry = FakeRegistry(
            mapOf(
                MimeTypes.AUDIO_AAC to DecoderQuery.Decoders(
                    listOf(DecoderDescriptor(MimeTypes.AUDIO_AAC, "c2.android.aac.decoder", false, true))
                ),
                MimeTypes.AUDIO_AC3 to DecoderQuery.Decoders(emptyList())
            )
        )
        assertEquals(DecoderSupport.SUPPORTED, PlaybackCapabilities.supportFor(registry, MimeTypes.AUDIO_AAC))
        assertEquals(DecoderSupport.UNSUPPORTED, PlaybackCapabilities.supportFor(registry, MimeTypes.AUDIO_AC3))
        assertEquals(DecoderSupport.UNSUPPORTED, PlaybackCapabilities.supportFor(registry, MimeTypes.AUDIO_E_AC3))
    }
}
