package com.streambridge.app.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import com.streambridge.app.addon.StreamHeaders
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** A selectable subtitle or audio track exposed by the current stream. */
data class TrackOption(
    val groupIndex: Int,
    val trackIndex: Int,
    val label: String,
    val selected: Boolean
)

/**
 * Wrapper around Media3 ExoPlayer.
 *
 * Playback parity with the reference behavior (sources that play in
 * Nuvio must play here whenever the device's decoders allow it):
 *  - REQUEST CONTEXT: the active stream's OWN headers (Referer,
 *    User-Agent, Cookie, Origin — whatever the provider supplied,
 *    sanitized) apply to EVERY request of that playback: the manifest,
 *    every segment, every key request and every redirect. They are
 *    session-scoped: a new stream replaces them, so no context ever
 *    leaks between sources.
 *  - CONTAINER/MANIFEST: the MIME type resolved by [PlaybackPlanning]
 *    (provider hint → URL evidence → server Content-Type) is set on
 *    the MediaItem, so extension-less HLS/DASH/TS links get the right
 *    media source instead of failing container recognition.
 *  - DECODERS: decoder fallback is enabled (a failing primary decoder
 *    no longer ends playback when a working alternative exists), and
 *    TS extraction accepts HDMV/DTS streams with deeper timestamp
 *    search — matching the reference configuration. Software-decoder
 *    extension renderers participate automatically when added to the
 *    build ([SoftwareDecoderExtensions]).
 *  - AUDIO AS A FIRST-CLASS requirement: every audio track the stream
 *    offers is preserved and inspected (codec, language, channels,
 *    bitrate) against the device's real decoder capabilities
 *    ([PlaybackCapabilities]). A KNOWN-undecodable preferred track is
 *    switched before playback; after a decoder failure the next
 *    compatible track is tried (bounded). Audio is only muted as the
 *    FINAL fallback when no decodable track exists — never as a
 *    shortcut, and a supported track is never downgraded.
 *  - BUFFERING: the reference load-control profile (deep buffers for
 *    slow provider CDNs).
 *  - DIAGNOSTICS: every failure is classified AND recorded as
 *    redacted structured [PlaybackDiagnostics] — the real cause
 *    survives, cookies/tokens/signed URLs never do.
 *
 * Playback hardening (unchanged):
 *  - Every URL is validated ([StreamValidator]) before the player sees it;
 *    bad links become error events, never crashes.
 *  - The HTTP stack is the app's shared OkHttpClient (connection reuse,
 *    OkHttp redirect handling incl. cross-protocol).
 *  - setMediaItem()/prepare() build media sources synchronously on the main
 *    thread; Media3 can throw there (e.g. a scheme no module supports).
 *    Those exceptions are converted to error events at this boundary —
 *    the actual cause (missing modules) is fixed in the build, this is the
 *    last line of defense for exotic inputs.
 *  - PlaybackException failures are classified ([PlaybackFailureClassifier])
 *    into clean categories + messages; users never see stack traces.
 */
