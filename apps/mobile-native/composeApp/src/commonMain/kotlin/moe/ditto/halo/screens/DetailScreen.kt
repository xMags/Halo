package moe.ditto.halo.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.launch
import moe.ditto.halo.SignedInGraph
import moe.ditto.halo.api.MetaCard
import moe.ditto.halo.api.MetaDetail
import moe.ditto.halo.api.MetaVideo
import moe.ditto.halo.api.WatchState
import moe.ditto.halo.browse.episodesIn
import moe.ditto.halo.browse.lastWatchedSeason
import moe.ditto.halo.browse.seasonLabel
import moe.ditto.halo.browse.seasonNumbers
import moe.ditto.halo.cache.QueryState
import moe.ditto.halo.sync.LibraryRepository
import moe.ditto.halo.ui.CenterMessage
import moe.ditto.halo.ui.HaloAsyncImage
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloIcons
import moe.ditto.halo.ui.HaloLayout
import moe.ditto.halo.ui.HaloRadius
import moe.ditto.halo.ui.HaloSpacing
import moe.ditto.halo.ui.HaloType
import moe.ditto.halo.ui.HeroScrim
import moe.ditto.halo.ui.LocalHazeState
import moe.ditto.halo.ui.MetaLine
import moe.ditto.halo.ui.ResponsiveInfo
import moe.ditto.halo.ui.SelectOption
import moe.ditto.halo.ui.SelectSheet
import moe.ditto.halo.ui.glassSource
import moe.ditto.halo.ui.monoStyle
import moe.ditto.halo.ui.rememberResponsive

/**
 * One title: hero art, what it is, whether it is saved, and — for a series — its
 * episodes under a season picker.
 *
 * This is the only screen that writes to the library, because it is the only one
 * showing a title rather than a collection of them.
 */
