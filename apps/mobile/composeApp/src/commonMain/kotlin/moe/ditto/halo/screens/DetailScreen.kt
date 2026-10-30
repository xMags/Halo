package moe.ditto.halo.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.launch
import moe.ditto.halo.SessionGraph
import moe.ditto.halo.api.MetaCard
import moe.ditto.halo.api.MetaDetail
import moe.ditto.halo.api.MetaPreview
import moe.ditto.halo.api.MetaVideo
import moe.ditto.halo.api.WatchState
import moe.ditto.halo.api.movieVideoId
import moe.ditto.halo.browse.aboutRows
import moe.ditto.halo.browse.creditsOf
import moe.ditto.halo.browse.episodePages
import moe.ditto.halo.browse.episodeTag
import moe.ditto.halo.browse.episodesIn
import moe.ditto.halo.browse.formatReleaseDate
import moe.ditto.halo.browse.formatRuntime
import moe.ditto.halo.browse.lastWatchedSeason
import moe.ditto.halo.browse.pageContaining
import moe.ditto.halo.browse.personSearchUrl
import moe.ditto.halo.browse.pickGenreCatalog
import moe.ditto.halo.browse.seasonLabel
import moe.ditto.halo.browse.seasonNumbers
import moe.ditto.halo.browse.summarizeAvailability
import moe.ditto.halo.browse.trailerUrl
import moe.ditto.halo.cache.QueryState
import moe.ditto.halo.downloads.DownloadStatus
import moe.ditto.halo.sync.LibraryRepository
import moe.ditto.halo.ui.CatalogRow
import moe.ditto.halo.ui.CenterMessage
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloIcons
import moe.ditto.halo.ui.HaloLayout
import moe.ditto.halo.ui.HaloRadius
import moe.ditto.halo.ui.HaloSpacing
import moe.ditto.halo.ui.LocalHazeState
import moe.ditto.halo.ui.ResponsiveInfo
import moe.ditto.halo.ui.SelectOption
import moe.ditto.halo.ui.SelectSheet
import moe.ditto.halo.ui.glassSource
import moe.ditto.halo.ui.rememberResponsive

/**
 * One title: its art with the name and facts over it, the way to play it (the
 * episode in progress for a series, with what the addons offer), the library,
 * download and trailer, the synopsis; then, for a series, its episodes under a
 * season picker (and a range picker for a very long season), for a film what
 * the addons offer by quality; then the cast, the facts and similar titles.
 * The desktop app's title page, laid out for a phone.
 *
 * This is the only screen that writes to the library from a button, because
 * it is the only one showing one title rather than a collection.
 */
