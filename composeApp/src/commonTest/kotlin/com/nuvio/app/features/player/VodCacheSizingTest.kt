package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

class VodCacheSizingTest {
    private val mb = 1024L * 1024L
    private val gb = 1024L * mb

    private fun autoBytes(freeGb: Long): Long {
        val free = freeGb * gb
        val runtimeMax = free - gb
        return VodCacheSizing.resolveAutoBytes(
            freeSpaceBytes = free,
            minBytes = 100L * mb,
            runtimeMaxBytes = runtimeMax,
        )
    }

    @Test
    fun lowStorageTakesTheFloorRatherThanAFifthOfFreeSpace() {
        assertEquals(2L * gb, autoBytes(6))
    }

    @Test
    fun theFreeSpaceCeilingStillWinsOverTheFloor() {
        assertEquals(1L * gb, autoBytes(2))
    }

    @Test
    fun ampleStorageKeepsOneFifthSizing() {
        assertEquals(4L * gb, autoBytes(20))
    }

    @Test
    fun diskCacheIsDisabledWhenAvailableSpaceCannotHoldTheMinimum() {
        assertEquals(0L, VodCacheSizing.resolveMaxBytes(VodCacheSizeMode.AUTO, 500, 0L))
        assertEquals(0L, VodCacheSizing.resolveMaxBytes(VodCacheSizeMode.MANUAL, 500, 120L * mb))
    }

    @Test
    fun manualSizeCannotConsumeReservedFreeSpace() {
        assertEquals(2L * gb, VodCacheSizing.resolveMaxBytes(VodCacheSizeMode.MANUAL, 6000, 3L * gb))
        assertEquals(500L * mb, VodCacheSizing.resolveMaxBytes(VodCacheSizeMode.MANUAL, 500, 3L * gb))
    }

    @Test
    fun manualSizeIsClampedToDocumentedRange() {
        assertEquals(100L * mb, VodCacheSizing.resolveMaxBytes(VodCacheSizeMode.MANUAL, -1, 3L * gb))
        assertEquals(65_536L * mb, VodCacheSizing.resolveMaxBytes(VodCacheSizeMode.MANUAL, Int.MAX_VALUE, 100L * gb))
    }
}
