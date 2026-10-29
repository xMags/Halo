package moe.ditto.halo.screens

import org.jetbrains.compose.resources.painterResource
import moe.ditto.halo.resources.halo_mark
import moe.ditto.halo.resources.Res
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.ditto.halo.SessionGraph
import moe.ditto.halo.api.MetaCard
import moe.ditto.halo.api.MetaPreview
import moe.ditto.halo.api.playableFromPreview
import moe.ditto.halo.browse.CatalogRowSpec
import moe.ditto.halo.browse.ContinueEntry
import moe.ditto.halo.browse.catalogExtra
import moe.ditto.halo.browse.catalogRows
import moe.ditto.halo.browse.homeShelves
import moe.ditto.halo.browse.matchesTypeFilter
import moe.ditto.halo.cache.QueryState
import moe.ditto.halo.sync.LibraryRepository
import moe.ditto.halo.ui.CatalogRow
import moe.ditto.halo.ui.CenterMessage
import moe.ditto.halo.ui.HaloAsyncImage
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloIcons
import moe.ditto.halo.ui.HaloRadius
import moe.ditto.halo.ui.HaloSpacing
import moe.ditto.halo.ui.HeroScrim
import moe.ditto.halo.ui.MetaLine
import moe.ditto.halo.ui.PosterItem
import moe.ditto.halo.ui.longPressClickable
import moe.ditto.halo.ui.rememberResponsive

/**
 * Home: a featured title, the three personal shelves, then one row per
 * browsable catalog.
 *
 * The header stays mounted through every state, including the failure one. It
 * owns no server data, so there is nothing about it that a failed fetch makes
 * wrong — and losing the search field because a catalog request timed out is a
 * worse outcome than an empty body.
 */
