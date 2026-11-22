@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class BufferedReadDataSourceTest {
    @Test
    fun readsSmallAndLargeRequestsAndResetsAfterReopen() {
        val bytes = ByteArray(25) { it.toByte() }
        val source = BufferedReadDataSource(ByteArrayDataSource(bytes), bufferBytes = 8)
        val spec = DataSpec(Uri.parse("https://example.test/video"))
        source.open(spec)
        val result = ByteArray(bytes.size)
        var offset = 0
        while (offset < result.size) {
            val count = source.read(result, offset, if (offset < 4) 2 else result.size - offset)
            if (count == C.RESULT_END_OF_INPUT) break
            offset += count
        }
        assertEquals(bytes.size, offset)
        assertContentEquals(bytes, result)
        assertEquals(C.RESULT_END_OF_INPUT, source.read(ByteArray(1), 0, 1))
        assertEquals(0, source.read(ByteArray(0), 0, 0))
        source.close()
        source.open(spec)
        val first = ByteArray(2)
        assertEquals(2, source.read(first, 0, 2))
        assertContentEquals(bytes.copyOfRange(0, 2), first)
        source.close()
    }
}
