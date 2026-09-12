package com.streambridge.app.addon

import com.streambridge.app.addon.model.StreamClassification
import com.streambridge.app.addon.model.StreamOption

/**
 * Parses and normalizes human-readable stream descriptors ("1080p",
 * "x264", "1.4 GB", "43 seeders", language names…) into the unified
 * stream model fields. Pure logic, unit tested.
 */
object StreamEnrichment {

    private val RESOLUTION = Regex("""\b(2160|1440|1080|720|480|360)[pi]\b""", RegexOption.IGNORE_CASE)
    private val QUALITY = Regex("""\b(4k|uhd|8k|hdr10?|dvdrip|web[-.]?dl|webrip|bluray|brrip|hdrip|hdtv|camrip)\b""", RegexOption.IGNORE_CASE)
    private val SIZE = Regex("""\b(\d+(?:[.,]\d+)?)\s*(gb|mb|tb|kb)\b""", RegexOption.IGNORE_CASE)
    private val SEEDERS = Regex("""\(?(\d+)\s*(?:seeders|seeds|peers)\)?""", RegexOption.IGNORE_CASE)
    private val LANGUAGE = Regex(
        """\b(english|eng|hindi|hin|spanish|esp|french|fre|français|german|ger|italian|ita|japanese|jap|korean|kor|portuguese|por|russian|rus|turkish|tur|arabic|ara|chinese|chi|tamil|telugu|malayalam|kannada|bengali|punjabi|marathi|dual|multi|undubbed|dubbed)\b""",
        RegexOption.IGNORE_CASE
    )
    private val LANGUAGE_CODES = mapOf(
        "eng" to "en", "english" to "en", "hin" to "hi", "hindi" to "hi",
        "esp" to "es", "spanish" to "es", "fre" to "fr", "french" to "fr",
        "ger" to "de", "german" to "de", "ita" to "it", "italian" to "it",
        "jap" to "ja", "japanese" to "ja", "kor" to "ko", "korean" to "ko",
        "por" to "pt", "portuguese" to "pt", "rus" to "ru", "russian" to "ru",
        "tur" to "tr", "turkish" to "tr", "ara" to "ar", "arabic" to "ar",
        "chi" to "zh", "chinese" to "zh", "tamil" to "ta", "telugu" to "te",
        "malayalam" to "ml", "kannada" to "kn", "bengali" to "bn",
        "punjabi" to "pa", "marathi" to "mr"
    )

    /** Returns a copy of [option] with parsed quality/resolution/language/size/seeders. */
    fun enrich(option: StreamOption): StreamOption {
        val haystack = listOf(option.label, option.description ?: "", option.url ?: "")
            .filter { it.isNotBlank() }
            .joinToString(" ")

        val resolution = RESOLUTION.find(haystack)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val quality = buildString {
            RESOLUTION.find(haystack)?.let { append(it.value.uppercase()) }
            QUALITY.findAll(haystack).distinctBy { it.value.lowercase() }.forEach {
                if (isNotEmpty()) append(" ")
                append(it.value.uppercase().replace("-", ""))
            }
            // Codec hints that matter for picking.
            if (Regex("""\b(x265|h265|hevc)\b""", RegexOption.IGNORE_CASE).containsMatchIn(haystack)) {
                if (isNotEmpty()) append(" ")
                append("HEVC")
            } else if (Regex("""\b(x264|h264|avc)\b""", RegexOption.IGNORE_CASE).containsMatchIn(haystack)) {
                if (isNotEmpty()) append(" ")
                append("AVC")
            }
        }

        val sizeBytes = SIZE.find(haystack)?.let { match ->
            val value = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 0.0
            when (match.groupValues[2].lowercase()) {
                "tb" -> (value * 1_000_000_000_000).toLong()
                "gb" -> (value * 1_000_000_000).toLong()
                "mb" -> (value * 1_000_000).toLong()
                "kb" -> (value * 1_000).toLong()
                else -> 0L
            }
        } ?: 0L

        val seeders = SEEDERS.find(haystack)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val language = LANGUAGE.find(haystack)?.value?.lowercase()
            ?.let { LANGUAGE_CODES[it] ?: it }

        return option.copy(
            quality = quality,
            resolution = resolution,
            language = language ?: "",
            sizeBytes = sizeBytes,
            seeders = seeders
        )
    }

    fun enrichAll(options: List<StreamOption>): List<StreamOption> = options.map(::enrich)

    /**
     * Picker ordering: playable direct streams first (by resolution
     * desc, then quality label), then torrents (by seeders), then
     * external links last.
     */
    fun sortForPicker(options: List<StreamOption>): List<StreamOption> =
        options.sortedWith(
            compareByDescending<StreamOption> { it.isPlayable }
                .thenByDescending { it.classification != StreamClassification.TORRENT }
                .thenByDescending { it.resolution }
                .thenByDescending { it.seeders }
                .thenByDescending { it.sizeBytes }
                .thenBy { it.addonName }
        )

    /** Groups enriched streams by provider for the selection UI. */
    fun groupByProvider(options: List<StreamOption>): List<Pair<String, List<StreamOption>>> =
        options
            .groupBy { it.addonName }
            .map { (provider, list) -> provider to sortForPicker(list) }
            .sortedByDescending { (_, list) -> list.count { it.isPlayable } }
}