@Composable
internal fun HomeScreen(
    graph: SessionGraph,
    onOpenSearch: () -> Unit,
    onOpenDetail: (MetaRef) -> Unit,
    /** Movies play straight from the featured card; series open their detail screen instead. */
    onPlayMovie: (MetaCard) -> Unit,
    /** A row's "See all": the grid of everything it holds. */
    onOpenShelf: (ShelfSnapshot) -> Unit,
    /** Recently watched's "See all": the whole history, in the Library tab. */
    onOpenHistory: () -> Unit,
    /** My library's "See all": the Library tab, which is that grid with its filter. */
    onOpenLibrary: () -> Unit,
    /** The account picture beside the title: the Settings tab. */
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val responsive = rememberResponsive()
    // Continue Watching deliberately ignores this — see homeShelves — while
    // every other shelf and every catalog row honours it.
    var filter by remember { mutableStateOf(MediaTypeFilter.All) }

    val addons by remember(graph) { graph.addons.observeEffective() }.collectAsState(QueryState())
    val watchStates by remember(graph) { graph.watchStates.observe() }.collectAsState(QueryState())
    // Tombstones included: the shelf derivation filters them itself, because it
    // also has to keep a removed entry from naming a watch-state row.
    val library by remember(graph) { graph.library.observe() }.collectAsState(QueryState())
    // Only for the language rows' language. Until it is known (or known not to
    // load) those rows wait, rather than load in the default language first.
    val settings by remember(graph) { graph.settings.observe() }.collectAsState(QueryState())
    val settingsReady = settings.value != null || settings.error != null
    val catalogLanguage = settings.value?.catalogLanguage

    val allRows = remember(addons.value) { catalogRows(addons.value.orEmpty()) }
    val rows = remember(allRows, filter) {
        allRows.filter { matchesTypeFilter(filter.type, it.type) }
    }
    val shelves = remember(watchStates.value, library.value, filter) {
        homeShelves(watchStates.value, library.value, filter.type)
    }

    // The featured titles are the head of the first visible row, then the full
    // meta of whichever is showing, for wide art and a rating. That catalog
    // request shares a cache key with the row below, so the strip costs one
    // fetch, and the meta costs one more per title actually reached.
    val lead = rows.firstOrNull()
    val leadCatalog by remember(graph, lead?.key) {
        graph.browse.catalog(
            addonId = lead?.addonId.orEmpty(),
            type = lead?.type.orEmpty(),
            catalogId = lead?.catalogId.orEmpty(),
            enabled = lead != null,
        )
    }.collectAsState(QueryState())
    // Keep the featured carousel consistent across phone and tablet. A single
    // fixed card wastes the hero space and hides the rest of the lead catalog.
    val featuredCount = FeaturedCount
    val previews = remember(leadCatalog.value, featuredCount) {
        leadCatalog.value.orEmpty().take(featuredCount)
    }
    var featuredIndex by remember(previews) { mutableStateOf(0) }
    // Picking a card is a decision to look at it, so the rotation stops rather
    // than moving on four seconds later.
    var autoAdvance by remember(previews) { mutableStateOf(true) }
    if (previews.size > 1 && autoAdvance) {
        LaunchedEffect(previews, featuredIndex) {
            delay(FeaturedDwellMs)
            featuredIndex = (featuredIndex + 1) % previews.size
        }
    }
    val preview = previews.getOrNull(featuredIndex)
    val featuredMeta by remember(graph, preview?.type, preview?.id) {
        graph.browse.meta(
            type = preview?.type.orEmpty(),
            metaId = preview?.id.orEmpty(),
            enabled = preview != null,
        )
    }.collectAsState(QueryState())
    // The preview carries a name and a poster already, so it stands in until the
    // richer record lands rather than leaving the hero blank.
    val featured: MetaCard? = featuredMeta.value?.takeIf { it.id == preview?.id } ?: preview

    val gutter = responsive.gutter
    val rowGap = responsive.pick(HaloSpacing.Lg, 26.dp)
    // A tablet held sideways gets the desktop's banner and Continue watching.
    val wide = usesWideHome(responsive)
    val scope = rememberCoroutineScope()
    val featuredItemId = featured?.let { LibraryRepository.itemId(it.type, it.id) }
    val featuredInLibrary = library.value.orEmpty().any { it.id == featuredItemId && !it.isRemoved }
    // Each catalog row's last result, by row key, for as long as Home is up.
    // The list disposes a row once it scrolls away, and the cache only hands
    // the result back a coroutine hop after the row reappears, so without this
    // a returning row spends a frame as a full-height skeleton before an empty
    // or failed catalog collapses it. Scrolling back up crossed one such row
    // after another, and their vanishing height ate the gesture: the list would
    // barely move towards the top. Read and written only on the main thread.
    val knownCatalogs = remember(graph) { HashMap<String, List<MetaPreview>>() }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        // The header owns the status-bar inset; what has to be allowed for here
        // is the chrome that floats over this screen — the navigation rail on
        // the leading edge, or the tab bar at the bottom. Carried by the list
        // rather than by each block, so the gutter every block applies stays the
        // symmetric one the layout is drawn with.
        contentPadding = PaddingValues(
            start = responsive.contentInsetStart,
            bottom = responsive.bottomContentPadding,
        ),
    ) {
        item(key = "header") {
            ScreenHeader(
                // Home is the app's front page, so it carries the brand rather
                // than its tab's name, as the desktop's title bar does.
                title = "Halo",
                modifier = Modifier.padding(horizontal = gutter),
                onOpenSearch = onOpenSearch,
                filter = filter,
                onFilterChange = { filter = it },
                leading = { HomeHaloMark() },
                // Where there is a navigation rail the account sits at its
                // foot, as on the desktop, rather than here.
                trailing = if (responsive.usesNavigationRail) null else { { AccountAvatar(graph, onClick = onOpenSettings) } },
            )
        }

        // Body states. A failure with nothing cached is the only one that
        // replaces the content; a failure with a cached list keeps rendering it
        // and revalidates, which is what the cache retains values for.
        if (addons.value == null) {
            item(key = "body-state") {
                Box(Modifier.fillMaxWidth().height(BodyStateHeight), contentAlignment = Alignment.Center) {
                    when {
                        addons.error != null -> CenterMessage("Could not reach your Halo server.")
                        else -> CircularProgressIndicator(color = HaloColors.Accent)
                    }
                }
            }
            return@LazyColumn
        }
        if (allRows.isEmpty()) {
            item(key = "body-state") {
                Box(Modifier.fillMaxWidth().height(BodyStateHeight), contentAlignment = Alignment.Center) {
                    CenterMessage("No browsable catalogs. Add an addon that publishes them (Cinemeta) in Settings.")
                }
            }
            return@LazyColumn
        }

        featured?.let { card ->
            item(key = "featured") {
                val select = { picked: Int ->
                    featuredIndex = picked
                    autoAdvance = false
                }
                val open = { onOpenDetail(MetaRef(card.type, card.id)) }
                val play = {
                    // A TMDB-listed film names its playable id only in its
                    // full meta, so its title page is the way in.
                    if (card.type == MovieType && card.playableFromPreview) onPlayMovie(card) else open()
                }
                if (wide) {
                    WideFeaturedHero(
                        card = card,
                        height = wideFeaturedHeight(responsive),
                        gutter = gutter,
                        inLibrary = featuredInLibrary,
                        count = previews.size,
                        index = featuredIndex,
                        onSelect = select,
                        onOpen = open,
                        onPlay = play,
                        onToggleLibrary = {
                            scope.launch {
                                // A rejected write marks the cache stale, which corrects the button.
                                runCatching {
                                    if (featuredInLibrary) graph.library.remove(LibraryRepository.itemId(card.type, card.id))
                                    else graph.library.add(card)
                                }
                            }
                        },
                        modifier = Modifier.padding(bottom = HaloSpacing.Lg),
                    )
                    return@item
                }
                FeaturedHero(
                    card = card,
                    height = responsive.homeHeroHeight,
                    gutter = gutter,
                    inset = responsive.pick(HaloSpacing.Md, FeaturedInsetTablet),
                    titleSize = responsive.pick(24.sp, 30.sp),
                    // One card needs no picker; the dots are how a rotating
                    // hero says how many there are and which one this is.
                    count = previews.size,
                    index = featuredIndex,
                    onSelect = {
                        featuredIndex = it
                        autoAdvance = false
                    },
                    onOpen = { onOpenDetail(MetaRef(card.type, card.id)) },
                    onPlay = {
                        // A TMDB-listed film names its playable id only in its
                        // full meta, so its title page is the way in.
                        if (card.type == MovieType && card.playableFromPreview) {
                            onPlayMovie(card)
                        } else {
                            onOpenDetail(MetaRef(card.type, card.id))
                        }
                    },
                )
            }
        }

        if (shelves.continueWatching.isNotEmpty()) {
            item(key = "shelf-continue") {
                if (wide) {
                    WideContinueRow(
                        graph = graph,
                        entries = shelves.continueWatching,
                        onOpen = { onOpenDetail(MetaRef(it.meta.type, it.meta.id)) },
                        onSeeAll = {
                            onOpenShelf(ShelfSnapshot("Continue watching", null, shelves.continueWatching.map { it.meta }))
                        },
                        gutter = gutter,
                        gap = responsive.catalogRowGap,
                        bottomPadding = rowGap,
                    )
                    return@item
                }
                ContinueWatchingRow(
                    graph = graph,
                    entries = shelves.continueWatching,
                    onItemClick = { onOpenDetail(it.metaRef()) },
                    onSeeAll = onOpenShelf,
                    posterWidth = responsive.shelfPosterWidth,
                    gutter = gutter,
                    gap = responsive.catalogRowGap,
                    bottomPadding = rowGap,
                )
            }
        }
        if (shelves.recentlyWatched.isNotEmpty()) {
            item(key = "shelf-recent") {
                CatalogRow(
                    title = "Recently watched",
                    items = shelves.recentlyWatched.map { it.posterItem() },
                    onItemClick = { onOpenDetail(it.metaRef()) },
                    itemMenu = posterMenu(shelves.recentlyWatched),
                    onSeeAll = onOpenHistory,
                    insetStrip = wide,
                    posterWidth = responsive.shelfPosterWidth,
                    gutter = gutter,
                    gap = responsive.catalogRowGap,
                    bottomPadding = rowGap,
                )
            }
        }
        if (shelves.library.isNotEmpty()) {
            item(key = "shelf-library") {
                CatalogRow(
                    title = "My library",
                    // Counts the whole library; the filter only narrows the
                    // posters. Without an account it is kept on this phone.
                    source = "${if (graph.signedIn) "SYNCED" else "ON THIS PHONE"} · " +
                        library.value.orEmpty().count { !it.isRemoved },
                    items = shelves.library.map { it.posterItem() },
                    onItemClick = { onOpenDetail(it.metaRef()) },
                    itemMenu = posterMenu(shelves.library),
                    onSeeAll = onOpenLibrary,
                    insetStrip = wide,
                    posterWidth = responsive.shelfPosterWidth,
                    gutter = gutter,
                    gap = responsive.catalogRowGap,
                    bottomPadding = rowGap,
                )
            }
        }

        items(items = rows, key = { it.key }) { spec ->
            val extra = remember(spec, catalogLanguage) { catalogExtra(spec.languageChoices, catalogLanguage) }
            // A language row's earlier result belongs to the language it was asked in.
            val knownKey = if (extra.isEmpty()) spec.key else "${spec.key}|$extra"
            HomeCatalogRow(
                graph = graph,
                spec = spec,
                extra = extra,
                ready = spec.languageChoices == null || settingsReady,
                // The same size as the personal shelves above, so every row
                // on Home reads as one set.
                posterWidth = responsive.shelfPosterWidth,
                gutter = gutter,
                gap = responsive.catalogRowGap,
                bottomPadding = rowGap,
                known = knownCatalogs[knownKey],
                onKnown = { knownCatalogs[knownKey] = it },
                insetStrip = wide,
                onOpenDetail = onOpenDetail,
                onOpenShelf = onOpenShelf,
            )
        }
    }
}

