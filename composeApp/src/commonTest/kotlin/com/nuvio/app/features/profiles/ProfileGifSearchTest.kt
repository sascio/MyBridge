package com.nuvio.app.features.profiles

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
        assertEquals("https://gifs.example.test/gifs/search?q=cat+%26+dog&limit=24", url)
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
    fun missingProxyConfigurationIsReportedNotHidden() {
        runBlocking {
            val result = ProfileGifSearchService.search(query = "cat", proxyBaseUrl = "")
            assertIs<ProfileGifSearchNotConfiguredException>(result.exceptionOrNull())
        }
    }
}
