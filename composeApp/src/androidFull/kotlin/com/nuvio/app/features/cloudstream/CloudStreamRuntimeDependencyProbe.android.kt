package com.nuvio.app.features.cloudstream

/**
 * Resolves the runtime ABI through the same loader supplied to a plugin.
 *
 * A CloudStream .cs3 is not loaded by the host class loader directly. Its
 * PathClassLoader first searches the plugin archive and then delegates normal
 * Android/AndroidX/CloudStream dependencies to the application parent. This
 * probe deliberately accepts that plugin loader instead of calling
 * Class.forName from an unrelated host-only code path.
 */
internal object CloudStreamRuntimeDependencyProbe {
    /** Representative classes from the dependency families used by extensions. */
    val representativeClasses: List<String> = listOf(
        "android.app.Activity",
        "androidx.fragment.app.Fragment",
        "androidx.appcompat.app.AppCompatActivity",
        "com.google.android.material.bottomsheet.BottomSheetDialogFragment",
        "androidx.lifecycle.Lifecycle",
        "androidx.navigation.NavController",
        "androidx.preference.PreferenceFragmentCompat",
        "kotlin.Unit",
        "kotlinx.coroutines.Job",
        "com.lagradost.cloudstream3.plugins.Plugin",
    )

    data class Result(
        val binaryName: String,
        val resolved: Boolean,
        val definingClassLoader: String? = null,
        val errorType: String? = null,
        val errorMessage: String? = null,
    )

    fun inspect(pluginClassLoader: ClassLoader): List<Result> =
        representativeClasses.map { binaryName -> resolve(binaryName, pluginClassLoader) }

    fun resolve(binaryName: String, pluginClassLoader: ClassLoader): Result = try {
        val resolved = Class.forName(binaryName, false, pluginClassLoader)
        Result(
            binaryName = binaryName,
            resolved = true,
            definingClassLoader = describe(resolved.classLoader),
        )
    } catch (error: Throwable) {
        if (error is kotlinx.coroutines.CancellationException) throw error
        Result(
            binaryName = binaryName,
            resolved = false,
            errorType = error::class.java.name,
            errorMessage = error.message,
        )
    }

    fun format(results: List<Result>): String = results.joinToString(";") { result ->
        if (result.resolved) {
            "${result.binaryName}=resolvedBy:${result.definingClassLoader.orEmpty()}"
        } else {
            "${result.binaryName}=missing:${result.errorType}:${result.errorMessage.orEmpty()}"
        }
    }

    fun describe(loader: ClassLoader?): String = loader?.let {
        "${it.javaClass.name}@${Integer.toHexString(System.identityHashCode(it))}"
    } ?: "bootstrap"
}
