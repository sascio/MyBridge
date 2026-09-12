package com.streambridge.app

import androidx.media3.common.PlaybackException
import com.streambridge.app.player.PlaybackFailureCategory
import com.streambridge.app.player.PlaybackFailureClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Player failures normalize into exactly one clean category with a
 * user-safe message — the EAC-3 and HEVC decoder failures, the
 * extension-less container failure and the 403 all classify
 * distinctly and never expose raw exceptions.
 */
class PlaybackFailureTest {

    private fun classify(
        errorCode: Int,
        httpStatus: Int? = null,
        decoderMimeType: String? = null,
        unrecognizedContainer: Boolean = false
    ) = PlaybackFailureClassifier.classifyFrom(
        errorCode, httpStatus, decoderMimeType, unrecognizedContainer
    )

    // -----------------------------------------------------------------
    // HTTP failures
    // -----------------------------------------------------------------

    @Test
    fun `http 403 classifies as HTTP_403 with a clean message`() {
        val result = classify(
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, httpStatus = 403
        )
        assertEquals(PlaybackFailureCategory.HTTP_403, result.category)
        assertEquals(403, result.httpStatus)
        assertTrue(result.message.contains("403"))
        assertTrue(result.message.contains("another source"))
        // No exception/stack noise in user-facing text.
        assertTrue(!result.message.contains("Exception"))
    }

    @Test
    fun `http 404 and 410 classify as expired sources`() {
        assertEquals(
            PlaybackFailureCategory.SOURCE_EXPIRED,
            classify(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, httpStatus = 404).category
        )
        assertEquals(
            PlaybackFailureCategory.SOURCE_EXPIRED,
            classify(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, httpStatus = 410).category
        )
    }

    @Test
    fun `other http statuses classify as HTTP_OTHER`() {
        val result = classify(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, httpStatus = 500)
        assertEquals(PlaybackFailureCategory.HTTP_OTHER, result.category)
        assertTrue(result.message.contains("500"))
    }

    // -----------------------------------------------------------------
    // Container failures
    // -----------------------------------------------------------------

    @Test
    fun `unrecognized input classifies as unsupported container`() {
        val result = classify(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            unrecognizedContainer = true
        )
        assertEquals(PlaybackFailureCategory.CONTAINER_UNSUPPORTED, result.category)
    }

    @Test
    fun `unsupported container error code also maps to container category`() {
        assertEquals(
            PlaybackFailureCategory.CONTAINER_UNSUPPORTED,
            classify(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED).category
        )
        assertEquals(
            PlaybackFailureCategory.CONTAINER_UNSUPPORTED,
            classify(PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE).category
        )
    }

    @Test
    fun `malformed media classifies separately from unsupported`() {
        assertEquals(
            PlaybackFailureCategory.MALFORMED_MEDIA,
            classify(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED).category
        )
    }

    // -----------------------------------------------------------------
    // Decoder failures (the EAC-3 / HEVC cases)
    // -----------------------------------------------------------------

    @Test
    fun `eac3 audio decoder failure classifies as audio with friendly name`() {
        val result = classify(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            decoderMimeType = "audio/eac3"
        )
        assertEquals(PlaybackFailureCategory.AUDIO_DECODER_UNSUPPORTED, result.category)
        assertTrue(result.message.contains("E-AC-3"))
        assertTrue(result.message.contains("audio"))
        assertNull(result.httpStatus)
    }

    @Test
    fun `hevc video decoder failure classifies as video with friendly name`() {
        val result = classify(
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            decoderMimeType = "video/hevc"
        )
        assertEquals(PlaybackFailureCategory.VIDEO_DECODER_UNSUPPORTED, result.category)
        assertTrue(result.message.contains("HEVC"))
    }

    @Test
    fun `decoder init failure without a mime classifies as codec configuration`() {
        val result = classify(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)
        assertEquals(PlaybackFailureCategory.CODEC_CONFIGURATION_FAILED, result.category)
    }

    @Test
    fun `capability-exceeding video still classifies via mime first`() {
        val result = classify(
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            decoderMimeType = "video/hevc"
        )
        assertEquals(PlaybackFailureCategory.VIDEO_DECODER_UNSUPPORTED, result.category)
    }

    // -----------------------------------------------------------------
    // Network and unknown
    // -----------------------------------------------------------------

    @Test
    fun `network failures classify as network`() {
        assertEquals(
            PlaybackFailureCategory.NETWORK_FAILURE,
            classify(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT).category
        )
        assertEquals(
            PlaybackFailureCategory.NETWORK_FAILURE,
            classify(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED).category
        )
    }

    @Test
    fun `everything else is unknown but still gets a clean message`() {
        val result = classify(PlaybackException.ERROR_CODE_UNSPECIFIED)
        assertEquals(PlaybackFailureCategory.UNKNOWN_PLAYBACK_ERROR, result.category)
        assertNotNull(result.message)
        assertTrue(result.message.isNotBlank())
    }

    @Test
    fun `no user-facing message ever contains stack traces`() {
        val all = PlaybackFailureCategory.entries.map { category ->
            // Exercise every category through its message builder.
            val (code, status, mime) = when (category) {
                PlaybackFailureCategory.HTTP_403 ->
                    Triple(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 403, null)
                PlaybackFailureCategory.HTTP_OTHER ->
                    Triple(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 500, null)
                PlaybackFailureCategory.CONTAINER_UNSUPPORTED ->
                    Triple(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, null, null)
                PlaybackFailureCategory.VIDEO_DECODER_UNSUPPORTED ->
                    Triple(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, null, "video/hevc")
                PlaybackFailureCategory.AUDIO_DECODER_UNSUPPORTED ->
                    Triple(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, null, "audio/eac3")
                PlaybackFailureCategory.CODEC_CONFIGURATION_FAILED ->
                    Triple(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, null, null)
                PlaybackFailureCategory.MALFORMED_MEDIA ->
                    Triple(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, null, null)
                PlaybackFailureCategory.NETWORK_FAILURE ->
                    Triple(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, null, null)
                PlaybackFailureCategory.SOURCE_EXPIRED ->
                    Triple(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, null, null)
                PlaybackFailureCategory.UNKNOWN_PLAYBACK_ERROR ->
                    Triple(PlaybackException.ERROR_CODE_UNSPECIFIED, null, null)
            }
            classify(code, status, mime)
        }
        assertEquals(PlaybackFailureCategory.entries.size, all.map { it.category }.toSet().size)
        all.forEach { result ->
            assertTrue(result.message.length <= 200)
            assertTrue(!result.message.contains("Exception"))
            assertTrue(!result.message.contains("at com."))
            assertTrue(!result.message.contains("\n"))
        }
    }
}
