package moe.ditto.halo.tv.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import moe.ditto.halo.api.MetaDetail
import moe.ditto.halo.api.MetaPreview
import moe.ditto.halo.api.MetaVideo
import moe.ditto.halo.api.movieVideoId
import moe.ditto.halo.api.WatchState
import moe.ditto.halo.browse.Credit
import moe.ditto.halo.browse.creditsOf
import moe.ditto.halo.browse.episodesIn
import moe.ditto.halo.browse.episodeTag
import moe.ditto.halo.browse.formatReleaseDate
import moe.ditto.halo.browse.GenreCatalog
import moe.ditto.halo.browse.isStandInPosterLink
import moe.ditto.halo.browse.lastWatchedSeason
import moe.ditto.halo.browse.medal
import moe.ditto.halo.browse.pickGenreCatalog
import moe.ditto.halo.browse.ratedValue
import moe.ditto.halo.browse.seasonLabel
import moe.ditto.halo.browse.seasonNumbers
import moe.ditto.halo.browse.summarizeAvailability
import moe.ditto.halo.cache.QueryState
import moe.ditto.halo.screens.heroFacts
import moe.ditto.halo.screens.MetaRef
import moe.ditto.halo.screens.playTargetOf
import moe.ditto.halo.screens.resumeStateOf
import moe.ditto.halo.screens.ShelfSnapshot
import moe.ditto.halo.screens.timeLeft
import moe.ditto.halo.screens.TitleArt
import moe.ditto.halo.screens.TitleMenus
import moe.ditto.halo.SessionGraph
import moe.ditto.halo.shell.episodeSources
import moe.ditto.halo.shell.movieSources
import moe.ditto.halo.shell.PersonRoute
import moe.ditto.halo.sync.LibraryRepository
import moe.ditto.halo.sync.runtimeMinutes
import moe.ditto.halo.sync.WatchMarkTarget
import moe.ditto.halo.tv.kit.defaultFocus
import moe.ditto.halo.tv.kit.rememberFocus
import moe.ditto.halo.tv.kit.rememberTvFocusMemory
import moe.ditto.halo.tv.kit.TvButton
import moe.ditto.halo.tv.kit.TvButtonStyle
import moe.ditto.halo.tv.kit.TvFocusMemory
import moe.ditto.halo.tv.kit.TvMetrics
import moe.ditto.halo.tv.kit.TvPosterCard
import moe.ditto.halo.tv.kit.TvProgressBar
import moe.ditto.halo.tv.kit.TvRow
import moe.ditto.halo.tv.kit.TvRowSurface
import moe.ditto.halo.tv.kit.TvSidePanel
import moe.ditto.halo.tv.kit.TvSurface
import moe.ditto.halo.tv.kit.TvSurfaceColors
import moe.ditto.halo.tv.kit.TvType
import moe.ditto.halo.ui.HaloAsyncImage
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloIcons
import moe.ditto.halo.ui.MedalSize
import moe.ditto.halo.ui.metahubPosterUrl
import moe.ditto.halo.ui.MetaLine
import moe.ditto.halo.ui.QualityMedal

private val DetailPosterWidth = 112.dp
private val DetailLogoMaxWidth = 400.dp
private val DetailLogoMaxHeight = 110.dp
private val ResumeBarWidth = 160.dp

/** More seasons than this and the pills give way to one button and a list. */
private const val SeasonPillLimit = 3
private val EpisodeSynopsisWidth = 720.dp

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
    val seasonList = remember(meta, seasons, progress) { seasonSummaries(meta.videos, seasons, progress) }
    var pickingSeason by remember { mutableStateOf(false) }
    val seasonButton = remember { FocusRequester() }
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
    Box(Modifier.fillMaxSize()) {
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
                            seasons = seasonList,
                            activeSeason = activeSeason,
                            episodes = episodes,
                            progress = progress,
                            resumeVideoId = resumeState?.videoId,
                            memory = memory,
                            seasonButton = seasonButton,
                            onSeason = { chosenSeason = it },
                            onPickSeason = { pickingSeason = true },
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
        if (pickingSeason) {
            SeasonPanel(
                seasons = seasonList,
                activeSeason = activeSeason,
                returnFocusTo = seasonButton,
                onSelect = { season ->
                    chosenSeason = season
                    pickingSeason = false
                },
                onDismiss = { pickingSeason = false },
            )
        }
    }
}

