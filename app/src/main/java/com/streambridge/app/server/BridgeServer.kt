package com.streambridge.app.server

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** Live state of the LAN bridge server. */
data class BridgeServerState(
    val running: Boolean = false,
    val ip: String? = null,
    val port: Int = 0,
    val requestCount: Long = 0,
    val lastError: String? = null
) {
    val baseUrl: String?
        get() = if (running && ip != null && port > 0) "http://$ip:$port" else null
    val addonUrl: String?
        get() = baseUrl?.let { "$it/manifest.json" }
}

/** Raised by the content provider when a resource genuinely does not exist. */
class BridgeNotFoundException(message: String) : Exception(message)

/** Raised when a request is malformed or unsafe. */
class BridgeBadRequestException(message: String) : Exception(message)

/**
 * Serves the bridge content. Implemented by [AggregatingBridgeProvider].
 * All methods are blocking; the server calls them on worker threads.
 */
interface BridgeContentProvider {
    fun manifest(): String
    fun health(): String
    fun landing(baseUrl: String): String

    /** @throws BridgeNotFoundException when the catalog does not exist. */
    fun catalog(type: String, compositeCatalogId: String, extraSegment: String?): String

    /** @return the meta JSON or null when nothing is known about the id. */
    fun meta(type: String, id: String): String?

    /** Merged stream list for the id. */
    fun stream(type: String, id: String): String

    /** Merged subtitle list for the id (may be empty). */
    fun subtitles(type: String, id: String): String
}

/**
 * A small, dependency-free HTTP server that exposes Stream Bridge's
 * aggregated extension content to the local network so other devices
 * (for example Stremio on a TV or desktop) can add it as an addon.
 *
 * - Binds all interfaces (0.0.0.0); never hard-codes an IP.
 * - Picks a free port automatically (or honors a configured one).
 * - GET/HEAD only, CORS enabled, JSON only.
 */
