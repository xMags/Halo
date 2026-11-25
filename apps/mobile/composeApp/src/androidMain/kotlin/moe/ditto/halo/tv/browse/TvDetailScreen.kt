package moe.ditto.halo.tv.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import moe.ditto.halo.SessionGraph
import moe.ditto.halo.api.MetaDetail
import moe.ditto.halo.api.MetaPreview
import moe.ditto.halo.api.MetaVideo
import moe.ditto.halo.api.WatchState
import moe.ditto.halo.api.movieVideoId
import moe.ditto.halo.browse.Credit
import moe.ditto.halo.browse.GenreCatalog
import moe.ditto.halo.browse.creditsOf
import moe.ditto.halo.browse.episodeTag
import moe.ditto.halo.browse.episodesIn
import moe.ditto.halo.browse.isStandInPosterLink
import moe.ditto.halo.browse.lastWatchedSeason
import moe.ditto.halo.browse.medal
import moe.ditto.halo.browse.pickGenreCatalog
import moe.ditto.halo.browse.ratedValue
import moe.ditto.halo.browse.seasonLabel
import moe.ditto.halo.browse.seasonNumbers
import moe.ditto.halo.browse.summarizeAvailability
import moe.ditto.halo.cache.QueryState
import moe.ditto.halo.screens.MetaRef
import moe.ditto.halo.screens.ShelfSnapshot
import moe.ditto.halo.screens.TitleArt
import moe.ditto.halo.screens.TitleMenus
import moe.ditto.halo.screens.heroFacts
import moe.ditto.halo.screens.playTargetOf
import moe.ditto.halo.screens.resumeStateOf
import moe.ditto.halo.screens.timeLeft
import moe.ditto.halo.shell.PersonRoute
import moe.ditto.halo.shell.episodeSources
import moe.ditto.halo.shell.movieSources
import moe.ditto.halo.sync.LibraryRepository
import moe.ditto.halo.sync.WatchMarkTarget
import moe.ditto.halo.sync.runtimeMinutes
import moe.ditto.halo.tv.kit.TvButton
import moe.ditto.halo.tv.kit.TvButtonStyle
import moe.ditto.halo.tv.kit.TvFocusMemory
import moe.ditto.halo.tv.kit.TvMetrics
import moe.ditto.halo.tv.kit.TvPosterCard
import moe.ditto.halo.tv.kit.TvProgressBar
import moe.ditto.halo.tv.kit.TvRow
import moe.ditto.halo.tv.kit.TvSurface
import moe.ditto.halo.tv.kit.TvSurfaceColors
import moe.ditto.halo.tv.kit.TvType
import moe.ditto.halo.tv.kit.TvWideCard
import moe.ditto.halo.tv.kit.defaultFocus
import moe.ditto.halo.tv.kit.rememberFocus
import moe.ditto.halo.tv.kit.rememberTvFocusMemory
import moe.ditto.halo.ui.HaloAsyncImage
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloIcons
import moe.ditto.halo.ui.MetaLine
import moe.ditto.halo.ui.MedalSize
import moe.ditto.halo.ui.QualityMedal
import moe.ditto.halo.ui.metahubPosterUrl

private val DetailPosterWidth = 112.dp
private val DetailLogoMaxWidth = 400.dp
private val DetailLogoMaxHeight = 110.dp
private val ResumeBarWidth = 160.dp

/**
 * A title's own page on TV: its art, facts and the quality of the best source
 * on offer, the play, library and watched buttons, and for a series a season
 * switcher over its episodes. Cast (when people search is available) and
 * "More like this" follow. Trailers and other web links are left out: a TV
 * has no browser to open them in.
 *
 * Playing opens the source picker ([moe.ditto.halo.shell.StreamsRoute]), as
 * every other way into playback does.
 */
