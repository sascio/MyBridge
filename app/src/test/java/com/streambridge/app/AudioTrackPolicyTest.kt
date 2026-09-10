package com.streambridge.app

import com.streambridge.app.player.AudioTrackPolicy
import com.streambridge.app.player.AudioTrackCandidate
import com.streambridge.app.player.DecoderSupport

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The capability-aware audio track policy, per the parity contract:
 *  - never downgrade a supported preferred track (E-AC-3 stays E-AC-3
 *    when the device can decode it)
 *  - switch off a KNOWN-undecodable track to a KNOWN-decodable one
 *    before any failure happens
 *  - after a failure, try remaining candidates in quality order
 *  - only the caller may mute, and only when nothing is left to try
 */
class AudioTrackPolicyTest {

    private fun format(
        mime: String? = null,
        codecs: String? = null,
        channels: Int = 2,
        bitrate: Int = 128_000,
        language: String? = null,
        label: String? = null
    ): Format = Format.Builder()
        .setSampleMimeType(mime)
        .setCodecs(codecs)
        .setChannelCount(channels)
        .setAverageBitrate(bitrate)
        .setLanguage(language)
        .setLabel(label)
        .build()

    private fun candidate(
        group: Int,
        track: Int,
        support: DecoderSupport,
        format: Format
    ): AudioTrackCandidate = AudioTrackCandidate(group, track, format, support)

    private val eac3 = format(MimeTypes.AUDIO_E_AC3, channels = 6, bitrate = 384_000, language = "en")
    private val aac = format(MimeTypes.AUDIO_AAC, channels = 2, bitrate = 128_000, language = "en")
    private val ac3 = format(MimeTypes.AUDIO_AC3, channels = 6, bitrate = 448_000, language = "en")

    // ---------------- preemptive switching ----------------

    @Test
    fun `supported selected track is never downgraded`() {
        val selected = candidate(0, 0, DecoderSupport.SUPPORTED, eac3)
        val candidates = listOf(
            selected,
            candidate(1, 0, DecoderSupport.SUPPORTED, aac)
        )
        assertNull(AudioTrackPolicy.preemptiveSwitch(selected, candidates))
    }

    @Test
    fun `unknown capability defers to the player's own selection`() {
        val selected = candidate(0, 0, DecoderSupport.UNKNOWN, eac3)
        assertNull(AudioTrackPolicy.preemptiveSwitch(selected, listOf(selected)))
    }

    @Test
    fun `known undecodable selection switches to a decodable alternative`() {
        val selected = candidate(0, 0, DecoderSupport.UNSUPPORTED, eac3)
        val alternative = candidate(1, 0, DecoderSupport.SUPPORTED, aac)
        val decision = AudioTrackPolicy.preemptiveSwitch(selected, listOf(selected, alternative))
        assertEquals(alternative.key, decision?.key)
    }

    @Test
    fun `no preemptive switch when no supported alternative exists`() {
        val selected = candidate(0, 0, DecoderSupport.UNSUPPORTED, eac3)
        val alsoBad = candidate(1, 0, DecoderSupport.UNSUPPORTED, ac3)
        val unknown = candidate(2, 0, DecoderSupport.UNKNOWN, aac)
        // Unknown-capability tracks are NOT forced (defer to the player).
        assertNull(AudioTrackPolicy.preemptiveSwitch(selected, listOf(selected, alsoBad, unknown)))
    }

    // ---------------- post-failure selection ----------------

    @Test
    fun `failed codec is excluded from the retry`() {
        val failedEac3 = candidate(0, 0, DecoderSupport.UNSUPPORTED, eac3)
        val sameCodecElsewhere = candidate(1, 0, DecoderSupport.SUPPORTED, eac3)
        val aacTrack = candidate(2, 0, DecoderSupport.SUPPORTED, aac)
        val decision = AudioTrackPolicy.bestAlternative(
            candidates = listOf(failedEac3, sameCodecElsewhere, aacTrack),
            excludeMimeTypes = setOf(MimeTypes.AUDIO_E_AC3)
        )
        assertEquals(aacTrack.key, decision?.key)
    }

    @Test
    fun `already tried tracks are not retried`() {
        val first = candidate(0, 0, DecoderSupport.SUPPORTED, aac)
        val second = candidate(1, 0, DecoderSupport.SUPPORTED, aac.buildUpon().setLabel("other").build())
        val decision = AudioTrackPolicy.bestAlternative(
            candidates = listOf(first, second),
            excludeKeys = setOf(first.key)
        )
        assertEquals(second.key, decision?.key)
    }

