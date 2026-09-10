package com.streambridge.app.player

import android.content.Context
import android.util.Log
import android.view.Surface
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVNode
import `is`.xyz.mpv.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Translation of the active source's legitimate request context into
 * libmpv options — the exact pattern the reference app uses:
 *  - User-Agent → the dedicated `user-agent` option
 *  - every other header → `http-header-fields` (mpv list syntax:
 *    "Name: value" items, commas inside values escaped as `\,`)
 *
 * Pure and unit-testable; never logs header values anywhere.
 */
object MpvRequestOptions {

    /** The User-Agent header, if the source supplied one. */
    fun userAgentFrom(headers: Map<String, String>): String? =
        headers.entries
            .firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
            ?.value?.takeIf { it.isNotBlank() }

    /**
     * All headers except User-Agent, serialized for mpv's
     * `http-header-fields` list option. Commas are escaped because mpv
     * separates list items with commas (cookies contain commas).
     */
    fun headerFieldsFrom(headers: Map<String, String>): String =
        headers.entries
            .filter { !it.key.equals("User-Agent", ignoreCase = true) }
            .joinToString(",") { (name, value) -> "$name: ${value.replace(",", "\\,")}" }

    /**
     * mpv per-file `start` option for position-preserving fallback:
     * "start=745.300" (seconds), or null when starting from the
     * beginning. Locale-ROOT so locales with comma decimals never
     * corrupt the number.
     */
    fun startPositionOption(startPositionMs: Long): String? =
        if (startPositionMs > 0L) {
            "start=" + String.format(Locale.ROOT, "%.3f", startPositionMs / 1000.0)
        } else {
            null
        }
}

/**
 * Deterministic engine-escalation ladder (pure decision, no provider
 * or URL inputs — the same failure category always produces the same
 * decision):
 *
 *  Media3 (hardware + its own recovery)
 *    ↓ on failure with no in-place recovery left
 *  libmpv (software decoding tier) — at most ONE attempt per stream
 *    ↓ if libmpv also fails
 *  source failure → the bounded alternate-source fallback
 *
 * Audio failures keep their in-place recovery (compatible audio track
 * switch inside Media3) BEFORE this ladder is consulted; muting is the
 * final fallback only when no audio path exists anywhere.
 */
object EngineEscalationPolicy {

    enum class Decision {
        /** Switch the active engine to libmpv, preserving position. */
        ESCALATE_TO_LIBMPV,

        /** No usable audio path exists and no engine can provide one. */
        MUTE_AUDIO,

        /** Give up on this source (bounded alternate-source follows). */
        FAIL_SOURCE
    }

    data class State(
        val mpvAvailable: Boolean,
        val mpvAttempted: Boolean,
        val mpvFailed: Boolean
    )

    fun decide(state: State, audioNoPath: Boolean): Decision = when {
        // Case E: libmpv already tried and failed on this source.
        state.mpvFailed -> Decision.FAIL_SOURCE

        // No libmpv in the build: audio-only failures may still mute as
        // the final fallback; everything else fails the source.
        !state.mpvAvailable ->
            if (audioNoPath) Decision.MUTE_AUDIO else Decision.FAIL_SOURCE

        // One engine switch per stream — never repeatedly switch.
        state.mpvAttempted -> Decision.FAIL_SOURCE

        // Cases B/C/D: decoder, container or audio-path failure → let
        // software decoding try before abandoning the source.
        else -> Decision.ESCALATE_TO_LIBMPV
    }
}

/** Media facts libmpv discovered about the loaded file (diagnostics). */
data class LibMpvMediaInfo(
    val containerFormat: String? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val audioTracks: List<String> = emptyList()
)

/**
 * REAL libmpv playback engine (the software-decoding fallback tier).
 *
 * Wraps the bundled MPVLib JNI bindings (mpv + FFmpeg, built by the
 * mpv-android/mpvKt lineage — the same binding family the reference
 * app uses). One engine instance = one libmpv player lifetime; a new
 * instance is created for each escalation and released on teardown, so
 * native resources never leak and no global player state survives
 * between streams.
 *
 * Safety model:
 *  - every mpv call runs on ONE dedicated dispatcher thread in FIFO
 *    order (surface attach, option sets and loadfile can never race);
 *  - event callbacks (native thread) only write volatile state and
 *    publish events — they never call back into mpv;
 *  - every native call is guarded so a dead player degrades to a
 *    clean error event, never a native crash;
 *  - release() is idempotent and joins the dispatcher after destroy.
 */
