package moe.ditto.halo.tv

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import moe.ditto.halo.SessionGraph
import moe.ditto.halo.shell.HomeRoute
import moe.ditto.halo.shell.LibraryRoute
import moe.ditto.halo.shell.SearchRoute
import moe.ditto.halo.shell.SettingsRoute

/**
 * The signed-in TV app: the shell frame around one navigation graph over the
 * same routes the phone uses. There is no Downloads destination on TV.
 */
@Composable
internal fun TvShell(
    graph: SessionGraph,
    onExitApp: () -> Unit,
) {
    val navController = rememberNavController()
    TvShellFrame(graph = graph, navController = navController, onExitApp = onExitApp) {
        NavHost(
            navController = navController,
            startDestination = HomeRoute,
            // A short cross-fade: sliding screens read poorly from across a room.
            enterTransition = { fadeIn(tween(FadeMs)) },
            exitTransition = { fadeOut(tween(FadeMs)) },
            popEnterTransition = { fadeIn(tween(FadeMs)) },
            popExitTransition = { fadeOut(tween(FadeMs)) },
        ) {
            composable<HomeRoute> { TvPlaceholderScreen("Home") }
            composable<SearchRoute> { TvPlaceholderScreen("Search") }
            composable<LibraryRoute> { TvPlaceholderScreen("Library") }
            composable<SettingsRoute> { TvPlaceholderScreen("Settings") }
        }
    }
}

private const val FadeMs = 160