/**
 * Continue Watching can contain rows written before a player had title art in
 * its route. Resolve only those missing posters here, so old rows recover on
 * Home without adding a metadata request for healthy entries.
 */
@Composable
private fun ContinueWatchingRow(
    graph: SessionGraph,
    entries: List<ContinueEntry>,
    onItemClick: (PosterItem) -> Unit,
    onSeeAll: (ShelfSnapshot) -> Unit,
    posterWidth: Dp,
    gutter: Dp,
    gap: Dp,
    bottomPadding: Dp,
) {
    val items = buildList {
        entries.forEach { entry ->
            key(entry.itemId) {
                val metaState by remember(graph, entry.meta.type, entry.meta.id, entry.meta.poster) {
                    graph.browse.meta(
                        type = entry.meta.type,
                        metaId = entry.meta.id,
                        enabled = entry.meta.poster == null,
                    )
                }.collectAsState(QueryState())
                val poster = entry.meta.poster ?: metaState.value?.poster
                add(entry.meta.copy(poster = poster).posterItem(progress = entry.progress))
            }
        }
    }
    val menus = LocalTitleMenus.current
    val source = "${entries.size} IN PROGRESS"
    CatalogRow(
        title = "Continue watching",
        source = source,
        // The cards as shown, posters recovered here included. No source line:
        // the grid's own count already says how many are in progress.
        onSeeAll = {
            onSeeAll(ShelfSnapshot("Continue watching", null, entries.map { it.meta }))
        },
        items = items,
        onItemClick = onItemClick,
        // A card's key is its item id, the same `type:metaId` form.
        itemMenu = menus?.let { m -> { item -> entries.firstOrNull { it.itemId == item.key }?.let(m::openContinue) } },
        posterWidth = posterWidth,
        gutter = gutter,
        gap = gap,
        bottomPadding = bottomPadding,
    )
}

