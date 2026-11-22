package com.nuvio.app.features.mdblist

import com.nuvio.app.features.tracking.TrackingExternalIds
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingRatingException
import com.nuvio.app.features.tracking.TrackingRatingProvider
import com.nuvio.app.features.tracking.TrackingRatingRecord
import com.nuvio.app.features.tracking.TrackingRatingScope
import com.nuvio.app.features.tracking.TrackingRatingTarget
import com.nuvio.app.features.tracking.arrayOrEmpty
import com.nuvio.app.features.tracking.countersTotal
import com.nuvio.app.features.tracking.notFoundTotal
import com.nuvio.app.features.tracking.objectOrNull
import com.nuvio.app.features.tracking.toTrackingExternalIds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToInt
import kotlin.time.Clock

private const val MDBLIST_RATINGS_PAGE_LIMIT = 5000
private const val MDBLIST_RATINGS_MAX_PAGES = 20
private const val MDBLIST_RATINGS_REUSE_MS = 15_000L

/**
 * MDBList personal ratings via its Trakt-compatible sync API: `GET /sync/ratings` (movies,
 * shows, seasons and episodes in one paged response), `POST /sync/ratings` and
 * `POST /sync/ratings/remove`, all on a 1–10 scale.
 */
internal class MdbListRatingsProvider(
    private val api: MdbListApiClient,
    private val store: MdbListAuthStore,
) : TrackingRatingProvider {
    override val providerId = TrackingProviderId.MDBLIST
    override val supportedScopes = TrackingRatingScope.entries.toSet()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val fetchMutex = Mutex()
    private var lastFetch: Triple<MdbListAuthScope, Long, List<TrackingRatingRecord>>? = null

    override fun canRate(target: TrackingRatingTarget): Boolean = target.ids.toMdbListIds() != null

    override suspend fun fetchRatings(profileId: Int, scope: TrackingRatingScope): List<TrackingRatingRecord> =
        allRatings(scope(profileId)).filter { it.scope == scope }

    /** One paged read serves every scope; reuse it briefly so four scope lookups cost one fetch. */
    private suspend fun allRatings(authScope: MdbListAuthScope): List<TrackingRatingRecord> = fetchMutex.withLock {
        val now = Clock.System.now().toEpochMilliseconds()
        lastFetch?.let { (cachedScope, fetchedAt, records) ->
            if (cachedScope == authScope && now - fetchedAt <= MDBLIST_RATINGS_REUSE_MS) return@withLock records
        }
        val records = fetchMdbListRatingPages { query ->
            val response = api.get("/sync/ratings", query = query, scope = authScope)
            store.checkScope(authScope)
            json.parseToJsonElement(response.body.ifBlank { "{}" }).objectOrNull()
                ?: throw TrackingRatingException("Invalid MDBList ratings response")
        }
        lastFetch = Triple(authScope, now, records)
        records
    }

    override suspend fun setRating(profileId: Int, target: TrackingRatingTarget, rating: Int) {
        val authScope = scope(profileId)
        val response = api.post("/sync/ratings", buildBody(target, rating).toString(), authScope)
        val payload = json.parseToJsonElement(response.body.ifBlank { "{}" }).objectOrNull()
        val updated = payload?.get("updated").countersTotal() + payload?.get("added").countersTotal()
        if (payload?.get("not_found").notFoundTotal() > 0 || payload?.get("errors").arrayOrEmpty().isNotEmpty() ||
            (payload != null && (payload.containsKey("updated") || payload.containsKey("added")) && updated <= 0)
        ) {
            throw TrackingRatingException("MDBList did not find this item")
        }
        invalidate()
    }

    override suspend fun removeRating(profileId: Int, target: TrackingRatingTarget) {
        val authScope = scope(profileId)
        val response = api.post("/sync/ratings/remove", buildBody(target, null).toString(), authScope)
        val payload = json.parseToJsonElement(response.body.ifBlank { "{}" }).objectOrNull()
        if (payload?.get("not_found").notFoundTotal() > 0 || payload?.get("errors").arrayOrEmpty().isNotEmpty()) {
            throw TrackingRatingException("MDBList did not remove this rating")
        }
        invalidate()
    }

    private suspend fun invalidate() = fetchMutex.withLock { lastFetch = null }

    private fun buildBody(target: TrackingRatingTarget, rating: Int?): JsonObject {
        val ids = target.ids.toMdbListIds()?.requestIds() ?: throw TrackingRatingException("Missing MDBList ids")
        val item = buildJsonObject {
            put("ids", ids)
            when (target.scope) {
                TrackingRatingScope.MOVIE, TrackingRatingScope.SHOW -> rating?.let { put("rating", it) }
                TrackingRatingScope.SEASON -> put("seasons", JsonArray(listOf(buildJsonObject {
                    put("number", requireNotNull(target.season))
                    rating?.let { put("rating", it) }
                })))
                TrackingRatingScope.EPISODE -> put("seasons", JsonArray(listOf(buildJsonObject {
                    put("number", requireNotNull(target.season))
                    put("episodes", JsonArray(listOf(buildJsonObject {
                        put("number", requireNotNull(target.episode))
                        rating?.let { put("rating", it) }
                    })))
                })))
            }
        }
        val group = if (target.scope == TrackingRatingScope.MOVIE) "movies" else "shows"
        return buildJsonObject { put(group, JsonArray(listOf(item))) }
    }

    private fun scope(profileId: Int): MdbListAuthScope = store.scope().also {
        if (it.profileId != profileId) throw CancellationException("MDBList profile changed")
    }
}

