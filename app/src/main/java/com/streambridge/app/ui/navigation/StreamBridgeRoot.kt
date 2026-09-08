package com.streambridge.app.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.streambridge.app.di.AppContainer
import com.streambridge.app.ui.browse.BrowseScreen
import com.streambridge.app.ui.catalog.CatalogGridScreen
import com.streambridge.app.ui.components.PillNavBar
import com.streambridge.app.ui.components.PillTab
import com.streambridge.app.ui.components.rememberPillNavScrollState
import com.streambridge.app.ui.details.DetailScreen
import com.streambridge.app.ui.extensions.ExtensionsScreen
import com.streambridge.app.ui.home.HomeScreen
import com.streambridge.app.ui.library.LibraryScreen
import com.streambridge.app.ui.player.PlayerScreen
import com.streambridge.app.ui.plugins.PluginsScreen
import com.streambridge.app.ui.search.SearchScreen
import com.streambridge.app.ui.settings.SettingsScreen

private val tabs = listOf(
    PillTab(Routes.HOME, "Home", Icons.Outlined.Home, Icons.Filled.Home),
    PillTab(Routes.SEARCH, "Search", Icons.Outlined.Search, Icons.Filled.Search),
    PillTab(Routes.LIBRARY, "Library", Icons.Outlined.Bookmarks, Icons.Filled.Bookmarks),
    PillTab(Routes.SETTINGS, "Settings", Icons.Outlined.Settings, Icons.Filled.Settings)
)

@Composable
fun StreamBridgeRoot(container: AppContainer) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = tabs.any { it.route == currentRoute }
    val pillScroll = rememberPillNavScrollState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            AnimatedVisibility(
                visible = showBottomBar,
                enter = slideInVertically(animationSpec = tween(240)) { it / 2 } + fadeIn(tween(240)),
                exit = slideOutVertically(animationSpec = tween(180)) { it / 2 } + fadeOut(tween(180))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    PillNavBar(
                        tabs = tabs,
                        selectedRoute = currentRoute ?: Routes.HOME,
                        onSelect = { tab ->
                            if (currentRoute != tab.route) {
                                pillScroll.expand()
                                navController.navigate(tab.route) {
                                    popUpTo(Routes.HOME) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp)
                    )
                }
            }
        }
    ) { padding ->
        val fullBleed = Routes.fullBleedRoutePrefixes.any { prefix ->
            currentRoute?.startsWith(prefix) == true
        }
        val navModifier = if (fullBleed) {
            Modifier.fillMaxSize()
        } else {
            Modifier
                .fillMaxSize()
                .padding(padding)
        }

        androidx.compose.runtime.CompositionLocalProvider(
            com.streambridge.app.ui.components.LocalPillNavScroll provides pillScroll
        ) {
            StreamBridgeNavHost(
                navController = navController,
                container = container,
                modifier = navModifier
            )
        }
    }
}

