package com.streambridge.app.ui.navigation

import android.net.Uri
import com.streambridge.app.addon.model.MediaItem
import com.streambridge.app.data.db.WatchProgressEntity
import com.streambridge.app.player.PlaybackRequest

/** Route table for the app. */
object Routes {
    const val HOME = "home"
    const val SEARCH = "search"
    const val LIBRARY = "library"
    const val SETTINGS = "settings"
    const val EXTENSIONS = "extensions"

    /** Sub-pages of the settings hub: settings/{page}. */
    const val SETTINGS_PAGE = "settings/{page}"

    val settingsPages = listOf("appearance", "playback", "integrations", "network", "about")

    const val DETAIL =
        "detail?type={type}&id={id}&name={name}&poster={poster}&backdrop={backdrop}" +
            "&source={source}&imdbId={imdbId}&releaseInfo={releaseInfo}&rating={rating}"

    const val PLAYER =
        "player?type={type}&metaId={metaId}&name={name}&imdbId={imdbId}&poster={poster}" +
            "&backdrop={backdrop}&videoId={videoId}&season={season}&episode={episode}" +
            "&episodeTitle={episodeTitle}"

    const val BROWSE = "browse?genre={genre}"

    /** Routes rendered edge-to-edge (no scaffold padding). */
    val fullBleedRoutePrefixes = listOf("player", "detail")
}

object Nav {

    private fun enc(value: String?): String = Uri.encode(value ?: "")

    fun detail(item: MediaItem): String =
        "detail" +
            "?type=${enc(item.type)}" +
            "&id=${enc(item.id)}" +
            "&name=${enc(item.name)}" +
            "&poster=${enc(item.poster)}" +
            "&backdrop=${enc(item.backdrop)}" +
            "&source=${enc(item.source)}" +
            "&imdbId=${enc(item.imdbId)}" +
            "&releaseInfo=${enc(item.releaseInfo)}" +
            "&rating=${enc(item.rating)}"

    fun player(request: PlaybackRequest): String =
        "player" +
            "?type=${enc(request.type)}" +
            "&metaId=${enc(request.metaId)}" +
            "&name=${enc(request.metaName)}" +
            "&imdbId=${enc(request.imdbId)}" +
            "&poster=${enc(request.poster)}" +
            "&backdrop=${enc(request.backdrop)}" +
            "&videoId=${enc(request.videoId)}" +
            "&season=${request.season}" +
            "&episode=${request.episode}" +
            "&episodeTitle=${enc(request.episodeTitle)}"

    fun resumePlayback(entry: WatchProgressEntity): String =
        "player" +
            "?type=${enc(entry.type)}" +
            "&metaId=${enc(entry.metaId)}" +
            "&name=${enc(entry.metaName)}" +
            "&imdbId=${enc(entry.imdbId)}" +
            "&poster=${enc(entry.poster)}" +
            "&backdrop=${enc(entry.backdrop)}" +
            "&videoId=${enc(entry.videoId)}" +
            "&season=${entry.season}" +
            "&episode=${entry.episode}" +
            "&episodeTitle=${enc(entry.episodeTitle)}"

    fun browse(genre: String): String =
        "browse?genre=${enc(genre)}"
}
