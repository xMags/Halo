package moe.ditto.halo.shell

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.coroutines.flow.Flow
import dev.chrisbanes.haze.rememberHazeState
import moe.ditto.halo.NativePlayerSurface
import moe.ditto.halo.PlaybackHost
import moe.ditto.halo.SignedInGraph
import moe.ditto.halo.api.MetaCard
import moe.ditto.halo.api.MetaDetail
import moe.ditto.halo.api.MetaVideo
import moe.ditto.halo.browse.episodeTag
import moe.ditto.halo.screens.DetailScreen
import moe.ditto.halo.screens.DownloadsScreen
import moe.ditto.halo.screens.HomeScreen
import moe.ditto.halo.screens.LibraryScreen
import moe.ditto.halo.screens.MetaRef
import moe.ditto.halo.player.PlayerSystemPort
import moe.ditto.halo.player.VideoFrameSource
import moe.ditto.halo.resources.Res
import moe.ditto.halo.resources.halo_mark
import moe.ditto.halo.screens.player.EpisodeChoice
import moe.ditto.halo.screens.player.PlayerScreen
import moe.ditto.halo.screens.SearchScreen
import moe.ditto.halo.screens.SettingsScreen
import moe.ditto.halo.screens.StreamsScreen
import moe.ditto.halo.screens.SourcesRequest
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloDimensions
import moe.ditto.halo.ui.HaloIcons
import moe.ditto.halo.ui.HaloLayout
import moe.ditto.halo.ui.HaloRadius
import moe.ditto.halo.ui.LocalHazeState
import moe.ditto.halo.ui.glassSource
import moe.ditto.halo.ui.glassSurface
import moe.ditto.halo.ui.rememberResponsive
import org.jetbrains.compose.resources.painterResource

/**
 * The signed-in shell: four tabs over one navigation graph, as a frosted bar
 * floating above the content or — on a tablet in landscape — as a rail beside
 * it. Same four tabs, same icons, same accent-marks-selection rule either way.
 *
 * Because the bar floats, screens scroll underneath it and are responsible for
 * ending their own content with [HaloDimensions.TabBarSpace] of bottom padding.
 * A screen that forgets it will hide its last row behind the glass, which is
 * the price of letting poster art bleed through the chrome. The rail does not
 * float — it takes its own column — so beside it that allowance is not merely
 * unnecessary but wrong, which is what [ResponsiveInfo.bottomContentPadding]
 * settles for every screen.
 *
 * The whole graph is registered as the blur source here, so any frosted surface
 * anywhere inside a screen samples that screen's own content.
 */
