package com.streambridge.app

import com.streambridge.app.player.MpvLoadGate
import com.streambridge.app.player.MpvLoadGateState
import com.streambridge.app.player.MpvPendingLoad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Nuvio / mpv-android contract: loadfile is forbidden until BOTH
 * the native player exists AND a valid Surface has been attached.
 *
 * 1a2d077 called loadfile from start() as soon as MPV was created,
 * while the Compose SurfaceView only appeared after the engine switch.
 * These tests pin the gate that forbids that order.
 */
class MpvLoadGateTest {

    private val load = MpvPendingLoad(
        url = "https://cdn.example.com/stream",
        headers = mapOf("Referer" to "https://provider.example.com/"),
        startPositionMs = 0L
    )

    @Test
    fun `loadfile is not ready when only the engine exists`() {
        val gate = MpvLoadGate()
        gate.onEngineCreated()
        gate.setPending(load)
        assertFalse(gate.canLoadfile)
        assertEquals(MpvLoadGateState.WAITING_FOR_SURFACE, gate.state)
        assertNull(gate.consumeIfReady())
        assertNotNull(gate.pending)
    }

    @Test
    fun `loadfile is not ready when only the surface exists`() {
        val gate = MpvLoadGate()
        gate.onSurfaceAttached()
        gate.setPending(load)
        assertFalse(gate.canLoadfile)
        assertEquals(MpvLoadGateState.WAITING_FOR_ENGINE, gate.state)
        assertNull(gate.consumeIfReady())
    }

    @Test
    fun `loadfile is ready only after engine AND surface AND pending url`() {
        val gate = MpvLoadGate()
        gate.setPending(load)
        gate.onEngineCreated()
        gate.onSurfaceAttached()
        assertTrue(gate.canLoadfile)
        assertEquals(MpvLoadGateState.READY_TO_LOAD, gate.state)
        val consumed = gate.consumeIfReady()
        assertEquals(load, consumed)
        assertNull(gate.pending)
        assertFalse(gate.canLoadfile)
        assertEquals(MpvLoadGateState.LOADED, gate.state)
    }

    @Test
    fun `surface-before-engine still loads once both are present`() {
        val gate = MpvLoadGate()
        gate.onSurfaceAttached()
        gate.setPending(load)
        assertNull(gate.consumeIfReady())
        gate.onEngineCreated()
        assertEquals(load, gate.consumeIfReady())
    }

    @Test
    fun `engine-before-surface still loads once surface attaches`() {
        val gate = MpvLoadGate()
        gate.onEngineCreated()
        gate.setPending(load)
        assertNull(gate.consumeIfReady())
        gate.onSurfaceAttached()
        assertEquals(load, gate.consumeIfReady())
    }

    @Test
    fun `detached surface blocks a subsequent load until re-attached`() {
        val gate = MpvLoadGate()
        gate.onEngineCreated()
        gate.onSurfaceAttached()
        gate.setPending(load)
        assertEquals(load, gate.consumeIfReady())
        gate.onSurfaceDetached()
        gate.setPending(load.copy(startPositionMs = 1_000L))
        assertEquals(MpvLoadGateState.WAITING_FOR_SURFACE, gate.state)
        assertNull(gate.consumeIfReady())
        gate.onSurfaceAttached()
        assertEquals(1_000L, gate.consumeIfReady()?.startPositionMs)
    }

    @Test
    fun `destroying the engine clears pending load and surface`() {
        val gate = MpvLoadGate()
        gate.onEngineCreated()
        gate.onSurfaceAttached()
        gate.setPending(load)
        gate.onEngineDestroyed()
        assertEquals(MpvLoadGateState.IDLE, gate.state)
        assertNull(gate.pending)
        assertFalse(gate.engineCreated)
        assertFalse(gate.surfaceAttached)
    }

    @Test
    fun `reload pending keeps original request headers`() {
        val gate = MpvLoadGate()
        gate.onEngineCreated()
        gate.onSurfaceAttached()
        val original = MpvPendingLoad(
            url = "https://cdn.example.com/stream",
            headers = mapOf(
                "Referer" to "https://provider.example.com/",
                "Cookie" to "session=" + "x".repeat(600),
                "Origin" to "https://provider.example.com"
            ),
            startPositionMs = 0L
        )
        gate.setPending(original)
        assertEquals(original, gate.consumeIfReady())
        val reload = original.copy(startPositionMs = 12_500L)
        gate.setPending(reload)
        val consumed = gate.consumeIfReady()
        assertEquals(original.headers, consumed?.headers)
        assertEquals(12_500L, consumed?.startPositionMs)
        assertTrue(consumed!!.headers["Cookie"]!!.length > 512)
    }

    @Test
    fun `no pending url never reports ready even with engine and surface`() {
        val gate = MpvLoadGate()
        gate.onEngineCreated()
        gate.onSurfaceAttached()
        assertFalse(gate.canLoadfile)
        assertNull(gate.consumeIfReady())
        assertEquals(MpvLoadGateState.LOADED, gate.state)
    }
}
