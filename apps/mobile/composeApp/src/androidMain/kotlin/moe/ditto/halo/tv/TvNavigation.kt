package moe.ditto.halo.tv

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import moe.ditto.halo.NativePlayerSurface
import moe.ditto.halo.PlaybackHost
import moe.ditto.halo.SessionGraph
import moe.ditto.halo.player.PlayerSystemPort
import moe.ditto.halo.screens.player.EpisodeChoice
import moe.ditto.halo.shell.episodeSources
import moe.ditto.halo.shell.playbackContext
import moe.ditto.halo.shell.sourcesRoute
import moe.ditto.halo.shell.withContext
import moe.ditto.halo.tv.player.TvAudioOutput
import moe.ditto.halo.tv.settings.TvSettingsScreen
import moe.ditto.halo.tv.player.TvPlaybackPreferences
import moe.ditto.halo.tv.player.TvPlayerScreen
import moe.ditto.halo.screens.ShelfSnapshots
import moe.ditto.halo.screens.TitleNavigation
import moe.ditto.halo.screens.rememberTitleMenuHost
import moe.ditto.halo.shell.DetailRoute
import moe.ditto.halo.shell.HomeRoute
import moe.ditto.halo.shell.LibraryRoute
import moe.ditto.halo.shell.SearchRoute
import moe.ditto.halo.shell.SettingsRoute
import moe.ditto.halo.shell.PersonRoute
import moe.ditto.halo.shell.PlayerRoute
import moe.ditto.halo.shell.ShelfRoute
import moe.ditto.halo.shell.StreamsRoute
import moe.ditto.halo.shell.playerRoute
import moe.ditto.halo.shell.movieSources
import moe.ditto.halo.tv.browse.TvBrowseNavigation
import moe.ditto.halo.tv.browse.TvDetailScreen
import moe.ditto.halo.tv.browse.TvHomeScreen
import moe.ditto.halo.tv.browse.TvLibraryScreen
import moe.ditto.halo.tv.browse.TvPersonScreen
import moe.ditto.halo.tv.browse.TvSearchScreen
import moe.ditto.halo.tv.browse.TvShelfScreen
import moe.ditto.halo.tv.browse.TvSourcesScreen

/**
 * The signed-in TV app: the shell frame around one navigation graph over the
 * same routes the phone uses. There is no Downloads destination on TV, and
 * the title menus leave download actions out.
 */
