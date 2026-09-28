package com.nuvio.app.features.cloudstream

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Small, process-local execution journal for the CloudStream boundary.
 *
 * Empty catalog/channel states are not evidence that a provider succeeded. The
 * journal keeps the stage and outcome that led to an empty result so the UI and
 * device reports can distinguish discovery, installation, class loading,
 * provider initialization, normalization and playback failures. It deliberately
 * stores no request URLs, headers, cookies, tokens or other credentials.
 */
enum class CloudStreamDiagnosticLevel {
    INFO,
    WARNING,
    ERROR,
}

enum class CloudStreamDiagnosticStage {
    DISCOVERY,
    PERSISTENCE,
    INSTALLATION,
    CLASSLOADER,
    PROVIDER_INITIALIZATION,
    GET_MAIN_PAGE,
    SEARCH,
    LOAD,
    NORMALIZATION,
    LOAD_LINKS,
    UI,
    PLAYBACK,
}

data class CloudStreamDiagnosticEvent(
    val level: CloudStreamDiagnosticLevel,
    val stage: CloudStreamDiagnosticStage,
    val provider: String? = null,
    val message: String,
)

internal object CloudStreamDiagnostics {
    private const val MAX_EVENTS = 80
    private val lock = Any()
    private val _events = MutableStateFlow<List<CloudStreamDiagnosticEvent>>(emptyList())
    val events: StateFlow<List<CloudStreamDiagnosticEvent>> = _events.asStateFlow()

    fun info(
        stage: CloudStreamDiagnosticStage,
        provider: String? = null,
        message: String,
    ) = record(CloudStreamDiagnosticLevel.INFO, stage, provider, message)

    fun warning(
        stage: CloudStreamDiagnosticStage,
        provider: String? = null,
        message: String,
    ) = record(CloudStreamDiagnosticLevel.WARNING, stage, provider, message)

    fun error(
        stage: CloudStreamDiagnosticStage,
        provider: String? = null,
        message: String,
    ) = record(CloudStreamDiagnosticLevel.ERROR, stage, provider, message)

    fun error(
        stage: CloudStreamDiagnosticStage,
        provider: String? = null,
        throwable: Throwable,
    ) = error(stage, provider, diagnosticMessage(throwable))

    /** Most recent failure, suitable for an actionable empty-state message. */
    fun latestFailureSummary(): String? = synchronized(lock) {
        _events.value.asReversed()
            .firstOrNull { it.level == CloudStreamDiagnosticLevel.ERROR }
            ?.let(::format)
    }

    /** Most recent failure for one stage, used by focused UI flows. */
    fun latestFailureSummary(stage: CloudStreamDiagnosticStage): String? = synchronized(lock) {
        _events.value.asReversed()
            .firstOrNull { it.level == CloudStreamDiagnosticLevel.ERROR && it.stage == stage }
            ?.let(::format)
    }

    fun clear() {
        synchronized(lock) { _events.value = emptyList() }
    }

    private fun record(
        level: CloudStreamDiagnosticLevel,
        stage: CloudStreamDiagnosticStage,
        provider: String?,
        message: String,
    ) {
        val event = CloudStreamDiagnosticEvent(
            level = level,
            stage = stage,
            provider = redact(provider),
            message = redact(message).ifBlank { "No additional detail was provided." },
        )
        synchronized(lock) {
            // Providers can emit the same error for several homepage sections;
            // collapsing adjacent duplicates keeps the diagnostic card useful.
            if (_events.value.lastOrNull() == event) return
            _events.value = (_events.value + event).takeLast(MAX_EVENTS)
        }
    }

    private fun format(event: CloudStreamDiagnosticEvent): String {
        val owner = event.provider?.takeIf(String::isNotBlank)?.let { " [$it]" }.orEmpty()
        return "${event.stage.name.replace('_', ' ')}$owner: ${event.message}"
    }

    /** Keep diagnostics useful without ever turning provider input into logs. */
    private fun redact(value: String?): String = value.orEmpty()
        .replace(
            Regex("(?i)(authorization|cookie|token|password|secret|api[-_ ]?key)\\s*[:=]\\s*[^,;\\s]+"),
            "$1=<redacted>",
        )
        .replace(Regex("https?://[^\\s)\\]}]+"), "<url>")
        .take(320)

    private fun diagnosticMessage(error: Throwable): String {
        val type = error::class.simpleName?.takeIf(String::isNotBlank) ?: "Error"
        val detail = error.message?.trim().orEmpty()
        return if (detail.isBlank()) type else "$type: $detail"
    }
}

internal fun Throwable.cloudStreamDiagnosticMessage(): String {
    val type = this::class.simpleName?.takeIf(String::isNotBlank) ?: "Error"
    val detail = message?.trim().orEmpty()
    return if (detail.isBlank()) type else "$type: $detail"
}
