package com.streambridge.app

import com.streambridge.app.server.BridgeContentProvider
import com.streambridge.app.server.BridgeNotFoundException
import com.streambridge.app.server.BridgeServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spins up the real LAN bridge server on an ephemeral port and exercises
 * its HTTP behavior end-to-end.
 */
class BridgeServerTest {

    private val client = OkHttpClient()

    private val provider = object : BridgeContentProvider {
        override fun manifest(): String = """{"id":"app.streambridge.bridge","name":"Stream Bridge"}"""

        override fun health(): String = """{"status":"ok"}"""

        override fun landing(baseUrl: String): String = """{"service":"Stream Bridge"}"""

        override fun catalog(type: String, compositeCatalogId: String, extraSegment: String?): String {
            if (compositeCatalogId != "com.good::top") {
                throw BridgeNotFoundException("no such catalog")
            }
            return """{"metas":[{"id":"tt1","name":"A"}]}"""
        }

        override fun meta(type: String, id: String): String? =
            if (id == "tt1") """{"meta":{"id":"tt1"}}""" else null

        override fun stream(type: String, id: String): String = """{"streams":[]}"""

        override fun subtitles(type: String, id: String): String =
            if (id == "tt1") {
                """{"subtitles":[{"url":"https://subs.example.com/tt1.en.vtt","lang":"eng","label":"English"}]}"""
            } else {
                """{"subtitles":[]}"""
            }
    }

    private val server = BridgeServer(
        portProvider = { 0 },
        providerFactory = { provider }
    )

    @After
    fun tearDown() {
        server.stop()
    }

    @Test
    fun `serves manifest with cors headers on ephemeral port`() {
        server.start()
        awaitRunning()

        val response = client.newCall(
            Request.Builder().url("${baseUrl()}/manifest.json").build()
        ).execute()

        response.use {
            assertEquals(200, it.code)
            assertEquals("*", it.header("Access-Control-Allow-Origin"))
            assertTrue(it.body!!.string().contains("Stream Bridge"))
        }
    }

    @Test
    fun `health endpoint answers`() {
        server.start()
        awaitRunning()

        val response = client.newCall(
            Request.Builder().url("${baseUrl()}/health").build()
        ).execute()
        response.use {
            assertEquals(200, it.code)
            assertTrue(it.body!!.string().contains("ok"))
        }
    }

    @Test
    fun `catalog routes to provider with extras`() {
        server.start()
        awaitRunning()

        val response = client.newCall(
            Request.Builder().url("${baseUrl()}/catalog/movie/com.good::top/search=batman.json").build()
        ).execute()
        response.use {
            assertEquals(200, it.code)
            assertTrue(it.body!!.string().contains("tt1"))
        }
    }

    @Test
    fun `unknown catalog returns 404`() {
        server.start()
        awaitRunning()

        val response = client.newCall(
            Request.Builder().url("${baseUrl()}/catalog/movie/com.missing::top.json").build()
        ).execute()
        response.use {
            assertEquals(404, it.code)
        }
    }

    @Test
    fun `meta null returns null meta body`() {
        server.start()
        awaitRunning()

        val response = client.newCall(
            Request.Builder().url("${baseUrl()}/meta/movie/tt999.json").build()
        ).execute()
        response.use {
            assertEquals(200, it.code)
            assertEquals("""{"meta":null}""", it.body!!.string())
        }
    }

    @Test
    fun `subtitles route serves the provider response`() {
        server.start()
        awaitRunning()

        val response = client.newCall(
            Request.Builder().url("${baseUrl()}/subtitles/movie/tt1.json").build()
        ).execute()
        response.use {
            assertEquals(200, it.code)
            assertTrue(it.body!!.string().contains("tt1.en.vtt"))
        }
    }

    @Test
    fun `subtitles without results serve an empty list`() {
        server.start()
        awaitRunning()

        val response = client.newCall(
            Request.Builder().url("${baseUrl()}/subtitles/movie/tt404.json").build()
        ).execute()
        response.use {
            assertEquals(200, it.code)
            assertEquals("""{"subtitles":[]}""", it.body!!.string())
        }
    }

    @Test
    fun `unknown paths return 404`() {
        server.start()
        awaitRunning()

        val response = client.newCall(
            Request.Builder().url("${baseUrl()}/definitely/not/here.json").build()
        ).execute()
        response.use {
            assertEquals(404, it.code)
        }
    }

    @Test
    fun `state exposes the running port and url`() {
        server.start()
        awaitRunning()

        val state = server.state.value
        assertTrue(state.running)
        assertTrue(state.port > 0)
        assertTrue(state.baseUrl!!.startsWith("http://"))
        assertTrue(state.addonUrl!!.endsWith("/manifest.json"))
    }

    private fun awaitRunning() {
        runBlocking {
            var attempts = 0
            while (!server.state.value.running && attempts < 100) {
                Thread.sleep(20)
                attempts++
            }
        }
        check(server.state.value.running) { "server did not start" }
    }

    private fun baseUrl(): String {
        val state = server.state.value
        return "http://127.0.0.1:${state.port}"
    }
}
