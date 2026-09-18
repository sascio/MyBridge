package com.nuvio.app.features.cloudstream

/**
 * iOS: CloudStream extensions are **never executed**.
 *
 * `.cs3` packages contain Android DEX bytecode. iOS has no Android class
 * loader, and downloading code that changes app behaviour is prohibited by
 * App Store Review Guideline 2.5.2, so no execution backend can exist here.
 *
 * Repository browsing and extension metadata still work; extensions are
 * reported as requiring native execution.
 */
internal actual object CloudStreamPlatformRuntime {

    actual val supportsExecution: Boolean = false

    actual fun initialize(context: Any?) = Unit

    actual fun executor(): CloudStreamPluginExecutor? = null
}
