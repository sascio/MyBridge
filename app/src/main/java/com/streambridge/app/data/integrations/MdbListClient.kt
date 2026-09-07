package com.streambridge.app.data.integrations

import com.streambridge.app.addon.SbHttpClient
import com.streambridge.app.addon.model.LenientStringSerializer
import com.streambridge.app.addon.model.MediaItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder

/**
 * Optional MDBList integration.
 *
 * OFF by default. Requires the user to enter their own API key in
 * Settings; no key is bundled with the app. When active, the user's
 * MDBList lists appear as rails on Home.
 */
class MdbListClient(
    private val http: SbHttpClient,
    private val json: Json
) {

    @Serializable
    private data class MdbUser(
        val id: Long = 0,
        @Serializable(with = LenientStringSerializer::class) val name: String = ""
    )

    @Serializable
    private data class MdbListSummary(
        val id: Long = 0,
        @Serializable(with = LenientStringSerializer::class) val name: String = ""
    )

    @Serializable
    private data class MdbListDetail(
        val id: Long = 0,
        @Serializable(with = LenientStringSerializer::class) val name: String = "",
        val movies: List<MdbListItem> = emptyList()
    )

    @Serializable
    private data class MdbListItem(
        @Serializable(with = LenientStringSerializer::class) val title: String = "",
        @Serializable(with = LenientStringSerializer::class) val year: String = "",
        @Serializable(with = LenientStringSerializer::class) val imdb_id: String = "",
        val tmdb_id: Long? = null,
        @Serializable(with = LenientStringSerializer::class) val type: String = ""
    )

    data class MdbList(val id: Long, val name: String)

    /** Returns the signed-in user's lists (name + id). */
    suspend fun userLists(apiKey: String): List<MdbList> {
        val userBody = http.get("$BASE/api/user/me?apikey=${enc(apiKey)}")
        val user = json.decodeFromString(MdbUser.serializer(), userBody)
        if (user.id == 0L) return emptyList()
        val listsBody = http.get("$BASE/api/lists/user/${user.id}?apikey=${enc(apiKey)}")
        return json.decodeFromString(MdbListSummary.serializer(), listsBody)
            .filter { it.id != 0L }
            .map { MdbList(it.id, it.name.ifBlank { "List ${it.id}" }) }
    }

    /** Returns the items of one list, mapped to app media items. */
    suspend fun listItems(apiKey: String, listId: Long): List<MediaItem> {
        val body = http.get("$BASE/api/lists/$listId?apikey=${enc(apiKey)}")
        val detail = json.decodeFromString(MdbListDetail.serializer(), body)
        return detail.movies.mapNotNull { item ->
            val title = item.title.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val type = if (item.type.equals("show", ignoreCase = true) ||
                item.type.equals("series", ignoreCase = true)
            ) "series" else "movie"
            val imdbId = item.imdb_id.takeIf { it.isNotBlank() }
            val id = imdbId ?: item.tmdb_id?.let { "tmdb:$it" } ?: return@mapNotNull null
            MediaItem(
                id = id,
                imdbId = imdbId,
                type = type,
                name = title,
                poster = null,
                backdrop = null,
                releaseInfo = item.year.takeIf { it.isNotBlank() },
                rating = null,
                description = null,
                genres = emptyList(),
                source = SOURCE
            )
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    companion object {
        const val SOURCE = "mdblist"
        private const val BASE = "https://mdblist.com"
    }
}
