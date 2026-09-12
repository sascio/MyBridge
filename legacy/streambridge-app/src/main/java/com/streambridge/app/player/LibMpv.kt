package com.streambridge.app.player

import android.content.Context
import android.util.Log
import android.view.Surface
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVNode
import `is`.xyz.mpv.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
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
     * User-Agent actually sent to libmpv. Media3 always identifies as a
     * browser ([PlaybackUserAgent.DEFAULT]) when the source does not
     * supply one; libmpv must do the same. mpv's built-in UA is
     * typically "libmpv"/"mpv", which CDNs that accept Media3 then 403.
     */
    fun effectiveUserAgent(headers: Map<String, String>): String =
        userAgentFrom(headers) ?: PlaybackUserAgent.DEFAULT

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

    /**
     * argv for `loadfile`. Position 0 uses the 3-arg form. A resume
     * offset is passed as a per-file option (4th argv), matching the
     * existing binding call shape.
     */
    fun loadfileArgs(url: String, startPositionMs: Long): List<String> {
        val start = startPositionOption(startPositionMs)
        return if (start != null) {
            listOf("loadfile", url, "replace", start)
        } else {
            listOf("loadfile", url, "replace")
        }
    }
}

/**
 * Pure gate that decides WHEN libmpv may invoke loadfile.
 *
 * Nuvio / mpv-android [BaseMPVView] contract:
 *   playFile() only stashes the path
 *   surfaceCreated → attachSurface → THEN loadfile
 *
 * StreamBridge 1a2d077 called loadfile from start() as soon as MPV
 * was created, while the Compose SurfaceView only appeared after the
 * engine switch. That is the first proven Media3→libmpv divergence.
 *
 * loadfile is allowed only when the native player exists AND a valid
 * Surface has been attached. Unit-testable without JNI.
 */
internal enum class MpvLoadGateState {
    IDLE,
    WAITING_FOR_ENGINE,
    WAITING_FOR_SURFACE,
    READY_TO_LOAD,
    LOADED
}

internal data class MpvPendingLoad(
    val url: String,
    val headers: Map<String, String>,
    val startPositionMs: Long
)

internal class MpvLoadGate {
    var engineCreated: Boolean = false
        private set
    var surfaceAttached: Boolean = false
        private set
    var pending: MpvPendingLoad? = null
        private set

    fun onEngineCreated() {
        engineCreated = true
    }

    fun onEngineDestroyed() {
        engineCreated = false
        surfaceAttached = false
        pending = null
    }

    fun onSurfaceAttached() {
        surfaceAttached = true
    }

    fun onSurfaceDetached() {
        surfaceAttached = false
    }

    fun setPending(load: MpvPendingLoad) {
        pending = load
    }

    /** Returns the pending load and clears it iff both engine and surface are ready. */
    fun consumeIfReady(): MpvPendingLoad? {
        val load = pending ?: return null
        if (!engineCreated || !surfaceAttached) return null
        pending = null
        return load
    }

    val state: MpvLoadGateState
        get() = when {
            pending != null && !engineCreated -> MpvLoadGateState.WAITING_FOR_ENGINE
            pending != null && engineCreated && !surfaceAttached -> MpvLoadGateState.WAITING_FOR_SURFACE
            pending != null && engineCreated && surfaceAttached -> MpvLoadGateState.READY_TO_LOAD
            pending == null && engineCreated && surfaceAttached -> MpvLoadGateState.LOADED
            else -> MpvLoadGateState.IDLE
        }

