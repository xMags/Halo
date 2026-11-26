package moe.ditto.halo.tv.browse

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.ditto.halo.api.MetaCard
import moe.ditto.halo.api.playableFromPreview
import moe.ditto.halo.browse.catalogExtra
import moe.ditto.halo.browse.CatalogPageSource
import moe.ditto.halo.browse.catalogRows
import moe.ditto.halo.browse.CatalogRowSpec
import moe.ditto.halo.browse.ContinueEntry
import moe.ditto.halo.browse.homeShelves
import moe.ditto.halo.browse.HomeShelves
import moe.ditto.halo.browse.isStandInPosterLink
import moe.ditto.halo.browse.matchesTypeFilter
import moe.ditto.halo.cache.QueryState
import moe.ditto.halo.screens.episodeTagOf
import moe.ditto.halo.screens.lookup
import moe.ditto.halo.screens.LookUpNextEpisodes
import moe.ditto.halo.screens.MediaTypeFilter
import moe.ditto.halo.screens.MetaRef
import moe.ditto.halo.screens.rememberNextEpisodeAnswers
import moe.ditto.halo.screens.resumeRoute
import moe.ditto.halo.screens.ShelfPaging
import moe.ditto.halo.screens.ShelfSnapshot
import moe.ditto.halo.screens.timeLeft
import moe.ditto.halo.screens.TitleMenus
import moe.ditto.halo.SessionGraph
import moe.ditto.halo.sync.LibraryRepository
import moe.ditto.halo.tv.kit.defaultFocus
import moe.ditto.halo.tv.kit.rememberFocus
import moe.ditto.halo.tv.kit.rememberTvFocusMemory
import moe.ditto.halo.tv.kit.TvButton
import moe.ditto.halo.tv.kit.TvButtonStyle
import moe.ditto.halo.tv.kit.TvFocusMemory
import moe.ditto.halo.tv.kit.TvMetrics
import moe.ditto.halo.tv.kit.TvPosterCard
import moe.ditto.halo.tv.kit.TvRow
import moe.ditto.halo.tv.kit.tvRowHeadingRoom
import moe.ditto.halo.tv.kit.TvType
import moe.ditto.halo.tv.kit.TvWideCard
import moe.ditto.halo.ui.HaloAsyncImage
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloIcons
import moe.ditto.halo.ui.metahubPosterUrl

/**
 * A fixed height read while laying out, so an animated one moves the layout
 * without composing anything again. Capped to what the parent allows.
 */
private fun Modifier.heightOf(height: () -> Dp): Modifier = layout { measurable, constraints ->
    val wanted = height().roundToPx().coerceAtLeast(0)
    val px = if (constraints.hasBoundedHeight) wanted.coerceAtMost(constraints.maxHeight) else wanted
    val placeable = measurable.measure(constraints.copy(minHeight = px, maxHeight = px))
    layout(placeable.width, px) { placeable.place(0, 0) }
}

/** What the hero shows: a title, and the video to resume when it came from Continue watching. */
private data class HeroTitle(val meta: MetaCard, val resume: ContinueEntry? = null)

private val HomePosterWidth = 112.dp
private val HeroHeight = 262.dp
private val HeroTextWidth = 470.dp

/** The hero once the rows are scrolled: its title and facts only. */
private val HeroCompactHeight = 140.dp

/**
 * TV Home: a hero for the focused title at the top, then the personal rows
 * (Continue watching, Recently watched, My library) and one row per catalog.
 * The hero follows the focused card after a short pause, and returns to the
 * featured titles when focus moves back up onto it; its buttons act on
 * whatever it shows. It is full size only while the rows are at their top
 * (the hero's buttons or the first row have focus); further down it keeps
 * just its title and facts, which leaves room for a poster row with its
 * heading and captions. Built on the same row and shelf rules as the phone
 * ([catalogRows], [homeShelves]).
 */
