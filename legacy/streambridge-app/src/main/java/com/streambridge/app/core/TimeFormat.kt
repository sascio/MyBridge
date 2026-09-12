package com.streambridge.app.core

/**
 * Formatting helpers for playback time, runtime labels and progress math.
 * Pure Kotlin, unit tested.
 */
object TimeFormat {

    /** Formats milliseconds as a clock, e.g. `32:05` or `1:32:05`. */
    fun msToClock(ms: Long): String {
        if (ms <= 0L) return "0:00"
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(java.util.Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    /** Formats milliseconds as a human duration label, e.g. `1h 32m` or `45m`. */
    fun msToDurationLabel(ms: Long): String {
        if (ms <= 0L) return ""
        val totalMinutes = (ms / 60000L).toInt()
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
            hours > 0 -> "${hours}h"
            else -> "${minutes}m"
        }
    }

    /** Parses runtime strings like `116`, `116 min`, `116m`, `1h 56m` into minutes. */
    fun runtimeToMinutes(raw: String?): Int {
        if (raw.isNullOrBlank()) return 0
        val text = raw.trim().lowercase(java.util.Locale.US)
        val hourMatch = Regex("(\\d+)\\s*h").find(text)
        val minuteMatch = Regex("(\\d+)\\s*m").find(text)
        if (hourMatch != null || minuteMatch != null) {
            val hours = hourMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val minutes = minuteMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
            return hours * 60 + minutes
        }
        return text.toIntOrNull() ?: 0
    }

    /** Formats minutes as `1h 56m`. */
    fun minutesToLabel(minutes: Int): String {
        if (minutes <= 0) return ""
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h > 0 && m > 0 -> "${h}h ${m}m"
            h > 0 -> "${h}h"
            else -> "${m}m"
        }
    }

    /** 0f..1f progress fraction, safe against zero/unknown durations. */
    fun progressFraction(positionMs: Long, durationMs: Long): Float {
        if (durationMs <= 0L || positionMs <= 0L) return 0f
        val fraction = positionMs.toFloat() / durationMs.toFloat()
        return fraction.coerceIn(0f, 1f)
    }

    /** True when playback is considered finished for watch-state purposes. */
    fun isFinished(positionMs: Long, durationMs: Long, thresholdPercent: Int): Boolean {
        if (durationMs <= 0L) return false
        return positionMs >= durationMs * thresholdPercent / 100L
    }
}
