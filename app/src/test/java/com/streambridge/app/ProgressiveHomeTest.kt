package com.streambridge.app

import com.streambridge.app.addon.AddonApi
import com.streambridge.app.addon.ExtensionManager
import com.streambridge.app.addon.model.AddonManifest
import com.streambridge.app.addon.model.AddonMeta
import com.streambridge.app.addon.model.AddonMetaPreview
import com.streambridge.app.addon.model.CatalogResponse
import com.streambridge.app.addon.model.HomeSection
import com.streambridge.app.addon.model.StreamResponse
import com.streambridge.app.addon.model.SubtitleResponse
import com.streambridge.app.data.db.ExtensionDao
import com.streambridge.app.data.db.ExtensionEntity
import com.streambridge.app.data.discovery.DiscoveryRepository
import com.streambridge.app.data.integrations.MdbListClient
import com.streambridge.app.data.integrations.TmdbClient
import com.streambridge.app.data.settings.SettingsState
import com.streambridge.app.addon.SbHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Home must render progressively: one slow addon can never hold the
 * whole screen hostage behind placeholders, and repeat loads reuse the
 * cache instead of re-downloading everything.
 */
class ProgressiveHomeTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /** AddonApi fake with per-base-URL latency and call counting. */
    private class FakeApi : AddonApi {
        val catalogCalls = mutableMapOf<String, Int>()
        var latencyMs: (String) -> Long = { 0 }

        override suspend fun fetchManifest(baseUrl: String): AddonManifest {
            throw java.io.IOException("not used")
        }

        override suspend fun fetchCatalog(
            baseUrl: String,
            type: String,
            catalogId: String,
            search: String?,
            genre: String?,
            skip: Int?
        ): CatalogResponse {
            catalogCalls[baseUrl] = (catalogCalls[baseUrl] ?: 0) + 1
            delay(latencyMs(baseUrl))
            val prefix = baseUrl.substringAfter("//").substringBefore('.')
            return CatalogResponse(
                listOf(
                    AddonMetaPreview(id = "$prefix-1", name = "$prefix One", type = type),
                    AddonMetaPreview(id = "$prefix-2", name = "$prefix Two", type = type)
                )
            )
        }

        override suspend fun fetchMeta(baseUrl: String, type: String, id: String): AddonMeta? = null

        override suspend fun fetchStreams(baseUrl: String, type: String, id: String): StreamResponse =
            StreamResponse()

        override suspend fun fetchSubtitles(
            baseUrl: String,
            type: String,
            id: String
        ): List<com.streambridge.app.addon.model.AddonSubtitle> = emptyList()

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

    private fun entity(addonId: String, name: String): ExtensionEntity {
        val manifest = AddonManifest(
            id = addonId,
            version = "1.0.0",
            name = name,
            types = listOf("movie"),
            resources = listOf("catalog"),
            catalogs = listOf(
                com.streambridge.app.addon.model.AddonCatalog(type = "movie", id = "top", name = "$name catalog")
            )
        )
        return ExtensionEntity(
            addonId = addonId,
            name = name,
            version = "1.0.0",
            baseUrl = "https://$addonId.example.com",
            manifestJson = json.encodeToString(AddonManifest.serializer(), manifest),
            enabled = true,
            installedAt = 0L,
            updatedAt = 0L
        )
    }

    private fun repository(
        api: FakeApi,
        dao: FakeDao,
        dedupeScope: CoroutineScope
    ): DiscoveryRepository {
        val manager = ExtensionManager(dao, api, json, dedupeScope)
        // Let the manager's init flow populate its state synchronously.
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeout(1000) {
                manager.extensions.first { it.isNotEmpty() }
            }
        }
        return DiscoveryRepository(
            api = api,
            extensionManager = manager,
            tmdb = TmdbClient(SbHttpClient(), json),
            mdblist = MdbListClient(SbHttpClient(), json),
            dedupeScope = dedupeScope
        )
    }

    @Test
    fun `a slow addon does not block the first home emission`() = runTest {
        val api = FakeApi()
        // "fast" answers at 1s (virtual), "slow" at 8s (virtual).
        api.latencyMs = { base -> if (base.contains("fast")) 1_000L else 8_000L }
        val dao = FakeDao(listOf(entity("fast", "Fast"), entity("slow", "Slow")))
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val repo = repository(api, dao, scope)

        val emissions = repo.loadHomeProgressive(
            SettingsState(), watchedKeys = emptySet()
        ).toList()

        // Progressive: at least the fast-only partial state plus the final state.
        assertTrue("expected progressive emissions, got ${emissions.size}", emissions.size >= 2)

        val first = emissions.first()
        val firstTitles = first.sections.filterIsInstance<HomeSection.Rail>().map { it.title }
        assertTrue("first emission must include the fast catalog", firstTitles.any { it.contains("Fast") })
        assertTrue(
            "first emission must NOT wait for the slow catalog",
            firstTitles.none { it.contains("Slow") }
        )

        val last = emissions.last()
        val lastTitles = last.sections.filterIsInstance<HomeSection.Rail>().map { it.title }
        assertTrue(lastTitles.any { it.contains("Fast") })
        assertTrue(lastTitles.any { it.contains("Slow") })
    }

    @Test
    fun `failed addons become error chips without blocking others`() = runTest {
        val api = object : FakeApi() {
            override suspend fun fetchCatalog(
                baseUrl: String,
                type: String,
                catalogId: String,
                search: String?,
                genre: String?,
                skip: Int?
            ): CatalogResponse {
                catalogCalls[baseUrl] = (catalogCalls[baseUrl] ?: 0) + 1
                if (baseUrl.contains("broken")) throw java.io.IOException("connection reset")
                delay(500)
                return CatalogResponse(listOf(AddonMetaPreview(id = "ok-1", name = "OK One", type = type)))
            }
        }
        val dao = FakeDao(listOf(entity("ok", "Ok"), entity("broken", "Broken")))
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val repo = repository(api, dao, scope)

        val emissions = repo.loadHomeProgressive(SettingsState(), emptySet()).toList()

        val last = emissions.last()
        assertTrue(last.sections.any { it is HomeSection.Rail })
        assertTrue(
            "broken addon must surface as an error chip",
            last.sourceErrors.any { it.contains("Broken") }
        )
    }

    @Test
    fun `repeat home load within the cache ttl does not refetch`() = runTest {
        val api = FakeApi()
        val dao = FakeDao(listOf(entity("alpha", "Alpha")))
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val repo = repository(api, dao, scope)

        repo.loadHomeProgressive(SettingsState(), emptySet()).toList()
        assertEquals(1, api.catalogCalls["https://alpha.example.com"])

        // Second load, no force: served from cache.
        repo.loadHomeProgressive(SettingsState(), emptySet(), force = false).toList()
        assertEquals(
            "cached load must not hit the network again",
            1,
            api.catalogCalls["https://alpha.example.com"]
        )

        // Forced refresh (pull-to-refresh) bypasses the cache.
        repo.loadHomeProgressive(SettingsState(), emptySet(), force = true).toList()
        assertEquals(2, api.catalogCalls["https://alpha.example.com"])
    }

    @Test
    fun `concurrent identical catalog requests are deduplicated`() = runTest {
        val api = FakeApi()
        api.latencyMs = { 1_000L }
        val dao = FakeDao(listOf(entity("gamma", "Gamma")))
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val repo = repository(api, dao, scope)

        val results = coroutineScope {
            listOf(
                async {
                    repo.fetchCatalogCached("https://gamma.example.com", "movie", "top")
                },
                async {
                    repo.fetchCatalogCached("https://gamma.example.com", "movie", "top")
                }
            ).map { it.await() }
        }
        assertEquals(2, results.size)
        assertEquals(
            "two concurrent identical requests must share one network call",
            1,
            api.catalogCalls["https://gamma.example.com"]
        )
    }
}
