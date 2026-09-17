package com.nuvio.app.navigation

import com.nuvio.app.features.settings.SettingsPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression tests for the CloudStream Extension settings navigation.
 *
 * Root cause guarded against: `SettingsPage.CloudStream` existed and the Compose page content was
 * wired, but there was no dedicated [CloudStreamSettingsRoute] destination. On the phone layout
 * `useNativeNavigation` is false, so `onNavigatePage` is null and `SettingsScreen` falls back to the
 * per-page click lambda. That lambda was never supplied by the app shell, so it defaulted to a no-op
 * `{}` and tapping "CloudStream Extension" did nothing.
 *
 * These tests lock in the structural invariants that make the tap work, for every sibling page that
 * uses the same dedicated-route mechanism.
 */
class CloudStreamSettingsNavigationTest {

    @Test
    fun cloudStreamSettingsRouteExistsAndIsASettingsDestination() {
        val route = CloudStreamSettingsRoute("CloudStream Extension")

        // Must be a SettingsDestinationRoute so SettingsDestination()/back handling applies,
        // exactly like Addons and Plugins.
        val destination: SettingsDestinationRoute = route
        assertEquals("CloudStream Extension", destination.title)
    }

    @Test
    fun cloudStreamRouteDefaultsToBlankTitleLikeSiblingSettingsRoutes() {
        assertEquals("", CloudStreamSettingsRoute().title)
        assertEquals("", AddonsSettingsRoute().title)
        assertEquals("", PluginsSettingsRoute().title)
    }

    @Test
    fun cloudStreamRouteEqualityAndCopySupportBackStackDeduplication() {
        val a = CloudStreamSettingsRoute("CloudStream Extension")
        val b = CloudStreamSettingsRoute("CloudStream Extension")

        // Nav3 relies on data-class equality for launchSingleTop / back stack identity.
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(a != CloudStreamSettingsRoute("Other"))
    }

    @Test
    fun cloudStreamRouteIsSerializableSoSavedStateCanRestoreIt() {
        // A missing polymorphic serializer registration would crash on process-death restore.
        val serializer = CloudStreamSettingsRoute.serializer()
        assertNotNull(serializer)
        assertNotNull(serializer.descriptor.serialName)
    }

    @Test
    fun cloudStreamSettingsPageIsReachableUnderContentDiscovery() {
        val page = SettingsPage.CloudStream

        // The page must hang off Contents & Discovery so back navigation returns there,
        // mirroring Addons.
        assertEquals(SettingsPage.ContentDiscovery, page.parentPage)
        assertEquals(SettingsPage.ContentDiscovery, SettingsPage.Addons.parentPage)
    }

    @Test
    fun cloudStreamPageNameRoundTripsThroughTheGenericSettingsPageRoute() {
        // The tablet/native-navigation path routes by enum name through SettingsPageRoute.
        val pageName = SettingsPage.CloudStream.name
        val route = SettingsPageRoute(pageName, "CloudStream Extension")

        assertEquals(pageName, route.pageName)
        assertEquals(SettingsPage.CloudStream, SettingsPage.valueOf(route.pageName))
    }

    @Test
    fun everyContentDiscoveryChildPageHasAResolvableEnumName() {
        // Guards the `SettingsPage.valueOf(...)` lookups used by both navigation paths.
        val children = SettingsPage.entries.filter { it.parentPage == SettingsPage.ContentDiscovery }

        assertTrue(SettingsPage.CloudStream in children)
        children.forEach { page ->
            assertEquals(page, SettingsPage.valueOf(page.name))
        }
    }
}
