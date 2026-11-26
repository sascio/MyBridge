package com.nuvio.app.features.media.service

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class MediaServiceHttpTest {
    private val connection = NavidromeConnection("https://example.org/music", "listener", "a".repeat(32), "salt")
    @Test fun serverRejectsCleartextEmbeddedAuthAndQueryCredentials() {
        for (url in listOf("http://example.org", "https://u:p@example.org", "https://example.org/?token=secret", "https://example.org/#secret", "https://example.org/%2e%2e/")) {
            assertFails { validateMediaServer(url) }
        }
    }
    @Test fun navidromeAuthUsesPostBodyNeverQuery() = runTest {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/music/rest/getAlbumList2.view", request.url.encodedPath)
            assertTrue(request.url.parameters.isEmpty())
            val fields = parseQueryString((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
            assertEquals("listener", fields["u"]); assertEquals("a".repeat(32), fields["t"])
            assertEquals("salt", fields["s"]); assertEquals("StreamBridge", fields["c"])
            assertFalse(request.url.toString().contains("salt"))
            respond("""{"subsonic-response":{"status":"ok","albumList2":{"album":[{"id":"1","name":"Album","artist":"Artist"}]}}}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        HttpClient(engine) { followRedirects = false }.use { client ->
            val result = NavidromeService(client, connection).home()
            assertEquals("album:1", result.items.single().key.id); assertNull(result.continuation)
        }
    }
    @Test fun apiErrorIsNotReportedAsEmptySuccess() = runTest {
        HttpClient(MockEngine { respond("""{"subsonic-response":{"status":"failed","error":{"code":40,"message":"secret"}}}""") }).use { client ->
            val error = assertFailsWith<MediaServiceException> { NavidromeService(client, connection).ping() }
            assertEquals(MediaFailure.Authentication, error.reason); assertFalse(error.toString().contains("secret"))
        }
    }
    @Test fun redirectDoesNotForwardCredentials() = runTest {
        var calls = 0
        HttpClient(MockEngine { calls++; respond("", HttpStatusCode.TemporaryRedirect, headersOf(HttpHeaders.Location, "https://other.example/")) }) { followRedirects = false }.use { client ->
            assertFailsWith<MediaServiceException> { NavidromeService(client, connection).ping() }; assertEquals(1, calls)
        }
    }
    @Test fun foreignKeysCannotReachNavidrome() = runTest {
        HttpClient(MockEngine { error("Must not send") }).use { client ->
            assertFailsWith<IllegalArgumentException> { NavidromeService(client, connection).browse(MediaKey(MediaServiceId.YouTube, "album:1")) }
        }
    }
    @Test fun youtubeKeyIsOnlyInHeaderAndPaginationIsPreserved() = runTest {
        HttpClient(MockEngine { request ->
            assertEquals("user-project-key", request.headers["X-Goog-Api-Key"])
            assertFalse(request.url.toString().contains("user-project-key"))
            assertEquals("next", request.url.parameters["pageToken"])
            respond("""{"items":[{"id":{"kind":"youtube#channel","channelId":"channel"},"snippet":{"title":"Channel","channelTitle":"Author"}}],"nextPageToken":"more"}""")
        }).use { client ->
            val result = YouTubeDataService(client, "user-project-key").search("query", "next")
            assertEquals(MediaKind.Channel, result.items.single().kind); assertEquals("more", result.continuation)
        }
    }
    @Test fun youtubeQuotaErrorIsNotSuccessfulFeed() = runTest {
        HttpClient(MockEngine { respond("{}", HttpStatusCode.Forbidden) }).use { client ->
            assertEquals(MediaFailure.Permission, assertFailsWith<MediaServiceException> { YouTubeDataService(client, "key").home() }.reason)
        }
    }
    @Test fun cancellationPropagatesWithoutRetry() = runTest {
        HttpClient(MockEngine { throw CancellationException("cancelled") }).use { client ->
            assertFailsWith<CancellationException> { NavidromeService(client, connection).ping() }
        }
    }
    @Test fun malformedEnvelopeIsNotAnEmptyAlbumList() = runTest {
        HttpClient(MockEngine { respond("""{"subsonic-response":{"status":"ok"}}""") }).use { client ->
            assertEquals(MediaFailure.InvalidResponse, assertFailsWith<MediaServiceException> { NavidromeService(client, connection).home() }.reason)
        }
    }
    @Test fun commentsRequestUsesSelectedOrderAndPlainText() = runTest {
        HttpClient(MockEngine { request ->
            assertEquals("time", request.url.parameters["order"])
            assertEquals("plainText", request.url.parameters["textFormat"])
            respond("""{"items":[{"snippet":{"totalReplyCount":2,"topLevelComment":{"id":"comment","snippet":{"authorDisplayName":"Author","textDisplay":"Text"}}}}]}""")
        }).use { client ->
            val comments = YouTubeDataService(client, "key").comments(MediaKey(MediaServiceId.YouTube, "video:1"), CommentOrder.Newest)
            assertEquals(2, comments.items.single().replyCount)
        }
    }
    @Test fun failedServerMutationIsNeverReportedAsSuccess() = runTest {
        HttpClient(MockEngine { respond("""{"subsonic-response":{"status":"failed","error":{"code":50}}}""") }).use { client ->
            val error = assertFailsWith<MediaServiceException> { NavidromeService(client, connection).setLiked(MediaKey(MediaServiceId.Navidrome, "song:1"), true) }
            assertEquals(MediaFailure.Permission, error.reason)
        }
    }

}
