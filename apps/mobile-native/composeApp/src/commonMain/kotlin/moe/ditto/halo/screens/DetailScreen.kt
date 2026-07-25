package moe.ditto.halo.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.lazy.LazyListScope
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
import moe.ditto.halo.ui.HaloRadius
import moe.ditto.halo.ui.HaloSpacing
import moe.ditto.halo.ui.HaloType
import moe.ditto.halo.ui.HeroScrim
import moe.ditto.halo.ui.LocalHazeState
import moe.ditto.halo.ui.MetaLine
import moe.ditto.halo.ui.SelectOption
import moe.ditto.halo.ui.SelectSheet
import moe.ditto.halo.ui.glassSource
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

                    DetailBody(
                        // The sheet blurs this, and only this.
                        modifier = Modifier.glassSource(bodyHaze),
                        meta = meta,
                        episodes = episodes,
                        progress = progress,
                        seasons = seasons,
                        activeSeason = activeSeason,
                        inLibrary = inLibrary,
                        isSeries = type == SeriesType,
                        twoPane = responsive.isTablet && responsive.isLandscape && type == SeriesType,
                        contentMaxWidth = responsive.contentMaxWidth,
                        onToggleLibrary = toggleLibrary,
                        onOpenSeasons = { seasonSheetOpen = true },
                        onPlayMovie = { onPlayMovie(meta) },
                        onPlayEpisode = { video -> onPlayEpisode(meta, video) },
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
                        start = HaloSpacing.Md,
                        top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + HaloSpacing.Xs,
                    ),
            )
        }
    }
}

private const val SeriesType = "series"

/** Fixed, and deliberately large: the art is the reason this screen looks like this. */
private val HeroHeight = 460.dp
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
    twoPane: Boolean,
    contentMaxWidth: Dp?,
    onToggleLibrary: () -> Unit,
    onOpenSeasons: () -> Unit,
    onPlayMovie: () -> Unit,
    onPlayEpisode: (MetaVideo) -> Unit,
) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val listPadding = PaddingValues(bottom = bottomInset + HaloSpacing.Lg)
    val header: @Composable () -> Unit = {
        DetailHeader(
            meta = meta,
            seasons = seasons,
            activeSeason = activeSeason,
            inLibrary = inLibrary,
            isSeries = isSeries,
            // A cap on the synopsis is pointless in two-pane, where the left
            // pane is already narrow, and on a phone, where it is narrower still.
            bodyMaxWidth = contentMaxWidth.takeIf { !twoPane },
            onToggleLibrary = onToggleLibrary,
            onOpenSeasons = onOpenSeasons,
            onPlayMovie = onPlayMovie,
        )
    }

    // Master-detail earns its keep only for a series in landscape: art and
    // synopsis on the left, the episode list holding its own scroll on the
    // right. A movie has no list to fill the second pane with.
    if (twoPane) {
        Row(modifier.fillMaxSize()) {
            LazyColumn(Modifier.weight(1f), contentPadding = listPadding) {
                item(key = "header") { header() }
            }
            Box(Modifier.fillMaxHeight().width(1.dp).background(HaloColors.Hairline))
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(top = HaloSpacing.Sm, bottom = bottomInset + HaloSpacing.Lg),
            ) {
                episodeItems(episodes, progress, onPlayEpisode)
            }
        }
        return
    }

    LazyColumn(modifier.fillMaxSize(), contentPadding = listPadding) {
        item(key = "header") { header() }
        if (isSeries) episodeItems(episodes, progress, onPlayEpisode)
    }
}

private fun LazyListScope.episodeItems(
    episodes: List<MetaVideo>,
    progress: Map<String, WatchState>,
    onPlayEpisode: (MetaVideo) -> Unit,
) {
    items(items = episodes, key = { it.id }) { video ->
        EpisodeRow(
            video = video,
            state = progress[video.id],
            onClick = { onPlayEpisode(video) },
        )
    }
}

@Composable
private fun DetailHeader(
    meta: MetaDetail,
    seasons: List<Int>,
    activeSeason: Int?,
    inLibrary: Boolean,
    isSeries: Boolean,
    bodyMaxWidth: Dp?,
    onToggleLibrary: () -> Unit,
    onOpenSeasons: () -> Unit,
    onPlayMovie: () -> Unit,
) {
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .height(HeroHeight)
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
                    .padding(horizontal = BodyPadding, vertical = HaloSpacing.Sm),
            ) {
                Text(
                    text = meta.name,
                    color = HaloColors.Text,
                    fontSize = 32.sp,
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

        Column(
            Modifier
                .then(if (bodyMaxWidth != null) Modifier.widthIn(max = bodyMaxWidth) else Modifier)
                .fillMaxWidth()
                .padding(horizontal = BodyPadding)
                .padding(top = HaloSpacing.Sm),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Sm + 2.dp)) {
                if (!isSeries) {
                    SourcesButton(onClick = onPlayMovie, modifier = Modifier.weight(1f))
                }
                LibraryButton(
                    inLibrary = inLibrary,
                    // A movie's row already has a full-width primary action, so the
                    // bookmark shrinks to its icon; a series has no such button and
                    // the bookmark takes the width instead of floating in a corner.
                    labelled = isSeries,
                    onClick = onToggleLibrary,
                    modifier = if (isSeries) Modifier.weight(1f) else Modifier,
                )
            }

            meta.description?.let {
                Text(
                    text = it,
                    color = DescriptionColor,
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                    modifier = Modifier.padding(top = HaloSpacing.Md),
                )
            }

            if (seasons.isNotEmpty() && activeSeason != null) {
                SeasonChip(
                    label = seasonLabel(activeSeason),
                    onClick = onOpenSeasons,
                    modifier = Modifier.padding(top = HaloSpacing.Lg),
                )
            }
        }
    }
}

@Composable
private fun SourcesButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(HaloRadius.Md))
            .background(HaloColors.Primary)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 13.dp),
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
) {
    Row(
        modifier = modifier
            .then(if (labelled) Modifier else Modifier.width(52.dp))
            .clip(RoundedCornerShape(HaloRadius.Md))
            .background(HaloColors.Glass)
            .border(1.dp, HaloColors.GlassBorder, RoundedCornerShape(HaloRadius.Md))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 13.dp),
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

@Composable
private fun SeasonChip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
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
                text = buildString {
                    video.episode?.let { append("$it. ") }
                    append(video.displayTitle ?: video.id)
                },
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
                Box(
                    Modifier
                        .padding(top = 6.dp)
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
