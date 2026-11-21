package com.nuvio.app.features.profiles

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileGifSearchTest {

    @Test
    fun searchUrlTargetsProxyAndEncodesQuery() {
        val url = buildProfileGifSearchUrl("https://gifs.example.test/", "  cat & dog  ", limit = 24)
        assertEquals("https://gifs.example.test/gifs/search?q=cat%20%26%20dog&limit=24", url)
    }

    @Test
    fun searchUrlClampsLimitAndRejectsUnusableInput() {
        assertTrue(buildProfileGifSearchUrl("https://gifs.example.test", "cat", limit = 999)!!.endsWith("limit=50"))
        assertTrue(buildProfileGifSearchUrl("https://gifs.example.test", "cat", limit = 0)!!.endsWith("limit=1"))
        assertNull(buildProfileGifSearchUrl("https://gifs.example.test", "   "))
        assertNull(buildProfileGifSearchUrl("", "cat"))
        assertNull(buildProfileGifSearchUrl("ftp://gifs.example.test", "cat"))
        assertNull(buildProfileGifSearchUrl("https://", "cat"))
    }

    @Test
    fun parsesValidResultsAndKeepsOnlyAbsoluteHttpUrls() {
        val body = """
            {"results":[
              {"id":"a1","title":"Happy cat","preview_url":"https://cdn.example.test/a1-p.gif","gif_url":"https://cdn.example.test/a1.gif"},
              {"id":"bad","title":"Relative","preview_url":"/p.gif","gif_url":"https://cdn.example.test/b.gif"},
              {"id":"js","title":"Script","preview_url":"javascript:alert(1)","gif_url":"javascript:alert(1)"},
              {"id":"","title":"","preview_url":"https://cdn.example.test/c-p.gif","gif_url":"https://cdn.example.test/c.gif"}
            ]}
        """.trimIndent()

        val items = parseProfileGifSearchResponse(body)

        assertEquals(2, items.size)
        assertEquals("a1", items[0].id)
        assertEquals("Happy cat", items[0].title)
        assertEquals("https://cdn.example.test/a1.gif", items[0].gifUrl)
        // Blank id falls back to the GIF URL, blank title falls back to "GIF".
        assertEquals("https://cdn.example.test/c.gif", items[1].id)
        assertEquals("GIF", items[1].title)
    }

    @Test
    fun emptyResultsAreAnEmptyListNotAnError() {
        assertEquals(emptyList(), parseProfileGifSearchResponse("""{"results":[]}"""))
        assertEquals(emptyList(), parseProfileGifSearchResponse("{}"))
    }

    @Test
    fun malformedResponseFailsInsteadOfLookingEmpty() {
        assertFailsWith<ProfileGifSearchResponseException> {
            parseProfileGifSearchResponse("<html>502 Bad Gateway</html>")
        }
    }

    @Test
    fun selectedGifUrlSurvivesAvatarPersistenceNormalization() {
        // The editor saves normalizedAvatarUrl(avatarUrl); a selected GIF must not be dropped by it.
        val gif = "https://cdn.example.test/cat.gif"
        assertEquals(gif, normalizedAvatarUrl(gif))
        assertNull(normalizedAvatarUrl("javascript:alert(1)"))
    }

    @Test
    fun blankQueryReturnsEmptyWithoutNetwork() {
        runBlocking {
            val result = ProfileGifSearchService.search(query = "   ", proxyBaseUrl = "https://gifs.example.test")
            assertEquals(emptyList(), result.getOrThrow())
        }
    }

    @Test
    fun unconfiguredProxyUsesEnhancedDuckDuckGoSearch(): Unit = runBlocking {
        val requests = mutableListOf<Pair<String, Map<String, String>>>()
        val result = ProfileGifSearchService.search(query = "cat & dog", proxyBaseUrl = "", limit = 2) { url, headers ->
            requests += url to headers
            if (requests.size == 1) "<script>vqd='4-123456'</script>" else """
                {"results":[
                  {"image":"https://img.example/cat.gif?size=1","thumbnail":"https://img.example/p.png","title":"Cat","image_token":"x"},
                  {"image":"https://img.example/cat.gif?size=1"},
                  {"image":"javascript:alert(1).gif"},
                  {"image":"https://u:p@img.example/secret.gif"},
                  {"image":"http://127.0.0.1/private.gif"},
                  {"image":"https://img.example/dog.gif","thumbnail":"/bad"},
                  {"image":"https://img.example/third.gif"}
                ]}
            """.trimIndent()
        }.getOrThrow()
        assertEquals(2, requests.size)
        assertTrue(requests[0].first.startsWith("https://duckduckgo.com/?q=cat%20%26%20dog%20gif"))
        assertTrue(requests[1].first.contains("vqd=4-123456"))
        assertEquals(requests[0].first, requests[1].second["Referer"])
        assertEquals(listOf("https://img.example/cat.gif?size=1", "https://img.example/dog.gif"), result.map { it.gifUrl })
        assertEquals("https://img.example/dog.gif", result[1].previewUrl)
    }

    @Test
    fun configuredProxyDoesNotCallDuckDuckGo(): Unit = runBlocking {
        val calls = mutableListOf<String>()
        val result = ProfileGifSearchService.search("cat", "https://gifs.example.test", fetch = { url, _ ->
            calls += url
            """{"results":[]}"""
        }).getOrThrow()
        assertEquals(emptyList(), result)
        assertEquals(listOf("https://gifs.example.test/gifs/search?q=cat&limit=24"), calls)
    }

    @Test
    fun invalidSearchTokenOrResponseIsAnError(): Unit = runBlocking {
        assertNull(extractGifSearchToken("vqd='bad&token'"))
        assertEquals("4-123", extractGifSearchToken("vqd='4-123'"))
        assertFailsWith<ProfileGifSearchResponseException> { parseDuckDuckGoGifResponse("{}", 10) }
        val result = ProfileGifSearchService.search("cat", "", fetch = { _, _ -> "no token" })
        assertIs<ProfileGifSearchResponseException>(result.exceptionOrNull())
    }

    @Test
    fun searchCancellationPropagates(): Unit = runBlocking {
        assertFailsWith<CancellationException> {
            ProfileGifSearchService.search("cat", "", fetch = { _, _ -> throw CancellationException("cancel") })
        }
    }

}