@Composable
internal fun TvHomeScreen(graph: SessionGraph, navigation: TvBrowseNavigation, menus: TitleMenus) {
    val memory = rememberTvFocusMemory()
    var retry by remember { mutableIntStateOf(0) }
    val addons by remember(graph, retry) { graph.addons.observeEffective() }.collectAsState(QueryState())
    // A failed load stays on screen until one succeeds. The query drops its
    // error each time a fetch starts (another part of the screen subscribing
    // starts one), and showing the loading rows meanwhile would take the
    // Try again button away from under the remote's focus.
    var addonsFailed by remember(graph) { mutableStateOf(false) }
    LaunchedEffect(addons) {
        if (addons.value != null) addonsFailed = false else if (addons.error != null) addonsFailed = true
    }
    val watchStates by remember(graph) { graph.watchStates.observe() }.collectAsState(QueryState())
    val library by remember(graph) { graph.library.observe() }.collectAsState(QueryState())
    val settings by remember(graph) { graph.settings.observe() }.collectAsState(QueryState())
    val settingsReady = settings.value != null || settings.error != null
    val catalogLanguage = settings.value?.catalogLanguage

    // All, Movies or Series: narrows the catalog rows, Recently watched and My
    // library, as on the phone. Continue watching ignores it there too.
    var filter by rememberSaveable { mutableStateOf(MediaTypeFilter.All) }
    val allRows = remember(addons.value) { catalogRows(addons.value.orEmpty()) }
    val rows = remember(allRows, filter) { allRows.filter { matchesTypeFilter(filter.type, it.type) } }
    val personalReady = (watchStates.value != null || watchStates.error != null) &&
        (library.value != null || library.error != null)
    // A finished series moves on to its next episode once that is looked up.
    val nextEpisodes = rememberNextEpisodeAnswers(graph)
    val known = nextEpisodes.toMap()
    val shelves = remember(watchStates.value, library.value, filter, known) {
        homeShelves(watchStates.value, library.value, filter.type, nextEpisodes::lookup)
    }
    LookUpNextEpisodes(graph, nextEpisodes, shelves.nextEpisodeRequests)

    // Until a card has focus, the hero takes turns through the first catalog's
    // lead titles, wide art first, as the phone's featured banner does.
    // Continue watching has its own row below, so it leads only when there is
    // no catalog to feature.
    val lead = rows.firstOrNull()
    val leadCatalog by remember(graph, lead?.key) {
        graph.browse.catalog(
            addonId = lead?.addonId.orEmpty(),
            type = lead?.type.orEmpty(),
            catalogId = lead?.catalogId.orEmpty(),
            enabled = lead != null,
        )
    }.collectAsState(QueryState())
    val featured = remember(leadCatalog.value) {
        leadCatalog.value.orEmpty().sortedBy { it.background.isNullOrBlank() }.take(FeaturedCount)
    }
    // Still loading the lead catalog: show nothing rather than a fallback
    // that the featured titles would replace a moment later.
    val featuredSettled = lead == null || leadCatalog.value != null || leadCatalog.error != null
    var featuredIndex by remember(featured) { mutableIntStateOf(0) }
    var focusedTitle by remember { mutableStateOf<HeroTitle?>(null) }
    var hero by remember { mutableStateOf<HeroTitle?>(null) }
    // A short pause, so moving across a row does not flicker the hero.
    LaunchedEffect(focusedTitle) {
        val next = focusedTitle ?: return@LaunchedEffect
        delay(HeroFollowMs)
        hero = next
    }
    // The featured titles take turns until a card takes the hero over.
    if (hero == null && featured.size > 1) {
        LaunchedEffect(featured, featuredIndex) {
            delay(FeaturedDwellMs)
            featuredIndex = (featuredIndex + 1) % featured.size
        }
    }
    val shown = hero
        ?: featured.getOrNull(featuredIndex)?.let { HeroTitle(it) }
        ?: shelves.continueWatching.firstOrNull()?.takeIf { featuredSettled }?.let { HeroTitle(it.meta, it) }
    val featuredPosition = if (hero == null && featured.size > 1) featuredIndex to featured.size else null

    // At the top of the rows the hero is full size, with its buttons there for
    // the remote to reach from the first row; anywhere lower it is compact.
    // Focusing the first row always scrolls the list back to its top, so the
    // scroll position alone decides this.
    val listState = rememberLazyListState()
    val expanded by remember(listState) { derivedStateOf { !listState.canScrollBackward } }
    // Read only while laying out (heightOf, offset), never while composing:
    // read here, every frame of the resize would compose the whole screen again.
    val heroHeight = animateDpAsState(
        targetValue = if (expanded) HeroHeight else HeroCompactHeight,
        animationSpec = tween(HeroResizeMs),
        label = "tv-hero-height",
    )

    Box(Modifier.fillMaxSize()) {
        shown?.let { HeroBackdrop(graph, it.meta) { heroHeight.value } }
        Column(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightOf { heroHeight.value }
                    .clipToBounds()
                    // Back above the rows, the hero is the featured banner
                    // again: a card only holds it while a card has focus.
                    // Clearing the focused title also drops a follow that
                    // was still waiting out its pause.
                    .onFocusChanged { state ->
                        if (!state.hasFocus) return@onFocusChanged
                        focusedTitle = null
                        hero = null
                    },
            ) {
                shown?.let { title ->
                    HeroInfo(
                        graph = graph,
                        title = title,
                        expanded = expanded,
                        featured = featuredPosition != null,
                        memory = memory,
                        inLibrary = library.value.orEmpty().any {
                            it.id == LibraryRepository.itemId(title.meta.type, title.meta.id) && !it.isRemoved
                        },
                        navigation = navigation,
                    )
                }
                // Above the hero's buttons, Up from them, rather than a stop
                // between the buttons and the first row. Named in full: inside
                // the Column the ColumnScope overload would be picked.
                androidx.compose.animation.AnimatedVisibility(
                    visible = expanded && allRows.isNotEmpty(),
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = TvMetrics.VerticalMargin, end = TvMetrics.SideMargin),
                ) {
                    // On a dark backing: the hero's art behind can be bright.
                    Row(
                        Modifier
                            .background(HaloColors.Background.copy(alpha = 0.7f), RoundedCornerShape(24.dp))
                            .padding(6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        MediaTypeFilter.entries.forEach { option ->
                            TvFilterPill(
                                option.label,
                                selected = option == filter,
                                onClick = { filter = option },
                                modifier = Modifier.rememberFocus(memory, "home-filter/${option.name}"),
                            )
                        }
                    }
                }
            }
            when {
                addons.value == null && (addons.error != null || addonsFailed) -> TvBodyMessage(
                    "Could not reach your Halo server.",
                    retryModifier = Modifier.defaultFocus(memory, "retry"),
                    onRetry = { retry += 1 },
                )
                // The personal rows sit above the catalogs, so all of them wait
                // for history and library: a list that showed the catalogs
                // first would keep its place on them when the rows above arrive.
                addons.value == null || !personalReady -> TvSkeletonRow(6, HomePosterWidth, HomePosterWidth * 1.5f)
                // A filter that leaves nothing: said, with the way back to All.
                allRows.isNotEmpty() && rows.isEmpty() && shelves.continueWatching.isEmpty() -> TvBodyMessage(
                    "None of your catalogs list ${filter.label.lowercase()}.",
                    retryModifier = Modifier.defaultFocus(memory, "filter-all"),
                    onRetry = { filter = MediaTypeFilter.All },
                    actionLabel = "Show all",
                )
                rows.isEmpty() && shelves.continueWatching.isEmpty() -> TvBodyMessage(
                    "No catalogs to show. Add an addon that publishes them, such as Cinemeta, in Settings.",
                    retryModifier = Modifier.defaultFocus(memory, "settings"),
                    onRetry = navigation.openSettings,
                    actionLabel = "Open Settings",
                )
                else -> HomeRows(
                    graph = graph,
                    listState = listState,
                    rows = rows,
                    shelves = shelves,
                    catalogLanguage = catalogLanguage,
                    settingsReady = settingsReady,
                    memory = memory,
                    menus = menus,
                    navigation = navigation,
                    onFocusTitle = { focusedTitle = it },
                    libraryCount = library.value.orEmpty().count { !it.isRemoved },
                )
            }
        }
        // On the first row's heading line, ending where the hero's text does:
        // the hero leaves no room of its own, and the heading is short.
        AnimatedVisibility(
            visible = expanded && featuredPosition != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .offset { IntOffset(0, heroHeight.value.roundToPx()) }
                .padding(start = TvMetrics.ContentStart)
                .width(HeroTextWidth)
                .height(with(LocalDensity.current) { TvType.Heading.lineHeight.toDp() }),
            label = "tv-featured-dots",
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
                featuredPosition?.let { (index, count) -> FeaturedDots(index, count) }
            }
        }
    }
}