// Supports both cursor and offset pages, without caching partial responses.
internal suspend fun fetchMdbListRatingPages(fetch: suspend (Map<String, String>) -> JsonObject): List<TrackingRatingRecord> {
    val records = mutableListOf<TrackingRatingRecord>()
    var query = mapOf("offset" to "0", "limit" to MDBLIST_RATINGS_PAGE_LIMIT.toString())
    val visited = mutableSetOf<Map<String, String>>()
    repeat(MDBLIST_RATINGS_MAX_PAGES) {
        if (!visited.add(query)) throw TrackingRatingException("MDBList ratings pagination repeated")
        val payload = fetch(query)
        if (listOf("movies", "shows", "seasons", "episodes").none(payload::containsKey)) {
            throw TrackingRatingException("Invalid MDBList ratings response")
        }
        val items = parseMdbListRatingPage(payload)
        val pagination = payload["pagination"].objectOrNull() ?: payload
        // Older responses only provided has_more; retain the prior offset-based
        // behavior when the request itself supplies an unambiguous offset.
        val legacyMore = (pagination["has_more"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() == true
        val legacyOffset = query["offset"]?.toIntOrNull()
        val page = if (legacyMore && pagination.text("next_cursor") == null &&
            pagination["offset"] == null && pagination["limit"] == null && legacyOffset != null
        ) {
            MdbListPage(items, nextOffset = legacyOffset + MDBLIST_RATINGS_PAGE_LIMIT)
        } else {
            mdbListPage(payload, items)
        }
        records += page.items
        query = when {
            page.nextCursor != null -> mapOf("cursor" to page.nextCursor, "limit" to MDBLIST_RATINGS_PAGE_LIMIT.toString())
            page.nextOffset != null -> mapOf("offset" to page.nextOffset.toString(), "limit" to MDBLIST_RATINGS_PAGE_LIMIT.toString())
            else -> return records
        }
    }
    throw TrackingRatingException("MDBList ratings exceeded the page limit")
}

internal fun parseMdbListRatingPage(payload: JsonObject): List<TrackingRatingRecord> = buildList {
    for ((name, scope) in listOf(
        "movie" to TrackingRatingScope.MOVIE,
        "show" to TrackingRatingScope.SHOW,
        "season" to TrackingRatingScope.SEASON,
        "episode" to TrackingRatingScope.EPISODE,
    )) {
        payload["${name}s"].arrayOrEmpty().forEach { element ->
            val row = element.objectOrNull() ?: return@forEach
            val target = row[name].objectOrNull() ?: row
            val parent = when (scope) {
                TrackingRatingScope.MOVIE, TrackingRatingScope.SHOW -> target
                else -> row["show"].objectOrNull() ?: target["show"].objectOrNull() ?: return@forEach
            }
            // The fork's rating UI is integer-only; round a valid precise value
            // to the nearest supported star rather than dropping the record.
            val rating = sequenceOf("rating_precise", "rating")
                .mapNotNull { row.text(it)?.toDoubleOrNull()?.takeIf { value -> value.isFinite() && value > 0.0 && value <= 10.0 } }
                .firstOrNull()?.roundToInt()?.coerceIn(1, 10) ?: return@forEach
            val season = when (scope) {
                TrackingRatingScope.SEASON -> target.integer("number", "season")?.takeIf { it >= 0 } ?: return@forEach
                TrackingRatingScope.EPISODE -> target.integer("season")?.takeIf { it >= 0 } ?: return@forEach
                else -> null
            }
            val episode = if (scope == TrackingRatingScope.EPISODE) {
                target.integer("number", "episode")?.takeIf { it > 0 } ?: return@forEach
            } else null
            val directIds = (parent["ids"] ?: parent).toTrackingExternalIds()
            val aliases = runCatching { decodeMdbListIds(parent) }.getOrNull()
            val ids = if (aliases == null) directIds else directIds.mergeMissing(TrackingExternalIds(
                imdb = aliases.imdb, tmdb = aliases.tmdb, tvdb = aliases.tvdb?.toString(),
                trakt = aliases.trakt, mdblist = aliases.mdblist,
            ))
            if (ids.hasAny) add(TrackingRatingRecord(scope, ids, rating, season, episode))
        }
    }
}
