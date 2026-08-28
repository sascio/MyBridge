package com.mybridge.phase1

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.concurrent.thread

data class ServerState(
    val running: Boolean,
    val ip: String?,
    val port: Int,
    val requestCount: Long
)

class BridgeServer(
    private val port: Int = 8080,
    private val onState: (ServerState) -> Unit
) {
    @Volatile
    private var running = false

    @Volatile
    private var requestCount = 0L

    private var serverSocket: ServerSocket? = null

    private val executor = Executors.newCachedThreadPool()

    fun start() {
        if (running) return

        thread(name = "mybridge-server") {
            try {
                val socket = ServerSocket(port)
                serverSocket = socket
                running = true
                publish()

                while (running) {
                    try {
                        val client = socket.accept()
                        executor.execute {
                            handle(client)
                        }
                    } catch (_: Exception) {
                        if (running) {
                            publish()
                        }
                    }
                }

            } catch (_: Exception) {
                running = false
                publish()
            }
        }
    }

    fun stop() {
        running = false

        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }

        serverSocket = null
        publish()
    }

    fun state(): ServerState {
        return ServerState(
            running = running,
            ip = detectLanIp(),
            port = port,
            requestCount = requestCount
        )
    }

    private fun publish() {
        onState(state())
    }

    private fun handle(socket: Socket) {
        socket.use { client ->

            requestCount++

            val reader = BufferedReader(
                InputStreamReader(
                    client.getInputStream(),
                    StandardCharsets.UTF_8
                )
            )

            val requestLine = reader.readLine() ?: return

            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }

            val path = requestLine
                .split(" ")
                .getOrNull(1)
                ?.substringBefore("?")
                ?: "/"

            val body = when {
                path == "/" -> homeJson()

                path == "/health" ->
                    """{"status":"ok","service":"MyBridge","version":"0.1.0"}"""

                path == "/manifest.json" ->
                    manifestJson()

                path.startsWith("/catalog/") ->
                    emptyCatalogResponse()

                path.startsWith("/meta/") ->
                    emptyMetaResponse()

                path.startsWith("/stream/") ->
                    emptyStreamResponse()

                else ->
                    """{"error":"not_found","path":"${escape(path)}"}"""
            }

            val status = when {
                path == "/" ||
                path == "/health" ||
                path == "/manifest.json" ||
                path.startsWith("/catalog/") ||
                path.startsWith("/meta/") ||
                path.startsWith("/stream/") -> "200 OK"

                else -> "404 Not Found"
            }

            val bytes = body.toByteArray(StandardCharsets.UTF_8)

            val headers = buildString {
                append("HTTP/1.1 $status\r\n")
                append("Content-Type: application/json; charset=utf-8\r\n")
                append("Access-Control-Allow-Origin: *\r\n")
                append("Access-Control-Allow-Methods: GET, OPTIONS\r\n")
                append("Access-Control-Allow-Headers: *\r\n")
                append("Cache-Control: no-store\r\n")
                append("Content-Length: ${bytes.size}\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }

            client.getOutputStream().use { output ->
                output.write(
                    headers.toByteArray(StandardCharsets.UTF_8)
                )

                output.write(bytes)
                output.flush()
            }

            publish()
        }
    }

    private fun homeJson(): String {
        return """
            {
              "service":"MyBridge",
              "status":"${if (running) "running" else "stopped"}",
              "phase":"1A",
              "endpoints":[
                "/health",
                "/manifest.json",
                "/catalog/...",
                "/meta/...",
                "/stream/..."
              ]
            }
        """.trimIndent()
    }

    private fun manifestJson(): String {
        return """
            {
              "id":"com.mybridge.addon",
              "version":"0.1.0",
              "name":"MyBridge",
              "description":"A local Stremio bridge. No extensions are bundled by default.",
              "resources":[
                "catalog",
                "meta",
                "stream"
              ],
              "types":[
                "movie",
                "series"
              ],
              "catalogs":[],
              "idPrefixes":[
                "mybridge:"
              ]
            }
        """.trimIndent()
    }

    private fun emptyCatalogResponse(): String {
        return """{"metas":[]}"""
    }

    private fun emptyMetaResponse(): String {
        return """{"meta":null}"""
    }

    private fun emptyStreamResponse(): String {
        return """{"streams":[]}"""
    }

    private fun escape(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
    }

    companion object {

        fun detectLanIp(): String? {
            return try {
                val interfaces =
                    Collections.list(
                        NetworkInterface.getNetworkInterfaces()
                    )

                interfaces
                    .asSequence()
                    .filter {
                        it.isUp &&
                        !it.isLoopback &&
                        !it.isVirtual
                    }
                    .flatMap {
                        Collections.list(
                            it.inetAddresses
                        ).asSequence()
                    }
                    .filterIsInstance<Inet4Address>()
                    .map {
                        it.hostAddress
                    }
                    .firstOrNull {
                        it != "127.0.0.1"
                    }

            } catch (_: Exception) {
                null
            }
        }
    }
}
