package com.streambridge.app.player

/**
 * In-memory handoff between the detail screen and the player. The episode
 * queue is parked here just before navigating so the player can offer
 * next/previous episode without serializing a large list into nav args.
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
}
