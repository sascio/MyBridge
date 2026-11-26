package com.nuvio.app.features.media.service

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ConnectedLibraryTest {
    @Test fun googleAccountRequiresBearerAndMapsRealChannel() = runTest {
        HttpClient(MockEngine { request ->
            assertEquals("Bearer user-access", request.headers[HttpHeaders.Authorization])
            assertEquals("true", request.url.parameters["mine"])
            respond("""{"items":[{"id":"channel-id","snippet":{"title":"Account"}}]}""")
        }).use { client ->
            val account = YouTubeDataService(client, accessToken = { "user-access" }).account()
            assertEquals("channel-id", account.accountId)
        }
    }
    @Test fun privateLibraryCannotBeRequestedWithApiKeyOnly() = runTest {
        HttpClient(MockEngine { error("Must not send") }).use { client ->
            assertFailsWith<IllegalArgumentException> { YouTubeDataService(client, "key").playlists() }
        }
    }
    @Test fun subscriptionsMapChannelNotSubscriptionRecordId() = runTest {
        HttpClient(MockEngine { respond("""{"items":[{"id":"subscription-id","snippet":{"title":"Channel","resourceId":{"channelId":"channel-id"}}}]}""") }).use { client ->
            val entry = YouTubeDataService(client, accessToken = { "access" }).subscriptions().items.single()
            assertEquals("channel:channel-id", entry.key.id)
        }
    }
    @Test fun musicVideoSearchHasIndependentIdentityAndCategory() = runTest {
        HttpClient(MockEngine { request ->
            assertEquals("10", request.url.parameters["videoCategoryId"])
            assertEquals("video", request.url.parameters["type"])
            respond("""{"items":[{"id":{"kind":"youtube#video","videoId":"video"},"snippet":{"title":"Song"}}]}""")
        }).use { client ->
            assertEquals(MediaServiceId.YouTubeMusic, YouTubeDataService(client, "key", musicOnly = true).search("song").items.single().key.service)
        }
    }
    @Test fun deletePlaylistUsesDeleteAndNoBodySuccess() = runTest {
        HttpClient(MockEngine { request ->
            assertEquals(HttpMethod.Delete, request.method); assertEquals("p", request.url.parameters["id"])
            respond("", HttpStatusCode.NoContent)
        }).use { client -> YouTubeDataService(client, accessToken = { "access" }).deletePlaylist(MediaKey(MediaServiceId.YouTube, "playlist:p")) }
    }
    @Test fun repeatedLibraryContinuationFailsRatherThanHanging() = runTest {
        HttpClient(MockEngine { respond("""{"items":[],"nextPageToken":"same"}""") }).use { client ->
            assertEquals(MediaFailure.InvalidResponse, assertFailsWith<MediaServiceException> { YouTubeDataService(client, accessToken = { "access" }).playlists() }.reason)
        }
    }
    @Test fun navidromeReturnsServerTimedLyrics() = runTest {
        HttpClient(MockEngine { respond("""{"subsonic-response":{"status":"ok","lyrics":{"value":"[00:01.20]Line"}}}""") }).use { client ->
            val service = NavidromeService(client, NavidromeConnection("https://example.org", "user", "a".repeat(32), "salt"))
            val lyrics = service.lyrics(MediaEntry(MediaKey(MediaServiceId.Navidrome, "song:1"), MediaKind.Track, "Title", "Artist"))
            assertEquals(1200L, lyrics?.lines?.single()?.atMs); assertEquals(true, lyrics?.synced)
        }
    }
    @Test fun unsupportedRadioDoesNotInventRecommendations() = runTest {
        HttpClient(MockEngine { respond("""{"subsonic-response":{"status":"failed","error":{"code":70}}}""") }).use { client ->
            val service = NavidromeService(client, NavidromeConnection("https://example.org", "user", "a".repeat(32), "salt"))
            assertFailsWith<MediaServiceException> { service.radio(MediaKey(MediaServiceId.Navidrome, "song:1")) }
        }
    }

    @Test fun discoveryCategoriesUseDocumentedCategoryIds() = runTest {
        val expected = mapOf(YouTubeFeed.Music to "10", YouTubeFeed.Gaming to "20", YouTubeFeed.News to "25", YouTubeFeed.Sports to "17")
        for ((feed, category) in expected) HttpClient(MockEngine { request ->
            assertEquals("mostPopular", request.url.parameters["chart"])
            assertEquals(category, request.url.parameters["videoCategoryId"])
            assertEquals("next", request.url.parameters["pageToken"])
            respond("""{"items":[]}""")
        }).use { YouTubeDataService(it, "key").feed(feed, "next") }
    }
    @Test fun liveSearchRequiresVideoTypeAndLiveEvent() = runTest {
        HttpClient(MockEngine { request ->
            assertEquals("live", request.url.parameters["eventType"])
            assertEquals("video", request.url.parameters["type"])
            respond("""{"items":[{"id":{"videoId":"live-id"},"snippet":{"title":"Live","liveBroadcastContent":"live"}}]}""")
        }).use { assertTrue(YouTubeDataService(it, "key").feed(YouTubeFeed.Live).items.single().isLive) }
    }
    @Test fun unsupportedFeedsDoNotPretendToBeHome() = runTest {
        HttpClient(MockEngine { error("Must not send") }).use { client ->
            for (feed in listOf(YouTubeFeed.History, YouTubeFeed.Shorts))
                assertFailsWith<MediaServiceException> { YouTubeDataService(client, "key").feed(feed) }
        }
    }
}
