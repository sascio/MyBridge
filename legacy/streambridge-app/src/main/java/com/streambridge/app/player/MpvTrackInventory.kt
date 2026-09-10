package com.streambridge.app.player

/**
 * Nuvio maps libmpv `track-list` onto the same audio/subtitle UI as
 * ExoPlayer (`PlayerEngine.android.kt` extractLibmpvTracks). StreamBridge
 * previously returned empty lists while MPV was active, which is why
 * Audio Track showed "No tracks available yet" after an engine switch.
 *
 * Pure: the JNI node is reduced to [MpvTrackNode] by the engine.
 */
data class MpvTrackNode(
    val type: String?,
    val id: Int?,
    val title: String?,
    val language: String?,
    val codec: String?,
    val selected: Boolean = false,
    val forced: Boolean = false
)

object MpvTrackInventory {

    fun audioOptions(nodes: List<MpvTrackNode>): List<TrackOption> =
        optionsOfType(nodes, type = "audio", fallbackPrefix = "Audio")

    fun subtitleOptions(nodes: List<MpvTrackNode>): List<TrackOption> =
        optionsOfType(nodes, type = "sub", fallbackPrefix = "Subtitle")

    fun summaries(nodes: List<MpvTrackNode>, type: String): List<String> =
        nodes.filter { it.type == type && it.id != null }
            .map { node ->
                val name = node.title?.takeIf { it.isNotBlank() }
                    ?: node.language?.uppercase()
                    ?: node.codec
                    ?: type
                val selected = if (node.selected) " (selected)" else ""
                name + selected
            }

    private fun optionsOfType(
        nodes: List<MpvTrackNode>,
        type: String,
        fallbackPrefix: String
    ): List<TrackOption> {
        val result = ArrayList<TrackOption>()
        for (node in nodes) {
            if (node.type != type) continue
            val id = node.id ?: continue
            val label = node.title?.takeIf { it.isNotBlank() }
                ?: node.language?.uppercase()
                ?: node.codec
                ?: "$fallbackPrefix ${result.size + 1}"
            result += TrackOption(
                groupIndex = 0,
                trackIndex = id,
                label = label,
                selected = node.selected
            )
        }
        return result
    }
}