@Composable
internal fun DetailScreen(
    graph: SessionGraph,
    type: String,
    metaId: String,
    onBack: () -> Unit,
    /** The movie's own sources; an episode's are [onPlayEpisode]. */
    onPlayMovie: (MetaCard) -> Unit,
    onPlayEpisode: (MetaDetail, MetaVideo) -> Unit,
    onOpenDetail: (MetaRef) -> Unit,
    /** "More like this"'s "See all": the grid of what the row holds. */
    onOpenShelf: (ShelfSnapshot) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Builds what a [SourcesRail] over this title needs, for the video passed —
     * null for a film, whose sources are the title's own. Supplying it lets a
     * tablet open the picker beside the title instead of pushing a screen that
     * replaces it; leaving it out keeps the pushed picker, which is all a phone
     * has room for.
     */
    sourcesRequest: ((MetaDetail, MetaVideo?) -> SourcesRequest)? = null,
) {
    val responsive = rememberResponsive()
    val scope = rememberCoroutineScope()
    val itemId = remember(type, metaId) { LibraryRepository.itemId(type, metaId) }

    // This screen's own backdrop, replacing the shell's for everything below.
    // The sheets frost what is behind them, and a blur cannot sample content
    // it belongs to — so the source has to be this screen's body, with the
    // sheets beside it rather than inside it.
    val bodyHaze = rememberHazeState()

    val metaState by remember(graph, type, metaId) { graph.browse.meta(type, metaId) }.collectAsState(QueryState())
    val library by remember(graph) { graph.library.observeActive() }.collectAsState(QueryState())
    val watchStates by remember(graph) { graph.watchStates.observe() }.collectAsState(QueryState())

    var chosenSeason by remember(metaId) { mutableStateOf<Int?>(null) }
    var seasonSheetOpen by remember { mutableStateOf(false) }
    var pageSheetOpen by remember { mutableStateOf(false) }
    // The video whose sources the rail is showing, on the layouts that have one.
    var railRequest by remember(metaId) { mutableStateOf<SourcesRequest?>(null) }
    val listState = rememberLazyListState()
    // Once the hero has scrolled away, a bar takes its place behind the back
    // button, so the page never runs under the status bar or the button.
    // It comes in as soon as what is left of the hero is shorter than the bar,
    // which is when the page starts running under the status bar.
    val density = LocalDensity.current
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    // A tablet held sideways gets the desktop's page, whose bar comes in once
    // the Play button near the hero's foot has gone under it.
    val wide = usesWideDetail(responsive)
    val barThreshold = if (wide) {
        wideDetailHeroHeight(responsive) - WidePlayButtonLift - statusBarTop - WideCompactBarHeight
    } else {
        responsive.detailHeroHeight - statusBarTop - CompactBarHeight
    }
    val barThresholdPx = with(density) { barThreshold.toPx() }
    val pageGutter = if (wide) wideDetailGutter(responsive) else responsive.gutter
    // What the wide page's compact bar plays, set while the title is shown.
    var compactPlay: PlayTarget? = null
    var compactPlayAction: () -> Unit = {}
    val heroScrolledAway by remember(listState, barThresholdPx) {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > barThresholdPx
        }
    }
    // Null wherever the picker is pushed instead: a phone, or a caller that
    // offered no way to build the request.
    val railFor = sourcesRequest.takeIf { responsive.isTablet }

    CompositionLocalProvider(LocalHazeState provides bodyHaze) {
        Box(modifier.fillMaxSize().background(HaloColors.Background)) {
            val meta = metaState.value
            when {
                meta != null -> {
                    val isSeries = meta.videos.isNotEmpty()
                    val seasons = remember(meta) { seasonNumbers(meta.videos) }
                    val openOn = remember(meta, watchStates.value, itemId) {
                        lastWatchedSeason(meta.videos, watchStates.value, itemId)
                    }
                    // Explicit choice first, then where the last episode was watched,
                    // then the earliest season — so a viewer mid-binge lands where
                    // they were rather than back at season one.
                    val activeSeason = chosenSeason ?: openOn ?: seasons.firstOrNull()
                    val episodes = remember(meta, activeSeason) { episodesIn(meta.videos, activeSeason) }
                    val progress = remember(watchStates.value) { watchStates.value.orEmpty().associateBy { it.videoId } }

                    // The episode actually left partway: what Resume targets and
                    // which row is highlighted. A film's own video, likewise.
                    val resumeState = remember(watchStates.value, meta, itemId) { resumeStateOf(meta, watchStates.value, itemId) }
                    val targetVideo = if (isSeries) {
                        meta.videos.firstOrNull { it.id == resumeState?.videoId } ?: episodes.firstOrNull()
                    } else {
                        null
                    }
                    val targetVideoId = targetVideo?.id ?: meta.movieVideoId

                    // What the addons offer for what Play would play, asked as the
                    // page opens so the line under the button can say it.
                    val streams by remember(graph, type, targetVideoId) {
                        graph.browse.streams(type, targetVideoId)
                    }.collectAsState(QueryState())
                    val availability = remember(streams.value) {
                        streams.value?.let { result -> summarizeAvailability(result.results.flatMap { it.streams }.filter { it.isPlayable }) }
                    }

                    val pages = remember(episodes) { episodePages(episodes) }
                    var page by remember(metaId, activeSeason) { mutableIntStateOf(pageContaining(pages, resumeState?.videoId)) }
                    val shownPage = page.coerceIn(0, (pages.size - 1).coerceAtLeast(0))

                    val inLibrary = library.value.orEmpty().any { it.id == itemId }
                    val toggleLibrary = {
                        scope.launch {
                            // Failures surface through the cache entry rather than
                            // here: the button reflects cached truth, and a rejected
                            // write marks the entry stale so the next read corrects it.
                            runCatching {
                                if (inLibrary) graph.library.remove(itemId) else graph.library.add(meta)
                            }
                        }
                        Unit
                    }

                    // One way in to a video's sources for both layouts: the rail
                    // where there is room beside the title, the pushed picker
                    // where there is not.
                    val openSources = { video: MetaVideo? ->
                        when {
                            railFor != null -> railRequest = railFor(meta, video)
                            video != null -> onPlayEpisode(meta, video)
                            else -> onPlayMovie(meta)
                        }
                    }

                    val downloaded = graph.downloads.entryFor(targetVideoId)?.status == DownloadStatus.Done
                    val addons by remember(graph) { graph.addons.observeEffective() }.collectAsState(QueryState())
                    val similarSource = remember(addons.value, meta) {
                        addons.value?.let { pickGenreCatalog(it, type, meta.genres) }
                    }

                    val playTarget = playTargetOf(isSeries, targetVideo, resumeState, progress[targetVideoId])
                    compactPlay = playTarget
                    compactPlayAction = { openSources(targetVideo) }
                    if (wide) {
                        DetailWideBody(
                            modifier = Modifier.glassSource(bodyHaze),
                            listState = listState,
                            graph = graph,
                            meta = meta,
                            isSeries = isSeries,
                            seasons = seasons,
                            activeSeason = activeSeason,
                            episodes = pages.getOrNull(shownPage)?.episodes ?: episodes,
                            episodeCount = episodes.size,
                            pageLabels = pages.map { it.label },
                            page = shownPage,
                            progress = progress,
                            resumeState = resumeState,
                            playTarget = playTarget,
                            availability = availability,
                            streamsLoading = streams.value == null && streams.error == null,
                            inLibrary = inLibrary,
                            downloaded = downloaded,
                            similarSource = similarSource,
                            responsive = responsive,
                            onPlay = { openSources(targetVideo) },
                            onToggleLibrary = toggleLibrary,
                            onOpenSeasons = { seasonSheetOpen = true },
                            onOpenPages = { pageSheetOpen = true },
                            onPlayEpisode = { openSources(it) },
                            onOpenDetail = onOpenDetail,
                            onOpenShelf = onOpenShelf,
                        )
                    } else DetailBody(
                        modifier = Modifier.glassSource(bodyHaze),
                        listState = listState,
                        graph = graph,
                        meta = meta,
                        isSeries = isSeries,
                        seasons = seasons,
                        activeSeason = activeSeason,
                        episodes = pages.getOrNull(shownPage)?.episodes ?: episodes,
                        episodeCount = episodes.size,
                        pageLabels = pages.map { it.label },
                        page = shownPage,
                        progress = progress,
                        resumeState = resumeState,
                        playTarget = playTarget,
                        availability = availability,
                        streamsLoading = streams.value == null && streams.error == null,
                        inLibrary = inLibrary,
                        downloaded = downloaded,
                        similarSource = similarSource,
                        responsive = responsive,
                        onPlay = { openSources(targetVideo) },
                        onToggleLibrary = toggleLibrary,
                        onOpenSeasons = { seasonSheetOpen = true },
                        onOpenPages = { pageSheetOpen = true },
                        onPage = { page = it },
                        onPlayEpisode = { openSources(it) },
                        onOpenDetail = onOpenDetail,
                        onOpenShelf = onOpenShelf,
                    )

                    // Last children of the root box, so they draw over the hero and
                    // the episode list instead of inside whichever one scrolls.
                    SelectSheet(
                        visible = seasonSheetOpen,
                        title = "Season",
                        options = seasons.map {
                            SelectOption(key = it.toString(), label = seasonLabel(it), selected = it == activeSeason)
                        },
                        onSelect = { key -> chosenSeason = key.toIntOrNull() },
                        onClose = { seasonSheetOpen = false },
                        // The tab bar draws over this screen's sheets.
                        bottomClearance = responsive.floatingBarClearance,
                    )
                    SelectSheet(
                        visible = pageSheetOpen,
                        title = "Episodes",
                        options = pages.mapIndexed { index, it ->
                            SelectOption(key = index.toString(), label = it.label, selected = index == shownPage)
                        },
                        onSelect = { key -> key.toIntOrNull()?.let { page = it } },
                        onClose = { pageSheetOpen = false },
                        bottomClearance = responsive.floatingBarClearance,
                    )
                }
                metaState.error != null -> CenterMessage("No installed addon could describe this title.")
                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = HaloColors.Accent)
                }
            }

            if (wide) {
                WideCompactBar(
                    visible = heroScrolledAway,
                    title = metaState.value?.name.orEmpty(),
                    playTarget = compactPlay,
                    titleStart = responsive.contentInsetStart + pageGutter + 44.dp + HaloSpacing.Md,
                    endPadding = pageGutter,
                    onPlay = compactPlayAction,
                )
            } else {
                CompactBar(
                    visible = heroScrolledAway,
                    title = metaState.value?.name.orEmpty(),
                    titleStart = responsive.contentInsetStart + responsive.gutter + 44.dp + HaloSpacing.Md,
                )
            }

            // Above everything except the sheets: the hero runs under the status
            // bar, so there is no bar to put a back button in.
            BackButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(
                        start = responsive.contentInsetStart + pageGutter,
                        top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() +
                            if (wide) (WideCompactBarHeight - 44.dp) / 2 else HaloSpacing.Xs,
                    ),
            )

            // Last child of the root box, so the rail draws over the back button
            // and the hero rather than under either.
            SourcesRail(
                graph = graph,
                request = railRequest,
                onClose = { railRequest = null },
            )
        }
    }
}