@OptIn(UnstableApi::class)
class PlayerHolder(
    context: Context,
    okHttpClient: OkHttpClient,
    private val onPlaybackEvent: (PlaybackEvent) -> Unit,
    /** Device decoder capabilities; injectable for tests. */
    private val decoderRegistry: DecoderRegistry = MediaCodecDecoderRegistry()
) {

    sealed interface PlaybackEvent {
        data class StateChanged(
            val isPlaying: Boolean,
            val buffering: Boolean,
            val ended: Boolean,
            val positionMs: Long,
            val durationMs: Long
        ) : PlaybackEvent

        data class Error(
            val message: String,
            val category: PlaybackFailureCategory = PlaybackFailureCategory.UNKNOWN_PLAYBACK_ERROR,
            val httpStatus: Int? = null,
            val decoderMimeType: String? = null,
            /** Redacted structured facts behind this failure. */
            val diagnostics: PlaybackDiagnostics? = null
        ) : PlaybackEvent

        /**
         * Playback CONTINUES but something worth telling the user
         * happened (e.g. "audio switched to AAC — E-AC-3 is not
         * decodable on this device"). Not an error.
         */
        data class Info(
            val text: String,
            val diagnostics: PlaybackDiagnostics? = null
        ) : PlaybackEvent
    }

    /**
     * Per-URL header overrides (e.g. a side-loaded subtitle download
     * that needs its own User-Agent/Authorization). An entry here fully
     * replaces the session headers for that URL — no mixing of one
     * URL's context with another's. Cleared whenever a new stream starts.
     */
    private val headersByUrl = ConcurrentHashMap<String, Map<String, String>>()

    /**
     * The ACTIVE stream's own request context, applied to every HTTP
     * request of this playback: the manifest/initialization URL, every
     * HLS/DASH segment and key, and every redirect in between. This is
     * what keeps token/Referer-protected CDNs serving segments instead
     * of 403s. Replaced wholesale on every new stream — never merged
     * across sources.
     */
    private val sessionHeaders = ConcurrentHashMap<String, String>()

    /** Sanitized headers + MIME of the stream currently loaded (for restarts). */
    private var activeHeaders: Map<String, String> = emptyMap()
    private var activeMimeType: String? = null
    private var activeSourceName: String? = null
    private var activeBackendId: String = Media3PlaybackBackend.ID

    /** Redacted structured facts about the active playback. */
    private var diagnostics: PlaybackDiagnostics = PlaybackDiagnostics(backendId = Media3PlaybackBackend.ID)

    /**
     * Bounded audio recovery state (per stream): each track is tried at
     * most once, each failed codec excludes all its tracks, and the
     * whole chain is capped — no infinite retries, no hammering.
     */
    private val attemptedAudioTrackKeys = mutableSetOf<String>()
    private val failedAudioMimeTypes = mutableSetOf<String>()
    private var audioFallbackAttempts = 0
    private var userSelectedAudio = false
    private var autoAudioOverrideKey: String? = null

    /**
     * Registers headers for an auxiliary URL (e.g. a side-loaded
     * subtitle download that needs its own User-Agent/Authorization).
     * Cleared whenever a new stream starts.
     */
    fun associateHeaders(url: String, headers: Map<String, String>) {
        val safe = StreamHeaders.sanitize(headers)
        if (safe.isNotEmpty()) {
            headersByUrl[url] = safe
        }
    }

    // A stream can legitimately be silent for long stretches (slow CDN,
    // paused buffering of live edges); 30 s without a byte is a generous
    // inactivity ceiling. Shares the app's pool/dispatcher via newBuilder().
    private val streamHttpClient = okHttpClient.newBuilder()
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val player: ExoPlayer = buildPlayer(context.applicationContext)

    private fun buildPlayer(appContext: Context): ExoPlayer {
        // Playback requests identify as a browser by default (UA-checking
        // CDNs otherwise answer 403); a provider-supplied User-Agent for
        // the active source overrides this per request.
        val httpFactory = OkHttpDataSource.Factory(streamHttpClient)
            .setUserAgent(PlaybackUserAgent.DEFAULT)

        // Request-context resolution: every HTTP request of the active
        // playback carries that source's OWN headers — manifest, segments,
        // keys and redirects alike. A per-URL override (subtitles) fully
        // replaces the session context for its URL, so contexts never mix.
        val resolvingFactory = ResolvingDataSource.Factory(httpFactory) { dataSpec ->
            resolveRequestHeaders(dataSpec)
        }
        val dataSourceFactory = DefaultDataSource.Factory(appContext, resolvingFactory)

        // Progressive container extraction: enable DTS-HD/HDMV audio
        // streams inside TS and search deeper for the first timestamp —
        // both required by real-world provider streams.
        val extractorsFactory = DefaultExtractorsFactory()
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS)
            .setTsExtractorTimestampSearchBytes(TS_TIMESTAMP_SEARCH_BYTES * TsExtractor.TS_PACKET_SIZE)

        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)

        // Decoder fallback: when the preferred decoder fails to initialize
        // (codec/profile mismatch), Media3 falls back to the next capable
        // decoder instead of failing playback — the reference configuration.
        // Software-decoder extension renderers (ffmpeg/av1/…), when added to
        // the build, participate as fallback decoders automatically.
        val renderersFactory = DefaultRenderersFactory(appContext)
            .setEnableDecoderFallback(true)
            .apply {
                val extensions = SoftwareDecoderExtensions.present()
                if (extensions.isNotEmpty()) {
                    setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                }
            }

        // Track selection reacts to renderer capability changes (needed for
        // the capability-aware audio recovery path), mirroring the reference.
        val trackSelector = DefaultTrackSelector(appContext)
        trackSelector.setParameters(
            trackSelector.buildUponParameters()
                .setAllowInvalidateSelectionsOnRendererCapabilitiesChange(true)
        )

        // Reference buffering profile: deep buffers absorb slow provider
        // CDNs instead of stuttering into a failure.
        val loadControl = DefaultLoadControl.Builder()
            .setTargetBufferBytes(REFERENCE_TARGET_BUFFER_BYTES)
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 70_000,
                /* bufferForPlaybackMs = */ DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                /* bufferForPlaybackAfterRebufferMs = */ 5_000
            )
            .build()

        return ExoPlayer.Builder(appContext, renderersFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .apply {
                playWhenReady = true
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        publish()
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        publish()
                    }

                    override fun onTracksChanged(tracks: Tracks) {
                        // Preserve the full track inventory (all audio tracks
                        // with codec/language/channels, selected video) in
                        // diagnostics, and steer away from known-undecodable
                        // audio before it can fail.
                        refreshTrackDiagnostics(tracks)
                        preemptivelyAvoidUndecodableAudio()
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.w(TAG, "Playback error ${error.errorCodeName}: ${error.message}")
                        val classification = PlaybackFailureClassifier.classify(error)
                        diagnostics = diagnostics.copy(
                            httpStatus = classification.httpStatus ?: diagnostics.httpStatus,
                            errorCategory = classification.category,
                            errorCause = causeChainOf(error)
                        )
                        // Audio failures are handled IN PLACE when possible:
                        // another compatible audio track, or — only as the
                        // final fallback — muted video. An audio problem must
                        // never become a video failure.
                        val audioFailed =
                            classification.category == PlaybackFailureCategory.AUDIO_DECODER_UNSUPPORTED ||
                                (
                                    classification.category == PlaybackFailureCategory.CODEC_CONFIGURATION_FAILED &&
                                        classification.decoderMimeType?.startsWith("audio/") == true
                                    )
                        if (audioFailed) {
                            val note = tryAudioTrackFallback(classification.decoderMimeType)
                            if (note != null) {
                                Log.w(TAG, diagnostics.toLogString())
                                onPlaybackEvent(PlaybackEvent.Info(note, diagnostics))
                                return
                            }
                        }
                        Log.w(TAG, diagnostics.toLogString())
                        onPlaybackEvent(
                            PlaybackEvent.Error(
                                message = classification.message,
                                category = classification.category,
                                httpStatus = classification.httpStatus,
                                decoderMimeType = classification.decoderMimeType,
                                diagnostics = diagnostics
                            )
                        )
                    }
                })
            }
    }

    /** Resolves the headers for one HTTP request of the active playback. */
    private fun resolveRequestHeaders(dataSpec: DataSpec): DataSpec {
        val headers = resolveRequestHeadersFor(
            url = dataSpec.uri.toString(),
            sessionHeaders = sessionHeaders.toMap(),
            urlOverrides = headersByUrl.toMap()
        )
        if (headers.isEmpty()) return dataSpec
        return dataSpec.buildUpon()
            .setHttpRequestHeaders(headers + dataSpec.httpRequestHeaders)
            .build()
    }

    private fun publish() {
        val state = player.playbackState
        onPlaybackEvent(
            PlaybackEvent.StateChanged(
                isPlaying = player.isPlaying,
                buffering = state == Player.STATE_BUFFERING,
                ended = state == Player.STATE_ENDED,
                positionMs = player.currentPosition.coerceAtLeast(0L),
                durationMs = if (player.duration < 0) 0L else player.duration
            )
        )
    }

    /**
     * Starts (or restarts) playback of a direct stream URL, with optional
     * side-loaded subtitles, that source's own HTTP headers, and the
     * resolved container MIME type (from [PlaybackPlanning]; null leaves
     * progressive byte-sniffing in charge — no fake MIME is ever set).
     */
    fun play(
        url: String,
        startPositionMs: Long,
        subtitleConfigurations: List<MediaItem.SubtitleConfiguration> = emptyList(),
        speed: Float = 1f,
        headers: Map<String, String> = emptyMap(),
        mimeType: String? = null,
        sourceName: String? = null,
        backendId: String = Media3PlaybackBackend.ID
    ) {
        when (val verdict = StreamValidator.validate(url)) {
            is StreamValidator.Result.Invalid -> {
                Log.w(TAG, "Rejected stream URL: ${verdict.reason}")
                onPlaybackEvent(
                    PlaybackEvent.Error(
                        verdict.reason,
                        PlaybackFailureCategory.UNKNOWN_PLAYBACK_ERROR
                    )
                )
                return
            }

            is StreamValidator.Result.Valid -> {
                val safeHeaders = StreamHeaders.sanitize(headers)
                Log.d(
                    TAG,
                    "Playing ${verdict.contentType} stream from ${verdict.url.toUriHost()}" +
                        " (${safeHeaders.size} headers, mime=${mimeType ?: "unresolved"})"
                )
                // New source, new context: nothing of the previous source's
                // headers may survive into this playback.
                headersByUrl.clear()
                sessionHeaders.clear()
                sessionHeaders.putAll(safeHeaders)
                activeHeaders = safeHeaders
                activeMimeType = mimeType
                activeSourceName = sourceName
                activeBackendId = backendId
                resetPerStreamState(url, safeHeaders, mimeType, sourceName, backendId)
                try {
                    val mediaItem = MediaItem.Builder()
                        .setUri(Uri.parse(verdict.url))
                        .setSubtitleConfigurations(subtitleConfigurations)
                        .apply { mimeType?.let { setMimeType(it) } }
                        .build()
                    // A previous stream's graceful degradation (e.g. audio
                    // disabled after an undecodable E-AC-3 track) must not
                    // silence the next source: track selection resets for
                    // every new stream.
                    player.trackSelectionParameters = player.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
                        .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                        .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                        .build()
                    player.setMediaItem(mediaItem)
                    if (startPositionMs > 0L) {
                        player.seekTo(startPositionMs)
                    }
                    player.setPlaybackSpeed(speed.coerceIn(0.25f, 4f))
                    player.prepare()
                    player.play()
                    publish()
                } catch (e: Exception) {
                    // Media3 builds media sources synchronously inside
                    // setMediaItem; inputs it cannot classify throw here
                    // (previously an app crash). Convert to the standard
                    // error path so the user can pick another stream.
                    Log.e(TAG, "Player rejected the media item", e)
                    diagnostics = diagnostics.copy(
                        errorCategory = PlaybackFailureCategory.CONTAINER_UNSUPPORTED,
                        errorCause = causeChainOf(e)
                    )
                    onPlaybackEvent(
                        PlaybackEvent.Error(
                            PlayerErrorMessages.forException(e),
                            PlaybackFailureCategory.CONTAINER_UNSUPPORTED,
                            diagnostics = diagnostics
                        )
                    )
                }
            }
        }
    }

    /** Fresh diagnostics + audio-recovery state for a new stream. */
    private fun resetPerStreamState(
        url: String,
        safeHeaders: Map<String, String>,
        mimeType: String?,
        sourceName: String?,
        backendId: String
    ) {
        attemptedAudioTrackKeys.clear()
        failedAudioMimeTypes.clear()
        audioFallbackAttempts = 0
        userSelectedAudio = false
        autoAudioOverrideKey = null
        diagnostics = PlaybackDiagnostics(
            backendId = backendId,
            sourceName = sourceName,
            containerMime = mimeType,
            streamHost = PlaybackDiagnostics.hostOf(url),
            headerNames = safeHeaders.keys.toList().sorted()
        )
    }

    /** Restarts the current media with side-loaded external subtitles. */
    fun applyExternalSubtitles(
        url: String,
        positionMs: Long,
        subtitleConfigurations: List<MediaItem.SubtitleConfiguration>,
        speed: Float
    ) {
        play(
            url,
            positionMs,
            subtitleConfigurations,
            speed,
            activeHeaders,
            activeMimeType,
            activeSourceName,
            activeBackendId
        )
    }

    fun setPlaybackSpeed(speed: Float) {
        player.setPlaybackSpeed(speed.coerceIn(0.25f, 4f))
        publish()
    }

    fun retry() {
        val position = player.currentPosition.coerceAtLeast(0L)
        try {
            player.prepare()
            player.seekTo(position)
            player.play()
            publish()
        } catch (e: Exception) {
            Log.e(TAG, "Retry failed", e)
            onPlaybackEvent(PlaybackEvent.Error(PlayerErrorMessages.forException(e)))
        }
    }

    fun togglePlayPause() {
        if (player.isPlaying) {
            player.pause()
        } else {
            player.play()
        }
        publish()
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(
            positionMs.coerceIn(0L, if (player.duration > 0) player.duration else Long.MAX_VALUE)
        )
        publish()
    }

    fun snapshotPosition(): Pair<Long, Long> {
        val position = player.currentPosition.coerceAtLeast(0L)
        val duration = if (player.duration > 0) player.duration else 0L
        return position to duration
    }

    // -----------------------------------------------------------------
    // Track selection (subtitles / audio)
    // -----------------------------------------------------------------

    fun textTracks(): List<TrackOption> = collectTracks(C.TRACK_TYPE_TEXT, "Subtitle")

    fun audioTracks(): List<TrackOption> = collectTracks(C.TRACK_TYPE_AUDIO, "Audio")

    private fun collectTracks(
        @androidx.annotation.IntRange(from = 0) type: Int,
        fallbackPrefix: String
    ): List<TrackOption> {
        val result = mutableListOf<TrackOption>()
        val groups = player.currentTracks.groups
        for (groupIndex in groups.indices) {
            val group = groups[groupIndex]
            if (group.getType() != type) continue
            for (trackIndex in 0 until group.length) {
                val format = group.getTrackFormat(trackIndex)
                val label = format.label?.takeIf { it.isNotBlank() }
                    ?: format.language?.uppercase()
                    ?: "$fallbackPrefix ${result.size + 1}"
                result += TrackOption(groupIndex, trackIndex, label, group.isTrackSelected(trackIndex))
            }
        }
        return result
    }

    /** Selects a text track, or disables text tracks entirely when null. */
    fun selectTextTrack(option: TrackOption?) {
        val builder = player.trackSelectionParameters.buildUpon()
        if (option == null) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            val group = player.currentTracks.groups.getOrNull(option.groupIndex) ?: return
            builder.setOverrideForType(
                TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex)
            )
        }
        player.trackSelectionParameters = builder.build()
    }

    /**
     * Capability-aware audio recovery after a decoder failure.
     *
     * Order of recourse (audio failures stay AUDIO failures — the video
     * keeps playing whenever it can):
     *  1. another audio track that is decodable on this device
     *     (preferred: the failed track's language, then channels, then
     *     bitrate) — each track is tried at most once, the whole chain
     *     is capped, so nothing hammers the server;
     *  2. if and only if NO decodable audio track exists: muted video
     *     as the final fallback, with the real reason stated.
     *
     * Returns the user-visible note when playback continues, or null
     * when this failure was not handled here (the caller surfaces it
     * through the normal error path).
     */
    private fun tryAudioTrackFallback(failedMimeType: String?): String? {
        val candidates = audioCandidates()
        if (candidates.isEmpty()) return null
        if (audioFallbackAttempts >= MAX_AUDIO_FALLBACK_ATTEMPTS) return null

        val failedMime = failedMimeType?.lowercase()
        if (failedMime != null) {
            failedAudioMimeTypes.add(failedMime)
            // Every track using the failed codec will fail the same way —
            // exclude them all from this and later retries.
            candidates
                .filter { it.mimeType?.lowercase() == failedMime }
                .forEach { attemptedAudioTrackKeys.add(it.key) }
        }
        val selected = selectedAudioCandidate()
        val next = AudioTrackPolicy.bestAlternative(
            candidates = candidates,
            excludeKeys = attemptedAudioTrackKeys,
            preferLanguageOf = selected?.format
        )
        audioFallbackAttempts++

        if (next == null) {
            // Final fallback: video-only playback. Muting is only ever
            // reached when no decodable audio track exists at all.
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
            val missing = friendlyCodecName(failedMime) ?: "audio track"
            diagnostics = diagnostics.copy(
                audioMimeType = failedMime ?: diagnostics.audioMimeType,
                fallbackAttempts = diagnostics.fallbackAttempts +
                    "audio disabled — no decodable audio track (missing decoder: $failedMime)"
            )
            resumePlaybackAtCurrentPosition()
            return "Playing the video without audio — this device has no decoder for that $missing track, " +
                "and the stream offers no alternative audio."
        }

        attemptedAudioTrackKeys.add(next.key)
        autoAudioOverrideKey = next.key
        val group = player.currentTracks.groups.getOrNull(next.groupIndex) ?: return null
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, next.trackIndex))
            .build()
        diagnostics = diagnostics.copy(
            audioMimeType = next.mimeType ?: diagnostics.audioMimeType,
            audioCodec = next.format.codecs ?: diagnostics.audioCodec,
            selectedAudio = next.summary,
            fallbackAttempts = diagnostics.fallbackAttempts + "audio switch -> ${next.summary}"
        )
        resumePlaybackAtCurrentPosition()
        val fromName = friendlyCodecName(failedMime) ?: "the failed"
        return "Audio switched to ${next.summary} — this device can't decode $fromName audio."
    }

    /** Re-prepares the current media from where it failed (never from 0). */
    private fun resumePlaybackAtCurrentPosition() {
        val resumeAtMs = player.currentPosition.coerceAtLeast(0L)
        player.prepare()
        if (resumeAtMs > 0L) {
            player.seekTo(resumeAtMs)
        }
        player.play()
        publish()
    }

    /** All audio tracks of the current stream with their decode capability. */
    private fun audioCandidates(): List<AudioTrackCandidate> {
        val groups = player.currentTracks.groups
        val result = mutableListOf<AudioTrackCandidate>()
        for (groupIndex in groups.indices) {
            val group = groups[groupIndex]
            if (group.getType() != C.TRACK_TYPE_AUDIO) continue
            for (trackIndex in 0 until group.length) {
                val format = group.getTrackFormat(trackIndex)
                result += AudioTrackCandidate(groupIndex, trackIndex, format, supportForFormat(format))
            }
        }
        return result
    }

    private fun selectedAudioCandidate(): AudioTrackCandidate? {
        val groups = player.currentTracks.groups
        for (groupIndex in groups.indices) {
            val group = groups[groupIndex]
            if (group.getType() != C.TRACK_TYPE_AUDIO) continue
            for (trackIndex in 0 until group.length) {
                if (group.isTrackSelected(trackIndex)) {
                    val format = group.getTrackFormat(trackIndex)
                    return AudioTrackCandidate(groupIndex, trackIndex, format, supportForFormat(format))
                }
            }
        }
        return null
    }

    /**
     * Keeps the FULL track inventory in diagnostics (every audio track
     * with codec/language/channels, the selected video) and updates the
     * selected-codec facts as the selector moves between tracks.
     */
    private fun refreshTrackDiagnostics(tracks: Tracks) {
        var selectedAudio: AudioTrackCandidate? = null
        var selectedVideoFormat: Format? = null
        val audioSummaries = mutableListOf<String>()
        val groups = tracks.groups
        for (groupIndex in groups.indices) {
            val group = groups[groupIndex]
            for (trackIndex in 0 until group.length) {
                val format = group.getTrackFormat(trackIndex)
                when (group.getType()) {
                    C.TRACK_TYPE_AUDIO -> {
                        val candidate = AudioTrackCandidate(
                            groupIndex, trackIndex, format, supportForFormat(format)
                        )
                        audioSummaries += candidate.summary
                        if (group.isTrackSelected(trackIndex)) selectedAudio = candidate
                    }
                    C.TRACK_TYPE_VIDEO -> if (group.isTrackSelected(trackIndex)) {
                        selectedVideoFormat = format
                    }
                }
            }
        }
        diagnostics = diagnostics.copy(
            audioMimeType = selectedAudio?.mimeType ?: diagnostics.audioMimeType,
            audioCodec = selectedAudio?.format?.codecs ?: diagnostics.audioCodec,
            selectedAudio = selectedAudio?.summary ?: diagnostics.selectedAudio,
            availableAudioTracks = audioSummaries.distinct(),
            videoMimeType = selectedVideoFormat?.sampleMimeType ?: diagnostics.videoMimeType,
            videoCodec = selectedVideoFormat?.codecs ?: diagnostics.videoCodec,
            selectedVideo = selectedVideoFormat?.let { videoSummary(it) } ?: diagnostics.selectedVideo
        )
    }

    /**
     * Defense in depth: if the selector somehow landed on a KNOWN-
     * undecodable audio track while a decodable one exists, switch
     * before a decoder failure can happen. A supported or unknown-
     * capability selection is never touched (no downgrades, no
     * interference with the player's own capability logic).
     */
    private fun preemptivelyAvoidUndecodableAudio() {
        if (userSelectedAudio || autoAudioOverrideKey != null) return
        val selected = selectedAudioCandidate() ?: return
        if (selected.support != DecoderSupport.UNSUPPORTED) return
        val alternative = AudioTrackPolicy.preemptiveSwitch(selected, audioCandidates()) ?: return
        val group = player.currentTracks.groups.getOrNull(alternative.groupIndex) ?: return
        autoAudioOverrideKey = alternative.key
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, alternative.trackIndex))
            .build()
        diagnostics = diagnostics.copy(
            selectedAudio = alternative.summary,
            fallbackAttempts = diagnostics.fallbackAttempts +
                "pre-selected ${alternative.summary} (auto-selected track undecodable)"
        )
    }

    private fun supportForFormat(format: Format): DecoderSupport {
        // Same effective MIME the candidate carries: sample MIME, or the
        // manifest codec string resolved through media3's own mapping.
        val mime = format.sampleMimeType
            ?: format.codecs?.let { MimeTypes.getMediaMimeType(it) }
        return PlaybackCapabilities.supportFor(decoderRegistry, mime)
    }

    /** Exception class chain only — messages can embed URLs and are dropped. */
    private fun causeChainOf(error: Throwable): String =
        generateSequence<Throwable>(error) { it.cause }
            .take(5)
            .joinToString(" -> ") { it.javaClass.simpleName }

    private fun videoSummary(format: Format): String {
        val codec = friendlyCodecName(format.sampleMimeType) ?: format.sampleMimeType ?: "video"
        val size = if (format.width > 0 && format.height > 0) " ${format.width}x${format.height}" else ""
        return codec + size
    }

    fun selectAudioTrack(option: TrackOption) {
        val group = player.currentTracks.groups.getOrNull(option.groupIndex) ?: return
        userSelectedAudio = true
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex))
            .build()
    }

    fun release() {
        headersByUrl.clear()
        sessionHeaders.clear()
        try {
            player.release()
        } catch (e: Exception) {
            Log.w(TAG, "Player release failed", e)
        }
    }

    private companion object {
        const val TAG = "SBPlayer"
        const val TS_TIMESTAMP_SEARCH_BYTES = 1500

        /** Hard cap on automatic audio-track retries per stream. */
        const val MAX_AUDIO_FALLBACK_ATTEMPTS = 3

        /** Reference buffering profile: 100 MB target buffer. */
        const val REFERENCE_TARGET_BUFFER_BYTES = 100 * 1024 * 1024
    }
}

/**
 * Pure decision: which headers apply to ONE HTTP request of the active
 * playback? A per-URL override (e.g. a subtitle's own authorization)
 * REPLACES the session context for that URL — one source's Referer,
 * cookies and user-agent are never attached to another URL's request.
 * Everything else carries the active source's full request context.
 */
internal fun resolveRequestHeadersFor(
    url: String,
    sessionHeaders: Map<String, String>,
    urlOverrides: Map<String, Map<String, String>>
): Map<String, String> {
    val override = urlOverrides[url]
    if (override != null) return override
    // Per-URL overrides registered for a DIFFERENT URL must not partially
    // match: only an exact URL entry replaces the session context.
    return sessionHeaders
}

/** Host name only — never logged with paths or query strings (token safety). */
private fun String.toUriHost(): String =
    runCatching { Uri.parse(this).host ?: "unknown-host" }.getOrDefault("unknown-host")
