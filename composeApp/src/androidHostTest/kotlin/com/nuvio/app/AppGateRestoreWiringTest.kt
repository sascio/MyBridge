package com.nuvio.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guards the wiring of the restored-session gate (StreamBridge 0.1.13).
 *
 * Root cause guarded against: backgrounding the app while a video was paused let Android reclaim the
 * process. On return Compose restored the gate as `Main` (back stack and all), but the gate's routing
 * effect saw a transient `AuthState.Loading` and re-derived the cold-start profile gate, which tore
 * the restored main content down. The user landed on profile selection, or on Home with the player
 * gone.
 *
 * [restoredSessionGateAction] is unit-tested in `commonTest`; what cannot be unit-tested is that the
 * activity actually *tells* the gate it is restoring, and that it stops replaying the launch intent.
 * Both are one-line connections that no Kotlin type forces to exist, so they are checked here against
 * the same sources the build compiles.
 */
class AppGateRestoreWiringTest {

    private val repositoryRoot: File by lazy {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile &&
                File(dir, "streambridge.version.properties").isFile
            ) {
                return@lazy dir
            }
            dir = dir.parentFile
        }
        error("Could not locate the StreamBridge repository root from ${File(".").absolutePath}")
    }

    private fun commonMain(relativePath: String): String =
        File(repositoryRoot, "composeApp/src/commonMain/kotlin/com/nuvio/app/$relativePath").readText()

    private fun androidMain(relativePath: String): String =
        File(repositoryRoot, "composeApp/src/androidMain/kotlin/com/nuvio/app/$relativePath").readText()

    private fun String.compacted(): String = replace(Regex("""\s+"""), " ")

    @Test
    fun gateConsultsTheRestoredSessionPolicyBeforeReplayingTheProfileGate() {
        val gate = commonMain("AppGate.kt").compacted()

        assertTrue(
            gate.contains("restoredSessionGateAction("),
            "AppGate must consult restoredSessionGateAction before re-deriving the profile gate.",
        )
        assertTrue(
            gate.contains("if (keepRestoredMainGate) return@LaunchedEffect"),
            "AppGate must return early so a restored Main gate keeps its navigation back stack.",
        )
        assertTrue(
            gate.contains("activeProfileIndex = profileState.activeProfile?.profileIndex"),
            "The restored policy must be fed the active profile it is being compared against.",
        )
    }

    @Test
    fun mainActivityReportsWhetherItIsRestoringSavedState() {
        val activity = androidMain("MainActivity.kt").compacted()

        assertTrue(
            activity.contains("val restoredFromSavedState = savedInstanceState != null"),
            "MainActivity must derive restore-ness from its saved instance state.",
        )
        assertTrue(
            activity.contains("showContent(restoredFromSavedState)"),
            "MainActivity must pass restore-ness into showContent.",
        )
        assertTrue(
            activity.contains("App(restoredFromSavedState = restoredFromSavedState)"),
            "MainActivity must hand restore-ness to the app gate.",
        )
    }

    @Test
    fun restoredActivityDoesNotReplayItsLaunchIntent() {
        val activity = androidMain("MainActivity.kt").compacted()
        val showContent = activity.substringAfter("private fun showContent(")

        assertTrue(
            showContent.contains("if (!restoredFromSavedState) { handleIncomingAppIntent(intent) }"),
            "A restored activity must not redeliver its original launch intent: doing so navigated a " +
                "restored session away from where the user left it.",
        )
    }
}
