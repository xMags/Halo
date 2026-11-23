package moe.ditto.halo.tv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import kotlin.reflect.KClass
import kotlinx.coroutines.delay
import moe.ditto.halo.SessionGraph
import moe.ditto.halo.screens.AccountAvatar
import moe.ditto.halo.shell.DetailRoute
import moe.ditto.halo.shell.HomeRoute
import moe.ditto.halo.shell.LibraryRoute
import moe.ditto.halo.shell.PersonRoute
import moe.ditto.halo.shell.PlayerRoute
import moe.ditto.halo.shell.SearchRoute
import moe.ditto.halo.shell.SettingsRoute
import moe.ditto.halo.shell.ShelfRoute
import moe.ditto.halo.shell.StreamsRoute
import moe.ditto.halo.tv.kit.TvAction
import moe.ditto.halo.tv.kit.TvButton
import moe.ditto.halo.tv.kit.TvKeyMap
import moe.ditto.halo.tv.kit.LocalTvFocusTracker
import moe.ditto.halo.tv.kit.TvConfirmDialog
import moe.ditto.halo.tv.kit.defaultFocus
import moe.ditto.halo.tv.kit.rememberTvFocusMemory
import moe.ditto.halo.tv.kit.TvMetrics
import moe.ditto.halo.tv.kit.TvSurface
import moe.ditto.halo.tv.kit.TvSurfaceColors
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloIcons

/** A rail entry: a top-level destination. */
internal class TvRailItem(val route: Any, val label: String, val icon: ImageVector)

internal val TvRailItems = listOf(
    TvRailItem(SearchRoute, "Search", HaloIcons.Search),
    TvRailItem(HomeRoute, "Home", HaloIcons.Home),
    TvRailItem(LibraryRoute, "Library", HaloIcons.Bookmark),
    TvRailItem(SettingsRoute, "Settings", HaloIcons.Settings),
)

/** Destinations the rail stands beside; every other one covers the screen. */
private val RailRoutes: List<KClass<*>> = listOf(SearchRoute::class, HomeRoute::class, LibraryRoute::class, SettingsRoute::class)

/**
 * Full-screen destinations, opened from a screen and left with Back: a title
 * page, its sources, the player, a "See all" grid and a person page.
 */
private val CoveringRoutes: List<KClass<*>> =
    listOf(DetailRoute::class, StreamsRoute::class, PlayerRoute::class, ShelfRoute::class, PersonRoute::class)

internal fun NavDestination?.isRoute(route: KClass<*>): Boolean =
    this != null && hierarchy.any { it.hasRoute(route) }

/**
 * Opens a top-level destination at its own screen. Popping to the start keeps
 * Back from walking every destination visited.
 */
internal fun NavHostController.switchTo(route: Any) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id)
        launchSingleTop = true
    }
}

/**
 * The TV shell's frame: the rail on the left, [content] (the navigation host)
 * filling the screen behind it, and the Back rules for top-level screens: Back
 * from Search, Library or Settings goes Home, and Back on Home asks to exit.
 */