private const val MovieType = "movie"

/** How many titles the hero rotates through, and for how long each. */
private const val FeaturedCount = 5
private const val FeaturedDwellMs = 5_000L

/** The hero's own inset, which grows with it. */
private val FeaturedInsetTablet = 26.dp

/** Tall enough that a spinner or message lands near the middle of the screen. */
private val BodyStateHeight = 420.dp

/**
 * One catalog's row, observing its own query.
 *
 * The row is what fetches, so a screen full of them costs one request per row
 * and only for rows that have been scrolled to — the lazy list never composes
 * the ones below the fold. A catalog that fails or comes back empty removes
 * itself; [CatalogRow] handles that, which is why the error is not surfaced
 * here.
 */
@Composable
private fun HomeCatalogRow(
    graph: SessionGraph,
    spec: CatalogRowSpec,
    /** What the catalog is asked with: a language row's chosen language, or nothing. */
    extra: Map<String, String>,
    /** False while a language row waits to learn its language. */
    ready: Boolean,
    posterWidth: Dp,
    gutter: Dp,
    gap: Dp,
    bottomPadding: Dp,
    /** This row's result from an earlier pass, so it reappears at its settled height. */
    known: List<MetaPreview>?,
    onKnown: (List<MetaPreview>) -> Unit,
    onOpenDetail: (MetaRef) -> Unit,
    onOpenShelf: (ShelfSnapshot) -> Unit,
    insetStrip: Boolean,
) {
    val state by remember(graph, spec.key, extra, ready) {
        graph.browse.catalog(spec.addonId, spec.type, spec.catalogId, enabled = ready, extra = extra)
    }.collectAsState(QueryState(value = known))
    LaunchedEffect(state.value) { state.value?.let(onKnown) }

    val metas = state.value
    CatalogRow(
        title = spec.title,
        source = spec.source,
        items = remember(metas) { metas.orEmpty().map { it.posterItem() } },
        onItemClick = { onOpenDetail(it.metaRef()) },
        itemMenu = posterMenu(metas.orEmpty()),
        onSeeAll = { onOpenShelf(ShelfSnapshot(spec.title, spec.source, metas.orEmpty())) },
        posterWidth = posterWidth,
        isLoading = metas == null && state.error == null,
        gutter = gutter,
        gap = gap,
        bottomPadding = bottomPadding,
        insetStrip = insetStrip,
    )
}