/** The newest unfinished row of this title whose video it still lists; a film's own, which it never lists. */
private fun resumeStateOf(meta: MetaDetail, watchStates: List<WatchState>?, itemId: String): WatchState? {
    val videoIds = meta.videos.mapTo(mutableSetOf()) { it.id }
    return watchStates.orEmpty()
        .filter { it.itemId == itemId && !it.watched && it.durationSec > 0 && it.positionSec > 0 }
        .filter { videoIds.isEmpty() || it.videoId in videoIds }
        .maxByOrNull { it.updatedAt }
}

private fun playTargetOf(isSeries: Boolean, video: MetaVideo?, resume: WatchState?, targetState: WatchState?): PlayTarget {
    val partway = targetState?.takeIf { !it.watched && it.durationSec > 0 && it.positionSec > 0 }
    val verb = if (partway != null) "Resume" else "Play"
    val label = when {
        !isSeries -> verb
        video != null -> "$verb ${episodeTag(video)}"
        else -> "No episodes yet"
    }
    return PlayTarget(
        label = label,
        left = partway?.let { "${timeLeft(it.positionSec, it.durationSec)} LEFT" },
        progress = partway?.let { (it.positionSec / it.durationSec).toFloat() },
        enabled = !isSeries || video != null || resume != null,
    )
}

