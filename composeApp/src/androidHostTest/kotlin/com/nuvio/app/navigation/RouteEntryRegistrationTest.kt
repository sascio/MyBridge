package com.nuvio.app.navigation

import androidx.navigation3.runtime.NavKey
import com.nuvio.app.navigationSavedStateConfiguration
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Guards the Navigation 3 route -> destination registration.
 *
 * Root cause guarded against (StreamBridge 0.1.11): `PartyRoute` was declared, was registered for
 * saved-state serialization, and both the Settings entry point and the `nuvio://party/<code>` deep
 * link navigated to it -- but the `entry<PartyRoute>` block was missing from the NavDisplay's entry
 * provider. It had been overwritten by the `entry<CloudStreamSettingsRoute>` block that was inserted
 * in the same position. Navigation 3 has no destination for a key without an entry, so tapping
 * "Watch Party" crashed the app instead of opening the party screen.
 *
 * The checks are static (they read the same sources the build compiles) because the failure is a
 * structural one: nothing in Kotlin's type system connects "a route class exists" to "the NavDisplay
 * can display it". Every declared route must have an entry, and the party entry must actually compose
 * [com.nuvio.app.features.party.PartyScreen] with the route's invite code.
 */
class RouteEntryRegistrationTest {

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

    private val routesFile: File
        get() = File(repositoryRoot, "composeApp/src/commonMain/kotlin/com/nuvio/app/navigation/Routes.kt")

    private val mainAppContentFile: File
        get() = File(repositoryRoot, "composeApp/src/commonMain/kotlin/com/nuvio/app/MainAppContent.kt")

    /** Every `data class XRoute`, `data object XRoute` or `class XRoute` declared in Routes.kt. */
    private fun declaredRoutes(): Set<String> =
        Regex("""^(?:data\s+)?(?:class|object)\s+([A-Za-z0-9_]+Route)\b""", RegexOption.MULTILINE)
            .findAll(routesFile.readText())
            .map { it.groupValues[1] }
            .toSet()

    /** Every `entry<XRoute>` registered in the app's NavDisplay. */
    private fun registeredEntries(): Set<String> =
        Regex("""entry<([A-Za-z0-9_]+Route)>""")
            .findAll(mainAppContentFile.readText())
            .map { it.groupValues[1] }
            .toSet()

    @Test
    fun everyDeclaredRouteHasNavDisplayEntry() {
        val missing = declaredRoutes() - registeredEntries()

        assertTrue(
            missing.isEmpty(),
            "These routes are declared but have no entry<...> in MainAppContent.kt, so navigating to " +
                "them crashes: ${missing.sorted()}",
        )
    }

    @Test
    fun everyRegisteredEntryReferencesADeclaredRoute() {
        val unknown = registeredEntries() - declaredRoutes()

        assertTrue(
            unknown.isEmpty(),
            "These entry<...> blocks reference routes that are not declared in Routes.kt: ${unknown.sorted()}",
        )
    }

    @Test
    fun partyRouteIsRegisteredAndComposesThePartyScreen() {
        val source = mainAppContentFile.readText()
        val start = source.indexOf("entry<PartyRoute>")
        assertTrue(start >= 0, "entry<PartyRoute> is missing from MainAppContent.kt.")

        val nextEntry = source.indexOf("entry<", start + 1)
        val block = source.substring(start, if (nextEntry >= 0) nextEntry else source.length)

        assertTrue(block.contains("PartyScreen("), "The PartyRoute entry does not compose PartyScreen.")
        assertTrue(
            block.contains("initialCode = route.code"),
            "The PartyRoute entry must pass the invite code from the route to PartyScreen.",
        )
        assertTrue(
            block.contains("onWatch = ::openPartyMedia"),
            "The PartyRoute entry must hand the joined party's media back to the app shell.",
        )
    }

    @Test
    fun everyRouteIsRegisteredForSavedStateRestore() {
        // A route that cannot be serialized crashes on process-death restore, which is the other
        // half of the navigation contract that a missing entry breaks silently.
        declaredRoutes().forEach { name ->
            val instance = when (name) {
                "TabsRoute" -> TabsRoute
                "PartyRoute" -> PartyRoute()
                else -> null
            }
            if (instance == null) return@forEach
            val serializer = navigationSavedStateConfiguration.serializersModule
                .getPolymorphic(NavKey::class, instance)
            assertNotNull(serializer, "$name is not registered for saved-state serialization.")
            assertEquals(name, serializer.descriptor.serialName.substringAfterLast('.'))
        }
    }
}
