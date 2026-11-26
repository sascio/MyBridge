package com.nuvio.app.features.media.service

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class WebDavServiceTest {
    private val connection = WebDavConnection("https://example.org/music", "user", "private-password")
    @Test fun listingUsesDepthOneAndHeaderCredentials() = runTest {
        HttpClient(MockEngine { request ->
            assertEquals("PROPFIND", request.method.value); assertEquals("1", request.headers["Depth"])
            assertTrue(request.headers[HttpHeaders.Authorization]?.startsWith("Basic ") == true)
            assertFalse(request.url.toString().contains("private-password"))
            respond("""<multistatus><response><href>/music/song.mp3</href><propstat><prop><displayname>Song</displayname></prop><status>HTTP/1.1 200 OK</status></propstat></response></multistatus>""", HttpStatusCode.MultiStatus)
        }) { followRedirects = false }.use { client ->
            val item = WebDavService(client, connection).home().items.single()
            assertEquals("Song", item.title); assertEquals(MediaServiceId.WebDav, item.key.service)
        }
    }
    @Test fun resolverCannotLeakHeaderToAnotherOriginOrEscapeCollection() = runTest {
        HttpClient(MockEngine { error("Must not send") }).use { client ->
            val service = WebDavService(client, connection)
            for (url in listOf("https://evil.example/music/song.mp3", "https://example.org/other/song.mp3", "https://example.org/music/%2e%2e/secret.mp3")) {
                assertFailsWith<MediaServiceException> { service.resolve(MediaKey(MediaServiceId.WebDav, url)) }
            }
            val media = service.resolve(MediaKey(MediaServiceId.WebDav, "https://example.org/music/song.mp3"))
            assertTrue(media.headers.containsKey(HttpHeaders.Authorization)); assertFalse(media.url.contains("private-password"))
        }
    }
    @Test fun redirectIsNotSuccessfulLibraryConnection() = runTest {
        var calls = 0
        HttpClient(MockEngine { calls++; respond("", HttpStatusCode.TemporaryRedirect, headersOf(HttpHeaders.Location, "https://evil.example")) }) { followRedirects = false }.use { client ->
            assertFailsWith<MediaServiceException> { WebDavService(client, connection).home() }
            assertEquals(1, calls)
        }
    }
}
