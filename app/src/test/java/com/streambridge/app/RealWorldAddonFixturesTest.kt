package com.streambridge.app

import com.streambridge.app.addon.IdMapping
import com.streambridge.app.addon.ManifestValidator
import com.streambridge.app.addon.model.AddonCatalogResponse
import com.streambridge.app.addon.model.AddonManifest
import com.streambridge.app.addon.model.CatalogResponse
import com.streambridge.app.addon.model.MetaResponse
import com.streambridge.app.addon.model.StreamResponse
import com.streambridge.app.addon.model.toMediaDetails
import com.streambridge.app.addon.model.toMediaItem
import com.streambridge.app.addon.model.toStreamOption
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures captured LIVE from real public Stremio addons on 2026-09-09:
 *
 *  - Cinemeta (v3-cinemeta.strem.io, "com.linvo.cinemeta" v3.0.14), the
 *    OFFICIAL catalog addon: manifest, movie catalog, movie meta, series
 *    meta and its community `addon_catalog` endpoint.
 *  - AfterCredits (aftercredits.almosteffective.com), a stream addon
 *    listed in Cinemeta's community addon catalog.
 *
 * Trimming note: long arrays (links, genres lists, episode lists) were
 * shortened but EVERY retained field keeps the exact real-world shape,
 * including the quirks that broke naive parsers: `imdb_id` snake_case,
 * numeric `moviedb_id`, `released: null`, `imdbRating: ""`, string
 * arrays for cast/director/writer, resource objects with idPrefixes,
 * and the `{"addons":[{transportUrl, manifest}]}` addon_catalog
 * envelope (instead of `{"metas":[...]}`).
 */
class RealWorldAddonFixturesTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    // ------------------------------------------------------------------
    // Cinemeta manifest.json (live capture, trimmed)
    // ------------------------------------------------------------------

    private val cinemetaManifest = """
        {"id":"com.linvo.cinemeta","version":"3.0.14",
         "description":"The official addon for movie and series catalogs",
         "name":"Cinemeta","resources":["catalog","meta","addon_catalog"],
         "types":["movie","series"],"idPrefixes":["tt"],
         "addonCatalogs":[{"type":"all","id":"official","name":"Official"},
                          {"type":"movie","id":"official","name":"Official"},
                          {"type":"all","id":"community","name":"Community"},
                          {"type":"series","id":"community","name":"Community"}],
         "catalogs":[
           {"type":"movie","id":"top",
            "genres":["Action","Adventure","Comedy"],
            "extra":[{"name":"genre","options":["Action","Adventure","Comedy"]},{"name":"search"},{"name":"skip"}],
            "extraSupported":["search","genre","skip"],"name":"Popular"},
           {"type":"series","id":"top",
            "genres":["Action","Comedy","Reality-TV"],
            "extra":[{"name":"genre","options":["Action","Comedy"]},{"name":"search"},{"name":"skip"}],
            "extraSupported":["search","genre","skip"],"name":"Popular"},
           {"type":"movie","id":"year",
            "genres":["2026","2025","2024"],
            "extra":[{"name":"genre","options":["2026","2025","2024"],"isRequired":true},{"name":"skip"}],
            "extraSupported":["genre","skip"],"extraRequired":["genre"],"name":"New"}],
         "behaviorHints":{"newEpisodeNotifications":true}}
    """.trimIndent()

    @Test
    fun `cinemeta manifest parses and validates`() {
        val manifest = json.decodeFromString(AddonManifest.serializer(), cinemetaManifest)
        assertEquals("com.linvo.cinemeta", manifest.id)
        assertEquals("Cinemeta", manifest.name)
        assertEquals("3.0.14", manifest.version)
        assertEquals(listOf("catalog", "meta", "addon_catalog"), manifest.resources)
        assertEquals(listOf("tt"), manifest.idPrefixes)
        assertEquals(4, manifest.addonCatalogs.size)
        // extraSupported can also arrive via the `extra` array.
        val top = manifest.catalogs.first { it.id == "top" && it.type == "movie" }
        assertTrue(top.effectiveExtraSupported.contains("search"))
        // extraRequired can arrive via extra[].isRequired.
        val year = manifest.catalogs.first { it.id == "year" }
        assertEquals(listOf("genre"), year.effectiveExtraRequired)
        // Unknown behaviorHints fields (newEpisodeNotifications) must not
        // break parsing.
        assertTrue(ManifestValidator.validate(manifest) is ManifestValidator.Result.Valid)
    }

    // ------------------------------------------------------------------
    // Cinemeta catalog/movie/top.json (live capture, trimmed to 2 metas)
    // ------------------------------------------------------------------

    private val cinemetaCatalog = """
        {"metas":[
          {"imdb_id":"tt28014327","name":"Mayday",
           "popularities":{"trakt":2311,"moviedb":237.3724,"stremio":0.5118},
           "type":"movie",
           "cast":["Luis Fernando Becerra Sanchez","Ryan Reynolds","Kenneth Branagh"],
           "country":"United States",
           "description":"Lieutenant Troy Brennan's reconnaissance mission over Soviet territory goes awry.",
           "director":["John Francis Daley","Jonathan Goldstein"],
           "genre":["Action","Adventure","Comedy"],
           "imdbRating":"","released":null,
           "slug":"movie/mayday-28014327",
           "writer":["John Francis Daley","Jonathan Goldstein"],
           "year":"2026","moviedb_id":1137844,
           "poster":"https://images.metahub.space/poster/small/tt28014327/img",
           "runtime":"111 min",
           "trailers":[{"source":"om5Un9X720M","type":"Trailer"}],
           "popularity":0.5118,
           "logo":"https://images.metahub.space/logo/medium/tt28014327/img",
           "background":"https://images.metahub.space/background/medium/tt28014327/img",
           "id":"tt28014327","genres":["Action","Adventure","Comedy"],
           "releaseInfo":"2026",
           "trailerStreams":[{"title":"Mayday","ytId":"om5Un9X720M"}],
           "links":[{"name":"Mayday","category":"share","url":"https://www.strem.io/s/movie/mayday-28014327"}],
           "behaviorHints":{"defaultVideoId":"tt28014327","hasScheduledVideos":false}},
          {"imdb_id":"tt30825738","name":"Star Wars: The Mandalorian and Grogu",
           "type":"movie",
           "cast":["Pedro Pascal","Brendan Wayne","Lateef Crowder"],
           "country":"United States",
           "description":"Once a lone bounty hunter, Mandalorian Din Djarin and his apprentice Grogu embark on an exciting new Star Wars adventure.",
           "director":["Jon Favreau"],
           "genre":["Action","Adventure","Family"],
           "imdbRating":"6.8","released":"2026-05-22T00:00:00.000Z",
           "writer":["George Lucas","Jon Favreau","Dave Filoni"],
           "year":"2026","moviedb_id":1228710,
           "poster":"https://images.metahub.space/poster/small/tt30825738/img",
           "trailers":[{"source":"uwild1rw7Aw","type":"Trailer"}],
           "background":"https://images.metahub.space/background/medium/tt30825738/img",
           "logo":"https://images.metahub.space/logo/medium/tt30825738/img",
           "runtime":"132 min","awards":"3 nominations total",
           "popularity":3.01798,
           "id":"tt30825738","genres":["Action","Adventure","Family"],
           "releaseInfo":"2026",
           "trailerStreams":[{"title":"Star Wars: The Mandalorian and Grogu","ytId":"uwild1rw7Aw"}],
           "links":[{"name":"6.8","category":"imdb","url":"https://imdb.com/title/tt30825738"}],
           "behaviorHints":{"defaultVideoId":"tt30825738","hasScheduledVideos":false}}]}
    """.trimIndent()

    @Test
    fun `cinemeta catalog metas parse into the unified model`() {
        val response = json.decodeFromString(CatalogResponse.serializer(), cinemetaCatalog)
        assertEquals(2, response.metas.size)

        val first = response.metas[0]
        // The real-world snake_case field must survive (it used to be
        // silently dropped, breaking IMDb-based dedup and episode ids).
        assertEquals("tt28014327", first.imdbId)
        assertEquals("tt28014327", first.id)

        val item = first.toMediaItem("cinemeta")
        assertEquals("tt28014327", item.imdbId)
        assertEquals("movie", item.type)
        assertEquals("Mayday", item.name)
        assertEquals(listOf("Action", "Adventure", "Comedy"), item.genres)
        // Empty-string ratings degrade to null instead of "N/A"-style junk.
        assertNull(item.rating)
        assertNotNull(item.poster)
        assertNotNull(item.backdrop)

        val second = response.metas[1].toMediaItem("cinemeta")
        assertEquals("6.8", second.rating)
    }

    // ------------------------------------------------------------------
    // Cinemeta meta/movie/tt0111161.json (live capture)
    // ------------------------------------------------------------------

    private val cinemetaMovieMeta = """
        {"meta":{
          "awards":"Nominated for 7 Oscars. 21 wins & 43 nominations total",
          "cast":["Tim Robbins","Morgan Freeman","Bob Gunton"],
          "country":"United States",
          "description":"After a banker is sentenced to life in Shawshank Prison, he forms an unlikely friendship with a seasoned inmate and clings to hope amid cruelty and corruption.",
          "director":["Frank Darabont"],
          "dvdRelease":"2008-08-15T00:00:00.000Z",
          "genre":["Drama"],
          "imdbRating":"9.3","imdb_id":"tt0111161","moviedb_id":278,
          "name":"The Shawshank Redemption",
          "popularity":3.1764,
          "poster":"https://images.metahub.space/poster/small/tt0111161/img",
          "released":"1994-10-14T00:00:00.000Z",
          "runtime":"142 min",
          "trailers":[{"source":"PLl99DlL6b4","type":"Trailer"},{"source":"xyXX8LXiNJ4","type":"Trailer"}],
          "type":"movie",
          "writer":["Stephen King","Frank Darabont"],
          "year":"1994",
          "background":"https://images.metahub.space/background/medium/tt0111161/img",
          "logo":"https://images.metahub.space/logo/medium/tt0111161/img",
          "popularities":{"moviedb":95.294,"stremio":3.1764,"trakt":62},
          "slug":"movie/the-shawshank-redemption-0111161",
          "id":"tt0111161","genres":["Drama"],"releaseInfo":"1994","videos":[],
          "trailerStreams":[{"title":"The Shawshank Redemption","ytId":"PLl99DlL6b4"}],
          "links":[{"name":"9.3","category":"imdb","url":"https://imdb.com/title/tt0111161"}],
          "behaviorHints":{"defaultVideoId":"tt0111161","hasScheduledVideos":false}}}
    """.trimIndent()

    @Test
    fun `cinemeta movie meta fills the full detail model`() {
        val meta = json.decodeFromString(MetaResponse.serializer(), cinemetaMovieMeta).meta
        assertNotNull(meta)
        assertEquals("tt0111161", meta!!.imdbId)

        val details = meta.toMediaDetails("https://v3-cinemeta.strem.io")
        assertEquals("The Shawshank Redemption", details.name)
        assertEquals("9.3", details.rating)
        assertEquals("142 min", details.runtime)
        assertEquals("United States", details.country)
        assertTrue(details.awards!!.contains("7 Oscars"))
        assertEquals(listOf("Tim Robbins", "Morgan Freeman", "Bob Gunton"), details.cast)
        assertEquals(listOf("Frank Darabont"), details.director)
        assertEquals(listOf("Stephen King", "Frank Darabont"), details.writer)
        assertEquals(listOf("Drama"), details.genres)
        assertNotNull(details.logo)
        // Cinemeta sends trailer ids (trailerStreams/trailers), not URLs —
        // the model shapes them into a watchable YouTube link.
        assertEquals("https://www.youtube.com/watch?v=PLl99DlL6b4", details.trailer)
    }

    // ------------------------------------------------------------------
    // Cinemeta meta/series/tt7366338.json — Chernobyl (live, trimmed to
    // one season-0 special + two season-1 episodes)
    // ------------------------------------------------------------------

    private val cinemetaSeriesMeta = """
        {"meta":{
          "imdb_id":"tt7366338","name":"Chernobyl",
          "type":"series",
          "cast":["Jared Harris","Jessie Buckley","Stellan Skarsgård"],
          "country":"United States, United Kingdom",
          "description":"In April 1986, the city of Chernobyl in the Soviet Union suffers one of the worst nuclear disasters in the history of mankind.",
          "director":null,
          "genre":["Drama","History","Thriller"],
          "imdbRating":"9.3",
          "poster":"https://images.metahub.space/poster/small/tt7366338/img",
          "released":"2019-05-06T00:00:00.000Z",
          "runtime":"65 min",
          "slug":"series/chernobyl-7366338",
          "writer":["Craig Mazin"],
          "year":"2019","status":"Ended",
          "tvdb_id":360893,"moviedb_id":87108,
          "trailers":[{"source":"s9APLXM9Ei8","type":"Trailer"}],
          "id":"tt7366338","genres":["Drama","History","Thriller"],
          "releaseInfo":"2019",
          "videos":[
            {"name":"Inside the Episode - 1:23:45","season":0,"number":23,
             "firstAired":"2019-07-16T05:00:00.000Z","tvdb_id":11521949,"rating":"0",
             "overview":"Inside the Episode of 1:23:45",
             "thumbnail":"https://episodes.metahub.space/tt7366338/0/23/w780.jpg",
             "id":"tt7366338:0:23","released":"2019-07-16T05:00:00.000Z","episode":23,
             "description":"Inside the Episode of 1:23:45"},
            {"name":"1:23:45","season":1,"number":1,
             "firstAired":"2019-05-07T05:00:00.000Z","tvdb_id":7082952,"rating":"0",
             "overview":"Plant workers and firefighters put their lives on the line to control a catastrophic 1986 explosion at a Soviet nuclear power plant.",
             "thumbnail":"https://episodes.metahub.space/tt7366338/1/1/w780.jpg",
             "id":"tt7366338:1:1","released":"2019-05-07T05:00:00.000Z","episode":1,
             "description":"Plant workers and firefighters put their lives on the line to control a catastrophic 1986 explosion at a Soviet nuclear power plant."},
            {"name":"Please Remain Calm","season":1,"number":2,
             "firstAired":"2019-05-14T05:00:00.000Z","tvdb_id":7109113,"rating":"0",
             "overview":"With untold millions at risk after the Chernobyl explosion, nuclear physicist Ulana Khomyuk makes a desperate attempt to reach Valery Legasov.",
             "thumbnail":"https://episodes.metahub.space/tt7366338/1/2/w780.jpg",
             "id":"tt7366338:1:2","released":"2019-05-14T05:00:00.000Z","episode":2,
             "description":"With untold millions at risk after the Chernobyl explosion, nuclear physicist Ulana Khomyuk makes a desperate attempt to reach Valery Legasov."}],
          "behaviorHints":{"defaultVideoId":"tt7366338:1:1"}}}
    """.trimIndent()

    @Test
    fun `cinemeta series meta carries real per-episode metadata`() {
        val meta = json.decodeFromString(MetaResponse.serializer(), cinemetaSeriesMeta).meta
        assertNotNull(meta)

        val details = meta!!.toMediaDetails("https://v3-cinemeta.strem.io")
        assertEquals("Chernobyl", details.name)
        assertEquals("tt7366338", details.imdbId)
        // Season 0 specials are kept alongside the real seasons.
        assertEquals(listOf(0, 1), details.seasons)
        assertEquals(3, details.episodes.size)

        // Episode-level metadata is per-episode, never the show synopsis.
        val episode = details.episodes.first { it.id == "tt7366338:1:1" }
        assertEquals("1:23:45", episode.title)
        assertEquals(1, episode.season)
        assertEquals(1, episode.number)
        assertTrue(episode.overview!!.startsWith("Plant workers"))
        assertEquals("https://episodes.metahub.space/tt7366338/1/1/w780.jpg", episode.thumbnail)
        // A `null` director (real Cinemeta shape for series) is safe.
        assertEquals(emptyList<String>(), details.director)

        // The {imdb}:{season}:{episode} id convention resolves to itself
        // via the IMDb fallback (cross-addon resolution path).
        val candidates = IdMapping.episodeVideoIds("tt7366338:1:2", "tt7366338", 1, 2)
        assertTrue(candidates.contains("tt7366338:1:2"))
    }

    // ------------------------------------------------------------------
    // Cinemeta addon_catalog/all/community.json (live, trimmed to 3) —
    // the {"addons":[...]} envelope with embedded manifests
    // ------------------------------------------------------------------

    private val cinemetaAddonCatalog = """
        {"addons":[
          {"transportUrl":"https://anime-kitsu.strem.fun/manifest.json","transportName":"http",
           "manifest":{"id":"community.anime.kitsu","version":"0.0.10","name":"Anime Kitsu",
             "description":"Unofficial Kitsu.io anime catalog addon",
             "logo":"https://i.imgur.com/7N6XGoO.png",
             "resources":["catalog","meta","subtitles"],
             "types":["anime","movie","series"],
             "catalogs":[{"id":"kitsu-anime-trending","name":"Kitsu Trending","type":"anime"},
                         {"id":"kitsu-anime-list","name":"Kitsu","type":"anime",
                          "extra":[{"name":"search","isRequired":true},{"name":"skip"}]}],
             "idPrefixes":["kitsu","mal","anilist","anidb"]}},
          {"transportUrl":"https://aftercredits.almosteffective.com/manifest.json","transportName":"http",
           "manifest":{"id":"com.almosteffective.aftercredits","version":"1.0.0","name":"AfterCredits",
             "description":"Are there mid-credits or after credits scenes?",
             "resources":["stream"],"types":["movie"],"catalogs":[],"idPrefixes":["tt"]}},
          {"transportUrl":"https://stremio-content-deepdive-addon-dc8f7b513289.herokuapp.com/manifest.json","transportName":"http",
           "manifest":{"id":"org.stremio.deepdivecompanion","version":"4.1.1",
             "name":"Content Deep Dive Companion",
             "description":"A comprehensive companion addon.",
             "resources":[{"idPrefixes":["tt"],"name":"meta","types":["movie","series"]},
                          {"idPrefixes":["tt"],"name":"stream","types":["movie","series"]}],
             "types":["movie","series"],"catalogs":[]}}]}
    """.trimIndent()

    @Test
    fun `cinemeta addon catalog uses the addons envelope with embedded manifests`() {
        val response = json.decodeFromString(AddonCatalogResponse.serializer(), cinemetaAddonCatalog)
        assertEquals(3, response.addons.size)

        val kitsu = response.addons[0]
        assertEquals("https://anime-kitsu.strem.fun/manifest.json", kitsu.transportUrl)
        assertNotNull(kitsu.manifest)
        assertEquals("community.anime.kitsu", kitsu.manifest!!.id)
        assertTrue(ManifestValidator.validate(kitsu.manifest!!) is ManifestValidator.Result.Valid)

        // Resource OBJECTS (idPrefixes/name/types) collapse to names.
        val deepDive = response.addons[2].manifest!!
        assertTrue(deepDive.resources.contains("meta"))
        assertTrue(deepDive.resources.contains("stream"))

        // A stream-only addon (no catalogs) is still a valid install.
        val afterCredits = response.addons[1].manifest!!
        assertTrue(ManifestValidator.validate(afterCredits) is ManifestValidator.Result.Valid)
        assertEquals(emptyList<String>(), afterCredits.catalogs.map { it.id })
    }

    // ------------------------------------------------------------------
    // AfterCredits stream/movie/tt0111161.json (live capture) —
    // externalUrl streams
    // ------------------------------------------------------------------

    @Test
    fun `aftercredits stream response maps to an external stream option`() {
        val body = """
            {"streams":[{"name":"After Credits",
                         "title":"Stick around for:\n💚 mid credit scene",
                         "externalUrl":"https://mediastinger.com/the-shawshank-redemption-1994-extras-during-the-credits/"}]}
        """.trimIndent()
        val response = json.decodeFromString(StreamResponse.serializer(), body)
        assertEquals(1, response.streams.size)

        val option = response.streams[0].toStreamOption("AfterCredits")
        assertNotNull(option)
        option!!
        assertTrue(option.isExternal)
        assertEquals(
            com.streambridge.app.addon.model.StreamClassification.EXTERNAL,
            option.classification
        )
        assertEquals(
            "https://mediastinger.com/the-shawshank-redemption-1994-extras-during-the-credits/",
            option.externalUrl
        )
        assertEquals("After Credits", option.addonName)
    }
}