@Composable
internal fun DetailScreen(
    graph: SignedInGraph,
    type: String,
    metaId: String,
    onBack: () -> Unit,
    /** The movie's own sources; an episode's are [onPlayEpisode]. */
    onPlayMovie: (MetaCard) -> Unit,
    onPlayEpisode: (MetaDetail, MetaVideo) -> Unit,
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
    // The season sheet frosts what is behind it, and a blur cannot sample content
    // it belongs to — so the source has to be this screen's body, with the sheet
    // beside it rather than inside it. The shell's source stays whole for the tab
    // bar, which this screen hides anyway.
    val bodyHaze = rememberHazeState()

    val metaState by remember(graph, type, metaId) { graph.browse.meta(type, metaId) }.collectAsState(QueryState())
    val library by remember(graph) { graph.library.observeActive() }.collectAsState(QueryState())
    val watchStates by remember(graph) { graph.watchStates.observe() }.collectAsState(QueryState())

    var chosenSeason by remember(metaId) { mutableStateOf<Int?>(null) }
    var seasonSheetOpen by remember { mutableStateOf(false) }
    // The video whose sources the rail is showing, on the layouts that have one.
    var railRequest by remember(metaId) { mutableStateOf<SourcesRequest?>(null) }
    // Null wherever the picker is pushed instead: a phone, or a caller that
    // offered no way to build the request.
    val railFor = sourcesRequest.takeIf { responsive.isTablet }

    CompositionLocalProvider(LocalHazeState provides bodyHaze) {
        Box(modifier.fillMaxSize().background(HaloColors.Background)) {
            val meta = metaState.value
            when {
                meta != null -> {
                    val seasons = remember(meta) { seasonNumbers(meta.videos) }
                    val openOn = remember(meta, watchStates.value, itemId) {
                        lastWatchedSeason(meta.videos, watchStates.value, itemId)
                    }
                    // Explicit choice first, then where the last episode was watched,
                    // then the earliest season — so a viewer mid-binge lands where
                    // they were rather than back at season one.
                    val activeSeason = chosenSeason ?: openOn ?: seasons.firstOrNull()
                    val episodes = remember(meta, activeSeason) { episodesIn(meta.videos, activeSeason) }
                    val progress = remember(watchStates.value) {
                        watchStates.value.orEmpty().associateBy { it.videoId }
                    }
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

                    DetailBody(
                        // The sheet and the rail blur this, and only this.
                        modifier = Modifier.glassSource(bodyHaze),
                        meta = meta,
                        episodes = episodes,
                        progress = progress,
                        seasons = seasons,
                        activeSeason = activeSeason,
                        inLibrary = inLibrary,
                        isSeries = type == SeriesType,
                        responsive = responsive,
                        onToggleLibrary = toggleLibrary,
                        onOpenSeasons = { seasonSheetOpen = true },
                        onPlayMovie = { openSources(null) },
                        onPlayEpisode = { video -> openSources(video) },
                    )

                    // Last child of the root box, so it draws over the hero and the
                    // episode list instead of inside whichever one scrolls.
                    SelectSheet(
                        visible = seasonSheetOpen,
                        title = "Season",
                        options = seasons.map {
                            SelectOption(key = it.toString(), label = seasonLabel(it), selected = it == activeSeason)
                        },
                        onSelect = { key -> chosenSeason = key.toIntOrNull() },
                        onClose = { seasonSheetOpen = false },
                    )
                }
                metaState.error != null -> CenterMessage("No installed addon could describe this title.")
                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = HaloColors.Accent)
                }
            }

            // Above everything except the sheet: the hero runs under the status bar,
            // so there is no bar to put a back button in.
            BackButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(
                        start = responsive.contentInsetStart + responsive.gutter,
                        top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + HaloSpacing.Xs,
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

private const val SeriesType = "series"

private val BodyPadding = HaloSpacing.Md + 2.dp
private val EpisodeThumbWidth = 120.dp
private val EpisodeThumbHeight = 68.dp
private val DescriptionColor = Color(0xFFC3C9D6)

@Composable
private fun DetailBody(
    modifier: Modifier,
    meta: MetaDetail,
    episodes: List<MetaVideo>,
    progress: Map<String, WatchState>,
    seasons: List<Int>,
    activeSeason: Int?,
    inLibrary: Boolean,
    isSeries: Boolean,
    responsive: ResponsiveInfo,
    onToggleLibrary: () -> Unit,
    onOpenSeasons: () -> Unit,
    onPlayMovie: () -> Unit,
    onPlayEpisode: (MetaVideo) -> Unit,
) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val tablet = responsive.isTablet

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            // The rail floats over the leading edge, and the hero is the one
            // thing here that would otherwise run under it.
            start = responsive.contentInsetStart,
            // This screen covers the tab bar at every size, so there is nothing
            // at the bottom to clear — only margin under the last row.
            bottom = bottomInset + responsive.pick(HaloSpacing.Lg, HaloLayout.TabletBottomPadding),
        ),
    ) {
        item(key = "header") {
            DetailHeader(
                meta = meta,
                seasons = seasons,
                activeSeason = activeSeason,
                episodeCount = episodes.size,
                watchedCount = episodes.count { progress[it.id]?.watched == true },
                inLibrary = inLibrary,
                isSeries = isSeries,
                responsive = responsive,
                onToggleLibrary = onToggleLibrary,
                onOpenSeasons = onOpenSeasons,
                onPlayMovie = onPlayMovie,
            )
        }
        if (!isSeries) return@LazyColumn

        if (!tablet) {
            items(items = episodes, key = { it.id }) { video ->
                EpisodeRow(
                    video = video,
                    state = progress[video.id],
                    onClick = { onPlayEpisode(video) },
                )
            }
            return@LazyColumn
        }

        // Rows of cards rather than a lazy grid: the hero above them is
        // full-bleed, and a grid's content padding is the only way to gutter its
        // cells — one that would inset the art with them.
        val columns = responsive.episodeGridColumns
        val rows = episodes.chunked(columns)
        items(items = rows, key = { row -> row.first().id }) { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = responsive.gutter, vertical = HaloLayout.EpisodeGridGap / 2),
                horizontalArrangement = Arrangement.spacedBy(HaloLayout.EpisodeGridGap),
            ) {
                row.forEach { video ->
                    EpisodeCard(
                        video = video,
                        state = progress[video.id],
                        onClick = { onPlayEpisode(video) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // A short final row keeps its cards the width of every other
                // row's rather than stretching them across the gap.
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun DetailHeader(
    meta: MetaDetail,
    seasons: List<Int>,
    activeSeason: Int?,
    episodeCount: Int,
    watchedCount: Int,
    inLibrary: Boolean,
    isSeries: Boolean,
    responsive: ResponsiveInfo,
    onToggleLibrary: () -> Unit,
    onOpenSeasons: () -> Unit,
    onPlayMovie: () -> Unit,
) {
    val tablet = responsive.isTablet
    val gutter = if (tablet) responsive.gutter else BodyPadding

    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .height(responsive.detailHeroHeight)
                .background(HaloColors.Surface),
        ) {
            // fillMaxSize, not matchParentSize — matching the parent leaves async
            // art unloaded (see the same note on Home's hero).
            HaloAsyncImage(
                url = meta.background ?: meta.poster,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
            HeroScrim(Modifier.matchParentSize())
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = gutter, vertical = if (tablet) HaloSpacing.Md else HaloSpacing.Sm),
            ) {
                Text(
                    text = meta.name,
                    color = HaloColors.Text,
                    fontSize = if (tablet) 36.sp else 32.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.3.sp,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                MetaLine(
                    parts = listOfNotNull(meta.releaseInfo, meta.runtime),
                    rating = meta.imdbRating,
                )
            }
        }

        val actions: @Composable () -> Unit = {
            DetailActions(
                inLibrary = inLibrary,
                isSeries = isSeries,
                // Buttons sized to their labels rather than stretched: a
                // 700dp-wide primary button reads as a banner, not a control.
                stretch = !tablet,
                onToggleLibrary = onToggleLibrary,
                onPlayMovie = onPlayMovie,
            )
        }
        val synopsis: @Composable () -> Unit = {
            meta.description?.let {
                Text(
                    text = it,
                    color = DescriptionColor,
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                    modifier = Modifier.padding(top = HaloSpacing.Md),
                )
            }
        }

        if (tablet) {
            // Landscape has width for the facts beside the synopsis; portrait
            // does not, and stacking them there beats squeezing both.
            val stacked = !responsive.isLandscape
            val body = @Composable {
                Column(
                    // Stacked, the block runs the full column: the season row
                    // and the episodes under it do, and a reading cap here
                    // would end this block a few dp short of their edge, which
                    // reads as a misalignment rather than as a measure.
                    if (stacked) Modifier.fillMaxWidth() else Modifier.widthIn(max = HaloLayout.DetailBodyMaxWidth),
                ) {
                    actions()
                    synopsis()
                }
            }
            val facts = @Composable {
                FactsCard(
                    meta = meta,
                    seasons = seasons,
                    inLibrary = inLibrary,
                    modifier = if (stacked) {
                        Modifier.fillMaxWidth()
                    } else {
                        Modifier.width(HaloLayout.FactsCardWidth)
                    },
                )
            }
            if (stacked) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = gutter, end = gutter, top = HaloSpacing.Lg),
                    verticalArrangement = Arrangement.spacedBy(HaloSpacing.Md),
                ) {
                    body()
                    facts()
                }
            } else {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = gutter, end = gutter, top = HaloSpacing.Lg),
                    horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Xl),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(Modifier.weight(1f)) { body() }
                    facts()
                }
            }
        } else {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = gutter)
                    .padding(top = HaloSpacing.Sm),
            ) {
                actions()
                synopsis()
            }
        }

        if (seasons.isNotEmpty() && activeSeason != null) {
            SeasonRow(
                label = seasonLabel(activeSeason),
                // The counts are the one thing the picker cannot say: which
                // season is open is already on the chip.
                counts = if (tablet) episodeCountLabel(episodeCount, watchedCount) else null,
                onClick = onOpenSeasons,
                modifier = Modifier.padding(
                    start = gutter,
                    end = gutter,
                    top = HaloSpacing.Lg,
                    bottom = if (tablet) HaloSpacing.Sm + 4.dp else 0.dp,
                ),
            )
        }
    }
}

