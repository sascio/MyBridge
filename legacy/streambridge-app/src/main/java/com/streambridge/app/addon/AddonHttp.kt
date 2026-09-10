package com.streambridge.app.addon

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Thrown for non-2xx HTTP responses from an addon. */
class AddonHttpException(val statusCode: Int, message: String) : Exception(message)

/**
 * Shared OkHttp stack for all addon traffic. TLS validation is always on;
 * plain http:// is allowed because many community addons (and this app's
 * own LAN bridge) are hosted on local networks.
 */
class SbHttpClient(
    private val baseClient: OkHttpClient = defaultClient()
) {

    /**
     * The shared OkHttp stack (connection pool + dispatcher are shared by
     * every derived client). The player uses it too so video connections
     * reuse the same pool and user-agent.
     */
    val client: OkHttpClient get() = baseClient

    fun clientWithTimeout(timeoutMs: Long): OkHttpClient {
        return baseClient.newBuilder()
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
    }

    /** Performs a GET and returns the body string, throwing on HTTP errors. */
    suspend fun get(url: String, timeoutMs: Long = 15000L): String {
        val client = clientWithTimeout(timeoutMs)
        val request = okhttp3.Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", USER_AGENT)
            .build()
        val response = client.newCall(request).await()
        response.use {
            val body = it.body?.string() ?: ""
            if (!it.isSuccessful) {
                throw AddonHttpException(it.code, "HTTP ${it.code} from $url")
            }
            return body
        }
    }

    companion object {
        const val USER_AGENT = "StreamBridge/1.0 (Android; +https://github.com/sascio/MyBridge)"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(4, 2, TimeUnit.MINUTES))
            .build()
    }
}

/** Awaits an OkHttp call in a cancellable, suspending fashion. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response)
        }
    })
    continuation.invokeOnCancellation {
        try {
            cancel()
        } catch (_: Exception) {
        }
    }
}