@Composable
private fun FeaturedHero(
    card: MetaCard,
    height: Dp,
    gutter: Dp,
    /** How far the title block sits from the art's own corner. */
    inset: Dp,
    titleSize: TextUnit,
    count: Int,
    index: Int,
    onSelect: (Int) -> Unit,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
) {
    val menus = LocalTitleMenus.current
    Box(
        Modifier
            .padding(start = gutter, end = gutter)
            .padding(bottom = HaloSpacing.Lg)
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(HaloRadius.Xl))
            .background(HaloColors.Surface)
            .longPressClickable(onClick = onOpen, role = null, onLongPress = menus?.let { { it.openPoster(card) } }),
    ) {
        // Wide art when the addon has it, the poster cropped otherwise — a
        // hero with no image at all reads as a broken layout.
        //
        // Crossfaded by card rather than swapped, because on the rotating hero
        // the art changes under a viewer who did not ask for it; a cut reads as
        // the screen reloading.
        //
        // fillMaxSize, NOT matchParentSize: with matchParentSize the art never
        // loaded on device — the hero stayed empty while every poster on the
        // same screen, same host, loaded fine. The box is already a fixed size,
        // so filling it needs no deferred measurement pass. The scrim below is
        // a plain gradient with nothing to fetch, so it can keep matching.
        FeaturedArt(card) { shown ->
            HaloAsyncImage(
                url = shown.background ?: shown.poster,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
            HeroScrim(Modifier.matchParentSize())
        }
        FeaturedText(card, Modifier.fillMaxSize()) { shown ->
            Box(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = inset, end = inset, bottom = inset - 2.dp, top = inset),
                ) {
                    Text(
                        text = shown.name,
                        color = HaloColors.Text,
                        fontSize = titleSize,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.2.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    MetaLine(
                        parts = listOfNotNull(shown.releaseInfo, shown.genres.firstOrNull()),
                        rating = shown.imdbRating,
                        modifier = Modifier.padding(top = HaloSpacing.Xs),
                    )
                    PlayButton(
                        // A series opens its title page to pick an episode, as
                        // on the desktop app, and says so.
                        label = if (shown.type == MovieType) "Play" else "Choose episode",
                        onClick = onPlay,
                        modifier = Modifier.padding(top = HaloSpacing.Sm + 2.dp),
                    )
                }
            }
        }

        if (count > 1) {
            FeaturedDots(
                count = count,
                index = index,
                onSelect = onSelect,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = inset, bottom = inset),
            )
        }
    }
}

