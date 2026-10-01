package com.nuvio.app.features.player

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class PlayerTemporaryPlaylistStoreTest {
    @Test
    fun `HLS quality playlist is written through a real NSString with UTF8 content`() {
        val text = "#EXTM3U\n# UTF-8 café\n#EXTINF:1,fixture\nhttps://example.invalid/media.ts\n"
        val url = assertNotNull(writeTemporaryHlsPlaylist(text))
        assertTrue(url.startsWith("file://"))
        val path = url.removePrefix("file://")
        val manager = NSFileManager.defaultManager
        try {
            val data = assertNotNull(manager.contentsAtPath(path))
            assertEquals(text.encodeToByteArray().size.toULong(), data.length)
        } finally {
            manager.removeItemAtPath(path, null)
        }
    }
}