@Composable
internal fun TvShell(
    graph: SessionGraph,
    /** The app's one playback owner; the player route drives it. */
    playback: PlaybackHost,
    playerSurface: NativePlayerSurface,
    playerSystem: PlayerSystemPort,
    /** This TV's own playback settings (frame rate, surround). */
    playbackPreferences: TvPlaybackPreferences,
    audioOutput: TvAudioOutput,
    onSignOut: () -> Unit,
    /** Halo without an account: back to the sign-in screen. */
    onSignIn: () -> Unit,
    onExitApp: () -> Unit,
) {
    val navController = rememberNavController()
    val shelves = remember { ShelfSnapshots() }
    // Home's "See all" on Recently watched opens Library on its History view.
    var libraryHistory by remember { mutableStateOf(false) }

    val titleMenus = rememberTitleMenuHost(
        graph = graph,
        navigation = TitleNavigation(
            openDetail = { navController.navigate(DetailRoute(it.type, it.metaId)) },
            openSources = { navController.navigate(it) },
            playMovie = { navController.navigate(movieSources(it)) },
            // A TV keeps no downloads, so nothing offers to play one.
            playDownload = {},
        ),
        offersDownloads = false,
    )
    val navigation = remember(navController) {
        TvBrowseNavigation(
            openDetail = { navController.navigate(DetailRoute(it.type, it.metaId)) },
            openSources = { navController.navigate(it) },
            playMovie = { navController.navigate(movieSources(it)) },
            openShelf = { navController.navigate(ShelfRoute(shelves.put(it))) },
            openPerson = { navController.navigate(it) },
            play = { navController.navigate(it) },
            openLibrary = { history ->
                libraryHistory = history
                navController.switchTo(LibraryRoute)
            },
            openSettings = { navController.switchTo(SettingsRoute) },
        )
    }

    TvShellFrame(graph = graph, navController = navController, onExitApp = onExitApp) {
        Box(Modifier.fillMaxSize()) {
            NavHost(
                navController = navController,
                startDestination = HomeRoute,
                // A short cross-fade: sliding screens read poorly from across a room.
                enterTransition = { fadeIn(tween(FadeMs)) },
                exitTransition = { fadeOut(tween(FadeMs)) },
                popEnterTransition = { fadeIn(tween(FadeMs)) },
                popExitTransition = { fadeOut(tween(FadeMs)) },
            ) {
                composable<HomeRoute> { TvHomeScreen(graph, navigation, titleMenus.menus) }
                composable<SearchRoute> { TvSearchScreen(graph, navigation, titleMenus.menus) }
                composable<LibraryRoute> {
                    TvLibraryScreen(
                        graph = graph,
                        navigation = navigation,
                        menus = titleMenus.menus,
                        openHistory = libraryHistory,
                        onHistoryTaken = { libraryHistory = false },
                    )
                }
                composable<SettingsRoute> {
                    TvSettingsScreen(graph = graph, preferences = playbackPreferences, onSignOut = onSignOut, onSignIn = onSignIn)
                }
                composable<DetailRoute> { entry ->
                    val route = entry.toRoute<DetailRoute>()
                    TvDetailScreen(graph, route.type, route.metaId, navigation, titleMenus.menus)
                }
                composable<ShelfRoute> { entry ->
                    TvShelfScreen(graph, shelves[entry.toRoute<ShelfRoute>().key], navigation, titleMenus.menus)
                }
                composable<PersonRoute> { entry ->
                    val route = entry.toRoute<PersonRoute>()
                    TvPersonScreen(graph, route.tmdbId, route.name, route.photo, navigation)
                }
                composable<StreamsRoute> { entry ->
                    val route = entry.toRoute<StreamsRoute>()
                    TvSourcesScreen(graph, route) { addon, stream ->
                        val url = stream.url ?: return@TvSourcesScreen
                        // The picker is replaced rather than stacked, so
                        // leaving the player returns to the title.
                        navController.navigate(route.playerRoute(addon, stream, url)) {
                            popUpTo<StreamsRoute> { inclusive = true }
                        }
                    }
                }
                composable<PlayerRoute> { entry ->
                    val route = entry.toRoute<PlayerRoute>()
                    TvPlayerScreen(
                        graph = graph,
                        playback = playback,
                        surface = playerSurface,
                        system = playerSystem,
                        preferences = playbackPreferences,
                        audioOutput = audioOutput,
                        context = route.playbackContext(),
                        // Both replace the player rather than stacking on it, so
                        // Back still returns to the title.
                        onSelectEpisode = { choice ->
                            when (choice) {
                                is EpisodeChoice.Resolved -> navController.navigate(route.withContext(choice.context)) {
                                    popUpTo<PlayerRoute> { inclusive = true }
                                }
                                is EpisodeChoice.NeedsSource -> navController.navigate(route.episodeSources(choice)) {
                                    popUpTo<PlayerRoute> { inclusive = true }
                                }
                            }
                        },
                        onPickAnotherSource = if (route.recordsHistory) {
                            {
                                navController.navigate(route.sourcesRoute()) {
                                    popUpTo<PlayerRoute> { inclusive = true }
                                }
                            }
                        } else {
                            null
                        },
                        onBack = { navController.popBackStack() },
                    )
                }
            }
            TvTitleMenuOverlays(titleMenus)
        }
    }
}

private const val FadeMs = 160
