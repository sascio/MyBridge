package com.nuvio.app.core.build

/**
 * Shared credential-presence rules for build-time injected runtime configuration
 * (see `generateRuntimeConfigs` in composeApp/build.gradle.kts).
 *
 * Values reach the runtime as generated `const val` strings. When a key is not
 * configured the generated value is the empty string, so "configured" means
 * "non-blank after trimming". Trimming matters because a value can still carry
 * stray whitespace from a `local.properties` line or a CI secret that was pasted
 * with a trailing newline.
 *
 * This is deliberately a pure, dependency-free helper so the credential-detection
 * rules can be unit tested without the generated config being present.
 */
object RuntimeCredentials {

    /** True when a single build-time injected value is genuinely present. */
    fun isConfigured(value: String?): Boolean = !value.isNullOrBlank()

    /** True only when every supplied value is genuinely present. */
    fun allConfigured(vararg values: String?): Boolean =
        values.isNotEmpty() && values.all(::isConfigured)

    /**
     * Presence-only description for diagnostics/logging.
     * Never includes the value itself.
     */
    fun presenceReport(vararg entries: Pair<String, String?>): String =
        entries.joinToString(" ") { (name, value) -> "$name present=${isConfigured(value)}" }
}