@Composable
internal fun HaloShell(
    graph: SignedInGraph,
    /** The app's one playback owner; the player route drives it, nothing else does. */
    playback: PlaybackHost,
    playerSurface: NativePlayerSurface,
    /** See [moe.ditto.halo.PlatformDependencies.bundledSubtitleFonts]. */
    bundledSubtitleFonts: Set<String>,
    /** The device side of playback: brightness, volume, orientation, sleep. */
    playerSystem: PlayerSystemPort,
    /** Frames behind the player's scrub preview; see [VideoFrameSource]. */
    videoFrames: VideoFrameSource,
    openDownloadsEvents: Flow<Unit>,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Opens the diagnostics harness from Settings. Null in a shipped build,
     * which leaves the row out of the tree entirely rather than rendering a
     * disabled one.
     */
    onOpenDebugGate: (() -> Unit)? = null,
) {
    val navController = rememberNavController()
    val hazeState = rememberHazeState()
    val responsive = rememberResponsive()

    LaunchedEffect(navController, openDownloadsEvents) {
        openDownloadsEvents.collect { navController.switchTab(DownloadsRoute) }
    }

    CompositionLocalProvider(LocalHazeState provides hazeState) {
        Box(modifier.fillMaxSize().background(HaloColors.Background)) {
            NavHost(
                navController = navController,
                startDestination = HomeRoute,
                modifier = Modifier.fillMaxSize().glassSource(hazeState),
                // Tabs are peers: switching between them is not a journey and gets
                // no animation, which is also what the platform does with a tab
                // bar. Only a push onto a covering screen animates, and it animates
                // in the direction it travels.
                enterTransition = { if (targetState.destination.coversChrome()) pushEnter() else EnterTransition.None },
                exitTransition = { if (targetState.destination.coversChrome()) pushExit() else ExitTransition.None },
                popEnterTransition = {
                    if (initialState.destination.coversChrome()) popEnter() else EnterTransition.None
                },
                popExitTransition = {
                    if (initialState.destination.coversChrome()) popExit() else ExitTransition.None
                },
            ) {
                composable<HomeRoute> {
                    HomeScreen(
                        graph = graph,
                        onOpenSearch = { navController.navigate(SearchRoute) },
                        onOpenDetail = { navController.openDetail(it) },
                        onPlayMovie = { meta -> navController.navigate(movieSources(meta)) },
                    )
                }
                composable<SearchRoute> {
                    SearchScreen(
                        graph = graph,
                        onOpenDetail = { navController.openDetail(it) },
                        onClose = { navController.popBackStack() },
                    )
                }
                composable<LibraryRoute> {
                    LibraryScreen(
                        graph = graph,
                        onOpenSearch = { navController.navigate(SearchRoute) },
                        onOpenDetail = { navController.openDetail(it) },
                    )
                }
                composable<DetailRoute> { entry ->
                    val route = entry.toRoute<DetailRoute>()
                    DetailScreen(
                        graph = graph,
                        type = route.type,
                        metaId = route.metaId,
                        onBack = { navController.popBackStack() },
                        onPlayMovie = { meta -> navController.navigate(movieSources(meta)) },
                        onPlayEpisode = { meta, video -> navController.navigate(episodeSources(meta, video)) },
                        // Where there is room beside the title, the picker
                        // arrives as a rail over it instead of as a screen that
                        // replaces it. The routes it builds are the same ones
                        // the pushed picker is given.
                        sourcesRequest = { meta, video ->
                            navController.sourcesRequest(
                                if (video == null) movieSources(meta) else episodeSources(meta, video),
                            )
                        },
                    )
                }
                composable<StreamsRoute> { entry ->
                    val route = entry.toRoute<StreamsRoute>()
                    StreamsScreen(
                        graph = graph,
                        type = route.type,
                        videoId = route.videoId,
                        title = route.displayTitle,
                        onBack = { navController.popBackStack() },
                        downloadMedia = { addon, stream, url -> route.downloadMedia(addon, stream, url) },
                        onPlay = { addon, stream ->
                            val url = stream.url ?: return@StreamsScreen
                            // The picker is replaced rather than stacked, so
                            // leaving the player returns to the title. Being
                            // handed the source list again after choosing from
                            // it reads as the choice not having taken.
                            navController.navigate(route.playerRoute(addon, stream, url)) {
                                popUpTo<StreamsRoute> { inclusive = true }
                            }
                        },
                    )
                }
                composable<PlayerRoute> { entry ->
                    val route = entry.toRoute<PlayerRoute>()
                    PlayerScreen(
                        graph = graph,
                        playback = playback,
                        surface = playerSurface,
                        bundledSubtitleFonts = bundledSubtitleFonts,
                        system = playerSystem,
                        videoFrames = videoFrames,
                        context = route.playbackContext(),
                        // Both outcomes replace the player rather than stacking
                        // on it, so back still returns to the title rather than
                        // walking every episode watched in this sitting.
                        onSelectEpisode = { choice ->
                            when (choice) {
                                is EpisodeChoice.Resolved ->
                                    navController.navigate(route.withContext(choice.context)) {
                                        popUpTo<PlayerRoute> { inclusive = true }
                                    }
                                is EpisodeChoice.NeedsSource ->
                                    navController.navigate(route.episodeSources(choice)) {
                                        popUpTo<PlayerRoute> { inclusive = true }
                                    }
                            }
                        },
                        onPickAnotherSource = {
                            navController.navigate(route.sourcesRoute()) {
                                popUpTo<PlayerRoute> { inclusive = true }
                            }
                        },
                        onBack = { navController.popBackStack() },
                    )
                }
                composable<DownloadsRoute> {
                    DownloadsScreen(
                        graph = graph,
                        onPlay = { entry ->
                            // Stacked rather than replacing: leaving playback
                            // returns to the list it was started from, which is
                            // the tab the viewer is standing in.
                            graph.downloads.playbackFiles(entry)?.let { files ->
                                navController.navigate(entry.playerRoute(files))
                            }
                        },
                    )
                }
                composable<SettingsRoute> {
                    SettingsScreen(
                        graph = graph,
                        onSignOut = onSignOut,
                        onOpenDebugGate = onOpenDebugGate,
                    )
                }
            }
            // Both are chrome over the same graph, and exactly one of them is
            // ever in use: the rail replaces the bar in landscape rather than
            // joining it.
            if (responsive.usesNavigationRail) {
                HaloNavRail(navController, Modifier.align(Alignment.CenterStart))
            } else {
                HaloTabBar(navController, Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

/**
 * How long a push takes. Short enough to feel like a response to the tap rather
 * than a scene change, and the same in both directions so back never feels like
 * a different gesture from forward.
 */
private val PushSpec = tween<IntOffset>(durationMillis = 300, easing = FastOutSlowInEasing)

/**
 * How far the screen being left behind travels: a fraction of the way, not off
 * the edge. The two screens moving at different speeds is what reads as one
 * sliding *over* the other rather than the pair sliding sideways together.
 */
private const val ParallaxFraction = 3

/** The pushed screen arrives from the trailing edge. */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.pushEnter(): EnterTransition =
    slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, PushSpec)

/** The screen underneath drifts a third of the way out, and waits there. */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.pushExit(): ExitTransition =
    slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Start, PushSpec) { full ->
        full / ParallaxFraction
    }

/** Popping is the exact mirror: what drifted out drifts back in from where it went. */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.popEnter(): EnterTransition =
    slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End, PushSpec) { full ->
        full / ParallaxFraction
    }

/** And the pushed screen leaves the way it came, out through the trailing edge. */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.popExit(): ExitTransition =
    slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, PushSpec)

/** True for destinations that replace the shell chrome. See [ChromeCoveringRoutes]. */
private fun NavDestination?.coversChrome(): Boolean =
    this != null && ChromeCoveringRoutes.any { route -> hierarchy.any { it.hasRoute(route) } }

/**
 * True for destinations that take the navigation rail down as well.
 *
 * A shorter list than [ChromeCoveringRoutes] on purpose: those screens cover the
 * bar because a phone has no room to keep it, and a tablet in landscape does.
 * Only the player, which owns the whole window, takes the rail with it.
 */
private fun NavDestination?.coversRail(): Boolean =
    this != null && hierarchy.any { it.hasRoute(PlayerRoute::class) }

private class Tab(
    val route: Any,
    val label: String,
    val icon: ImageVector,
    val activeIcon: ImageVector,
)

private val tabs = listOf(
    Tab(HomeRoute, "Home", HaloIcons.HomeOutline, HaloIcons.Home),
    Tab(LibraryRoute, "Library", HaloIcons.BookmarkOutline, HaloIcons.Bookmark),
    Tab(DownloadsRoute, "Downloads", HaloIcons.DownloadOutline, HaloIcons.Download),
    Tab(SettingsRoute, "Settings", HaloIcons.SettingsOutline, HaloIcons.Settings),
)

/**
 * Tall enough for the icon, the gap, and a full label line plus padding. The
 * row clips rather than growing, so anything short here silently beheads every
 * label instead of pushing the bar taller.
 */
private val TabBarHeight = 56.dp
private val TabIconSize = 24.dp

@Composable
private fun HaloTabBar(navController: NavHostController, modifier: Modifier = Modifier) {
    val entry by navController.currentBackStackEntryAsState()
    val destination = entry?.destination

    // Screens that own the whole viewport hide the bar rather than having it
    // float over their controls. It leaves downwards, with the push, instead
    // of blinking out of existence while the screen it belongs to is still on
    // screen. Returning slides it back up under the returning screen.
    AnimatedVisibility(
        visible = !destination.coversChrome(),
        modifier = modifier,
        enter = slideInVertically(PushSpec) { height -> height },
        exit = slideOutVertically(PushSpec) { height -> height },
    ) {
        Column(Modifier.fillMaxWidth().glassSurface(HaloColors.TabBarTint)) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(HaloColors.GlassBorder))
            Row(
                // The inset padding sits inside the glass so the frosted fill runs
                // to the bottom of the screen, under the home indicator, instead of
                // stopping short and leaving an opaque strip beneath it.
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .height(TabBarHeight),
            ) {
                tabs.forEach { tab ->
                    val selected = destination?.hierarchy?.any { it.hasRoute(tab.route::class) } == true
                    TabButton(
                        tab = tab,
                        selected = selected,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (!selected) navController.switchTab(tab.route)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TabButton(tab: Tab, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    // Unselected tabs stay bright rather than dimmed: the bar is translucent, so
    // a low-contrast label competes with whatever poster art is scrolling behind
    // it. The accent marks the selection instead.
    val tint = if (selected) HaloColors.Accent else HaloColors.Text
    Column(
        modifier = modifier
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = if (selected) tab.activeIcon else tab.icon,
            // The label below already names this tab; describing the icon too
            // would make every tab announce itself twice.
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(TabIconSize),
        )
        Text(
            text = tab.label,
            color = tint,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/**
 * The tab bar turned on its side, for a landscape tablet.
 *
 * It floats over the content exactly as the bar does — screens inset their own
 * leading edge by [HaloLayout.NavRailWidth], the way they allow for
 * [HaloDimensions.TabBarSpace] at the bottom — so the glass has something to
 * frost. A rail with the content stopping at its edge would have nothing behind
 * it to blur, and the tint alone does not read as glass.
 *
 * Only the player takes the rail down: it owns the whole window, and every other
 * screen keeps it, because losing it for a title would make going back to
 * Library a two-step trip on a device with room for both.
 */
@Composable
private fun HaloNavRail(navController: NavHostController, modifier: Modifier = Modifier) {
    val entry by navController.currentBackStackEntryAsState()
    val destination = entry?.destination

    AnimatedVisibility(
        visible = !destination.coversRail(),
        modifier = modifier,
        // Leaves towards the leading edge, with the push that took it down,
        // rather than blinking out while the screen it belongs to is still up.
        enter = slideInHorizontally(PushSpec) { width -> -width },
        exit = slideOutHorizontally(PushSpec) { width -> -width },
    ) {
        Box(Modifier.fillMaxHeight().width(HaloLayout.NavRailWidth)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .glassSurface(HaloColors.TabBarTint)
                    // Inside the glass, so the frosted fill runs into the
                    // cutout and under the home indicator instead of stopping
                    // short of them. Only the sides this rail can actually
                    // touch: it is pinned to the leading edge at a fixed width.
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical),
                    )
                    .padding(top = RailTopPadding, bottom = RailBottomPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AppMark()
                // The tabs are their own group so the gap under the mark is the
                // one stated here, rather than that gap plus a tab's spacing.
                Column(
                    modifier = Modifier.padding(top = RailMarkGap),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(RailTabGap),
                ) {
                    tabs.forEach { tab ->
                        val selected = destination?.hierarchy?.any { it.hasRoute(tab.route::class) } == true
                        RailTabButton(
                            tab = tab,
                            selected = selected,
                            onClick = { if (!selected) navController.switchTab(tab.route) },
                        )
                    }
                }
            }
            // The trailing edge, drawn over the fill rather than as padding
            // inside it, so the rail stays exactly its stated width.
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(1.dp)
                    .background(HaloColors.GlassBorder),
            )
        }
    }
}

private val RailTopPadding = 18.dp
private val RailBottomPadding = 14.dp
private val RailTabGap = 2.dp
private val RailMarkGap = 24.dp
private val RailMarkSize = 34.dp
private val RailTabShape = RoundedCornerShape(14.dp)

/** The canonical Halo mark used by the main app. */
@Composable
private fun AppMark() {
    Image(
        painter = painterResource(Res.drawable.halo_mark),
        contentDescription = null,
        modifier = Modifier.size(RailMarkSize),
        contentScale = ContentScale.Fit,
    )
}

/** Selected tabs are tinted rather than filled: the rail is translucent too. */
private val RailSelectedFill = HaloColors.Accent.copy(alpha = 0.14f)

@Composable
private fun RailTabButton(tab: Tab, selected: Boolean, onClick: () -> Unit) {
    val tint = if (selected) HaloColors.Accent else HaloColors.Text
    Column(
        modifier = Modifier
            .width(HaloLayout.NavRailTabWidth)
            .clip(RailTabShape)
            .background(if (selected) RailSelectedFill else Color.Transparent)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = if (selected) tab.activeIcon else tab.icon,
            // The label below already names this tab.
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(TabIconSize),
        )
        Text(
            text = tab.label,
            color = tint,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * What the sources rail needs to show and act on one video's sources.
 *
 * Built from the same [StreamsRoute] the pushed picker would have been given, so
 * the rail and the screen cannot disagree about what is being played, what it is
 * called, or what a download of it would be.
 */
private fun NavHostController.sourcesRequest(route: StreamsRoute) = SourcesRequest(
    type = route.type,
    videoId = route.videoId,
    title = route.displayTitle,
    onPlay = { addon, stream ->
        val url = stream.url
        if (url != null) {
            // Stacked on the title rather than replacing it: the rail never
            // took the title's place, so there is nothing for leaving playback
            // to fall back to but the title itself.
            navigate(route.playerRoute(addon, stream, url))
        }
    },
    downloadMedia = { addon, stream, url -> route.downloadMedia(addon, stream, url) },
)

/**
 * Opens a title from any browse surface.
 *
 * Every one of them hands over the same [MetaRef], so the three entry points into
 * detail cannot disagree about how a title is addressed.
 */
private fun NavHostController.openDetail(ref: MetaRef) =
    navigate(DetailRoute(type = ref.type, metaId = ref.metaId))

/**
 * A film's sources. Its meta id is also its video id: there is one video, and it
 * is the title.
 */
private fun movieSources(meta: MetaCard) = StreamsRoute(
    type = meta.type,
    metaId = meta.id,
    videoId = meta.id,
    showTitle = meta.name,
    poster = meta.poster,
)

/**
 * An episode's sources, named in the parts that are set separately downstream:
 * the show, which episode of it, and what that episode is called.
 *
 * The name is dropped when it is the tag: [episodeTag] falls back to the video's
 * own title for anything unnumbered, and the same string printed twice reads as
 * a rendering fault rather than as a missing episode number.
 */
private fun episodeSources(meta: MetaDetail, video: MetaVideo): StreamsRoute {
    val tag = episodeTag(video)
    return StreamsRoute(
        type = meta.type,
        metaId = meta.id,
        videoId = video.id,
        showTitle = meta.name,
        episodeTag = tag,
        episodeName = video.displayTitle?.takeIf { it != tag },
        episodeThumbnail = video.thumbnail,
        poster = meta.poster,
    )
}

/**
 * Switches tabs without stacking them.
 *
 * Popping back to the graph's start destination keeps back from walking the
 * history of visited tabs, while saving and restoring state means a tab returns
 * to where it was left, with the same scroll position and filter, rather than
 * rebuilding from scratch on every switch.
 */
private fun NavHostController.switchTab(route: Any) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
