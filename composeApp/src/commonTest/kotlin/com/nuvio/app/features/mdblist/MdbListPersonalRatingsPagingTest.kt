package com.nuvio.app.features.mdblist

import com.nuvio.app.features.tracking.TrackingRatingException
import com.nuvio.app.features.tracking.TrackingRatingScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MdbListPersonalRatingsPagingTest {
    private fun json(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject

    @Test
    fun cursorPagesAreReadWithoutLosingTheFirstPage(): Unit = runBlocking {
        val queries = mutableListOf<Map<String, String>>()
        val records = fetchMdbListRatingPages { query ->
            queries += query
            if (queries.size == 1) json("""{"movies":[{"rating":9,"movie":{"ids":{"imdb":"tt1"}}}],"pagination":{"next_cursor":"abc"}}""")
            else json("""{"shows":[{"rating":7,"show":{"ids":{"tmdb":2}}}]}""")
        }
        assertEquals(listOf(TrackingRatingScope.MOVIE, TrackingRatingScope.SHOW), records.map { it.scope })
        assertEquals(mapOf("limit" to "5000", "cursor" to "abc"), queries[1])
    }

    @Test
    fun offsetAndLegacyHasMorePagesContinue(): Unit = runBlocking {
        val queries = mutableListOf<Map<String, String>>()
        fetchMdbListRatingPages { query ->
            queries += query
            when (queries.size) {
                1 -> json("""{"movies":[],"pagination":{"offset":0,"limit":2,"total":3}}""")
                2 -> json("""{"movies":[],"pagination":{"has_more":true,"next_cursor":null}}""")
                else -> json("""{"movies":[]}""")
            }
        }
        assertEquals("2", queries[1]["offset"])
        assertEquals("5002", queries[2]["offset"])
    }

    @Test
    fun repeatedCursorAndMalformedPageFailInsteadOfReturningPartialResults(): Unit = runBlocking {
        assertFailsWith<TrackingRatingException> {
            fetchMdbListRatingPages { json("""{"movies":[],"pagination":{"next_cursor":"same"}}""") }
        }
        assertFailsWith<TrackingRatingException> { fetchMdbListRatingPages { json("{}") } }
    }

    @Test
    fun paginationLimitDoesNotReturnPartialData(): Unit = runBlocking {
        var count = 0
        assertFailsWith<TrackingRatingException> {
            fetchMdbListRatingPages {
                count++
                json("""{"movies":[],"pagination":{"next_cursor":"$count"}}""")
            }
        }
        assertEquals(20, count)
    }
}