private fun episodeCountLabel(episodes: Int, watched: Int): String =
    "$episodes ${if (episodes == 1) "episode" else "episodes"} · $watched watched"

/** Sources and My List, as one row wherever the title's actions are shown. */
@Composable
private fun DetailActions(
    inLibrary: Boolean,
    isSeries: Boolean,
    stretch: Boolean,
    onToggleLibrary: () -> Unit,
    onPlayMovie: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(if (stretch) HaloSpacing.Sm + 2.dp else 10.dp)) {
        if (!isSeries) {
            SourcesButton(
                onClick = onPlayMovie,
                modifier = if (stretch) Modifier.weight(1f) else Modifier,
                horizontalPadding = if (stretch) 0.dp else 34.dp,
            )
        }
        LibraryButton(
            inLibrary = inLibrary,
            // A film's row already has a primary action beside it, so on a phone
            // — where that action takes the full width — the bookmark shrinks to
            // its icon. Everywhere else both are labelled and sized to fit.
            labelled = isSeries || !stretch,
            onClick = onToggleLibrary,
            modifier = if (stretch && isSeries) Modifier.weight(1f) else Modifier,
            horizontalPadding = if (stretch) 0.dp else 22.dp,
        )
    }
}

/**
 * What the title is, as label/value rows beside the synopsis.
 *
 * New at tablet width because it costs a column the phone does not have, and
 * every value in it is already on the record the screen fetched.
 */
