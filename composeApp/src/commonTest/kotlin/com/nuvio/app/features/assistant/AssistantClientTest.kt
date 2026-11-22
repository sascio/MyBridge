package com.nuvio.app.features.assistant

import com.nuvio.app.features.details.MetaDetails
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AssistantClientTest {
    private val meta = MetaDetails(id = "tt1", type = "movie", name = "Test \"Film\"", description = "A synopsis", releaseInfo = "2025")

    @Test
    fun postsToFixedOpenRouterHostWithUserKeyOnlyInHeader() = runTest {
        var called = false
        val http = HttpClient(MockEngine { request ->
            called = true
            assertEquals("openrouter.ai", request.url.host)
            assertEquals("/api/v1/chat/completions", request.url.encodedPath)
            assertEquals("Bearer private-key", request.headers[HttpHeaders.Authorization])
            val text = (request.body as TextContent).text
            assertFalse(text.contains("private-key"))
            val body = Json.parseToJsonElement(text).jsonObject
            assertEquals("provider/model", body.getValue("model").jsonPrimitive.content)
            val messages = body.getValue("messages").jsonArray
            assertTrue(messages.first().jsonObject.getValue("content").jsonPrimitive.content.contains("Test \"Film\""))
            assertEquals("Hello?", messages.last().jsonObject.getValue("content").jsonPrimitive.content)
            respond("""{"choices":[{"message":{"content":"Hi there"}}]}""", HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"))
        }) { followRedirects = false }
        val client = AssistantClient(http)
        try {
            assertEquals("Hi there", client.ask("private-key", "provider/model", meta, listOf(AssistantMessage("user", "Hello?"))))
            assertTrue(called)
        } finally { client.close() }
    }

    @Test
    fun invalidKeysMessagesAndModelsAreRejectedBeforeNetwork() = runTest {
        val http = HttpClient(MockEngine { error("Unexpected network request") }) { followRedirects = false }
        val client = AssistantClient(http)
        try {
            val message = listOf(AssistantMessage("user", "Question"))
            assertFailsWith<IllegalArgumentException> { client.ask("key\nheader", "provider/model", meta, message) }
            assertFailsWith<IllegalArgumentException> { client.ask("key", "model?x=1", meta, message) }
            assertFailsWith<IllegalArgumentException> { client.ask("key", "model", meta, listOf(AssistantMessage("system", "bad"))) }
        } finally { client.close() }
    }

    @Test
    fun serviceErrorsAndMalformedRepliesDoNotExposeResponseBody() = runTest {
        for ((status, body) in listOf(HttpStatusCode.Unauthorized to "sensitive service detail", HttpStatusCode.OK to "{}")) {
            val http = HttpClient(MockEngine { respond(body, status) }) { followRedirects = false }
            val client = AssistantClient(http)
            try {
                val failure = assertFailsWith<AssistantException> {
                    client.ask("key", "model", meta, listOf(AssistantMessage("user", "Question")))
                }
                assertFalse(failure.toString().contains("sensitive service detail"))
            } finally { client.close() }
        }
    }

    @Test
    fun cancellationIsNotSwallowed() = runTest {
        val http = HttpClient(MockEngine { throw CancellationException("cancel") }) { followRedirects = false }
        val client = AssistantClient(http)
        try {
            assertFailsWith<CancellationException> { client.ask("key", "model", meta, listOf(AssistantMessage("user", "Question"))) }
        } finally { client.close() }
    }
}