@Composable
private fun HeroBackdrop(graph: SessionGraph, meta: MetaCard, heroHeight: () -> Dp) {
    val full by remember(graph, meta.type, meta.id) { graph.browse.meta(meta.type, meta.id) }.collectAsState(QueryState())
    val art = (full.value?.background ?: meta.background ?: full.value?.poster ?: meta.poster)?.takeUnless(::isStandInPosterLink)
    Box(Modifier.fillMaxWidth().heightOf { heroHeight() + 60.dp }) {
        Crossfade(targetState = art, animationSpec = tween(400), label = "tv-hero-art") { url ->
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(0.38f))
                HaloAsyncImage(
                    url = url,
                    contentDescription = null,
                    modifier = Modifier.weight(0.62f).fillMaxHeight(),
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                )
            }
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to HaloColors.Background,
                    0.4f to HaloColors.Background.copy(alpha = 0.92f),
                    0.72f to HaloColors.Background.copy(alpha = 0.25f),
                    1f to HaloColors.Background.copy(alpha = 0.1f),
                ),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.5f to HaloColors.Background.copy(alpha = 0f), 1f to HaloColors.Background),
            ),
        )
    }
}

@Composable
private fun HeroInfo(
    graph: SessionGraph,
    title: HeroTitle,
    expanded: Boolean,
    /** One of the featured titles taking turns, not a focused card. */
    featured: Boolean,
    memory: TvFocusMemory,
    inLibrary: Boolean,
    navigation: TvBrowseNavigation,
) {
    val meta = title.meta
    val full by remember(graph, meta.type, meta.id) { graph.browse.meta(meta.type, meta.id) }.collectAsState(QueryState())
    val card: MetaCard = full.value?.takeIf { it.id == meta.id } ?: meta
    val resume = title.resume
    val kind = if (meta.type == SeriesType) "SERIES" else "MOVIE"
    val overline = when {
        resume?.upNext == true -> "$kind · UP NEXT"
        resume != null -> "$kind · CONTINUE WATCHING"
        featured -> "FEATURED"
        else -> kind
    }

    Column(
        Modifier
            .padding(start = TvMetrics.ContentStart, top = TvMetrics.VerticalMargin, end = TvMetrics.SideMargin)
            .width(HeroTextWidth),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(overline, style = TvType.Overline)
        Text(card.name, style = TvType.Hero, maxLines = 1, overflow = TextOverflow.Ellipsis)
        TvMetaLine(card)
        AnimatedVisibility(visible = expanded, label = "tv-hero-actions") {
            HeroActions(graph, title, card, memory, inLibrary, navigation)
        }
    }
}

