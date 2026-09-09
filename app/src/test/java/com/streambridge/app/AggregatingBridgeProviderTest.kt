package com.streambridge.app

import com.streambridge.app.addon.AddonApi
import com.streambridge.app.addon.ExtensionManager
import com.streambridge.app.addon.SbHttpClient
import com.streambridge.app.addon.model.AddonManifest
import com.streambridge.app.addon.model.AddonMeta
import com.streambridge.app.addon.model.AddonMetaPreview
import com.streambridge.app.addon.model.AddonSubtitle
import com.streambridge.app.addon.model.CatalogResponse
import com.streambridge.app.addon.model.StreamResponse
import com.streambridge.app.data.db.ExtensionDao
import com.streambridge.app.data.db.ExtensionEntity
import com.streambridge.app.server.AggregatingBridgeProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The LAN bridge's aggregating provider: the manifest it advertises to
 * external Stremio clients and the merged /subtitles fan-out, exercised
 * against fakes exactly as production wires them.
 */
class AggregatingBridgeProviderTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /** Two subtitle addons; one also serves catalogs/streams/meta. */
    private class FakeApi : AddonApi {
        var failForBase: String? = null

        override suspend fun fetchManifest(baseUrl: String): AddonManifest =
            throw java.io.IOException("not used")

        override suspend fun fetchCatalog(
            baseUrl: String,
            type: String,
            catalogId: String,
            search: String?,
            genre: String?,
            skip: Int?
        ): CatalogResponse = CatalogResponse(listOf(AddonMetaPreview(id = "tt1", name = "One", type = type)))

        override suspend fun fetchMeta(baseUrl: String, type: String, id: String): AddonMeta? = null

        override suspend fun fetchStreams(baseUrl: String, type: String, id: String): StreamResponse =
            StreamResponse()

        override suspend fun fetchSubtitles(baseUrl: String, type: String, id: String): List<AddonSubtitle> {
            if (baseUrl == failForBase) throw java.io.IOException("addon is down")
            return when (baseUrl) {
                "https://alpha.example.com" -> listOf(
                    AddonSubtitle(url = "https://subs.example.com/eng.vtt", lang = "eng", id = "a1"),
                    AddonSubtitle(url = "https://subs.example.com/shared.vtt", lang = "eng", id = "a2")
                )
                "https://beta.example.com" -> listOf(
                    // Same URL as alpha's: must collapse in the merge.
                    AddonSubtitle(url = "https://subs.example.com/shared.vtt", lang = "eng", id = "b1"),
                    AddonSubtitle(url = "https://subs.example.com/deu.vtt", lang = "deu", id = "b2")
                )
                else -> emptyList()
            }
        }

        override suspend fun fetchRaw(baseUrl: String, path: String): String = "{}"
    }

    private class FakeDao(entities: List<ExtensionEntity>) : ExtensionDao {
        private val rows = MutableStateFlow(entities.associateBy { it.addonId })

        override fun observeAll(): Flow<List<ExtensionEntity>> =
            rows.map { it.values.sortedBy { e -> e.sortOrder } }

        override fun observeEnabled(): Flow<List<ExtensionEntity>> =
            rows.map { it.values.filter { it.enabled } }

        override suspend fun maxSortOrder(): Int? = rows.value.values.maxOfOrNull { it.sortOrder }

        override suspend fun setSortOrder(addonId: String, sortOrder: Int) = Unit

        override suspend fun byId(addonId: String): ExtensionEntity? = rows.value[addonId]

        override suspend fun upsert(entity: ExtensionEntity) = Unit

        override suspend fun delete(addonId: String) = Unit
    }

    private fun entity(addonId: String, baseUrl: String, resources: List<String>): ExtensionEntity {
        val manifest = AddonManifest(
            id = addonId,
            version = "1.0.0",
            name = addonId.replaceFirstChar { it.uppercase() },
            types = listOf("movie"),
            resources = resources,
            catalogs = if ("catalog" in resources) {
                listOf(com.streambridge.app.addon.model.AddonCatalog(type = "movie", id = "top", name = "Top"))
            } else {
                emptyList()
            }
        )
        return ExtensionEntity(
            addonId = addonId,
            name = manifest.name,
            version = "1.0.0",
            baseUrl = baseUrl,
            manifestJson = json.encodeToString(AddonManifest.serializer(), manifest),
            enabled = true,
            installedAt = 0L,
            updatedAt = 0L
        )
    }

    private fun manager(vararg entities: ExtensionEntity): ExtensionManager {
        val manager = ExtensionManager(FakeDao(entities.toList()), FakeApi(), json, CoroutineScope(Job()))
        runBlocking {
            kotlinx.coroutines.withTimeout(2000) {
                manager.extensions.first { it.isNotEmpty() }
            }
        }
        return manager
    }

    @Test
    fun `manifest advertises all four resources and composite catalogs`() {
        val manager = manager(
            entity("alpha", "https://alpha.example.com", listOf("catalog", "meta", "subtitles")),
            entity("beta", "https://beta.example.com", listOf("stream", "subtitles"))
        )
        val provider = AggregatingBridgeProvider(manager, FakeApi(), json, appVersion = "1.0.0")

        val manifest = json.parseToJsonElement(provider.manifest()).let { it as kotlinx.serialization.json.JsonObject }
        val resources = (manifest["resources"] as kotlinx.serialization.json.JsonArray)
            .map { (it as kotlinx.serialization.json.JsonPrimitive).content }
        assertEquals(listOf("catalog", "meta", "stream", "subtitles"), resources)

        val catalogs = (manifest["catalogs"] as kotlinx.serialization.json.JsonArray)
            .map { ((it as kotlinx.serialization.json.JsonObject)["id"] as kotlinx.serialization.json.JsonPrimitive).content }
        // Only the addon that actually has catalogs is listed; ids are composite.
        assertEquals(listOf("alpha::top"), catalogs)
    }

    @Test
    fun `subtitles fan out across addons and dedupe by url`() {
        val manager = manager(
            entity("alpha", "https://alpha.example.com", listOf("subtitles")),
            entity("beta", "https://beta.example.com", listOf("subtitles"))
        )
        val api = FakeApi()
        val provider = AggregatingBridgeProvider(manager, api, json, appVersion = "1.0.0")

        val body = provider.subtitles("movie", "tt1")
        val parsed = json.decodeFromString(
            com.streambridge.app.addon.model.SubtitleResponse.serializer(),
            body
        )
        assertEquals(
            listOf(
                "https://subs.example.com/eng.vtt",
                "https://subs.example.com/shared.vtt",
                "https://subs.example.com/deu.vtt"
            ),
            parsed.subtitles.map { it.url }
        )
    }

    @Test
    fun `one failing subtitle addon does not break the bridge response`() {
        val manager = manager(
            entity("alpha", "https://alpha.example.com", listOf("subtitles")),
            entity("beta", "https://beta.example.com", listOf("subtitles"))
        )
        val api = FakeApi().apply { failForBase = "https://beta.example.com" }
        val provider = AggregatingBridgeProvider(manager, api, json, appVersion = "1.0.0")

        val body = provider.subtitles("movie", "tt1")
        val parsed = json.decodeFromString(
            com.streambridge.app.addon.model.SubtitleResponse.serializer(),
            body
        )
        // Alpha's subtitles still arrive; the dead addon contributed nothing.
        assertEquals(
            listOf("https://subs.example.com/eng.vtt", "https://subs.example.com/shared.vtt"),
            parsed.subtitles.map { it.url }
        )
    }

    @Test
    fun `subtitles with no providers serve an empty list, not an error`() {
        val manager = manager(
            entity("gamma", "https://gamma.example.com", listOf("stream"))
        )
        val provider = AggregatingBridgeProvider(manager, FakeApi(), json, appVersion = "1.0.0")

        val body = provider.subtitles("movie", "tt1")
        val parsed = json.decodeFromString(
            com.streambridge.app.addon.model.SubtitleResponse.serializer(),
            body
        )
        assertTrue(parsed.subtitles.isEmpty())
        assertFalse(body.isBlank())
    }
}