@Composable
internal fun TvShellFrame(
    graph: SessionGraph,
    navController: NavHostController,
    onExitApp: () -> Unit,
    content: @Composable () -> Unit,
) {
    val entry by navController.currentBackStackEntryAsState()
    val destination = entry?.destination
    val onHome = destination.isRoute(HomeRoute::class)
    val onOtherTopLevel = RailRoutes.any { destination.isRoute(it) } && !onHome
    val railVisible = destination != null && CoveringRoutes.none { destination.isRoute(it) }
    var askExit by remember { mutableStateOf(false) }
    val contentFocus = remember { FocusRequester() }
    val tracker = LocalTvFocusTracker.current
    // The screen control that had focus when Left took it into the rail.
    var returnTo by remember { mutableStateOf<FocusRequester?>(null) }
    val focusContent: () -> Unit = {
        val back = returnTo
        val restored = back != null && runCatching { back.requestFocus(FocusDirection.Enter) }.getOrDefault(false)
        if (!restored) runCatching { contentFocus.requestFocus(FocusDirection.Enter) }
    }
    var railFocused by remember { mutableStateOf(false) }
    // True while the rail has focus because Left put it there.
    var railChosen by remember { mutableStateOf(false) }
    // Focus can land in the rail without the viewer asking: a screen change
    // where the old screen's focused control went away mid-change and the
    // window took focus back, or a control that disappeared (a Try again
    // button once its retry worked). Once things have settled, the screen
    // gets it, tried again for a while: a screen still loading has nothing to
    // focus.
    LaunchedEffect(entry?.id, railFocused) {
        // Cleared here rather than as focus moves: entering with Left is
        // redirected to the current destination, and the rail can report a
        // moment without focus on the way, which must not forget the Left.
        if (!railFocused) {
            railChosen = false
            tracker.inRailByChoice = false
            return@LaunchedEffect
        }
        if (railChosen) return@LaunchedEffect
        delay(SettleMs)
        repeat(SettleTries) {
            if (!railFocused || railChosen) return@LaunchedEffect
            returnTo = null
            focusContent()
            delay(SettleRetryMs)
        }
    }

    // The viewer is done with the rail: the next screen may take focus.
    fun leaveRailForScreen() {
        railChosen = false
        tracker.inRailByChoice = false
    }

    BackHandler(enabled = onOtherTopLevel) { navController.switchTo(HomeRoute) }
    BackHandler(enabled = onHome && !askExit) { askExit = true }

    Box(Modifier.fillMaxSize().background(HaloColors.Background)) {
        // One group, so the rail can hand focus to the screen. Not a focus
        // restorer: during a screen change the old screen is still drawn,
        // and restoring into it would lose focus once it is gone.
        Box(Modifier.fillMaxSize().focusRequester(contentFocus).focusGroup()) {
            content()
        }
        AnimatedVisibility(visible = railVisible, enter = fadeIn(), exit = fadeOut()) {
            TvRail(
                graph = graph,
                isCurrent = { item -> destination.isRoute(item.route::class) },
                onSelect = { item ->
                    if (!destination.isRoute(item.route::class)) {
                        // Choosing a destination hands focus to it.
                        leaveRailForScreen()
                        navController.switchTo(item.route)
                    }
                },
                onAccount = {
                    leaveRailForScreen()
                    navController.switchTo(SettingsRoute)
                },
                onEnterFromScreen = {
                    returnTo = tracker.last
                    railChosen = true
                    tracker.inRailByChoice = true
                },
                onLeave = focusContent,
                onFocusChange = { focused -> railFocused = focused },
            )
        }
        if (askExit) {
            TvConfirmDialog(
                title = "Exit Halo?",
                message = null,
                confirmLabel = "Exit",
                cancelLabel = "Stay",
                // Back twice must not quit by accident.
                cancelFirst = true,
                onConfirm = {
                    askExit = false
                    onExitApp()
                },
                onDismiss = { askExit = false },
            )
        }
    }
}

/**
 * The left rail: icons only until it has focus, then wider with labels over a
 * shade. Focus entering it lands on the current destination, not on whichever
 * icon happens to be level with where it came from.
 */