/** One season's line in the season list: how many episodes, and how many are watched. */
private data class SeasonSummary(val season: Int, val episodes: Int, val watched: Int) {
    val line: String
        get() = buildString {
            append(if (episodes == 1) "1 episode" else "$episodes episodes")
            if (watched >= episodes && episodes > 0) append(" · watched") else if (watched > 0) append(" · $watched watched")
        }
}

private fun seasonSummaries(videos: List<MetaVideo>, seasons: List<Int>, progress: Map<String, WatchState>): List<SeasonSummary> {
    val bySeason = videos.groupBy { it.season }
    return seasons.map { season ->
        val inSeason = bySeason[season].orEmpty()
        SeasonSummary(season, inSeason.size, inSeason.count { progress[it.id]?.watched == true })
    }
}

/**
 * A series' episodes: the season choice, a row of episode cards, and the
 * focused episode's synopsis under them. A few seasons are pills a press
 * apart; past [SeasonPillLimit] the pills would run off the screen, so one
 * button opens the full list of seasons instead.
 */
@Composable
private fun EpisodesSection(
    meta: MetaDetail,
    seasons: List<SeasonSummary>,
    activeSeason: Int?,
    episodes: List<MetaVideo>,
    progress: Map<String, WatchState>,
    resumeVideoId: String?,
    memory: TvFocusMemory,
    seasonButton: FocusRequester,
    onSeason: (Int) -> Unit,
    onPickSeason: () -> Unit,
    onPlay: (MetaVideo) -> Unit,
    onMenu: (MetaVideo) -> Unit,
) {
    val start = TvMetrics.SideMargin + 8.dp
    val active = seasons.firstOrNull { it.season == activeSeason }
    var focusedEpisode by remember(episodes) { mutableStateOf<MetaVideo?>(null) }
    val described = focusedEpisode ?: episodes.firstOrNull { it.id == resumeVideoId } ?: episodes.firstOrNull()
    val scope = rememberCoroutineScope()
    val sectionInView = remember { BringIntoViewRequester() }

    Column(
        // Focus anywhere in it shows the whole section, synopsis included:
        // the list would otherwise stop as soon as the focused card is in
        // view, with the synopsis under the screen's edge.
        Modifier
            .bringIntoViewRequester(sectionInView)
            .onFocusChanged { if (it.hasFocus) scope.launch { sectionInView.bringIntoView() } },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when {
            seasons.size > SeasonPillLimit -> Row(
                Modifier.padding(start = start),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SeasonButton(
                    label = activeSeason?.let(::seasonLabel) ?: "Season",
                    onClick = onPickSeason,
                    modifier = Modifier.focusRequester(seasonButton).rememberFocus(memory, "season-picker"),
                )
                active?.let { Text(it.line, style = TvType.Meta) }
            }
            seasons.size > 1 -> TvRow(startPadding = start) {
                items(seasons, key = { it.season }) { summary ->
                    SeasonTab(
                        label = seasonLabel(summary.season),
                        selected = summary.season == activeSeason,
                        onClick = { onSeason(summary.season) },
                        modifier = Modifier.rememberFocus(memory, "season/${summary.season}"),
                    )
                }
                item(key = "count") {
                    Box(Modifier.height(36.dp).padding(start = 8.dp), contentAlignment = Alignment.CenterStart) {
                        Text(active?.line.orEmpty(), style = TvType.Caption)
                    }
                }
            }
            else -> Row(Modifier.padding(start = start), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Episodes", style = TvType.Heading)
                Text(active?.line ?: "${episodes.size} episodes", style = TvType.Meta)
            }
        }
        TvRow(startPadding = start) {
            items(episodes, key = { it.id }) { video ->
                EpisodeCard(
                    meta = meta,
                    video = video,
                    state = progress[video.id],
                    onClick = { onPlay(video) },
                    onMenu = { onMenu(video) },
                    onFocused = { focusedEpisode = video },
                    modifier = Modifier.rememberFocus(memory, "episode/${video.id}"),
                )
            }
        }
        // The synopsis of the episode in focus, or of the one to play next;
        // its room is kept while the episodes change, so the page holds still.
        Text(
            described?.overview?.trim().orEmpty(),
            style = TvType.Body.copy(fontSize = 15.sp, lineHeight = 22.sp, color = HaloColors.TextMeta),
            maxLines = 3,
            minLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = start).width(EpisodeSynopsisWidth),
        )
    }
}

