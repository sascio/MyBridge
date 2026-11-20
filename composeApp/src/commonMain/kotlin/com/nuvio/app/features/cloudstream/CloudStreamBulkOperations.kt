package com.nuvio.app.features.cloudstream

import kotlinx.coroutines.CancellationException

/** Operations always target the complete discovered list, not just the visible filter. */
enum class CloudStreamBulkAction { INSTALL, UPDATE, ENABLE, DISABLE }

data class CloudStreamBulkFailure(val extensionName: String, val message: String)

data class CloudStreamBulkStatus(
    val action: CloudStreamBulkAction,
    val total: Int,
    val completed: Int = 0,
    val current: String? = null,
    val failures: List<CloudStreamBulkFailure> = emptyList(),
    val running: Boolean = true,
)

/** Sequential by design: bounded downloads, progress after each result, and no hidden failures. */
internal suspend fun runCloudStreamBulk(
    action: CloudStreamBulkAction,
    extensions: List<CloudStreamExtension>,
    perform: suspend (CloudStreamExtension) -> String?,
    report: (CloudStreamBulkStatus) -> Unit,
) {
    var status = CloudStreamBulkStatus(action, extensions.size)
    report(status)
    for (extension in extensions) {
        status = status.copy(current = extension.name)
        report(status)
        val error = try {
            perform(extension)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            failure.message ?: "Operation failed."
        }
        status = status.copy(
            completed = status.completed + 1,
            failures = if (error == null) status.failures else status.failures + CloudStreamBulkFailure(extension.name, error),
        )
        report(status)
    }
    report(status.copy(running = false, current = null))
}
