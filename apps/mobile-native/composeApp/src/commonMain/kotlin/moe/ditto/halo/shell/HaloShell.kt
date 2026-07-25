package moe.ditto.halo.shell

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
import dev.chrisbanes.haze.rememberHazeState
import moe.ditto.halo.SignedInGraph
import moe.ditto.halo.screens.DetailScreen
import moe.ditto.halo.screens.HomeScreen
import moe.ditto.halo.screens.LibraryScreen
import moe.ditto.halo.screens.MetaRef
import moe.ditto.halo.screens.SearchScreen
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloDimensions
import moe.ditto.halo.ui.HaloIcons
import moe.ditto.halo.ui.HaloSpacing
import moe.ditto.halo.ui.HaloType
import moe.ditto.halo.ui.LocalHazeState
import moe.ditto.halo.ui.glassSource
import moe.ditto.halo.ui.glassSurface

/**
 * The signed-in shell: four tabs over one navigation graph, with a frosted bar
 * floating above the content rather than sitting beside it.
 *
 * Because the bar floats, screens scroll underneath it and are responsible for
 * ending their own content with [HaloDimensions.TabBarSpace] of bottom padding.
 * A screen that forgets it will hide its last row behind the glass — which is
 * the price of letting poster art bleed through the chrome.
 *
 * The whole graph is registered as the blur source here, so any frosted surface
 * anywhere inside a screen samples that screen's own content.
 */
@Composable
internal fun HaloShell(
    graph: SignedInGraph,
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
                        // The stream picker is not registered yet, so a movie's
                        // Play lands nowhere rather than being wired to a route
                        // that does not exist.
                        onPlayMovie = {},
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
                        onPlayMovie = {},
                        onPlayEpisode = { _, _ -> },
                    )
                }
                composable<DownloadsRoute> { PlaceholderScreen("Downloads") }
                composable<SettingsRoute> {
                    PlaceholderScreen("Settings") {
                        if (onOpenDebugGate != null) {
                            DebugGateRow(onOpenDebugGate)
                        }
                    }
                }
            }
            HaloTabBar(navController, Modifier.align(Alignment.BottomCenter))
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
 * How far the screen being left behind travels — a fraction of the way, not off
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

/** True for destinations that replace the shell chrome — see [ChromeCoveringRoutes]. */
private fun NavDestination?.coversChrome(): Boolean =
    this != null && ChromeCoveringRoutes.any { route -> hierarchy.any { it.hasRoute(route) } }

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
    // float over their controls — and it leaves downwards, with the push, instead
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
 * Opens a title from any browse surface.
 *
 * Every one of them hands over the same [MetaRef], so the three entry points into
 * detail cannot disagree about how a title is addressed.
 */
private fun NavHostController.openDetail(ref: MetaRef) =
    navigate(DetailRoute(type = ref.type, metaId = ref.metaId))

/**
 * Switches tabs without stacking them.
 *
 * Popping back to the graph's start destination keeps back from walking the
 * history of visited tabs, while saving and restoring state means a tab returns
 * to where it was left — same scroll position, same filter — rather than
 * rebuilding from scratch on every switch.
 */
private fun NavHostController.switchTab(route: Any) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * Entry into the diagnostics harness. It lives under Settings because that is
 * the one tab a debug affordance can sit in without displacing product content.
 */
@Composable
private fun DebugGateRow(onClick: () -> Unit) {
    Text(
        text = "Debug gate",
        style = HaloType.Callout.copy(color = HaloColors.Accent),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = HaloSpacing.Md),
    )
}

/**
 * Stand-in until the real screens land. Deliberately scrollable and taller than
 * the viewport so the bottom-padding contract above is visible rather than
 * theoretical.
 */
@Composable
private fun PlaceholderScreen(name: String, header: @Composable () -> Unit = {}) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = HaloSpacing.Md),
    ) {
        Text(
            text = name,
            style = HaloType.LargeTitle,
            modifier = Modifier.padding(vertical = HaloSpacing.Md),
        )
        header()
        repeat(24) { index ->
            Text(
                text = "$name row ${index + 1}",
                style = HaloType.Body,
                modifier = Modifier.padding(vertical = HaloSpacing.Sm),
            )
        }
        Spacer(Modifier.height(HaloDimensions.TabBarSpace))
    }
}