/** Which featured title is showing: the current one is a longer, brighter dash. */
@Composable
private fun FeaturedDots(index: Int, count: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { i ->
            val current = i == index
            val width by animateDpAsState(if (current) 18.dp else 6.dp, tween(HeroResizeMs), label = "tv-featured-dot")
            Box(
                Modifier
                    .size(width, 6.dp)
                    .background(if (current) HaloColors.Text else HaloColors.TextMeta.copy(alpha = 0.5f), CircleShape),
            )
        }
    }
}

/** The full hero's description and buttons, which the compact hero leaves out. */
@Composable
private fun HeroActions(
    graph: SessionGraph,
    title: HeroTitle,
    card: MetaCard,
    memory: TvFocusMemory,
    inLibrary: Boolean,
    navigation: TvBrowseNavigation,
) {
    val meta = title.meta
    val resume = title.resume
    val tag = resume?.let { episodeTagOf(it.videoId, meta.id) }
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            card.description.orEmpty(),
            style = TvType.Body.copy(fontSize = TvType.Meta.fontSize, lineHeight = TvType.Meta.lineHeight),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val (label, action) = when {
                // The next episode of a finished one was never started: it plays.
                resume != null -> (if (resume.upNext) "Play" else "Resume").let { verb -> if (tag != null) "$verb $tag" else verb } to {
                    navigation.openSources(resumeRoute(meta.type, meta.id, resume.videoId, meta.name, meta.poster))
                }
                meta.type == MovieType && card.playableFromPreview -> "Play" to { navigation.playMovie(card) }
                meta.type == MovieType -> "Play" to { navigation.openDetail(MetaRef(meta.type, meta.id)) }
                else -> "Episodes" to { navigation.openDetail(MetaRef(meta.type, meta.id)) }
            }
            TvButton(
                label,
                onClick = action,
                icon = HaloIcons.Play,
                style = TvButtonStyle.Primary,
                modifier = Modifier.defaultFocus(memory, "hero-play"),
            )
            TvButton("Details", onClick = { navigation.openDetail(MetaRef(meta.type, meta.id)) }, modifier = Modifier.rememberFocus(memory, "hero-details"))
            TvButton(
                if (inLibrary) "In library" else "Add to library",
                icon = if (inLibrary) HaloIcons.Check else HaloIcons.BookmarkOutline,
                onClick = {
                    scope.launch {
                        // A rejected write marks the cache stale, which corrects the button.
                        runCatching {
                            if (inLibrary) graph.library.remove(LibraryRepository.itemId(card.type, card.id)) else graph.library.add(card)
                        }
                    }
                },
                modifier = Modifier.rememberFocus(memory, "hero-library"),
            )
        }
    }
}