private val PhoneGutter = 20.dp
private val SectionGap = 30.dp

@Composable
private fun DetailBody(
    modifier: Modifier,
    listState: LazyListState,
    graph: SessionGraph,
    meta: MetaDetail,
    isSeries: Boolean,
    seasons: List<Int>,
    activeSeason: Int?,
    episodes: List<MetaVideo>,
    episodeCount: Int,
    pageLabels: List<String>,
    page: Int,
    progress: Map<String, WatchState>,
    resumeState: WatchState?,
    playTarget: PlayTarget,
    availability: moe.ditto.halo.browse.Availability?,
    streamsLoading: Boolean,
    inLibrary: Boolean,
    downloaded: Boolean,
    similarSource: moe.ditto.halo.browse.GenreCatalog?,
    responsive: ResponsiveInfo,
    onPlay: () -> Unit,
    onToggleLibrary: () -> Unit,
    onOpenSeasons: () -> Unit,
    onOpenPages: () -> Unit,
    onPage: (Int) -> Unit,
    onPlayEpisode: (MetaVideo) -> Unit,
    onOpenDetail: (MetaRef) -> Unit,
    onOpenShelf: (ShelfSnapshot) -> Unit,
) {
    val tablet = responsive.isTablet
    val gutter = if (tablet) responsive.gutter else PhoneGutter
    val menus = LocalTitleMenus.current
    val uriHandler = LocalUriHandler.current
    var synopsisOpen by remember(meta.id) { mutableStateOf(false) }
    val credits = remember(meta) { creditsOf(meta) }
    val about = remember(meta, isSeries) { aboutRows(meta, isSeries) }
    val trailer = remember(meta) { trailerUrl(meta) }
    // A tablet's text and controls keep a reading measure; the rows of
    // portraits, posters and episode cards still run the full width.
    val measure = if (tablet) Modifier.widthIn(max = HaloLayout.DetailBodyMaxWidth) else Modifier

    LazyColumn(
        modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(
            // The rail floats over the leading edge, and the hero is the one
            // thing here that would otherwise run under it.
            start = responsive.contentInsetStart,
            // The tab bar stays up over this screen, so the last row stops
            // short of it, as on every tab.
            bottom = responsive.bottomContentPadding,
        ),
    ) {
        item(key = "hero") {
            DetailHero(
                art = meta.background ?: meta.poster,
                title = meta.name,
                logo = meta.logo,
                facts = heroFacts(meta, isSeries, seasons),
                rating = meta.imdbRating,
                height = responsive.detailHeroHeight,
                gutter = gutter,
            )
        }

        item(key = "actions") {
            Column(
                measure.fillMaxWidth().padding(start = gutter, end = gutter, top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                PlayBlock(playTarget, availability, streamsLoading, onPlay)
                ActionTiles(
                    listOfNotNull(
                        ActionTile(
                            label = if (inLibrary) "In library" else "Add to library",
                            icon = if (inLibrary) HaloIcons.Bookmark else HaloIcons.BookmarkOutline,
                            tint = if (inLibrary) HaloColors.AccentLight else HaloColors.Text,
                            onClick = onToggleLibrary,
                        ),
                        // The download starts from the sources, where one is picked.
                        ActionTile(
                            label = if (downloaded) "Downloaded" else "Download",
                            icon = if (downloaded) HaloIcons.Download else HaloIcons.DownloadOutline,
                            tint = if (downloaded) HaloColors.Success else HaloColors.Text,
                            onClick = onPlay,
                        ),
                        trailer?.let { url ->
                            ActionTile("Trailer", HaloIcons.OpenInNew, HaloColors.Text) { runCatching { uriHandler.openUri(url) } }
                        },
                    ),
                )
                meta.description?.takeIf { it.isNotBlank() }?.let { text ->
                    Synopsis(text, synopsisOpen) { synopsisOpen = !synopsisOpen }
                }
            }
        }

        if (isSeries) {
            episodeSection(
                menus = menus,
                meta = meta,
                seasons = seasons,
                activeSeason = activeSeason,
                episodes = episodes,
                episodeCount = episodeCount,
                pageLabels = pageLabels,
                page = page,
                progress = progress,
                resumeVideoId = resumeState?.videoId,
                responsive = responsive,
                gutter = gutter,
                onOpenSeasons = onOpenSeasons,
                onOpenPages = onOpenPages,
                onPage = onPage,
                onPlayEpisode = onPlayEpisode,
            )
        } else if (availability != null && availability.total > 0) {
            item(key = "ways") {
                Column(measure.padding(start = gutter, end = gutter, top = SectionGap), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SectionHeading("Ways to watch", "${availability.total} ${if (availability.total == 1) "SOURCE" else "SOURCES"}")
                    WaysToWatch(availability, onPlay)
                }
            }
        }

        if (credits.isNotEmpty()) {
            item(key = "cast") {
                Column(Modifier.padding(top = SectionGap), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SectionHeading(
                        "Cast & crew",
                        "${credits.size} ${if (credits.size == 1) "PERSON" else "PEOPLE"}",
                        Modifier.padding(horizontal = gutter),
                    )
                    CastRow(credits, gutter) { credit -> runCatching { uriHandler.openUri(personSearchUrl(credit.name)) } }
                }
            }
        }

        if (about.isNotEmpty()) {
            item(key = "about") {
                Column(measure.padding(start = gutter, end = gutter, top = SectionGap), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SectionHeading("About", null)
                    AboutCard(about)
                }
            }
        }

        similarSource?.let { source ->
            item(key = "similar") {
                SimilarRow(graph, meta, source, responsive, gutter, onOpenDetail, onOpenShelf)
            }
        }
    }
}

/** Years, then seasons for a series or length for a film, then up to two genres. */
internal fun heroFacts(meta: MetaDetail, isSeries: Boolean, seasons: List<Int>): List<String> {
    val numbered = seasons.count { it != 0 }
    return listOfNotNull(
        meta.releaseInfo ?: formatReleaseDate(meta.released)?.takeLast(4),
        if (isSeries) numbered.takeIf { it > 0 }?.let { "$it ${if (it == 1) "season" else "seasons"}" } else formatRuntime(meta.runtime),
        meta.genres.take(2).joinToString(", ").ifBlank { null },
    )
}

private fun LazyListScope.episodeSection(
    menus: TitleMenus?,
    meta: MetaDetail,
    seasons: List<Int>,
    activeSeason: Int?,
    episodes: List<MetaVideo>,
    episodeCount: Int,
    pageLabels: List<String>,
    page: Int,
    progress: Map<String, WatchState>,
    resumeVideoId: String?,
    responsive: ResponsiveInfo,
    gutter: Dp,
    onOpenSeasons: () -> Unit,
    onOpenPages: () -> Unit,
    onPage: (Int) -> Unit,
    onPlayEpisode: (MetaVideo) -> Unit,
) {
    item(key = "episodes-head") {
        val seasonVideos = remember(meta, activeSeason) { episodesIn(meta.videos, activeSeason) }
        val watched = seasonVideos.count { progress[it.id]?.watched == true }
        val year = seasonVideos.firstNotNullOfOrNull { it.released }?.take(4)
        val note = listOfNotNull(
            "$episodeCount ${if (episodeCount == 1) "EPISODE" else "EPISODES"}",
            year,
            watched.takeIf { it > 0 }?.let { "$it WATCHED" },
        ).joinToString(" · ")
        Column(Modifier.padding(start = gutter, end = gutter, top = SectionGap), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeading("Episodes", note) {
                if (seasons.size > 1 && activeSeason != null) PickerPill(seasonLabel(activeSeason), onClick = onOpenSeasons)
            }
            if (pageLabels.size > 1) {
                PickerPill(pageLabels[page], active = true, onClick = onOpenPages)
            }
        }
    }

    val rowMeta = { video: MetaVideo ->
        val state = progress[video.id]
        val partway = state?.takeIf { !it.watched && it.durationSec > 0 && it.positionSec > 0 }
        listOfNotNull(
            partway?.let { "${timeLeft(it.positionSec, it.durationSec)} LEFT" } ?: formatRuntime(meta.runtime)?.uppercase(),
            formatReleaseDate(video.released)?.uppercase(),
        ).joinToString(" · ")
    }
    val menuFor = { video: MetaVideo -> menus?.let { m -> { m.openEpisode(meta, video) { onPlayEpisode(video) } } } }

    if (!responsive.isTablet) {
        items(items = episodes, key = { it.id }) { video ->
            EpisodeRow(
                video = video,
                state = progress[video.id],
                current = video.id == resumeVideoId,
                meta = rowMeta(video),
                onClick = { onPlayEpisode(video) },
                menu = menuFor(video),
                modifier = Modifier.padding(horizontal = gutter - 10.dp, vertical = 2.dp),
            )
        }
    } else {
        // Rows of cards: two or three episodes share a line at tablet width.
        val columns = responsive.episodeGridColumns
        items(items = episodes.chunked(columns), key = { row -> row.first().id }) { row ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = gutter - 10.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(HaloLayout.EpisodeGridGap),
            ) {
                row.forEach { video ->
                    EpisodeRow(
                        video = video,
                        state = progress[video.id],
                        current = video.id == resumeVideoId,
                        meta = rowMeta(video),
                        onClick = { onPlayEpisode(video) },
                        menu = menuFor(video),
                        modifier = Modifier.weight(1f),
                    )
                }
                // A short final row keeps its cards the width of every other row's.
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }

    if (pageLabels.size > 1) {
        item(key = "episodes-pages") {
            PageButtons(
                previous = pageLabels.getOrNull(page - 1),
                next = pageLabels.getOrNull(page + 1),
                onPrevious = { onPage(page - 1) },
                onNext = { onPage(page + 1) },
                modifier = Modifier.padding(start = gutter, end = gutter, top = 12.dp),
            )
        }
    }
}

/**
 * Titles of the same type sharing this one's genre.
 *
 * The row holds its place with skeleton posters from the start, as Home's rows
 * do, so the answer arriving fills it in rather than pushing a new row into
 * the page. Its first answer is the one it keeps: the catalog revalidating in
 * the background must not reshuffle posters someone is looking at. It leaves
 * only when the catalog turns out to have nothing (or fails before answering).
 */
@Composable
internal fun SimilarRow(
    graph: SessionGraph,
    meta: MetaDetail,
    source: moe.ditto.halo.browse.GenreCatalog,
    responsive: ResponsiveInfo,
    gutter: Dp,
    onOpenDetail: (MetaRef) -> Unit,
    onOpenShelf: (ShelfSnapshot) -> Unit,
    /** Where the row sits: under the section above it, or framed as a panel. */
    modifier: Modifier = Modifier.padding(top = SectionGap),
    posterWidth: Dp = responsive.shelfPosterWidth,
) {
    val state by remember(graph, source) {
        graph.browse.catalog(source.addonId, meta.type, source.catalogId, extra = mapOf("genre" to source.genre))
    }.collectAsState(QueryState())
    val answer = remember(state.value, meta.id) {
        state.value?.let { titles ->
            val seen = mutableSetOf("${meta.type}:${meta.id}")
            // Catalogs can repeat a title; a row must not draw it twice.
            titles.filter { seen.add("${it.type}:${it.id}") }.take(SimilarLimit)
        }
    }
    var shown by remember(source, meta.id) { mutableStateOf<List<MetaPreview>?>(null) }
    if (shown == null && answer != null) shown = answer
    val titles = shown
    if (titles == null && state.error != null) return
    if (titles != null && titles.isEmpty()) return
    val label = source.genre.uppercase()
    Box(modifier) {
        CatalogRow(
            title = "More like this",
            source = label,
            items = remember(titles) { titles.orEmpty().map { it.posterItem() } },
            isLoading = titles == null,
            onItemClick = { onOpenDetail(it.metaRef()) },
            itemMenu = titles?.let { posterMenu(it) },
            onSeeAll = titles?.let { { onOpenShelf(ShelfSnapshot("More like this", label, it)) } },
            posterWidth = posterWidth,
            gutter = gutter,
            gap = responsive.catalogRowGap,
            bottomPadding = 0.dp,
        )
    }
}

/** One row's worth: enough to page through, not a whole catalog. */
private const val SimilarLimit = 20

/**
 * The bar that stands in for the hero once it has scrolled away: the status
 * bar's height plus a row, solid, with the title beside the back button, so
 * the page under it never shows through. The desktop app's compact bar.
 */
@Composable
private fun BoxScope.CompactBar(visible: Boolean, title: String, titleStart: Dp) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(160)),
        exit = fadeOut(tween(160)),
        modifier = Modifier.align(Alignment.TopStart),
    ) {
        val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        Box(
            Modifier
                .fillMaxWidth()
                .background(HaloColors.Background)
                .padding(top = top)
                .height(CompactBarHeight),
        ) {
            Text(
                text = title,
                color = HaloColors.Text,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.CenterStart).padding(start = titleStart, end = HaloSpacing.Md),
            )
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(1.dp).background(HaloColors.Hairline))
        }
    }
}

/** Tall enough for the back button with a little air above and below. */
private val CompactBarHeight = 56.dp

@Composable
private fun BackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(RoundedCornerShape(HaloRadius.Pill))
            .background(Color(0x8C090B10))
            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(HaloRadius.Pill))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = HaloIcons.ChevronLeft, contentDescription = "Back", tint = HaloColors.Text, modifier = Modifier.size(24.dp))
    }
}