@Composable
internal fun TvDetailScreen(
    graph: SessionGraph,
    type: String,
    metaId: String,
    navigation: TvBrowseNavigation,
    menus: TitleMenus,
) {
    val memory = rememberTvFocusMemory()
    var retry by remember { mutableIntStateOf(0) }
    val metaState by remember(graph, type, metaId, retry) { graph.browse.meta(type, metaId) }.collectAsState(QueryState())
    val meta = metaState.value
    // Until the full record arrives, what a card already showed names the page.
    val known = remember(graph, type, metaId) { graph.browse.knownTitle(type, metaId) }

    Box(Modifier.fillMaxSize().background(HaloColors.Background)) {
        DetailBackdrop((meta?.background ?: known?.background ?: meta?.poster ?: known?.poster)?.takeUnless(::isStandInPosterLink))
        when {
            meta != null -> DetailBody(graph, meta, memory, navigation, menus)
            metaState.error != null -> Column(Modifier.padding(top = TvMetrics.VerticalMargin + 24.dp)) {
                Text(
                    known?.name ?: "This title",
                    style = TvType.Hero,
                    modifier = Modifier.padding(start = TvMetrics.SideMargin + 8.dp, bottom = 16.dp),
                )
                TvBodyMessage(
                    "Could not load this title. Check the connection and try again.",
                    retryModifier = Modifier.defaultFocus(memory, "retry"),
                    onRetry = { retry += 1 },
                )
            }
            else -> Column(Modifier.padding(start = TvMetrics.SideMargin + 8.dp, top = TvMetrics.VerticalMargin + 24.dp)) {
                Text(known?.name.orEmpty(), style = TvType.Hero)
            }
        }
    }
}

@Composable
private fun DetailBackdrop(url: String?) {
    Box(Modifier.fillMaxSize()) {
        HaloAsyncImage(
            url = url,
            contentDescription = null,
            modifier = Modifier.fillMaxWidth().height(400.dp).align(Alignment.TopEnd),
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to HaloColors.Background,
                    0.42f to HaloColors.Background.copy(alpha = 0.9f),
                    0.76f to HaloColors.Background.copy(alpha = 0.25f),
                    1f to HaloColors.Background.copy(alpha = 0.1f),
                ),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0.3f to HaloColors.Background.copy(alpha = 0f),
                    0.62f to HaloColors.Background.copy(alpha = 0.92f),
                    0.74f to HaloColors.Background,
                ),
            ),
        )
    }
}