/** How long the art takes to change under a viewer who did not ask it to. */
private const val FeaturedFadeMs = 400

/**
 * The featured art, crossfaded when the title changes and only then: keyed by
 * id, so the fuller record of the same title arriving does not fade it again.
 */
@Composable
internal fun FeaturedArt(card: MetaCard, content: @Composable BoxScope.(MetaCard) -> Unit) {
    AnimatedContent(
        targetState = card,
        contentKey = { it.id },
        transitionSpec = { fadeIn(tween(FeaturedFadeMs)) togetherWith fadeOut(tween(FeaturedFadeMs)) },
        label = "featured-art",
        modifier = Modifier.fillMaxSize(),
    ) { shown ->
        Box(Modifier.fillMaxSize()) { content(shown) }
    }
}

/**
 * The featured title's text. The old text leaves at once and the new one
 * rises in with its art, as on the desktop: crossfading the two put both
 * titles over each other, half drawn, for the length of the fade.
 */
@Composable
internal fun FeaturedText(card: MetaCard, modifier: Modifier, content: @Composable (MetaCard) -> Unit) {
    AnimatedContent(
        targetState = card,
        contentKey = { it.id },
        transitionSpec = {
            (fadeIn(tween(FeaturedFadeMs)) + slideInVertically(tween(FeaturedFadeMs)) { it / 40 }) togetherWith
                fadeOut(snap())
        },
        label = "featured-text",
        modifier = modifier,
    ) { shown ->
        content(shown)
    }
}

private val DotHeight = 4.dp
private val DotWidth = 8.dp
private val DotWidthActive = 22.dp

/** Which of the featured titles is showing, and a way to go straight to one. */
@Composable
internal fun FeaturedDots(count: Int, index: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { dot ->
            val active = dot == index
            val width by animateDpAsState(
                targetValue = if (active) DotWidthActive else DotWidth,
                animationSpec = tween(DotTransitionMs),
                label = "featured-dot",
            )
            Box(
                Modifier
                    // The target stays finger-sized while the mark stays small:
                    // a 4dp-tall dot is not something anyone can hit.
                    .clip(RoundedCornerShape(HaloRadius.Pill))
                    .selectable(
                        selected = active,
                        role = Role.Tab,
                        onClick = { onSelect(dot) },
                    )
                    .padding(vertical = HaloSpacing.Sm),
            ) {
                Box(
                    Modifier
                        .width(width)
                        .height(DotHeight)
                        .clip(RoundedCornerShape(HaloRadius.Pill))
                        .background(if (active) HaloColors.Text else DotIdleFill),
                )
            }
        }
    }
}

private const val DotTransitionMs = 200
private val DotIdleFill = Color.White.copy(alpha = 0.32f)

/**
 * Sits inside the hero's own clickable area. The inner press wins in Compose
 * exactly as it does in the shipping client, so Play plays and the surrounding
 * art opens the detail screen.
 */
@Composable
private fun PlayButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(HaloRadius.Pill))
            .background(HaloColors.Primary)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = HaloIcons.Play,
            // The label beside it already says what it does.
            contentDescription = null,
            tint = HaloColors.OnPrimary,
            modifier = Modifier.size(16.dp),
        )
        Text(text = label, color = HaloColors.OnPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

/** The Halo mark beside Home's title, sized to the large title's cap height. */
@Composable
private fun HomeHaloMark() {
    Image(
        painter = painterResource(Res.drawable.halo_mark),
        contentDescription = null,
        modifier = Modifier.size(HomeMarkSize),
        contentScale = ContentScale.Fit,
    )
}

private val HomeMarkSize = 40.dp
