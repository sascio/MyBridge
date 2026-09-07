package com.streambridge.app.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.streambridge.app.di.AppContainer
import com.streambridge.app.ui.browse.BrowseScreen
import com.streambridge.app.ui.details.DetailScreen
import com.streambridge.app.ui.extensions.ExtensionsScreen
import com.streambridge.app.ui.home.HomeScreen
import com.streambridge.app.ui.library.LibraryScreen
import com.streambridge.app.ui.player.PlayerScreen
import com.streambridge.app.ui.search.SearchScreen
import com.streambridge.app.ui.settings.SettingsScreen

private data class TabSpec(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector
)

private val tabs = listOf(
    TabSpec(Routes.HOME, "Home", Icons.Outlined.Home, Icons.Filled.Home),
    TabSpec(Routes.SEARCH, "Search", Icons.Outlined.Search, Icons.Filled.Search),
    TabSpec(Routes.LIBRARY, "Library", Icons.Outlined.Bookmarks, Icons.Filled.Bookmarks),
    TabSpec(Routes.SETTINGS, "Settings", Icons.Outlined.Settings, Icons.Filled.Settings)
)

@Composable
fun StreamBridgeRoot(container: AppContainer) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = tabs.any { it.route == currentRoute }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            AnimatedVisibility(
                visible = showBottomBar,
                enter = slideInVertically(animationSpec = tween(220)) { it } + fadeIn(tween(220)),
                exit = slideOutVertically(animationSpec = tween(180)) { it } + fadeOut(tween(180))
            ) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    tabs.forEach { tab ->
                        val selected = currentRoute == tab.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                if (!selected) {
                                    navController.navigate(tab.route) {
                                        popUpTo(Routes.HOME) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = if (selected) tab.selectedIcon else tab.icon,
                                    contentDescription = tab.label
                                )
                            },
                            label = {
                                Text(
                                    text = tab.label,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                    }
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

        StreamBridgeNavHost(
            navController = navController,
            container = container,
            modifier = navModifier
        )
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
                onBrowseGenre = { genre -> navController.navigate(Nav.browse(genre)) }
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
                onOpenExtensions = { navController.navigate(Routes.EXTENSIONS) }
            )
        }

        composable(Routes.EXTENSIONS) {
            ExtensionsScreen(
                container = container,
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