@Composable
private fun HomeRows(
    graph: SessionGraph,
    listState: LazyListState,
    rows: List<CatalogRowSpec>,
    shelves: HomeShelves,
    catalogLanguage: String?,
    settingsReady: Boolean,
    memory: TvFocusMemory,
    menus: TitleMenus,
    navigation: TvBrowseNavigation,
    onFocusTitle: (HeroTitle) -> Unit,
    libraryCount: Int,
) {
    // Every focused row moves to the top, its card just below its heading, so
    // nothing of the row before (its posters, its captions) shows under the
    // hero. Focus within a row leaves it where it is: the card is already there.
    ProvideVerticalPivot(pivot = tvRowHeadingRoom(), stayWhenVisible = false) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = TvMetrics.VerticalMargin + 80.dp),
            verticalArrangement = Arrangement.spacedBy(TvMetrics.RowGap),
        ) {
            if (shelves.continueWatching.isNotEmpty()) {
                item(key = "continue") {
                    TvRow(title = "Continue watching") {
                        items(shelves.continueWatching, key = { it.itemId }) { entry ->
                            ContinueCard(
                                graph = graph,
                                entry = entry,
                                modifier = Modifier.rememberFocus(memory, "continue/${entry.itemId}"),
                                onClick = {
                                    navigation.openSources(
                                        resumeRoute(entry.meta.type, entry.meta.id, entry.videoId, entry.meta.name, entry.meta.poster),
                                    )
                                },
                                onMenu = { menus.openContinue(entry) },
                                onFocus = { onFocusTitle(HeroTitle(entry.meta, entry)) },
                            )
                        }
                        item(key = "see-all") {
                            TvSeeAllCard(
                                onClick = {
                                    navigation.openShelf(ShelfSnapshot("Continue watching", null, shelves.continueWatching.map { it.meta }))
                                },
                                width = TvMetrics.WideCardWidth,
                                height = TvMetrics.WideCardHeight,
                            )
                        }
                    }
                }
            }
            if (shelves.recentlyWatched.isNotEmpty()) {
                item(key = "recent") {
                    PosterRow(
                        key = "recent",
                        title = "Recently watched",
                        metas = shelves.recentlyWatched,
                        memory = memory,
                        menus = menus,
                        navigation = navigation,
                        onFocusTitle = onFocusTitle,
                        onSeeAll = { navigation.openLibrary(true) },
                    )
                }
            }
            if (shelves.library.isNotEmpty()) {
                item(key = "library") {
                    PosterRow(
                        key = "library",
                        title = "My library · $libraryCount",
                        metas = shelves.library,
                        memory = memory,
                        menus = menus,
                        navigation = navigation,
                        onFocusTitle = onFocusTitle,
                        onSeeAll = { navigation.openLibrary(false) },
                    )
                }
            }
            items(rows, key = { it.key }) { spec ->
                CatalogRow(
                    graph = graph,
                    spec = spec,
                    catalogLanguage = catalogLanguage,
                    ready = spec.languageChoices == null || settingsReady,
                    memory = memory,
                    menus = menus,
                    navigation = navigation,
                    onFocusTitle = onFocusTitle,
                )
            }
        }
    }
}