class LibMpvEngine(
    private val appContext: Context,
    private val onEvent: (PlayerHolder.PlaybackEvent) -> Unit,
    private val onMediaInfo: (LibMpvMediaInfo) -> Unit = {}
) {

    private val mpvDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SBLibmpv").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + mpvDispatcher)
    private val released = AtomicBoolean(false)
    private var created = false
    private var currentUrl: String? = null

    // Observed state (written from the mpv event thread only).
    @Volatile private var paused = true
    @Volatile private var pausedForCache = false
    @Volatile private var seeking = false
    @Volatile private var ended = false
    @Volatile private var coreIdle = true
    @Volatile private var cacheBufferingState: Int? = null
    @Volatile private var positionMs = 0L
    @Volatile private var durationMs = 0L

    private val observer = object : MPVLib.EventObserver {
        override fun eventProperty(property: String) {}
        override fun eventProperty(property: String, value: Long) {
            if (property == "cache-buffering-state") {
                cacheBufferingState = value.toInt()
                publishState()
            }
        }

        override fun eventProperty(property: String, value: Boolean) {
            when (property) {
                "pause" -> paused = value
                "paused-for-cache" -> pausedForCache = value
                "seeking" -> seeking = value
                "eof-reached" -> ended = value
                "core-idle" -> coreIdle = value
                else -> return
            }
            publishState()
        }

        override fun eventProperty(property: String, value: Double) {
            when (property) {
                "time-pos" -> positionMs = value.toMillis()
                "duration" -> durationMs = value.toMillis()
            }
        }

        override fun eventProperty(property: String, value: String) {}

        override fun eventProperty(property: String, value: MPVNode) {}

        override fun event(eventId: Int, data: MPVNode) {
            when (eventId) {
                MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> {
                    coreIdle = false
                    readMediaInfo()
                    publishState()
                }
                MPVLib.MpvEvent.MPV_EVENT_END_FILE -> handleEndFile(data)
            }
        }
    }

    /** Whether the libmpv native library is present in the build. */
    fun isAvailable(): Boolean = runCatching {
        Class.forName("is.xyz.mpv.MPVLib", false, LibMpvEngine::class.java.classLoader)
    }.isSuccess

    /**
     * Starts (or restarts) playback of [url] with the source's own
     * headers, optionally resuming at [startPositionMs]. Asynchronous:
     * failures surface as Error events through [onEvent].
     */
    fun start(url: String, headers: Map<String, String>, startPositionMs: Long) {
        currentUrl = url
        positionMs = startPositionMs.coerceAtLeast(0L)
        durationMs = 0L
        ended = false
        paused = false
        coreIdle = true
        onDispatcher {
            if (!ensureCreated()) return@onDispatcher
            applyRequestHeaders(headers)
            MPVLib.setPropertyString("aid", "auto")
            val start = MpvRequestOptions.startPositionOption(startPositionMs)
            if (start != null) {
                MPVLib.command("loadfile", url, "replace", start)
            } else {
                MPVLib.command("loadfile", url, "replace")
            }
            publishState()
        }
    }

    /** Replays the current file from the live position. */
    fun reload() {
        val url = currentUrl ?: return
        val resumeMs = positionMs
        onDispatcher {
            if (released.get() || !created) return@onDispatcher
            val start = MpvRequestOptions.startPositionOption(resumeMs)
            if (start != null) {
                MPVLib.command("loadfile", url, "replace", start)
            } else {
                MPVLib.command("loadfile", url, "replace")
            }
        }
    }

    fun play() {
        onDispatcher { MPVLib.setPropertyBoolean("pause", false) }
    }

    fun pause() {
        onDispatcher { MPVLib.setPropertyBoolean("pause", true) }
    }

    fun togglePlayPause() {
        onDispatcher { MPVLib.setPropertyBoolean("pause", !paused) }
    }

    fun seekTo(positionMs: Long) {
        onDispatcher {
            MPVLib.command("seek", (positionMs.coerceAtLeast(0L) / 1000.0).toString(), "absolute")
        }
    }

    fun setSpeed(speed: Float) {
        onDispatcher {
            MPVLib.setPropertyDouble("speed", speed.coerceIn(0.25f, 4f).toDouble())
        }
    }

    /** Current position/duration — served from observed state, no native call. */
    fun snapshotPosition(): Pair<Long, Long> = positionMs.coerceAtLeast(0L) to durationMs

    fun isPlaying(): Boolean = !paused && !isBuffering() && !coreIdle && !ended

    fun isBuffering(): Boolean = pausedForCache ||
        (!paused && !ended &&
            (seeking || cacheBufferingState?.let { it in 0 until 100 } == true))

    // -------------------------------------------------------------
    // Surface binding (called from the UI layer)
    // -------------------------------------------------------------

    fun attachSurface(surface: Surface) {
        onDispatcher {
            if (!created || released.get()) return@onDispatcher
            MPVLib.attachSurface(surface)
            MPVLib.setOptionString("force-window", "yes")
            MPVLib.setPropertyString("vo", "gpu")
        }
    }

    fun detachSurface() {
        onDispatcher {
            if (!created || released.get()) return@onDispatcher
            runCatching {
                // Order matters: disable VO, then hand the surface back.
                MPVLib.setPropertyString("vo", "null")
                MPVLib.setPropertyString("force-window", "no")
                MPVLib.detachSurface()
            }
        }
    }

    fun updateSurfaceSize(width: Int, height: Int) {
        onDispatcher {
            if (!created || released.get()) return@onDispatcher
            MPVLib.setPropertyString("android-surface-size", "${width}x$height")
        }
    }

    /**
     * Releases the native player. Idempotent; safe to call from any
     * thread, also when the engine was never started.
     */
    fun release() {
        if (!released.compareAndSet(false, true)) return
        scope.launch {
            runCatching {
                if (created) MPVLib.removeObserver(observer)
            }
            runCatching { if (created) MPVLib.destroy() }
            mpvDispatcher.close()
        }
    }

    // -------------------------------------------------------------
    // Internals (mpv dispatcher thread)
    // -------------------------------------------------------------

    private fun onDispatcher(block: () -> Unit) {
        if (released.get()) return
        scope.launch {
            if (released.get()) return@launch
            runCatching(block).onFailure { error ->
                Log.w(TAG, "libmpv operation failed", error)
            }
        }
    }

    private fun ensureCreated(): Boolean {
        if (created) return true
        if (!isAvailable()) {
            publishError("The libmpv fallback engine is not available in this build.")
            return false
        }
        // TLS root store + subtitle font ship inside the library AAR.
        runCatching { Utils.copyAssets(appContext) }
            .onFailure { Log.w(TAG, "libmpv asset copy failed", it) }
        MPVLib.create(appContext)
        // Reference configuration (Nuvio's production option set).
        MPVLib.setOptionString("vo", "gpu")
        MPVLib.setOptionString("hwdec", "auto")
        MPVLib.setOptionString("msg-level", "all=warn")
        MPVLib.setOptionString("tls-verify", "yes")
        MPVLib.setOptionString("tls-ca-file", "${appContext.filesDir.path}/cacert.pem")
        MPVLib.setOptionString("demuxer-max-bytes", DEMUXER_CACHE_BYTES.toString())
        MPVLib.setOptionString("demuxer-max-back-bytes", DEMUXER_CACHE_BYTES.toString())
        MPVLib.setOptionString("keep-open", "yes")
        MPVLib.setOptionString("audio-fallback-to-null", "yes")
        MPVLib.init()
        // Hardcoded post-init options (BaseMPVView reference behavior):
        // never create a window on our own; stay idle until loadfile.
        MPVLib.setOptionString("force-window", "no")
        MPVLib.setOptionString("idle", "once")
        MPVLib.addObserver(observer)
        OBSERVED_PROPERTIES.forEach { (name, format) ->
            MPVLib.observeProperty(name, format)
        }
        created = true
        return true
    }

    private fun applyRequestHeaders(headers: Map<String, String>) {
        MpvRequestOptions.userAgentFrom(headers)?.let { userAgent ->
            MPVLib.setPropertyString("user-agent", userAgent)
        }
        val fields = MpvRequestOptions.headerFieldsFrom(headers)
        if (fields.isNotBlank()) {
            MPVLib.setPropertyString("http-header-fields", fields)
        }
    }

    private fun readMediaInfo() {
        val container = MPVLib.getPropertyString("file-format")
        val video = MPVLib.getPropertyString("video-format")
        val audio = MPVLib.getPropertyString("audio-codec-name")
        val tracks = MPVLib.getPropertyNode("track-list")?.asArray()
            ?.mapNotNull { node ->
                val map = node.asMap() ?: return@mapNotNull null
                if (map["type"]?.asString() != "audio") return@mapNotNull null
                val codec = map["codec"]?.asString()
                val lang = map["lang"]?.asString()
                val selected = map["selected"]?.asBoolean() == true
                listOfNotNull(codec, lang?.uppercase()).joinToString(" ") +
                    if (selected) " (selected)" else ""
            }
            ?.filter { it.isNotBlank() }
            ?.ifEmpty { null }
            ?: emptyList()
        onMediaInfo(
            LibMpvMediaInfo(
                containerFormat = container,
                videoCodec = video,
                audioCodec = audio,
                audioTracks = tracks
            )
        )
    }

    private fun handleEndFile(data: MPVNode) {
        val map = runCatching { data.asMap() }.getOrNull()
        val reason = map?.get("reason")?.asInt()
        if (reason == MPV_END_FILE_REASON_ERROR) {
            val rawError = map["error"]?.asString()
            // Never let an error string carry a URL into diagnostics.
            val safeError = rawError
                ?.take(120)
                ?.substringBefore("://")
                ?.takeIf { it.isNotBlank() }
                ?: "libmpv end-file error"
            publishError("The fallback engine could not play this source ($safeError).")
        }
    }

    private fun publishState() {
        onEvent(
            PlayerHolder.PlaybackEvent.StateChanged(
                isPlaying = isPlaying(),
                buffering = isBuffering(),
                ended = ended,
                positionMs = positionMs.coerceAtLeast(0L),
                durationMs = durationMs
            )
        )
    }

    private fun publishError(message: String) {
        onEvent(
            PlayerHolder.PlaybackEvent.Error(
                message = message,
                category = PlaybackFailureCategory.UNKNOWN_PLAYBACK_ERROR,
                diagnostics = PlaybackDiagnostics(
                    backendId = BACKEND_ID,
                    errorCause = "libmpv"
                )
            )
        )
    }

    private fun Double?.toMillis(): Long =
        this?.takeIf { it.isFinite() && it > 0.0 }?.let { (it * 1000.0).toLong() } ?: 0L

    private companion object {
        const val TAG = "SBLibmpv"
        const val BACKEND_ID = "libmpv"

        /** mpv end-file "error" reason code. */
        const val MPV_END_FILE_REASON_ERROR = 4L

        const val DEMUXER_CACHE_BYTES = 64 * 1024 * 1024

        val OBSERVED_PROPERTIES = mapOf(
            "pause" to MPVLib.MpvFormat.MPV_FORMAT_FLAG,
            "paused-for-cache" to MPVLib.MpvFormat.MPV_FORMAT_FLAG,
            "core-idle" to MPVLib.MpvFormat.MPV_FORMAT_FLAG,
            "eof-reached" to MPVLib.MpvFormat.MPV_FORMAT_FLAG,
            "seeking" to MPVLib.MpvFormat.MPV_FORMAT_FLAG,
            "cache-buffering-state" to MPVLib.MpvFormat.MPV_FORMAT_INT64,
            "duration" to MPVLib.MpvFormat.MPV_FORMAT_DOUBLE,
            "time-pos" to MPVLib.MpvFormat.MPV_FORMAT_DOUBLE,
            "demuxer-cache-time" to MPVLib.MpvFormat.MPV_FORMAT_DOUBLE
        )
    }
}