/**
 * An episode: its still with a small number tag, a watched mark or progress
 * bar, then its name on up to two lines and when it aired or how much is left.
 */
@Composable
private fun EpisodeCard(
    meta: MetaDetail,
    video: MetaVideo,
    state: WatchState?,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val watched = state?.watched == true
    val fraction = state?.takeIf { it.durationSec > 0 && !watched }?.let { (it.positionSec / it.durationSec).toFloat() }
    val tag = episodeTag(video)
    val name = video.displayTitle?.takeIf { it.isNotBlank() && it != tag } ?: tag
    Column(modifier.width(TvMetrics.WideCardWidth), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TvSurface(
            onClick = onClick,
            onMenu = onMenu,
            modifier = Modifier
                .size(TvMetrics.WideCardWidth, TvMetrics.WideCardHeight)
                .semantics { contentDescription = listOfNotNull(tag, name.takeIf { it != tag }).joinToString(", ") },
            onFocusChange = { isFocused ->
                focused = isFocused
                if (isFocused) onFocused()
            },
        ) {
            HaloAsyncImage(url = video.thumbnail ?: meta.background, contentDescription = null, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color(0xA6050709))))
            video.episode?.let { number ->
                OverlayTag("E$number", HaloColors.Text, Modifier.align(Alignment.TopStart).padding(8.dp))
            }
            if (watched) OverlayTag("Watched", HaloColors.Success, Modifier.align(Alignment.TopEnd).padding(8.dp))
            if (fraction != null && fraction > 0f) {
                TvProgressBar(fraction, Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 12.dp, bottom = 10.dp))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                name,
                color = if (focused) HaloColors.Text else HaloColors.TextMeta,
                fontSize = 15.sp,
                lineHeight = 19.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val line = when {
                watched -> null
                state != null && state.durationSec > 0 && state.positionSec > 0 ->
                    "${timeLeft(state.positionSec, state.durationSec).lowercase()} left"
                else -> formatReleaseDate(video.released)
            }
            line?.let { Text(it, style = TvType.Caption, maxLines = 1) }
        }
    }
}

/** A short label on a dark pill over art: an episode's number, its watched mark. */
@Composable
private fun OverlayTag(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        color = color,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(HaloColors.OverlayPill)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** The season in view, as a button that opens every season. */
@Composable
private fun SeasonButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TvSurface(
        onClick = onClick,
        modifier = modifier.height(40.dp).semantics { contentDescription = "$label, choose a season" },
        cornerRadius = 20.dp,
        colors = TvSurfaceColors(HaloColors.FieldFill, HaloColors.Text),
        contentAlignment = Alignment.Center,
    ) { focused ->
        Row(
            Modifier.padding(start = 18.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val ink = if (focused) HaloColors.OnPrimary else HaloColors.Text
            Text(label, color = ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Icon(HaloIcons.ChevronDown, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
        }
    }
}

/** Every season, each with its episode count and what is watched; opens on the one in view. */
@Composable
private fun SeasonPanel(
    seasons: List<SeasonSummary>,
    activeSeason: Int?,
    returnFocusTo: FocusRequester,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val activeIndex = seasons.indexOfFirst { it.season == activeSeason }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (activeIndex - 2).coerceAtLeast(0))
    val activeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // After the panel's own first focus, which goes to its first row.
        withFrameNanos { }
        runCatching { activeFocus.requestFocus() }
    }
    TvSidePanel(title = "Seasons", onDismiss = onDismiss, returnFocusTo = returnFocusTo, width = 420.dp) {
        LazyColumn(
            Modifier.weight(1f),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            itemsIndexed(seasons, key = { _, summary -> summary.season }) { index, summary ->
                TvRowSurface(
                    title = seasonLabel(summary.season),
                    hint = summary.line,
                    onClick = { onSelect(summary.season) },
                    modifier = (if (index == activeIndex) Modifier.focusRequester(activeFocus) else Modifier)
                        .semantics { if (summary.season == activeSeason) stateDescription = "Selected" },
                    trailing = { if (summary.season == activeSeason) Icon(HaloIcons.Check, contentDescription = null, tint = HaloColors.AccentLight) },
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
                type = title.type,
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