    @Test
    fun `supported ranks above unknown capability`() {
        val unknownTrack = candidate(0, 0, DecoderSupport.UNKNOWN, ac3)
        val supportedTrack = candidate(1, 1, DecoderSupport.SUPPORTED, aac)
        val decision = AudioTrackPolicy.bestAlternative(listOf(unknownTrack, supportedTrack))
        assertEquals(supportedTrack.key, decision?.key)
    }

    @Test
    fun `language of the failed track is preferred`() {
        val failedLanguageFormat = format(MimeTypes.AUDIO_E_AC3, language = "de")
        val spanish = candidate(0, 0, DecoderSupport.SUPPORTED, format(MimeTypes.AUDIO_AAC, language = "es"))
        val german = candidate(1, 0, DecoderSupport.SUPPORTED, format(MimeTypes.AUDIO_AAC, language = "de"))
        val decision = AudioTrackPolicy.bestAlternative(
            candidates = listOf(spanish, german),
            preferLanguageOf = failedLanguageFormat
        )
        assertEquals(german.key, decision?.key)
    }

    @Test
    fun `more channels win at equal support and language`() {
        val stereo = candidate(0, 0, DecoderSupport.SUPPORTED, format(MimeTypes.AUDIO_AAC, channels = 2))
        val surround = candidate(1, 0, DecoderSupport.SUPPORTED, format(MimeTypes.AUDIO_AAC, channels = 6))
        val decision = AudioTrackPolicy.bestAlternative(listOf(stereo, surround))
        assertEquals(surround.key, decision?.key)
    }

    @Test
    fun `higher bitrate breaks channel ties`() {
        val low = candidate(0, 0, DecoderSupport.SUPPORTED, format(MimeTypes.AUDIO_AAC, bitrate = 96_000))
        val high = candidate(1, 0, DecoderSupport.SUPPORTED, format(MimeTypes.AUDIO_AAC, bitrate = 256_000))
        val decision = AudioTrackPolicy.bestAlternative(listOf(low, high))
        assertEquals(high.key, decision?.key)
    }

    @Test
    fun `null when everything is unsupported or tried`() {
        val bad = candidate(0, 0, DecoderSupport.UNSUPPORTED, eac3)
        val tried = candidate(1, 0, DecoderSupport.SUPPORTED, aac)
        assertNull(
            AudioTrackPolicy.bestAlternative(
                candidates = listOf(bad, tried),
                excludeKeys = setOf(tried.key)
            )
        )
        assertNull(AudioTrackPolicy.bestAlternative(listOf(bad)))
    }

    @Test
    fun `full ties resolve in stable stream order`() {
        val first = candidate(0, 1, DecoderSupport.SUPPORTED, aac)
        val later = candidate(2, 0, DecoderSupport.SUPPORTED, aac)
        val decision = AudioTrackPolicy.bestAlternative(listOf(later, first))
        assertEquals(first.key, decision?.key)
    }

    // ---------------- metadata preserved ----------------

    @Test
    fun `codec string resolves the mime when the sample mime is missing`() {
        val candidate = AudioTrackCandidate(
            0, 0,
            format(codecs = "ec-3", channels = 6),
            DecoderSupport.UNSUPPORTED
        )
        assertEquals(MimeTypes.AUDIO_E_AC3, candidate.mimeType)
    }

    @Test
    fun `summary names codec and channels for diagnostics`() {
        val noLanguageNoLabel = format(MimeTypes.AUDIO_E_AC3, channels = 6, bitrate = 384_000)
        val candidate = AudioTrackCandidate(0, 0, noLanguageNoLabel, DecoderSupport.UNSUPPORTED)
        assertEquals("E-AC-3 6ch", candidate.summary)
    }

    @Test
    fun `summary prefers the track label when present`() {
        val labelled = eac3.buildUpon().setLabel("Surround 5.1").build()
        val candidate = AudioTrackCandidate(0, 0, labelled, DecoderSupport.SUPPORTED)
        assertEquals("Surround 5.1 (E-AC-3 6ch)", candidate.summary)
    }

    @Test
    fun `key is stable per group and track`() {
        assertEquals("2:3", AudioTrackCandidate(2, 3, aac, DecoderSupport.SUPPORTED).key)
    }
}
