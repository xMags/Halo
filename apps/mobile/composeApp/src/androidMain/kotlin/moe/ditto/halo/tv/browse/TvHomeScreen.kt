package moe.ditto.halo.tv.browse

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.ditto.halo.SessionGraph
import moe.ditto.halo.api.MetaCard
import moe.ditto.halo.api.playableFromPreview
import moe.ditto.halo.browse.CatalogPageSource
import moe.ditto.halo.browse.CatalogRowSpec
import moe.ditto.halo.browse.ContinueEntry
import moe.ditto.halo.browse.HomeShelves
import moe.ditto.halo.browse.catalogExtra
import moe.ditto.halo.browse.catalogRows
import moe.ditto.halo.browse.homeShelves
import moe.ditto.halo.cache.QueryState
import moe.ditto.halo.screens.MetaRef
import moe.ditto.halo.screens.ShelfPaging
import moe.ditto.halo.screens.ShelfSnapshot
import moe.ditto.halo.screens.TitleMenus
import moe.ditto.halo.screens.episodeTagOf
import moe.ditto.halo.screens.resumeRoute
import moe.ditto.halo.screens.timeLeft
import moe.ditto.halo.sync.LibraryRepository
import moe.ditto.halo.tv.kit.TvButton
import moe.ditto.halo.tv.kit.TvButtonStyle
import moe.ditto.halo.tv.kit.TvFocusMemory
import moe.ditto.halo.tv.kit.TvMetrics
import moe.ditto.halo.tv.kit.TvPosterCard
import moe.ditto.halo.tv.kit.TvRow
import moe.ditto.halo.tv.kit.TvType
import moe.ditto.halo.tv.kit.TvWideCard
import moe.ditto.halo.tv.kit.defaultFocus
import moe.ditto.halo.tv.kit.rememberFocus
import moe.ditto.halo.tv.kit.rememberTvFocusMemory
import moe.ditto.halo.ui.HaloAsyncImage
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloIcons
import moe.ditto.halo.ui.metahubPosterUrl

/** What the hero shows: a title, and the video to resume when it came from Continue watching. */
private data class HeroTitle(val meta: MetaCard, val resume: ContinueEntry? = null)

private val HomePosterWidth = 112.dp
private val HeroHeight = 262.dp

/**
 * TV Home: a hero for the focused title at the top, then the personal rows
 * (Continue watching, Recently watched, My library) and one row per catalog.
 * The hero follows the focused card after a short pause, and its buttons act
 * on whatever it shows. Built on the same row and shelf rules as the phone
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

    val rows = remember(addons.value) { catalogRows(addons.value.orEmpty()) }
    val personalReady = (watchStates.value != null || watchStates.error != null) &&
        (library.value != null || library.error != null)
    val shelves = remember(watchStates.value, library.value) { homeShelves(watchStates.value, library.value, null) }

    // Until a card has focus, the hero shows the first catalog's lead title
    // with wide art, as the phone's featured banner does.
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
        leadCatalog.value.orEmpty().sortedBy { it.background.isNullOrBlank() }.firstOrNull()
    }
    var focusedTitle by remember { mutableStateOf<HeroTitle?>(null) }
    var hero by remember { mutableStateOf<HeroTitle?>(null) }
    // A short pause, so moving across a row does not flicker the hero.
    LaunchedEffect(focusedTitle) {
        val next = focusedTitle ?: return@LaunchedEffect
        delay(HeroFollowMs)
        hero = next
    }
    val shown = hero ?: shelves.continueWatching.firstOrNull()?.let { HeroTitle(it.meta, it) } ?: featured?.let { HeroTitle(it) }

    Box(Modifier.fillMaxSize()) {
        shown?.let { HeroBackdrop(graph, it.meta) }
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(HeroHeight)) {
                shown?.let { title ->
                    HeroInfo(
                        graph = graph,
                        title = title,
                        memory = memory,
                        inLibrary = library.value.orEmpty().any {
                            it.id == LibraryRepository.itemId(title.meta.type, title.meta.id) && !it.isRemoved
                        },
                        navigation = navigation,
                    )
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
                rows.isEmpty() && shelves.continueWatching.isEmpty() -> TvBodyMessage(
                    "No catalogs to show. Add an addon that publishes them, such as Cinemeta, in Settings.",
                    retryModifier = Modifier.defaultFocus(memory, "settings"),
                    onRetry = navigation.openSettings,
                )
                else -> HomeRows(
                    graph = graph,
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
    }
}

@Composable
private fun HeroBackdrop(graph: SessionGraph, meta: MetaCard) {
    val full by remember(graph, meta.type, meta.id) { graph.browse.meta(meta.type, meta.id) }.collectAsState(QueryState())
    val art = full.value?.background ?: meta.background ?: full.value?.poster ?: meta.poster
    Box(Modifier.fillMaxWidth().height(HeroHeight + 60.dp)) {
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
    memory: TvFocusMemory,
    inLibrary: Boolean,
    navigation: TvBrowseNavigation,
) {
    val meta = title.meta
    val full by remember(graph, meta.type, meta.id) { graph.browse.meta(meta.type, meta.id) }.collectAsState(QueryState())
    val card: MetaCard = full.value?.takeIf { it.id == meta.id } ?: meta
    val scope = rememberCoroutineScope()
    val resume = title.resume
    val tag = resume?.let { episodeTagOf(it.videoId, meta.id) }
    val kind = if (meta.type == SeriesType) "SERIES" else "MOVIE"

    Column(
        Modifier
            .padding(start = TvMetrics.ContentStart, top = TvMetrics.VerticalMargin, end = TvMetrics.SideMargin)
            .width(470.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            if (resume != null) "$kind · CONTINUE WATCHING" else kind,
            style = TvType.Overline,
        )
        Text(card.name, style = TvType.Hero, maxLines = 1, overflow = TextOverflow.Ellipsis)
        TvMetaLine(card)
        Text(
            card.description.orEmpty(),
            style = TvType.Body.copy(fontSize = TvType.Meta.fontSize, lineHeight = TvType.Meta.lineHeight),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val (label, action) = when {
                resume != null -> (if (tag != null) "Resume $tag" else "Resume") to {
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
    val listState = rememberLazyListState()
    // The focused row's heading at the top of the list: any more room there
    // shows the end of the row above (its captions) under the hero.
    ProvideVerticalPivot(pivot = 4.dp) {
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
        items(metas, key = { "${it.type}:${it.id}" }) { meta ->
            TvPosterCard(
                title = meta.name,
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
        ?: meta?.background ?: entry.meta.background ?: meta?.poster ?: entry.meta.poster
    val tag = episodeTagOf(entry.videoId, entry.meta.id)
    TvWideCard(
        title = entry.meta.name,
        imageUrl = still,
        line = tag ?: "Movie",
        detail = "${timeLeft(0.0, entry.remainingSec).lowercase()} left",
        progress = entry.progress,
        onClick = onClick,
        onMenu = onMenu,
        onFocusChange = { if (it) onFocus() },
        modifier = modifier,
    )
}

private const val HeroFollowMs = 350L