@Composable
private fun FactsCard(
    meta: MetaDetail,
    seasons: List<Int>,
    inLibrary: Boolean,
    modifier: Modifier = Modifier,
) {
    val facts = buildList {
        meta.releaseInfo?.let { add("Released" to it) }
        if (seasons.isNotEmpty()) add("Seasons" to seasons.size.toString())
        meta.runtime?.let { add("Runtime" to it) }
        meta.genres.takeIf { it.isNotEmpty() }?.let { add("Genres" to it.take(3).joinToString(" · ")) }
        add("In library" to if (inLibrary) "Yes" else "No")
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(HaloRadius.Lg))
            .background(HaloColors.Glass)
            .border(1.dp, HaloColors.GlassBorder, RoundedCornerShape(HaloRadius.Lg))
            .padding(horizontal = HaloSpacing.Md, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        facts.forEach { (label, value) ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Md),
            ) {
                Text(text = label, color = HaloColors.TextDim, fontSize = 12.5.sp, modifier = Modifier.weight(1f))
                Text(
                    text = value,
                    color = HaloColors.Text,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.End,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1.4f),
                )
            }
        }
    }
}

@Composable
private fun SourcesButton(onClick: () -> Unit, modifier: Modifier = Modifier, horizontalPadding: Dp = 0.dp) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(HaloRadius.Md))
            .background(HaloColors.Primary)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = horizontalPadding, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = HaloIcons.Play,
            contentDescription = null,
            tint = HaloColors.OnPrimary,
            modifier = Modifier.size(19.dp),
        )
        Text(text = "Sources", color = HaloColors.OnPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun LibraryButton(
    inLibrary: Boolean,
    labelled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 0.dp,
) {
    Row(
        modifier = modifier
            .then(if (labelled) Modifier else Modifier.width(52.dp))
            .clip(RoundedCornerShape(HaloRadius.Md))
            .background(HaloColors.Glass)
            .border(1.dp, HaloColors.GlassBorder, RoundedCornerShape(HaloRadius.Md))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = if (labelled) horizontalPadding else 0.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Sm, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (inLibrary) HaloIcons.Bookmark else HaloIcons.BookmarkOutline,
            // Unlabelled, the icon is the only thing naming this control.
            contentDescription = if (labelled) null else if (inLibrary) "In library" else "Add to library",
            tint = HaloColors.Accent,
            modifier = Modifier.size(20.dp),
        )
        if (labelled) {
            Text(
                text = if (inLibrary) "In Library" else "My List",
                color = HaloColors.Accent,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** The season picker, with what that season holds beside it where there is room. */
@Composable
private fun SeasonRow(
    label: String,
    counts: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(HaloRadius.Sm))
                .background(HaloColors.SurfaceHigh)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = HaloSpacing.Md, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, style = HaloType.Callout)
            Icon(
                imageVector = HaloIcons.ChevronDown,
                contentDescription = null,
                tint = HaloColors.Text,
                modifier = Modifier.size(16.dp),
            )
        }
        if (counts != null) {
            // Monospaced: two counts that change as episodes are watched, and a
            // proportional face reflows the line under the reader each time.
            Text(
                text = counts,
                style = monoStyle(fontSize = 11.5.sp, color = HaloColors.TextDim),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Below this the bar is a sliver that reads as a rendering artifact. */
private const val MinVisibleProgress = 0.02

@Composable
private fun EpisodeRow(video: MetaVideo, state: WatchState?, onClick: () -> Unit) {
    val fraction = state?.takeIf { it.durationSec > 0 }?.let { it.positionSec / it.durationSec } ?: 0.0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = HaloSpacing.Md, vertical = HaloSpacing.Sm),
        horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Sm + 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(EpisodeThumbWidth)
                .height(EpisodeThumbHeight)
                .clip(RoundedCornerShape(HaloRadius.Sm - 2.dp))
                .background(HaloColors.Surface),
            contentAlignment = Alignment.Center,
        ) {
            if (video.thumbnail != null) {
                HaloAsyncImage(url = video.thumbnail, contentDescription = null, modifier = Modifier.fillMaxSize())
            } else {
                // An episode with no still gets the affordance instead of a blank
                // rectangle, which reads as a failed image rather than a design.
                Icon(
                    imageVector = HaloIcons.Play,
                    contentDescription = null,
                    tint = HaloColors.TextDim,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        Column(Modifier.weight(1f)) {
            Text(
                text = episodeLabel(video),
                color = HaloColors.Text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            video.overview?.let {
                Text(
                    text = it,
                    color = HaloColors.TextDim,
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = HaloSpacing.Xs),
                )
            }
            // Progress belongs to episodes still in flight; a finished one carries
            // the mark on the right instead, and showing both says two things.
            if (state?.watched == false && fraction > MinVisibleProgress) {
                ProgressBar(fraction = fraction, modifier = Modifier.padding(top = 6.dp))
            }
        }

        if (state?.watched == true) {
            Icon(
                imageVector = HaloIcons.CheckCircle,
                contentDescription = "Watched",
                tint = HaloColors.Success,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private val EpisodeCardShape = RoundedCornerShape(14.dp)
private val EpisodeCardFill = Color.White.copy(alpha = 0.045f)
private val EpisodeCardBorder = Color.White.copy(alpha = 0.07f)

/**
 * The same episode as [EpisodeRow], as a card for the tablet's grid.
 *
 * A card rather than a full-width row because two or three of them share a line:
 * without a fill and a border the columns read as one wide row with holes in it.
 * The watched mark and the progress bar keep the row's rules exactly.
 */
@Composable
private fun EpisodeCard(
    video: MetaVideo,
    state: WatchState?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fraction = state?.takeIf { it.durationSec > 0 }?.let { it.positionSec / it.durationSec } ?: 0.0
    Row(
        modifier = modifier
            .clip(EpisodeCardShape)
            .background(EpisodeCardFill)
            .border(1.dp, EpisodeCardBorder, EpisodeCardShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Md - 4.dp),
    ) {
        Box(
            Modifier
                .width(HaloLayout.EpisodeCardThumbWidth)
                .height(HaloLayout.EpisodeCardThumbHeight)
                .clip(RoundedCornerShape(HaloRadius.Sm))
                .background(HaloColors.Surface),
            contentAlignment = Alignment.Center,
        ) {
            if (video.thumbnail != null) {
                HaloAsyncImage(url = video.thumbnail, contentDescription = null, modifier = Modifier.fillMaxSize())
            } else {
                Icon(
                    imageVector = HaloIcons.Play,
                    contentDescription = null,
                    tint = HaloColors.TextDim,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        Column(Modifier.weight(1f).padding(top = 2.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Sm),
            ) {
                Text(
                    text = episodeLabel(video),
                    color = HaloColors.Text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (state?.watched == true) {
                    Icon(
                        imageVector = HaloIcons.CheckCircle,
                        contentDescription = "Watched",
                        tint = HaloColors.Success,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            video.overview?.let {
                Text(
                    text = it,
                    color = HaloColors.TextDim,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = HaloSpacing.Xs),
                )
            }
            if (state?.watched == false && fraction > MinVisibleProgress) {
                ProgressBar(fraction = fraction, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

/** "4. Foghorn", or whatever names an episode that carries no number. */
private fun episodeLabel(video: MetaVideo): String = buildString {
    video.episode?.let { append("$it. ") }
    append(video.displayTitle ?: video.id)
}

/** How far through an episode is, on the two surfaces that show one. */
@Composable
private fun ProgressBar(fraction: Double, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Color.White.copy(alpha = 0.18f)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction = fraction.coerceAtMost(1.0).toFloat())
                .fillMaxSize()
                .background(HaloColors.Accent),
        )
    }
}

@Composable
private fun BackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(34.dp)
            .clip(RoundedCornerShape(HaloRadius.Pill))
            .background(Color.Black.copy(alpha = 0.4f))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = HaloIcons.ChevronLeft,
            contentDescription = "Back",
            tint = HaloColors.Text,
            modifier = Modifier.size(24.dp),
        )
    }
}
