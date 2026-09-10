package com.streambridge.app.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
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
 *    every segment and every redirect. They are session-scoped: a new
 *    stream replaces them, so no context ever leaks between sources.
 *  - CONTAINER/MANIFEST: the MIME type resolved by [PlaybackPlanning]
 *    (provider hint → URL evidence → server Content-Type) is set on
 *    the MediaItem, so extension-less HLS/DASH/TS links get the right
 *    media source instead of failing container recognition.
 *  - DECODERS: decoder fallback is enabled (a failing primary decoder
 *    no longer ends playback when a working alternative exists), and
 *    TS extraction accepts HDMV/DTS streams with deeper timestamp
 *    search — matching the reference configuration.
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
    private val onPlaybackEvent: (PlaybackEvent) -> Unit
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
            val decoderMimeType: String? = null
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
        val renderersFactory = DefaultRenderersFactory(appContext)
            .setEnableDecoderFallback(true)

        // Track selection reacts to renderer capability changes (needed for
        // the graceful audio-degradation path).
        val trackSelector = DefaultTrackSelector(appContext)

        return ExoPlayer.Builder(appContext, renderersFactory)
            .setTrackSelector(trackSelector)
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

                    override fun onPlayerError(error: PlaybackException) {
                        Log.w(TAG, "Playback error ${error.errorCodeName}: ${error.message}")
                        val classification = PlaybackFailureClassifier.classify(error)
                        onPlaybackEvent(
                            PlaybackEvent.Error(
                                message = classification.message,
                                category = classification.category,
                                httpStatus = classification.httpStatus,
                                decoderMimeType = classification.decoderMimeType
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
        mimeType: String? = null
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
                    onPlaybackEvent(
                        PlaybackEvent.Error(
                            PlayerErrorMessages.forException(e),
                            PlaybackFailureCategory.CONTAINER_UNSUPPORTED
                        )
                    )
                }
            }
        }
    }

    /** Restarts the current media with side-loaded external subtitles. */
    fun applyExternalSubtitles(
        url: String,
        positionMs: Long,
        subtitleConfigurations: List<MediaItem.SubtitleConfiguration>,
        speed: Float
    ) {
        play(url, positionMs, subtitleConfigurations, speed, activeHeaders, activeMimeType)
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
     * Graceful degradation for undecodable audio: if another audio track
     * exists that does not use the failed codec, it is selected;
     * otherwise the audio track type is disabled entirely so the video
     * still plays. Returns a user-facing note about what happened.
     */
    fun degradeAudioAfterDecoderFailure(failedMimeType: String?): String? {
        val groups = player.currentTracks.groups
        val failed = failedMimeType?.lowercase()
        var fallback: Pair<Int, Int>? = null
        var hasUsableAudio = false
        for (groupIndex in groups.indices) {
            val group = groups[groupIndex]
            if (group.getType() != C.TRACK_TYPE_AUDIO) continue
            for (trackIndex in 0 until group.length) {
                val format = group.getTrackFormat(trackIndex)
                val mime = format.sampleMimeType?.lowercase()
                if (failed == null || mime != failed) {
                    hasUsableAudio = true
                    if (fallback == null) fallback = groupIndex to trackIndex
                }
            }
        }
        return if (fallback != null && hasUsableAudio) {
            val (groupIndex, trackIndex) = fallback
            val group = groups[groupIndex]
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                .setOverrideForType(
                    TrackSelectionOverride(group.mediaTrackGroup, trackIndex)
                )
                .build()
            "Switched to another audio track this device can decode."
        } else {
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
            "Playing the video without audio — this device cannot decode that audio codec."
        }
    }

    fun selectAudioTrack(option: TrackOption) {
        val group = player.currentTracks.groups.getOrNull(option.groupIndex) ?: return
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
