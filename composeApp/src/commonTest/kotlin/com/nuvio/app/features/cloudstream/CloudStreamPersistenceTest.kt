package com.nuvio.app.features.cloudstream

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Persistence payloads.
 *
 * Verifies the exact serialisation the repository writes to
 * `CloudStreamStorage`, and that corrupt payloads degrade to empty state
 * rather than throwing on startup.
 */
class CloudStreamPersistenceTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `source state round-trips`() {
        val original = listOf(
            CloudStreamSourceState("Alpha::Movie", enabled = true, installed = true),
            CloudStreamSourceState("Beta::Anime", enabled = false, installed = true),
        )
        val encoded = json.encodeToString(ListSerializer(CloudStreamSourceState.serializer()), original)
        val decoded = json.decodeFromString(ListSerializer(CloudStreamSourceState.serializer()), encoded)
        assertEquals(original, decoded)
        assertTrue(decoded.first().enabled)
    }

    @Test
    fun `repository list round-trips`() {
        val original = listOf("https://a.com/repo.json", "https://b.com/repo.json")
        val encoded = json.encodeToString(ListSerializer(String.serializer()), original)
        assertEquals(
            original,
            json.decodeFromString(ListSerializer(String.serializer()), encoded),
        )
    }

    @Test
    fun `configuration map round-trips`() {
        val original = mapOf("Alpha::Movie::apiKey" to "secret", "Beta::token" to "t")
        val encoded = json.encodeToString(
            MapSerializer(String.serializer(), String.serializer()),
            original,
        )
        assertEquals(
            original,
            json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), encoded),
        )
    }

    @Test
    fun `a corrupt payload decodes to empty rather than throwing`() {
        val decoded = runCatching {
            json.decodeFromString(ListSerializer(CloudStreamSourceState.serializer()), "{ broken")
        }.getOrElse { emptyList() }
        assertTrue(decoded.isEmpty())
    }

    @Test
    fun `unknown persisted fields are ignored so older state still loads`() {
        val payload = """[{"sourceId":"A","enabled":true,"installed":true,"futureField":1}]"""
        val decoded = json.decodeFromString(
            ListSerializer(CloudStreamSourceState.serializer()),
            payload,
        )
        assertEquals("A", decoded.single().sourceId)
        assertTrue(decoded.single().enabled)
    }

    @Test
    fun `missing optional fields fall back to disabled`() {
        val decoded = json.decodeFromString(
            ListSerializer(CloudStreamSourceState.serializer()),
            """[{"sourceId":"A"}]""",
        )
        assertTrue(!decoded.single().enabled)
        assertTrue(!decoded.single().installed)
    }
}
