package com.streambridge.app

import androidx.media3.common.PlaybackException
import com.streambridge.app.player.PlayerErrorMessages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every PlaybackException family must map to a readable, actionable message. */
class PlayerErrorMessagesTest {

    @Test
    fun `bad http status is explained`() {
        val message = PlayerErrorMessages.messageFor(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
        assertTrue(message.contains("HTTP error"))
    }

    @Test
    fun `http 404 maps to stream unavailable`() {
        val message = PlayerErrorMessages.messageFor(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        assertTrue(message.contains("no longer available"))
    }

    @Test
    fun `permission errors are described`() {
        val message = PlayerErrorMessages.messageFor(PlaybackException.ERROR_CODE_IO_NO_PERMISSION)
        assertTrue(message.contains("denied"))
    }

    @Test
    fun `network failures are described`() {
        assertTrue(
            PlayerErrorMessages.messageFor(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
                .contains("connect")
        )
        assertTrue(
            PlayerErrorMessages.messageFor(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
                .contains("too long")
        )
    }

    @Test
    fun `unsupported containers and codecs are explained`() {
        assertTrue(
            PlayerErrorMessages.messageFor(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED)
                .contains("not supported")
        )
        assertTrue(
            PlayerErrorMessages.messageFor(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)
                .contains("cannot decode")
        )
    }

    @Test
    fun `drm errors say drm explicitly`() {
        val message = PlayerErrorMessages.messageFor(PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED)
        assertTrue(message.contains("DRM"))
    }

    @Test
    fun `detail lines are appended`() {
        val message = PlayerErrorMessages.messageFor(2004, detail = "HTTP 403")
        assertTrue(message.contains("HTTP error"))
        assertTrue(message.contains("(HTTP 403)"))
    }

    @Test
    fun `unknown codes fall back to a generic message`() {
        assertEquals(
            "This stream could not be played.",
            PlayerErrorMessages.messageFor(999999)
        )
    }

    @Test
    fun `sync exceptions produce an openable-message`() {
        val message = PlayerErrorMessages.forException(IllegalStateException("no module"))
        assertTrue(message.contains("could not be opened"))
        assertTrue(message.contains("no module"))
    }
}