@Composable
private fun CatalogRow(
    graph: SessionGraph,
    spec: CatalogRowSpec,
    catalogLanguage: String?,
    ready: Boolean,
    memory: TvFocusMemory,
    menus: TitleMenus,
    navigation: TvBrowseNavigation,
    onFocusTitle: (HeroTitle) -> Unit,
) {
    val extra = remember(spec, catalogLanguage) { catalogExtra(spec.languageChoices, catalogLanguage) }
    val state by remember(graph, spec.key, extra, ready) {
        graph.browse.catalogPage(spec.addonId, spec.type, spec.catalogId, enabled = ready, extra = extra)
    }.collectAsState(QueryState())
    val page = state.value
    // A catalog that fails or comes back empty leaves Home, as on the phone.
    if (page == null && state.error != null) return
    if (page != null && page.metas.isEmpty()) return
    if (page == null) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(spec.title, style = TvType.Heading, modifier = Modifier.padding(start = TvMetrics.ContentStart))
            TvSkeletonRow(7, HomePosterWidth, HomePosterWidth * 1.5f)
        }
        return
    }
    PosterRow(
        key = spec.key,
        title = spec.title,
        metas = page.metas,
        memory = memory,
        menus = menus,
        navigation = navigation,
        onFocusTitle = onFocusTitle,
        onSeeAll = {
            // A catalog that pages carries where its next page starts, so the
            // grid can go on past what the row holds.
            val paging = page.takeIf { spec.supportsSkip }?.let {
                ShelfPaging(CatalogPageSource(spec.addonId, spec.type, spec.catalogId, extra), it.receivedCount)
            }
            navigation.openShelf(ShelfSnapshot(spec.title, spec.source, page.metas, paging))
        },
    )
}

@Composable
private fun PosterRow(
    key: String,
    title: String,
    metas: List<MetaCard>,
    memory: TvFocusMemory,
    menus: TitleMenus,
    navigation: TvBrowseNavigation,
    onFocusTitle: (HeroTitle) -> Unit,
    onSeeAll: () -> Unit,
) {
    TvRow(title = title) {
        // Short enough that See all is a few presses away; it opens the rest.
        items(metas.take(PosterRowLimit), key = { "${it.type}:${it.id}" }) { meta ->
            TvPosterCard(
                title = meta.name,
                type = meta.type,
                posterUrl = meta.poster,
                fallbackUrl = metahubPosterUrl(meta.id),
                width = HomePosterWidth,
                onClick = { navigation.openDetail(MetaRef(meta.type, meta.id)) },
                onMenu = { menus.openPoster(meta) },
                onFocusChange = { if (it) onFocusTitle(HeroTitle(meta)) },
                modifier = Modifier.rememberFocus(memory, "$key/${meta.type}:${meta.id}"),
            )
        }
        item(key = "see-all") {
            TvSeeAllCard(onClick = onSeeAll, width = HomePosterWidth, height = HomePosterWidth * 1.5f)
        }
    }
}

@Composable
private fun ContinueCard(
    graph: SessionGraph,
    entry: ContinueEntry,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The full record names the episode's still; the card shows the show's own
    // wide art until it arrives, and its poster when there is neither.
    val metaState by remember(graph, entry.meta.type, entry.meta.id) {
        graph.browse.meta(type = entry.meta.type, metaId = entry.meta.id)
    }.collectAsState(QueryState())
    val meta = metaState.value
    val still = meta?.videos?.firstOrNull { it.id == entry.videoId }?.thumbnail
        ?: (meta?.background ?: entry.meta.background ?: meta?.poster ?: entry.meta.poster)?.takeUnless(::isStandInPosterLink)
    val tag = episodeTagOf(entry.videoId, entry.meta.id)
    TvWideCard(
        title = entry.meta.name,
        imageUrl = still,
        // A film gets no line of its own: the hero above already says what it is.
        line = tag,
        detail = if (entry.upNext) "Next episode" else "${timeLeft(0.0, entry.remainingSec).lowercase()} left",
        progress = entry.progress,
        onClick = onClick,
        onMenu = onMenu,
        onFocusChange = { if (it) onFocus() },
        modifier = modifier,
    )
}

private const val HeroFollowMs = 350L
private const val HeroResizeMs = 250
private const val FeaturedCount = 5

/** Titles in a Home poster row before its See all card: about two screens across. */
private const val PosterRowLimit = 12

/** How long each featured title stays; longer than the phone's, read from across a room. */
private const val FeaturedDwellMs = 8_000L