@Composable
private fun StreamBridgeNavHost(
    navController: NavHostController,
    container: AppContainer,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        modifier = modifier,
        enterTransition = { fadeIn(animationSpec = tween(240)) },
        exitTransition = { fadeOut(animationSpec = tween(240)) },
        popEnterTransition = { fadeIn(animationSpec = tween(240)) },
        popExitTransition = { fadeOut(animationSpec = tween(240)) }
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                container = container,
                onOpenDetail = { item -> navController.navigate(Nav.detail(item)) },
                onOpenExtensions = { navController.navigate(Routes.EXTENSIONS) },
                onOpenSearch = { navController.navigate(Routes.SEARCH) { launchSingleTop = true } },
                onResumePlayback = { entry -> navController.navigate(Nav.resumePlayback(entry)) },
                onBrowseGenre = { genre -> navController.navigate(Nav.browse(genre)) },
                onOpenCatalog = { ref ->
                    navController.navigate(
                        Nav.catalogGrid(ref.addonId, ref.type, ref.catalogId, ref.catalogName, ref.baseUrl)
                    )
                },
                onPlayItem = { item ->
                    if (item.type == "movie") {
                        // Movies can go straight to the player, which resolves
                        // streams itself; series need episode selection first.
                        val request = com.streambridge.app.player.PlaybackRequest(
                            type = "movie",
                            metaId = item.id,
                            metaName = item.name,
                            imdbId = item.imdbId,
                            poster = item.poster,
                            backdrop = item.backdrop,
                            videoId = item.id,
                            season = 0,
                            episode = 0,
                            episodeTitle = null
                        )
                        navController.navigate(Nav.player(request))
                    } else {
                        navController.navigate(Nav.detail(item))
                    }
                }
            )
        }

        composable(Routes.SEARCH) {
            SearchScreen(
                container = container,
                onOpenDetail = { item -> navController.navigate(Nav.detail(item)) },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.LIBRARY) {
            LibraryScreen(
                container = container,
                onOpenDetail = { item -> navController.navigate(Nav.detail(item)) },
                onResumePlayback = { entry -> navController.navigate(Nav.resumePlayback(entry)) }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                container = container,
                page = "root",
                onOpenPage = { page -> navController.navigate(Nav.settingsPage(page)) },
                onOpenExtensions = { navController.navigate(Routes.EXTENSIONS) },
                onOpenPlugins = { navController.navigate(Routes.PLUGINS) },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.SETTINGS_PAGE,
            arguments = listOf(
                navArgument("page") { type = NavType.StringType; defaultValue = "root" }
            )
        ) { entry ->
            SettingsScreen(
                container = container,
                page = entry.arguments?.getString("page") ?: "root",
                onOpenPage = { page -> navController.navigate(Nav.settingsPage(page)) },
                onOpenExtensions = { navController.navigate(Routes.EXTENSIONS) },
                onOpenPlugins = { navController.navigate(Routes.PLUGINS) },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.EXTENSIONS) {
            ExtensionsScreen(
                container = container,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.PLUGINS) {
            PluginsScreen(
                container = container,
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.CATALOG_GRID,
            arguments = listOf(
                androidx.navigation.navArgument("addon") { type = NavType.StringType; defaultValue = "" },
                androidx.navigation.navArgument("type") { type = NavType.StringType; defaultValue = "movie" },
                androidx.navigation.navArgument("id") { type = NavType.StringType; defaultValue = "" },
                androidx.navigation.navArgument("name") { type = NavType.StringType; defaultValue = "Catalog" },
                androidx.navigation.navArgument("base") { type = NavType.StringType; defaultValue = "" }
            )
        ) {
            CatalogGridScreen(
                container = container,
                onOpenDetail = { item -> navController.navigate(Nav.detail(item)) },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.DETAIL,
            arguments = listOf(
                navArgument("type") { type = NavType.StringType; defaultValue = "movie" },
                navArgument("id") { type = NavType.StringType; defaultValue = "" },
                navArgument("name") { type = NavType.StringType; defaultValue = "" },
                navArgument("poster") { type = NavType.StringType; defaultValue = "" },
                navArgument("backdrop") { type = NavType.StringType; defaultValue = "" },
                navArgument("source") { type = NavType.StringType; defaultValue = "" },
                navArgument("imdbId") { type = NavType.StringType; defaultValue = "" },
                navArgument("releaseInfo") { type = NavType.StringType; defaultValue = "" },
                navArgument("rating") { type = NavType.StringType; defaultValue = "" }
            )
        ) {
            DetailScreen(
                container = container,
                onBack = { navController.popBackStack() },
                onOpenPlayer = { request -> navController.navigate(Nav.player(request)) },
                onOpenDetail = { item -> navController.navigate(Nav.detail(item)) },
                onBrowseGenre = { genre -> navController.navigate(Nav.browse(genre)) }
            )
        }

        composable(
            route = Routes.PLAYER,
            arguments = listOf(
                navArgument("type") { type = NavType.StringType; defaultValue = "movie" },
                navArgument("metaId") { type = NavType.StringType; defaultValue = "" },
                navArgument("name") { type = NavType.StringType; defaultValue = "" },
                navArgument("imdbId") { type = NavType.StringType; defaultValue = "" },
                navArgument("poster") { type = NavType.StringType; defaultValue = "" },
                navArgument("backdrop") { type = NavType.StringType; defaultValue = "" },
                navArgument("videoId") { type = NavType.StringType; defaultValue = "" },
                navArgument("season") { type = NavType.StringType; defaultValue = "0" },
                navArgument("episode") { type = NavType.StringType; defaultValue = "0" },
                navArgument("episodeTitle") { type = NavType.StringType; defaultValue = "" }
            )
        ) {
            PlayerScreen(
                container = container,
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.BROWSE,
            arguments = listOf(
                navArgument("genre") { type = NavType.StringType; defaultValue = "" }
            )
        ) {
            BrowseScreen(
                container = container,
                onOpenDetail = { item -> navController.navigate(Nav.detail(item)) },
                onBack = { navController.popBackStack() }
            )
        }
    }
}