@Composable
private fun DetailBody(
    graph: SessionGraph,
    meta: MetaDetail,
    memory: TvFocusMemory,
    navigation: TvBrowseNavigation,
    menus: TitleMenus,
) {
    val scope = rememberCoroutineScope()
    val itemId = remember(meta.type, meta.id) { LibraryRepository.itemId(meta.type, meta.id) }
    val library by remember(graph) { graph.library.observeActive() }.collectAsState(QueryState())
    val watchStates by remember(graph) { graph.watchStates.observe() }.collectAsState(QueryState())
    val capabilities by graph.capabilities.state.collectAsState()
    val addons by remember(graph) { graph.addons.observeEffective() }.collectAsState(QueryState())

    val isSeries = meta.videos.isNotEmpty()
    val seasons = remember(meta) { seasonNumbers(meta.videos) }
    val openOn = remember(meta, watchStates.value, itemId) { lastWatchedSeason(meta.videos, watchStates.value, itemId) }
    // Saved with the page, so Back from an episode's sources returns to its season.
    var chosenSeason by rememberSaveable(meta.id) { mutableStateOf<Int?>(null) }
    // Explicit choice first, then where the last episode was watched, then
    // the earliest season, as on the phone.
    val activeSeason = chosenSeason ?: openOn ?: seasons.firstOrNull()
    val episodes = remember(meta, activeSeason) { episodesIn(meta.videos, activeSeason) }
    val progress = remember(watchStates.value) { watchStates.value.orEmpty().associateBy { it.videoId } }
    val resumeState = remember(watchStates.value, meta, itemId) { resumeStateOf(meta, watchStates.value, itemId) }
    val targetVideo = if (isSeries) meta.videos.firstOrNull { it.id == resumeState?.videoId } ?: episodes.firstOrNull() else null
    val targetVideoId = targetVideo?.id ?: meta.movieVideoId
    val playTarget = playTargetOf(isSeries, targetVideo, resumeState, progress[targetVideoId])

    val streams by remember(graph, meta.type, targetVideoId) { graph.browse.streams(meta.type, targetVideoId) }.collectAsState(QueryState())
    val availability = remember(streams.value) {
        streams.value?.let { result -> summarizeAvailability(result.results.flatMap { it.streams }.filter { it.isPlayable }) }
    }
    val inLibrary = library.value.orEmpty().any { it.id == itemId }
    val watched = !isSeries && progress[targetVideoId]?.watched == true
    val credits = remember(meta) { creditsOf(meta) }
    val similar = remember(addons.value, meta) { addons.value?.let { pickGenreCatalog(it, meta.type, meta.genres) } }

    fun openSources(video: MetaVideo?) {
        navigation.openSources(if (video != null) episodeSources(meta, video) else movieSources(meta))
    }

    val listState = rememberLazyListState()
    val heroInView = remember { BringIntoViewRequester() }
    ProvideVerticalPivot(pivot = 56.dp) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = TvMetrics.VerticalMargin + 60.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            item(key = "hero") {
                Column(
                    Modifier
                        // Back at the buttons, the page returns to its top, title and all.
                        // Asked of the list as a request to show the whole hero rather
                        // than a scroll of its own: a separate scroll and the focused
                        // button's own request cancel each other, and the button's
                        // (which leaves the title out of view) usually came last. The
                        // list keeps the larger of two nested requests, and the hero,
                        // first on the page, can only be shown from the top.
                        .bringIntoViewRequester(heroInView)
                        .onFocusChanged { if (it.hasFocus) scope.launch { heroInView.bringIntoView() } }
                        .padding(start = TvMetrics.SideMargin + 8.dp, top = TvMetrics.VerticalMargin + 8.dp)
                        .width(560.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TitleArt(meta.name, meta.logo, logoMaxWidth = DetailLogoMaxWidth, logoMaxHeight = DetailLogoMaxHeight) {
                        Text(meta.name, style = TvType.Hero, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        // Addons send blank fields as often as missing ones.
                        val certificate = meta.ageRating?.takeIf { it.isNotBlank() }
                        MetaLine(parts = heroFacts(meta, isSeries, seasons) + listOfNotNull(certificate), rating = ratedValue(meta.imdbRating))
                        availability?.groups?.firstOrNull()?.let { best -> QualityMedal(best.medal, size = MedalSize.Compact) }
                    }
                    Text(
                        meta.description.orEmpty(),
                        style = TvType.Body.copy(fontSize = 16.sp, lineHeight = 24.sp),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    meta.director.filter { it.isNotBlank() }.take(2).takeIf { it.isNotEmpty() }?.let { directors ->
                        Text("Directed by ${directors.joinToString(" and ")}", style = TvType.Meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    playTarget.left?.let { left -> ResumeProgress(left, playTarget.progress) }
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        TvButton(
                            playTarget.label,
                            onClick = { openSources(targetVideo) },
                            icon = HaloIcons.Play,
                            style = TvButtonStyle.Primary,
                            enabled = playTarget.enabled,
                            modifier = Modifier.defaultFocus(memory, "play"),
                        )
                        TvButton(
                            if (inLibrary) "In library" else "Add to library",
                            icon = if (inLibrary) HaloIcons.Check else HaloIcons.BookmarkOutline,
                            onClick = {
                                scope.launch {
                                    // A rejected write marks the cache stale, which corrects the button.
                                    runCatching { if (inLibrary) graph.library.remove(itemId) else graph.library.add(meta) }
                                }
                            },
                            modifier = Modifier.rememberFocus(memory, "library"),
                        )
                        if (!isSeries) {
                            TvButton(
                                if (watched) "Mark as unwatched" else "Mark as watched",
                                icon = if (watched) HaloIcons.Undo else HaloIcons.DoneAll,
                                onClick = {
                                    val target = WatchMarkTarget(targetVideoId, itemId, meta.name, meta.poster)
                                    scope.launch {
                                        runCatching {
                                            if (watched) {
                                                graph.watchStates.markUnwatched(target)
                                            } else {
                                                graph.watchStates.markWatched(listOf(target), runtimeMinutes(meta.runtime)?.times(60.0))
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.rememberFocus(memory, "watched"),
                            )
                        }
                    }
                }
            }
            if (isSeries) {
                item(key = "episodes") {
                    EpisodesSection(
                        meta = meta,
                        seasons = seasons,
                        activeSeason = activeSeason,
                        episodes = episodes,
                        progress = progress,
                        resumeVideoId = resumeState?.videoId,
                        memory = memory,
                        onSeason = { chosenSeason = it },
                        onPlay = { openSources(it) },
                        onMenu = { video -> menus.openEpisode(meta, video) { openSources(video) } },
                    )
                }
            }
            if (credits.isNotEmpty() && capabilities.capabilities.people) {
                item(key = "cast") {
                    CastRow(credits, memory) { credit ->
                        navigation.openPerson(PersonRoute(credit.tmdbId ?: 0, credit.name, credit.photo?.takeIf { it.startsWith("https://") }))
                    }
                }
            } else if (credits.isNotEmpty()) {
                item(key = "cast-names") {
                    Text(
                        "Cast: " + credits.take(8).joinToString(", ") { it.name },
                        style = TvType.Meta,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = TvMetrics.ContentStart - 72.dp, end = TvMetrics.SideMargin),
                    )
                }
            }
            similar?.let { source ->
                item(key = "similar") { SimilarRow(graph, meta, source, memory, navigation, menus) }
            }
        }
    }
}

@Composable
private fun EpisodesSection(
    meta: MetaDetail,
    seasons: List<Int>,
    activeSeason: Int?,
    episodes: List<MetaVideo>,
    progress: Map<String, WatchState>,
    resumeVideoId: String?,
    memory: TvFocusMemory,
    onSeason: (Int) -> Unit,
    onPlay: (MetaVideo) -> Unit,
    onMenu: (MetaVideo) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (seasons.size > 1) {
            TvRow(startPadding = TvMetrics.SideMargin + 8.dp) {
                items(seasons, key = { it }) { season ->
                    SeasonTab(
                        label = seasonLabel(season),
                        selected = season == activeSeason,
                        onClick = { onSeason(season) },
                        modifier = Modifier.rememberFocus(memory, "season/$season"),
                    )
                }
                item(key = "count") {
                    Box(Modifier.height(36.dp).padding(start = 8.dp), contentAlignment = Alignment.CenterStart) {
                        Text("${episodes.size} episodes", style = TvType.Caption)
                    }
                }
            }
        } else {
            Text(
                "${episodes.size} episodes",
                style = TvType.Heading,
                modifier = Modifier.padding(start = TvMetrics.SideMargin + 8.dp),
            )
        }
        TvRow(startPadding = TvMetrics.SideMargin + 8.dp) {
            items(episodes, key = { it.id }) { video ->
                val state = progress[video.id]
                val fraction = state?.takeIf { it.durationSec > 0 }?.let { (it.positionSec / it.durationSec).toFloat() }
                val number = video.episode?.let { "$it. " }.orEmpty()
                val name = video.displayTitle?.takeIf { it != episodeTag(video) } ?: episodeTag(video)
                TvWideCard(
                    title = episodeTag(video),
                    imageUrl = video.thumbnail ?: meta.background,
                    line = "$number$name",
                    detail = when {
                        state?.watched == true -> "Watched"
                        state != null && state.durationSec > 0 && state.positionSec > 0 ->
                            "${timeLeft(state.positionSec, state.durationSec).lowercase()} left"
                        else -> video.released?.take(10)
                    },
                    progress = fraction?.takeIf { state?.watched != true },
                    badge = if (state?.watched == true) {
                        { WatchedBadge() }
                    } else {
                        null
                    },
                    onClick = { onPlay(video) },
                    onMenu = { onMenu(video) },
                    modifier = Modifier.rememberFocus(memory, "episode/${video.id}"),
                )
            }
        }
    }
}

/** How far into the title the viewer is: a short bar and the time left beside it. */
@Composable
private fun ResumeProgress(left: String, progress: Float?) {
    Row(
        Modifier.padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (progress != null) TvProgressBar(progress, Modifier.width(ResumeBarWidth))
        Text(left.lowercase().replaceFirstChar { it.uppercase() }, style = TvType.Caption)
    }
}

@Composable
private fun WatchedBadge() {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(HaloColors.OverlayPill)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text("Watched", color = HaloColors.Success, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SeasonTab(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TvSurface(
        onClick = onClick,
        modifier = modifier.height(36.dp),
        cornerRadius = 18.dp,
        colors = if (selected) TvSurfaceColors(HaloColors.Text, HaloColors.Primary) else TvSurfaceColors(HaloColors.FieldFill, HaloColors.SurfaceHigh),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) HaloColors.OnPrimary else HaloColors.TextMeta,
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

@Composable
private fun CastRow(credits: List<Credit>, memory: TvFocusMemory, onOpen: (Credit) -> Unit) {
    TvRow(title = "Cast", startPadding = TvMetrics.SideMargin + 8.dp) {
        items(credits, key = { "${it.name}|${it.role}" }) { credit ->
            Column(Modifier.width(112.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                TvSurface(
                    onClick = { onOpen(credit) },
                    modifier = Modifier
                        .size(96.dp)
                        .rememberFocus(memory, "cast/${credit.name}"),
                    cornerRadius = 48.dp,
                    contentAlignment = Alignment.Center,
                ) {
                    if (credit.photo != null) {
                        HaloAsyncImage(url = credit.photo, contentDescription = credit.name, modifier = Modifier.fillMaxSize().clip(CircleShape))
                    } else {
                        Text(
                            credit.name.split(' ').mapNotNull { it.firstOrNull()?.uppercase() }.take(2).joinToString(""),
                            color = HaloColors.TextMeta,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Text(credit.name, color = HaloColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                credit.role?.let { Text(it, style = TvType.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center) }
            }
        }
    }
}

@Composable
private fun SimilarRow(
    graph: SessionGraph,
    meta: MetaDetail,
    source: GenreCatalog,
    memory: TvFocusMemory,
    navigation: TvBrowseNavigation,
    menus: TitleMenus,
) {
    val state by remember(graph, source) {
        graph.browse.catalog(source.addonId, meta.type, source.catalogId, extra = mapOf("genre" to source.genre))
    }.collectAsState(QueryState())
    val titles: List<MetaPreview> = remember(state.value, meta.id) {
        val seen = mutableSetOf("${meta.type}:${meta.id}")
        // Catalogs can repeat a title; a row must not draw it twice.
        state.value.orEmpty().filter { seen.add("${it.type}:${it.id}") }.take(SimilarLimit)
    }
    if (titles.isEmpty()) return
    // Named by the genre it comes from, as the phone and desktop rows are.
    TvRow(title = "More like this · ${source.genre}", startPadding = TvMetrics.SideMargin + 8.dp) {
        items(titles, key = { "${it.type}:${it.id}" }) { title ->
            TvPosterCard(
                title = title.name,
                posterUrl = title.poster,
                fallbackUrl = metahubPosterUrl(title.id),
                width = DetailPosterWidth,
                onClick = { navigation.openDetail(MetaRef(title.type, title.id)) },
                onMenu = { menus.openPoster(title) },
                modifier = Modifier.rememberFocus(memory, "similar/${title.type}:${title.id}"),
            )
        }
        item(key = "see-all") {
            TvSeeAllCard(
                onClick = { navigation.openShelf(ShelfSnapshot("More like this", source.genre.uppercase(), titles)) },
                width = DetailPosterWidth,
                height = DetailPosterWidth * 1.5f,
            )
        }
    }
}

private const val SimilarLimit = 20