class BridgeServer(
    private val portProvider: () -> Int,
    private val providerFactory: () -> BridgeContentProvider
) {

    private val lock = Any()

    @Volatile
    private var running = false

    @Volatile
    private var serverSocket: ServerSocket? = null

    private var executor: ExecutorService? = null

    private val requestCount = AtomicLong(0)

    private val _state = MutableStateFlow(BridgeServerState())
    val state: StateFlow<BridgeServerState> = _state.asStateFlow()

    fun start() {
        synchronized(lock) {
            if (running) return
            running = true
        }
        thread(name = "streambridge-server") {
            var socket: ServerSocket? = null
            try {
                val desired = portProvider().coerceIn(0, 65535)
                if (desired > 0) {
                    // Honor the configured port, but fall back to the next
                    // few ports if something else already claimed them.
                    for (candidate in desired until desired + PORT_SCAN_RANGE) {
                        try {
                            socket = ServerSocket(candidate)
                            break
                        } catch (_: Exception) {
                        }
                    }
                }
                if (socket == null) {
                    socket = ServerSocket(0) // ephemeral port
                }
                val boundPort = socket.localPort
                serverSocket = socket
                executor = Executors.newFixedThreadPool(WORKER_THREADS)
                updateState { it.copy(running = true, port = boundPort, lastError = null) }
                acceptLoop(socket)
            } catch (e: Exception) {
                running = false
                try {
                    socket?.close()
                } catch (_: Exception) {
                }
                updateState {
                    it.copy(running = false, lastError = e.message ?: "server failed to start")
                }
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            if (!running && serverSocket == null) {
                updateState { it.copy(running = false) }
                return
            }
            running = false
        }
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        executor?.shutdown()
        executor = null
        updateState { it.copy(running = false) }
    }

    /** Re-detects the LAN address (called on network changes). */
    fun refreshAddress() {
        updateState { it.copy(ip = detectLanIp()) }
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val client = try {
                socket.accept()
            } catch (_: SocketException) {
                break
            } catch (_: Exception) {
                if (running) continue else break
            }
            executor?.execute { handleClient(client) }
        }
    }

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = CLIENT_TIMEOUT_MS.toInt()
            client.use { socket ->
                val reader = BufferedReader(
                    InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)
                )
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0].uppercase(java.util.Locale.US)
                val rawTarget = parts[1]

                var contentLength = 0
                var headerCount = 0
                while (headerCount < MAX_HEADERS) {
                    val line = reader.readLine() ?: break
                    headerCount++
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        val name = line.substring(0, colon).trim().lowercase(java.util.Locale.US)
                        if (name == "content-length") {
                            contentLength = line.substring(colon + 1).trim().toIntOrNull() ?: 0
                        }
                    }
                }
                if (contentLength > MAX_BODY_BYTES) return // refuse large bodies

                if (method == "OPTIONS") {
                    respond(socket, 204, "No Content", "", method, json = false)
                    return
                }
                if (method != "GET" && method != "HEAD") {
                    respond(
                        socket, 405, "Method Not Allowed",
                        """{"error":"method_not_allowed"}""", method
                    )
                    return
                }

                requestCount.incrementAndGet()
                val response = route(rawTarget)
                respond(
                    socket, response.first, response.second, response.third, method
                )
                updateState { it.copy(requestCount = requestCount.get()) }
            }
        } catch (_: SocketTimeoutException) {
        } catch (_: Exception) {
        }
    }

    private fun route(rawTarget: String): Triple<Int, String, String> {
        val path = rawTarget.substringBefore("?")
        val segments = path.trim('/')
            .split("/")
            .filter { it.isNotEmpty() }
            .map { decodeSegment(it) }

        val provider = providerFactory()

        return try {
            when {
                segments.isEmpty() || segments.first() == "index.html" -> {
                    val state = _state.value
                    val url = state.baseUrl ?: "http://<this-device>"
                    Triple(200, "OK", provider.landing(url))
                }

                segments.first() == "health" ->
                    Triple(200, "OK", provider.health())

                segments.first() == "manifest.json" ->
                    Triple(200, "OK", provider.manifest())

                segments.size >= 3 && segments.first() == "catalog" -> {
                    val type = segments[1]
                    val idAndExtras = segments.drop(2)
                    val catalogId = idAndExtras.first()
                    val extras = idAndExtras.drop(1).joinToString("/")
                    runServerCall { provider.catalog(type, catalogId, extras.ifBlank { null }) }
                        ?.let { Triple(200, "OK", it) }
                        ?: Triple(404, "Not Found", """{"error":"catalog_not_found"}""")
                }

                segments.size >= 3 && segments.first() == "meta" -> {
                    val type = segments[1]
                    val id = segments.drop(2).joinToString("/")
                    runServerCall { provider.meta(type, id) }
                        ?.let { Triple(200, "OK", it) }
                        ?: Triple(200, "OK", """{"meta":null}""")
                }

                segments.size >= 3 && segments.first() == "stream" -> {
                    val type = segments[1]
                    val id = segments.drop(2).joinToString("/")
                    val body = runServerCall { provider.stream(type, id) }
                    Triple(200, "OK", body ?: """{"streams":[]}""")
                }

                segments.size >= 3 && segments.first() == "subtitles" -> {
                    val type = segments[1]
                    val id = segments.drop(2).joinToString("/")
                    val body = runServerCall { provider.subtitles(type, id) }
                    Triple(200, "OK", body ?: """{"subtitles":[]}""")
                }

                else -> Triple(404, "Not Found", """{"error":"not_found"}""")
            }
        } catch (e: BridgeNotFoundException) {
            Triple(404, "Not Found", """{"error":"not_found","message":"${escape(e.message)}"}""")
        } catch (e: BridgeBadRequestException) {
            Triple(400, "Bad Request", """{"error":"bad_request","message":"${escape(e.message)}"}""")
        } catch (e: Exception) {
            Triple(502, "Bad Gateway", """{"error":"upstream_error","message":"${escape(e.message)}"}""")
        }
    }

    private fun respond(
        socket: Socket,
        status: Int,
        reason: String,
        body: String,
        method: String,
        json: Boolean = true
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            if (json) {
                append("Content-Type: application/json; charset=utf-8\r\n")
            }
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n")
            append("Access-Control-Allow-Headers: *\r\n")
            append("Cache-Control: no-store\r\n")
            append("Content-Length: ${if (method == "HEAD") 0 else bytes.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        val output: OutputStream = socket.getOutputStream()
        output.write(headers.toByteArray(StandardCharsets.UTF_8))
        if (method != "HEAD" && bytes.isNotEmpty()) {
            output.write(bytes)
        }
        output.flush()
    }

    private fun runServerCall(block: () -> String?): String? {
        return runBlocking {
            withTimeoutOrNull(UPSTREAM_TIMEOUT_MS) { block() }
        }
    }

    private fun updateState(transform: (BridgeServerState) -> BridgeServerState) {
        _state.value = transform(_state.value.copy(ip = detectLanIp()))
    }

    private fun decodeSegment(segment: String): String {
        return try {
            URLDecoder.decode(segment, "UTF-8")
        } catch (_: Exception) {
            segment
        }
    }

    private fun escape(value: String?): String {
        return (value ?: "")
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .take(200)
    }

    companion object {
        private const val WORKER_THREADS = 6
        private const val CLIENT_TIMEOUT_MS = 20_000L
        private const val UPSTREAM_TIMEOUT_MS = 25_000L
        private const val MAX_HEADERS = 100
        private const val MAX_BODY_BYTES = 8 * 1024
        private const val PORT_SCAN_RANGE = 20

        /**
         * Detects the LAN IPv4 address by enumerating network interfaces.
         * Prefers site-local wireless addresses; never hard-codes an IP.
         */
        fun detectLanIp(): String? {
            return try {
                Collections.list(NetworkInterface.getNetworkInterfaces())
                    .asSequence()
                    .filter { interface_ ->
                        try {
                            interface_.isUp && !interface_.isLoopback && !interface_.isVirtual
                        } catch (_: Exception) {
                            false
                        }
                    }
                    .flatMap { interface_ -> Collections.list(interface_.inetAddresses).asSequence() }
                    .filterIsInstance<Inet4Address>()
                    .map { it.hostAddress ?: "" }
                    .filter { it.isNotBlank() && it != "127.0.0.1" }
                    .sortedByDescending { it.startsWith("192.168.") || it.startsWith("10.") }
                    .firstOrNull()
            } catch (_: Exception) {
                null
            }
        }
    }
}
