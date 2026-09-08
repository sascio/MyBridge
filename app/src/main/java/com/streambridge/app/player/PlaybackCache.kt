package com.streambridge.app.player

import com.streambridge.app.addon.model.StreamOption

/**
 * In-memory handoff between the detail screen and the player. The episode
 * queue is parked here just before navigating so the player can offer
 * next/previous episode without serializing a large list into nav args.
 *
 * [preselectedStream] carries the exact stream the user chose in the
 * detail screen so the player can start immediately instead of
 * re-resolving the same addon fan-out a second time.
 */
object PlaybackCache {

    data class QueueEpisode(
        val videoId: String,
        val season: Int,
        val number: Int,
        val title: String
    )

    @Volatile
    var pendingQueue: List<QueueEpisode> = emptyList()

    @Volatile
    var preselectedStream: StreamOption? = null
}