@Composable
private fun TvRail(
    graph: SessionGraph,
    isCurrent: (TvRailItem) -> Boolean,
    onSelect: (TvRailItem) -> Unit,
    onAccount: () -> Unit,
    /** Left into the rail; called while the screen control still has focus. */
    onEnterFromScreen: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
    /**
     * Right from the rail, or focus arriving there any way but Left: back to
     * the screen. The widened rail covers the screen's edge, so a plain focus
     * search would find nothing to its right.
     */
    onLeave: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val width by animateDpAsState(
        if (expanded) TvMetrics.RailExpanded else TvMetrics.RailCollapsed,
        tween(180),
        label = "tv-rail-width",
    )
    val requesters = remember { TvRailItems.associateWith { FocusRequester() } }
    val current = TvRailItems.firstOrNull(isCurrent)
    // Read when focus arrives, not when the rule was set up: by then the
    // current destination may have changed.
    val currentRequester by rememberUpdatedState(current?.let { requesters[it] })

    Box(
        Modifier
            .fillMaxHeight()
            .width(width)
            .background(
                if (expanded) {
                    Brush.horizontalGradient(0f to HaloColors.Background, 0.7f to HaloColors.Background.copy(alpha = 0.96f), 1f to Color.Transparent)
                } else {
                    Brush.horizontalGradient(0f to HaloColors.Background.copy(alpha = 0.7f), 1f to Color.Transparent)
                },
            ),
    ) {
        Column(
            Modifier
                .fillMaxHeight()
                .padding(start = TvMetrics.SideMargin - 8.dp, top = TvMetrics.VerticalMargin + 4.dp, bottom = TvMetrics.VerticalMargin)
                .onFocusChanged {
                    expanded = it.hasFocus
                    onFocusChange(it.hasFocus)
                }
                .onPreviewKeyEvent { event ->
                    val right = TvKeyMap.actionFor(event.nativeKeyEvent.keyCode) == TvAction.Right
                    if (right && event.type == KeyEventType.KeyDown) onLeave()
                    right
                }
                .focusProperties {
                    onEnter = {
                        // A remote enters the rail with Left; it lands on
                        // the current destination. Focus can also arrive
                        // when the window takes focus back after a screen
                        // lost it: that is let through (refusing it would
                        // leave the window without focus and the remote
                        // dead), and TvShellFrame hands it to the screen
                        // once the screen has settled.
                        val target = currentRequester
                        if (requestedFocusDirection == FocusDirection.Left) {
                            onEnterFromScreen()
                            if (target != null) {
                                cancelFocusChange()
                                target.requestFocus()
                            }
                        }
                    }
                }
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier
                    .padding(start = 8.dp, bottom = 20.dp)
                    .size(40.dp)
                    .background(HaloColors.Accent, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("H", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
            }
            TvRailItems.forEach { item ->
                RailEntry(
                    item = item,
                    selected = item == current,
                    expanded = expanded,
                    modifier = Modifier.focusRequester(requesters.getValue(item)),
                    onClick = { onSelect(item) },
                )
            }
            Spacer(Modifier.weight(1f))
            TvSurface(
                onClick = onAccount,
                modifier = Modifier.size(56.dp).semantics { contentDescription = "Account" },
                cornerRadius = 28.dp,
                colors = TvSurfaceColors(Color.Transparent, HaloColors.SurfaceHigh),
                contentAlignment = Alignment.Center,
            ) {
                AccountAvatar(graph, onClick = null, size = 40.dp)
            }
        }
    }
}

private val RailEntryColors = TvSurfaceColors(rest = Color.Transparent, focused = HaloColors.Primary)

@Composable
private fun RailEntry(
    item: TvRailItem,
    selected: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TvSurface(
        onClick = onClick,
        modifier = modifier
            .height(48.dp)
            .width(if (expanded) TvMetrics.RailExpanded - TvMetrics.SideMargin - 16.dp else 56.dp)
            .semantics {
                contentDescription = item.label
                this.selected = selected
            },
        cornerRadius = 14.dp,
        colors = if (selected) TvSurfaceColors(HaloColors.FieldFill, HaloColors.Primary) else RailEntryColors,
        focusScale = 1.04f,
    ) { focused ->
        val tint = when {
            focused -> HaloColors.OnPrimary
            selected -> HaloColors.Text
            else -> HaloColors.TextDim
        }
        Row(
            Modifier.fillMaxSize().padding(start = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(item.icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            if (expanded) Text(item.label, color = tint, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        if (selected && !focused) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .width(3.dp)
                    .height(18.dp)
                    .background(HaloColors.Accent, RoundedCornerShape(2.dp)),
            )
        }
    }
}

/** A placeholder screen body while a destination has no TV screen yet. */
@Composable
internal fun TvPlaceholderScreen(title: String) {
    val memory = rememberTvFocusMemory()
    Column(
        Modifier.fillMaxWidth().padding(start = TvMetrics.ContentStart, top = TvMetrics.VerticalMargin),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(title, color = HaloColors.Text, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
        TvButton("Coming soon", onClick = {}, modifier = Modifier.defaultFocus(memory, "placeholder"))
    }
}

/** Longer than a screen change's cross-fade. */
private const val SettleMs = 400L

/** Retries every [SettleRetryMs] for up to about five seconds. */
private const val SettleTries = 40
private const val SettleRetryMs = 120L
