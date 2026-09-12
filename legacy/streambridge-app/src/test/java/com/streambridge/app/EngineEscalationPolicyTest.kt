package com.streambridge.app

import com.streambridge.app.player.EngineEscalationPolicy
import com.streambridge.app.player.EngineEscalationPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The deterministic Media3 → libmpv ladder, per the parity contract:
 *
 *  Case A — Media3 plays: the policy is never consulted (no failure).
 *  Case B — E-AC-3 audio failure with no Media3 audio path:
 *           escalate to libmpv (software decodes E-AC-3 WITH audio),
 *           never mute while an engine can still provide audio.
 *  Case C — HEVC video decoder failure: libmpv before abandoning.
 *  Case D — container/demuxer failure: libmpv before abandoning.
 *  Case E — libmpv also failed: fail the source (bounded alternate).
 *
 * Plus the bounds: exactly ONE engine switch per stream, never repeated
 * switching, never an infinite loop.
 */
class EngineEscalationPolicyTest {

    private val mpvAvailable = EngineEscalationPolicy.State(
        mpvAvailable = true, mpvAttempted = false, mpvFailed = false
    )

    // Case B: audio with no Media3 path → engine, not mute
    @Test
    fun `audio without a media3 path escalates instead of muting`() {
        assertEquals(
            Decision.ESCALATE_TO_LIBMPV,
            EngineEscalationPolicy.decide(mpvAvailable, audioNoPath = true)
        )
    }

    // Final fallback only when no engine can provide audio at all
    @Test
    fun `mute is the final fallback only when no engine exists`() {
        val noMpv = mpvAvailable.copy(mpvAvailable = false)
        assertEquals(
            Decision.MUTE_AUDIO,
            EngineEscalationPolicy.decide(noMpv, audioNoPath = true)
        )
    }

    // Case C/D: any other failure escalates before failing the source
    @Test
    fun `video and container failures escalate to libmpv`() {
        assertEquals(
            Decision.ESCALATE_TO_LIBMPV,
            EngineEscalationPolicy.decide(mpvAvailable, audioNoPath = false)
        )
    }

    @Test
    fun `non-audio failure without an engine fails the source`() {
        val noMpv = mpvAvailable.copy(mpvAvailable = false)
        assertEquals(
            Decision.FAIL_SOURCE,
            EngineEscalationPolicy.decide(noMpv, audioNoPath = false)
        )
    }

    // Case E: libmpv already failed on this source
    @Test
    fun `failed libmpv fails the source`() {
        val mpvFailed = mpvAvailable.copy(mpvFailed = true)
        assertEquals(Decision.FAIL_SOURCE, EngineEscalationPolicy.decide(mpvFailed, audioNoPath = true))
        assertEquals(Decision.FAIL_SOURCE, EngineEscalationPolicy.decide(mpvFailed, audioNoPath = false))
    }

    // Bound: one switch per stream — never repeated switching
    @Test
    fun `one engine attempt per stream`() {
        val alreadyTried = mpvAvailable.copy(mpvAttempted = true)
        assertEquals(Decision.FAIL_SOURCE, EngineEscalationPolicy.decide(alreadyTried, audioNoPath = true))
        assertEquals(Decision.FAIL_SOURCE, EngineEscalationPolicy.decide(alreadyTried, audioNoPath = false))
    }

    @Test
    fun `attempted-and-failed never escalates again`() {
        val exhausted = mpvAvailable.copy(mpvAttempted = true, mpvFailed = true)
        assertEquals(Decision.FAIL_SOURCE, EngineEscalationPolicy.decide(exhausted, audioNoPath = false))
    }

    @Test
    fun `unavailable engine never escalates`() {
        val noMpv = mpvAvailable.copy(mpvAvailable = false)
        assertEquals(Decision.MUTE_AUDIO, EngineEscalationPolicy.decide(noMpv, audioNoPath = true))
        assertEquals(Decision.FAIL_SOURCE, EngineEscalationPolicy.decide(noMpv, audioNoPath = false))
    }
}
