package com.nuvio.app.features.cloudstream

/**
 * Play Store distribution: CloudStream extensions are **never executed**.
 *
 * This build is compiled without the CloudStream runtime dependency, so there
 * is no `PathClassLoader` path, no `BasePlugin` ABI and no way to run a
 * downloaded `.cs3` — the capability is absent from the binary rather than
 * merely disabled by a flag.
 *
 * CloudStream repositories and extension metadata remain browsable; extensions
 * are reported as requiring native execution, which is the truthful state here.
 */
internal actual object CloudStreamPlatformRuntime {

    actual val supportsExecution: Boolean = false

    actual fun initialize(context: Any?) = Unit

    actual fun executor(): CloudStreamPluginExecutor? = null
}