    val canLoadfile: Boolean
        get() = engineCreated && surfaceAttached && pending != null
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
 *  - release() is idempotent and joins the dispatcher after destroy;
 *  - loadfile is SURFACE-GATED (see [MpvLoadGate]): never issued
 *    before a valid Android Surface is attached.
 */
class LibMpvEngine(
    private val appContext: Context,
    private val onEvent: (PlayerHolder.PlaybackEvent) -> Unit,
    private val onMediaInfo: (LibMpvMediaInfo) -> Unit = {},
    private val onStage: (String) -> Unit = {}
) {

    private val mpvDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SBLibmpv").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + mpvDispatcher)
    private val released = AtomicBoolean(false)
    private var created = false
    private var currentUrl: String? = null
    private var lastHeaders: Map<String, String> = emptyMap()

    private val gate = MpvLoadGate()
    @Volatile private var pendingSurface: Surface? = null
    private var surfaceWaitJob: Job? = null

    // Observed state (written from the mpv event thread only).
    @Volatile private var paused = true
    @Volatile private var pausedForCache = false
    @Volatile private var seeking = false
    @Volatile private var ended = false
    @Volatile private var coreIdle = true
    @Volatile private var cacheBufferingState: Int? = null
    @Volatile private var positionMs = 0L
    @Volatile private var durationMs = 0L
    @Volatile private var firstFrame = false
    @Volatile private var audioStarted = false
    @Volatile private var stage: String = STAGE_IDLE
    @Volatile private var fileLoaded = false
    @Volatile private var audioTrackCache: List<TrackOption> = emptyList()
    @Volatile private var subtitleTrackCache: List<TrackOption> = emptyList()

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
                "eof-reached" -> {
                    if (value && (firstFrame || fileLoaded)) {
                        ended = true
                    } else if (!value) {
                        ended = false
                    }
                }
                "core-idle" -> coreIdle = value
                "vo-configured" -> {
                    if (value && !firstFrame) {
                        firstFrame = true
                        markStage(STAGE_FIRST_FRAME)
                    }
                }
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

        override fun eventProperty(property: String, value: String) {
            when (property) {
                "audio-codec-name" -> {
                    if (value.isNotBlank() && !audioStarted) {
                        audioStarted = true
                        markStage(STAGE_AUDIO_STARTED)
                        publishState()
                    }
                }
            }
        }

        override fun eventProperty(property: String, value: MPVNode) {
            if (property == "track-list") {
                ingestTrackList(value)
                publishState()
            }
        }

        override fun event(eventId: Int, data: MPVNode) {
            when (eventId) {
                MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> {
                    fileLoaded = true
                    coreIdle = false
                    markStage(STAGE_FILE_LOADED)
                    readMediaInfo()
                    refreshTracksFromMpv()
                    markStage(STAGE_MEDIA_IDENTIFIED)
                    publishState()
                }
                MPVLib.MpvEvent.MPV_EVENT_END_FILE -> handleEndFile(data)
                else -> {
                    // Binding constants (mpv client.h): VIDEO_RECONFIG=17,
                    // AUDIO_RECONFIG=18, PLAYBACK_RESTART=21. Older numeric
                    // ids 13/14 were UNPAUSE/TICK and produced a fake
                    // first-frame on black.
                    if ((eventId == MPVLib.MpvEvent.MPV_EVENT_VIDEO_RECONFIG ||
                            eventId == MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART) &&
                        !firstFrame
                    ) {
                        firstFrame = true
                        markStage(STAGE_FIRST_FRAME)
                        publishState()
                    }
                    if (eventId == MPVLib.MpvEvent.MPV_EVENT_AUDIO_RECONFIG && !audioStarted) {
                        audioStarted = true
                        markStage(STAGE_AUDIO_STARTED)
                        publishState()
                    }
                }
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
     *
     * Does NOT call loadfile until a valid Surface is attached.
     */
    fun start(url: String, headers: Map<String, String>, startPositionMs: Long) {
        currentUrl = url
        lastHeaders = headers
        positionMs = startPositionMs.coerceAtLeast(0L)
        durationMs = 0L
        ended = false
        paused = false
        coreIdle = true
        firstFrame = false
        audioStarted = false
        fileLoaded = false
        audioTrackCache = emptyList()
        subtitleTrackCache = emptyList()
        gate.setPending(MpvPendingLoad(url, headers, startPositionMs))
        markStage(STAGE_LOADING)
        onDispatcher {
            if (!ensureCreated()) return@onDispatcher
            tryLoadIfReady()
            scheduleSurfaceWait()
            publishState()
        }
    }

    /** Replays the current file from the live position. */
    fun reload() {
        val url = currentUrl ?: return
        val resumeMs = positionMs
        firstFrame = false
        audioStarted = false
        fileLoaded = false
        gate.setPending(MpvPendingLoad(url, lastHeaders, resumeMs))
        onDispatcher {
            if (released.get() || !created) return@onDispatcher
            tryLoadIfReady()
            scheduleSurfaceWait()
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

    /**
     * Playing means an actual first video frame (or equivalent VO
     * configure) has been observed — never merely "loadfile invoked"
     * or "file-loaded".
     */
    fun isPlaying(): Boolean = firstFrame && !paused && !isBuffering() && !ended

    fun isBuffering(): Boolean {
        if (ended) return false
        if (!firstFrame) return true
        return pausedForCache ||
            seeking ||
            cacheBufferingState?.let { it in 0 until 100 } == true
    }

    // -------------------------------------------------------------
    // Surface binding (called from the UI layer)
    // -------------------------------------------------------------

    fun attachSurface(surface: Surface) {
        pendingSurface = surface
        onDispatcher {
            if (released.get()) return@onDispatcher
            pendingSurface = surface
            if (!surface.isValid) {
                publishNativeError(
                    classification = "SURFACE_ATTACH_FAILED",
                    detail = "surface invalid"
                )
                return@onDispatcher
            }
            if (!created) {
                // Surface arrived before MPV init — stash; start() attaches.
                markStage(STAGE_SURFACE_CREATED)
                Log.i(TAG, "SURFACE_CREATED (engine not ready; pending)")
                return@onDispatcher
            }
            doAttach(surface)
            tryLoadIfReady()
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
            }.onFailure { error ->
                Log.w(TAG, "SURFACE_DETACH failed: ${error.javaClass.simpleName}")
            }
            gate.onSurfaceDetached()
            pendingSurface = null
            Log.i(TAG, "SURFACE_DESTROYED stage=$stage")
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
        surfaceWaitJob?.cancel()
        scope.launch {
            runCatching {
                if (created) MPVLib.removeObserver(observer)
            }
            runCatching { if (created) MPVLib.destroy() }
            gate.onEngineDestroyed()
            created = false
            pendingSurface = null
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
                publishNativeError(
                    classification = "MPV_OP_FAILED",
                    detail = error.javaClass.simpleName
                )
            }
        }
    }

    private fun ensureCreated(): Boolean {
        if (created) return true
        if (!isAvailable()) {
            publishNativeError(
                classification = "MPV_INIT_FAILED",
                detail = "libmpv not in this build"
            )
            return false
        }
        // TLS root store + subtitle font ship inside the library AAR.
        runCatching { Utils.copyAssets(appContext) }
            .onFailure { Log.w(TAG, "libmpv asset copy failed", it) }
        return try {
            MPVLib.create(appContext)
            applyInitOptions()
            MPVLib.init()
            // Hardcoded post-init options (BaseMPVView reference behavior):
            // never create a window on our own; stay idle until loadfile.
            MPVLib.setOptionString("force-window", "no")
            MPVLib.setOptionString("idle", "yes")
            MPVLib.addObserver(observer)
            OBSERVED_PROPERTIES.forEach { (name, format) ->
                runCatching { MPVLib.observeProperty(name, format) }
            }
            created = true
            gate.onEngineCreated()
            markStage(STAGE_MPV_INIT)
            val version = runCatching { MPVLib.getPropertyString("mpv-version") }.getOrNull()
            Log.i(TAG, "MPV_INIT version=${version ?: "unknown"}")
            pendingSurface?.takeIf { it.isValid }?.let { doAttach(it) }
            true
        } catch (error: Throwable) {
            Log.w(TAG, "MPV_INIT_FAILED", error)
            publishNativeError(
                classification = "MPV_INIT_FAILED",
                detail = error.javaClass.simpleName
            )
            false
        }
    }

    /**
     * Nuvio-equivalent production option set (from NuvioMobile player
     * + mpv-android BaseMPVView). Only options with a documented
     * playback role: gpu VO into the SurfaceView, hwdec auto (hw first,
     * software fallback), TLS with the bundled CA store, demuxer cache,
     * keep-open so a brief stall is not treated as EOF.
     */
    private fun applyInitOptions() {
        // Documented Nuvio / mpv-android production set — required.
        MPVLib.setOptionString("vo", "gpu")
        MPVLib.setOptionString("hwdec", "auto")
        MPVLib.setOptionString("msg-level", "all=warn")
        MPVLib.setOptionString("tls-verify", "yes")
        MPVLib.setOptionString("tls-ca-file", "${appContext.filesDir.path}/cacert.pem")
        MPVLib.setOptionString("demuxer-max-bytes", DEMUXER_CACHE_BYTES.toString())
        MPVLib.setOptionString("demuxer-max-back-bytes", DEMUXER_CACHE_BYTES.toString())
        MPVLib.setOptionString("keep-open", "yes")
        MPVLib.setOptionString("audio-fallback-to-null", "yes")
        runCatching { MPVLib.setOptionString("profile", "fast") }
        // Best-effort Android extras. Unknown options must not fail init.
        runCatching { MPVLib.setOptionString("gpu-context", "android") }
        runCatching { MPVLib.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1") }
        runCatching { MPVLib.setOptionString("ao", "audiotrack") }
        runCatching { MPVLib.setOptionString("cache", "yes") }
        runCatching { MPVLib.setOptionString("ytdl", "no") }
        markStage(STAGE_OPTIONS_APPLIED)
    }

    private fun doAttach(surface: Surface) {
        try {
            MPVLib.attachSurface(surface)
            MPVLib.setOptionString("force-window", "yes")
            MPVLib.setPropertyString("vo", "gpu")
            gate.onSurfaceAttached()
            markStage(STAGE_SURFACE_ATTACHED)
            Log.i(TAG, "SURFACE_ATTACHED thread=${Thread.currentThread().name}")
        } catch (error: Throwable) {
            Log.w(TAG, "SURFACE_ATTACH_FAILED", error)
            publishNativeError(
                classification = "SURFACE_ATTACH_FAILED",
                detail = error.javaClass.simpleName
            )
        }
    }

    private fun tryLoadIfReady() {
        val load = gate.consumeIfReady() ?: return
        surfaceWaitJob?.cancel()
        applyRequestHeaders(load.headers)
        markStage(STAGE_HEADERS_APPLIED)
        runCatching { MPVLib.setPropertyString("aid", "auto") }
        val args = MpvRequestOptions.loadfileArgs(load.url, load.startPositionMs)
        val result = runCatching {
            when (args.size) {
                4 -> MPVLib.command(args[0], args[1], args[2], args[3])
                else -> MPVLib.command(args[0], args[1], args[2])
            }
        }
        if (result.isFailure) {
            val kind = result.exceptionOrNull()?.javaClass?.simpleName ?: "unknown"
            publishNativeError(classification = "LOADFILE_FAILED", detail = kind)
            return
        }
        markStage(STAGE_LOADFILE)
        Log.i(TAG, "LOADFILE issued (surface attached, startMs=${load.startPositionMs})")
        publishState()
    }

    private fun scheduleSurfaceWait() {
        if (gate.state != MpvLoadGateState.WAITING_FOR_SURFACE) return
        markStage(STAGE_WAITING_FOR_SURFACE)
        surfaceWaitJob?.cancel()
        surfaceWaitJob = scope.launch {
            delay(SURFACE_WAIT_TIMEOUT_MS)
            if (released.get()) return@launch
            if (gate.state == MpvLoadGateState.WAITING_FOR_SURFACE) {
                publishNativeError(
                    classification = "SURFACE_WAIT_TIMEOUT",
                    detail = "no Surface arrived"
                )
            }
        }
    }

    private fun applyRequestHeaders(headers: Map<String, String>) {
        val userAgent = MpvRequestOptions.effectiveUserAgent(headers)
        MPVLib.setPropertyString("user-agent", userAgent)
        val fields = MpvRequestOptions.headerFieldsFrom(headers)
        MPVLib.setPropertyString("http-header-fields", fields)
        Log.i(
            TAG,
            "HEADERS_APPLIED uaDefault=${MpvRequestOptions.userAgentFrom(headers) == null} " +
                "headerNames=${headers.keys.sorted().joinToString("/")}"
        )
    }

    private fun readMediaInfo() {
        val container = runCatching { MPVLib.getPropertyString("file-format") }.getOrNull()
        val video = runCatching { MPVLib.getPropertyString("video-format") }.getOrNull()
        val audio = runCatching { MPVLib.getPropertyString("audio-codec-name") }.getOrNull()
        val tracks = runCatching {
            MPVLib.getPropertyNode("track-list")?.asArray()
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
        }.getOrDefault(emptyList())
        if (!audio.isNullOrBlank() && !audioStarted) {
            audioStarted = true
            markStage(STAGE_AUDIO_STARTED)
        }
        Log.i(
            TAG,
            "MEDIA_IDENTIFIED container=${container ?: "-"} video=${video ?: "-"} audio=${audio ?: "-"}"
        )
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
        when (MpvEndFilePolicy.decide(reason, firstFrame = firstFrame, fileLoaded = fileLoaded)) {
            MpvEndFilePolicy.Decision.FAILED -> {
                val rawError = map?.get("error")?.asString()
                val safeError = rawError
                    ?.take(120)
                    ?.substringBefore("://")
                    ?.takeIf { it.isNotBlank() }
                    ?: "end-file-before-playback"
                publishNativeError(classification = "END_FILE_ERROR", detail = safeError)
            }
            MpvEndFilePolicy.Decision.ENDED -> {
                ended = true
                publishState()
            }
            MpvEndFilePolicy.Decision.IGNORE -> Unit
        }
    }

    fun audioTracks(): List<TrackOption> = audioTrackCache
    fun textTracks(): List<TrackOption> = subtitleTrackCache

    fun selectAudioTrack(option: TrackOption) {
        onDispatcher { MPVLib.setPropertyInt("aid", option.trackIndex) }
    }

    fun selectTextTrack(option: TrackOption?) {
        onDispatcher {
            if (option == null) {
                MPVLib.setPropertyString("sid", "no")
            } else {
                MPVLib.setPropertyInt("sid", option.trackIndex)
            }
        }
    }

    private fun ingestTrackList(node: MPVNode) {
        val parsed = parseTrackNodes(node)
        audioTrackCache = MpvTrackInventory.audioOptions(parsed)
        subtitleTrackCache = MpvTrackInventory.subtitleOptions(parsed)
        val audioSummaries = MpvTrackInventory.summaries(parsed, "audio")
        if (audioSummaries.isNotEmpty()) {
            onMediaInfo(
                LibMpvMediaInfo(
                    audioTracks = audioSummaries,
                    audioCodec = parsed.firstOrNull { it.type == "audio" && it.selected }?.codec,
                    videoCodec = parsed.firstOrNull { it.type == "video" && it.selected }?.codec
                )
            )
        }
    }

    private fun refreshTracksFromMpv() {
        val node = runCatching { MPVLib.getPropertyNode("track-list") }.getOrNull() ?: return
        ingestTrackList(node)
    }

    private fun parseTrackNodes(node: MPVNode): List<MpvTrackNode> =
        runCatching {
            node.asArray()?.mapNotNull { child ->
                val map = child.asMap() ?: return@mapNotNull null
                MpvTrackNode(
                    type = map["type"]?.asString(),
                    id = map["id"]?.asInt()?.toInt(),
                    title = map["title"]?.asString(),
                    language = map["lang"]?.asString(),
                    codec = map["codec"]?.asString(),
                    selected = map["selected"]?.asBoolean() == true,
                    forced = map["forced"]?.asBoolean() == true
                )
            } ?: emptyList()
        }.getOrDefault(emptyList())

    private fun publishState() {
        if (firstFrame && !paused && !ended && stage != STAGE_PLAYING) {
            markStage(STAGE_PLAYING)
        }
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

    private fun publishNativeError(classification: String, detail: String) {
        val lastSuccessful = stage
        markStage(STAGE_ERROR)
        val safeDetail = detail.substringBefore("://").take(120)
        onEvent(
            PlayerHolder.PlaybackEvent.Error(
                message = "The fallback engine could not play this source.",
                category = PlaybackFailureCategory.UNKNOWN_PLAYBACK_ERROR,
                diagnostics = PlaybackDiagnostics(
                    backendId = BACKEND_ID,
                    errorCause = "$classification: $safeDetail",
                    mpvStage = classification,
                    notes = listOf("lastSuccessfulStage=$lastSuccessful")
                )
            )
        )
    }

    private fun markStage(next: String) {
        stage = next
        Log.i(TAG, next)
        onStage(next)
    }

    private fun Double?.toMillis(): Long =
        this?.takeIf { it.isFinite() && it > 0.0 }?.let { (it * 1000.0).toLong() } ?: 0L

    private companion object {
        const val TAG = "SBLibmpv"
        const val BACKEND_ID = "libmpv"

        /** mpv end-file reason codes. */
        const val MPV_END_FILE_REASON_EOF = 0L
        const val MPV_END_FILE_REASON_ERROR = 4L

        const val DEMUXER_CACHE_BYTES = 64 * 1024 * 1024
        const val SURFACE_WAIT_TIMEOUT_MS = 8_000L

        const val STAGE_IDLE = "IDLE"
        const val STAGE_MPV_INIT = "MPV_INIT"
        const val STAGE_WAITING_FOR_SURFACE = "WAITING_FOR_SURFACE"
        const val STAGE_SURFACE_CREATED = "SURFACE_CREATED"
        const val STAGE_SURFACE_ATTACHED = "SURFACE_ATTACHED"
        const val STAGE_OPTIONS_APPLIED = "OPTIONS_APPLIED"
        const val STAGE_HEADERS_APPLIED = "HEADERS_APPLIED"
        const val STAGE_LOADING = "LOADING"
        const val STAGE_LOADFILE = "LOADFILE"
        const val STAGE_FILE_LOADED = "FILE_LOADED"
        const val STAGE_MEDIA_IDENTIFIED = "MEDIA_IDENTIFIED"
        const val STAGE_FIRST_FRAME = "FIRST_FRAME"
        const val STAGE_AUDIO_STARTED = "AUDIO_STARTED"
        const val STAGE_PLAYING = "PLAYING"
        const val STAGE_ERROR = "ERROR"

        val OBSERVED_PROPERTIES = mapOf(
            "pause" to MPVLib.MpvFormat.MPV_FORMAT_FLAG,
            "paused-for-cache" to MPVLib.MpvFormat.MPV_FORMAT_FLAG,
            "core-idle" to MPVLib.MpvFormat.MPV_FORMAT_FLAG,
            "eof-reached" to MPVLib.MpvFormat.MPV_FORMAT_FLAG,
            "seeking" to MPVLib.MpvFormat.MPV_FORMAT_FLAG,
            "vo-configured" to MPVLib.MpvFormat.MPV_FORMAT_FLAG,
            "cache-buffering-state" to MPVLib.MpvFormat.MPV_FORMAT_INT64,
            "duration" to MPVLib.MpvFormat.MPV_FORMAT_DOUBLE,
            "time-pos" to MPVLib.MpvFormat.MPV_FORMAT_DOUBLE,
            "demuxer-cache-time" to MPVLib.MpvFormat.MPV_FORMAT_DOUBLE,
            "track-list" to MPVLib.MpvFormat.MPV_FORMAT_NODE
        )
    }
}
