package moe.ditto.halo.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import moe.ditto.halo.SessionGraph
import moe.ditto.halo.api.MetaDetail
import moe.ditto.halo.api.MetaVideo
import moe.ditto.halo.api.WatchState
import moe.ditto.halo.browse.Availability
import moe.ditto.halo.browse.Credit
import moe.ditto.halo.browse.GenreCatalog
import moe.ditto.halo.browse.aboutRows
import moe.ditto.halo.browse.creditsOf
import moe.ditto.halo.browse.episodeTag
import moe.ditto.halo.browse.formatReleaseDate
import moe.ditto.halo.browse.formatRuntime
import moe.ditto.halo.browse.medal
import moe.ditto.halo.browse.personSearchUrl
import moe.ditto.halo.browse.seasonLabel
import moe.ditto.halo.browse.trailerUrl
import moe.ditto.halo.downloads.DownloadStatus
import moe.ditto.halo.ui.HaloAsyncImage
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloIcons
import moe.ditto.halo.ui.QualityMedal
import moe.ditto.halo.ui.ResponsiveInfo
import moe.ditto.halo.ui.glassSurface
import moe.ditto.halo.ui.longPressClickable
import moe.ditto.halo.ui.monoStyle

/*
 * The title page as the desktop app draws it, for a tablet held sideways: a
 * screen that size has the desktop's room, so it gets the desktop's page
 * rather than the phone's column stretched across it.
 *
 * The title's art is pinned behind the whole page, sharp across the hero and
 * blurred and dimmed behind everything else, and the panels that scroll over
 * it are frosted glass, so the page keeps the title's own colours all the way
 * down. Measurements follow the desktop's stylesheet (`detail.css`), which is
 * where to look before changing one here.
 */

/** Whether a screen gets the desktop's title page instead of the phone's. */
internal fun usesWideDetail(responsive: ResponsiveInfo): Boolean = responsive.isTablet && responsive.isLandscape

/** The art across the top: three quarters of the screen, within the desktop's bounds. */
internal fun wideDetailHeroHeight(responsive: ResponsiveInfo): Dp =
    (responsive.height * 0.74f).coerceIn(480.dp, 780.dp)

/** The page's side margin, widening with the content column as the desktop's does. */
internal fun wideDetailGutter(responsive: ResponsiveInfo): Dp = when {
    responsive.contentWidth >= 1500.dp -> 56.dp
    responsive.contentWidth >= 1100.dp -> 48.dp
    else -> 32.dp
}

/** How far above the hero's bottom the Play button sits; the compact bar comes in once it has gone. */
internal val WidePlayButtonLift = 100.dp

/** The desktop's compact bar. */
internal val WideCompactBarHeight = 64.dp

private val Ink = Color(0xFFF5F5F7)
private val InkOnLight = Color(0xFF0B0B0D)
private val PageDark = Color(0xFF0B0B0D)
private val GlassFill = Color(0x7A16161A)
private val GlassEdge = Color.White.copy(alpha = 0.10f)
private val GlassLine = Color.White.copy(alpha = 0.09f)
private val GoldLow = Color(0xFFC79A2E)
private val GoldHigh = Color(0xFFF8E7A6)
private val PanelShape = RoundedCornerShape(18.dp)
private val RowShape = RoundedCornerShape(12.dp)
private val PanelGap = 20.dp
private val PanelPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 20.dp, bottom = 22.dp)

/** Below this the "Ways to watch" card leaves the hero, as on the desktop. */
private val WaysToWatchMinWidth = 940.dp

/** Below this About goes under the cast instead of beside it. */
private val SplitMinWidth = 1040.dp

private fun ink(alpha: Float) = Ink.copy(alpha = alpha)

/** Frosted glass over the pinned art, which is what [state] samples. */
private fun Modifier.wideGlass(state: HazeState): Modifier = this
    .clip(PanelShape)
    .hazeEffect(state) {
        blurRadius = 28.dp
        backgroundColor = PageDark
        tints = listOf(HazeTint(GlassFill))
    }
    .border(1.dp, GlassEdge, PanelShape)

/** Fades the layer out over its last part, the way the desktop masks the hero art. */
private fun Modifier.fadeOutBelow(from: Float): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.verticalGradient(0f to Color.Black, from to Color.Black, 1f to Color.Transparent),
            blendMode = BlendMode.DstIn,
        )
    }

@Composable
internal fun DetailWideBody(
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
    availability: Availability?,
    streamsLoading: Boolean,
    inLibrary: Boolean,
    downloaded: Boolean,
    similarSource: GenreCatalog?,
    responsive: ResponsiveInfo,
    onPlay: () -> Unit,
    onToggleLibrary: () -> Unit,
    onOpenSeasons: () -> Unit,
    onOpenPages: () -> Unit,
    onPlayEpisode: (MetaVideo) -> Unit,
    onOpenDetail: (MetaRef) -> Unit,
    onOpenShelf: (ShelfSnapshot) -> Unit,
) {
    val gutter = wideDetailGutter(responsive)
    val heroHeight = wideDetailHeroHeight(responsive)
    val credits = remember(meta) { creditsOf(meta) }
    val about = remember(meta, isSeries) { aboutRows(meta, isSeries) }
    // The panels frost the pinned art, so the art is their source. It sits
    // beside the list rather than in it, which is what lets them sample it.
    val backdrop = rememberHazeState()

    Box(modifier.fillMaxSize().background(PageDark)) {
        WideBackdrop(meta.background ?: meta.poster, heroHeight, Modifier.hazeSource(backdrop))

        // One list of what follows the hero, so the gap above each panel can
        // leave out the first one's, as the desktop's flex column does.
        val panels = buildList {
            if (isSeries) add("episodes")
            if (credits.isNotEmpty() || about.isNotEmpty()) add("people")
            if (similarSource != null) add("similar")
        }
        val panelModifier = { key: String ->
            Modifier.padding(start = gutter, end = gutter, top = if (panels.firstOrNull() == key) 0.dp else PanelGap)
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(start = responsive.contentInsetStart, bottom = responsive.bottomContentPadding + 24.dp),
        ) {
            item(key = "hero") {
                WideHero(
                    meta = meta,
                    facts = heroFacts(meta, isSeries, seasons),
                    playTarget = playTarget,
                    availability = availability,
                    streamsLoading = streamsLoading,
                    inLibrary = inLibrary,
                    downloaded = downloaded,
                    height = heroHeight,
                    gutter = gutter,
                    showWays = responsive.contentWidth >= WaysToWatchMinWidth,
                    glass = backdrop,
                    onPlay = onPlay,
                    onToggleLibrary = onToggleLibrary,
                )
            }
            if (isSeries) {
                item(key = "episodes") {
                    EpisodesPanel(
                        graph = graph,
                        meta = meta,
                        seasons = seasons,
                        activeSeason = activeSeason,
                        episodes = episodes,
                        episodeCount = episodeCount,
                        pageLabels = pageLabels,
                        page = page,
                        progress = progress,
                        resumeVideoId = resumeState?.videoId,
                        glass = backdrop,
                        onOpenSeasons = onOpenSeasons,
                        onOpenPages = onOpenPages,
                        onPlayEpisode = onPlayEpisode,
                        modifier = panelModifier("episodes"),
                    )
                }
            }
            if ("people" in panels) {
                item(key = "people") {
                    PeoplePanels(
                        credits = credits,
                        about = about,
                        besideEachOther = responsive.contentWidth >= SplitMinWidth,
                        glass = backdrop,
                        modifier = panelModifier("people"),
                    )
                }
            }
            similarSource?.let { source ->
                item(key = "similar") {
                    SimilarRow(
                        graph = graph,
                        meta = meta,
                        source = source,
                        responsive = responsive,
                        gutter = 22.dp,
                        onOpenDetail = onOpenDetail,
                        onOpenShelf = onOpenShelf,
                        modifier = panelModifier("similar").wideGlass(backdrop).padding(vertical = 20.dp),
                        posterWidth = 148.dp,
                    )
                }
            }
        }
    }
}

/**
 * The art behind the page: blurred and dimmed across all of it, and sharp
 * across the hero, darkest behind the text and fading out at its foot.
 */
@Composable
private fun WideBackdrop(art: String?, heroHeight: Dp, modifier: Modifier) {
    Box(modifier.fillMaxSize()) {
        if (art == null) return@Box
        // Scaled past the edges so the blur has art to sample there too
        // instead of fading into the page colour.
        HaloAsyncImage(
            url = art,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = 1.15f
                    scaleY = 1.15f
                }
                .blur(60.dp, BlurredEdgeTreatment.Rectangle),
        )
        Box(Modifier.fillMaxSize().background(Color(0x9E08080A)))
        Box(Modifier.fillMaxWidth().height(heroHeight).fadeOutBelow(0.66f)) {
            HaloAsyncImage(
                url = art,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                // The desktop keeps the art's upper part, where faces usually are.
                alignment = BiasAlignment(0f, -0.6f),
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(
                        0f to Color(0xDB0B0B0D),
                        0.34f to Color(0x850B0B0D),
                        0.62f to Color(0x1A0B0B0D),
                        1f to Color(0x520B0B0D),
                    ),
                ),
            )
        }
    }
}

@Composable
private fun WideHero(
    meta: MetaDetail,
    facts: List<String>,
    playTarget: PlayTarget,
    availability: Availability?,
    streamsLoading: Boolean,
    inLibrary: Boolean,
    downloaded: Boolean,
    height: Dp,
    gutter: Dp,
    showWays: Boolean,
    glass: HazeState,
    onPlay: () -> Unit,
    onToggleLibrary: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val trailer = remember(meta) { trailerUrl(meta) }
    var synopsisOpen by remember(meta.id) { mutableStateOf(false) }
    val sourceCount = availability?.total ?: 0
    val best = availability?.best?.takeIf { sourceCount > 0 }

    Box(Modifier.fillMaxWidth().height(height)) {
        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = gutter, end = gutter, top = 72.dp, bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(40.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(Modifier.weight(1f)) {
                Column(Modifier.widthIn(max = 560.dp)) {
                    HeroTitle(meta.name, meta.logo)
                    HeroFacts(
                        facts = facts,
                        // Addons send blank fields as often as missing ones.
                        certificate = meta.ageRating?.takeIf { it.isNotBlank() },
                        rating = meta.imdbRating?.takeIf { it.isNotBlank() },
                        modifier = Modifier.padding(top = 18.dp),
                    )
                    meta.description?.takeIf { it.isNotBlank() }?.let { text ->
                        Text(
                            text = text,
                            color = ink(0.86f),
                            fontSize = 15.sp,
                            lineHeight = 24.sp,
                            maxLines = if (synopsisOpen) Int.MAX_VALUE else 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .padding(top = 14.dp)
                                .clickable(onClickLabel = if (synopsisOpen) "Show less" else "Show more") { synopsisOpen = !synopsisOpen },
                        )
                    }
                    Row(
                        Modifier.padding(top = 28.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        PlayPill(playTarget.label, playTarget.enabled, onClick = onPlay)
                        RoundGlassButton(
                            icon = if (inLibrary) HaloIcons.Bookmark else HaloIcons.BookmarkOutline,
                            label = if (inLibrary) "In library" else "Add to library",
                            on = inLibrary,
                            onClick = onToggleLibrary,
                        )
                        // A download starts from the sources, where one is picked.
                        RoundGlassButton(
                            icon = if (downloaded) HaloIcons.Download else HaloIcons.DownloadOutline,
                            label = if (downloaded) "Downloaded" else "Download",
                            on = downloaded,
                            onClick = onPlay,
                        )
                        trailer?.let { url ->
                            RoundGlassButton(HaloIcons.OpenInNew, "Watch the trailer on YouTube", on = false) {
                                runCatching { uriHandler.openUri(url) }
                            }
                        }
                    }
                    Row(
                        Modifier.padding(top = 16.dp).heightIn(min = 30.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        val fraction = playTarget.progress
                        if (fraction != null) {
                            Box(Modifier.width(160.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.2f))) {
                                Box(Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f)).fillMaxHeight().background(Ink))
                            }
                            playTarget.left?.let { Text(it.lowercase(), color = ink(0.72f), fontSize = 13.sp) }
                        }
                        if (best != null) {
                            if (fraction != null) Text("·", color = ink(0.4f), fontSize = 13.sp)
                            QualityMedal(best.medal)
                            Text(
                                text = "$sourceCount ${if (sourceCount == 1) "source" else "sources"}",
                                color = ink(0.72f),
                                fontSize = 13.sp,
                                textDecoration = TextDecoration.Underline,
                                modifier = Modifier.clickable(role = Role.Button, onClick = onPlay),
                            )
                        }
                    }
                }
            }
            if (showWays) {
                WaysToWatchCard(availability, streamsLoading, glass, onPlay, Modifier.width(340.dp))
            }
        }
    }
}

/**
 * The title's logo art when its addon has one, else the name. A logo that
 * fails to load falls back to the name, never a gap.
 */
@Composable
private fun HeroTitle(name: String, logo: String?) {
    val painter = logo?.let { rememberAsyncImagePainter(model = it, contentScale = ContentScale.Fit) }
    val state = painter?.state?.collectAsState()?.value
    val nameText = @Composable {
        Text(
            text = name,
            color = Ink,
            fontSize = 44.sp,
            lineHeight = 48.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (painter == null || state is AsyncImagePainter.State.Error) {
        nameText()
        return
    }
    // Bounded both ways: addons ship anything from square marks to long wordmarks.
    // The name stands in while the logo loads, so the title is never a gap.
    // The logo stays composed meanwhile: it is what asks for the picture.
    Box {
        if (state !is AsyncImagePainter.State.Success) {
            nameText()
        }
        Image(
            painter = painter,
            contentDescription = name,
            contentScale = ContentScale.Fit,
            alignment = Alignment.BottomStart,
            modifier = Modifier.widthIn(max = 440.dp).heightIn(max = 120.dp),
        )
    }
}

@Composable
private fun HeroFacts(facts: List<String>, certificate: String?, rating: String?, modifier: Modifier) {
    val separator = @Composable { Text("·", color = ink(0.4f), fontSize = 14.sp, modifier = Modifier.padding(horizontal = 9.dp)) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        facts.forEachIndexed { index, fact ->
            if (index > 0) separator()
            Text(fact, color = ink(0.74f), fontSize = 14.sp, maxLines = 1)
        }
        certificate?.takeIf { it.isNotBlank() }?.let {
            if (facts.isNotEmpty()) separator()
            Text(
                text = it,
                color = ink(0.74f),
                fontSize = 12.sp,
                maxLines = 1,
                modifier = Modifier.border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp),
            )
        }
        rating?.let {
            if (facts.isNotEmpty() || certificate != null) separator()
            Text(
                text = "IMDb",
                color = InkOnLight,
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.clip(RoundedCornerShape(3.dp)).background(HaloColors.Gold).padding(horizontal = 4.dp, vertical = 1.dp),
            )
            Text(it, color = ink(0.74f), fontSize = 14.sp, modifier = Modifier.padding(start = 6.dp))
        }
    }
}

@Composable
private fun PlayPill(label: String, enabled: Boolean, small: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier
            .height(if (small) 36.dp else 46.dp)
            .clip(RoundedCornerShape(if (small) 9.dp else 10.dp))
            .background(if (enabled) Ink else Ink.copy(alpha = 0.4f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (small) 18.dp else 26.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(HaloIcons.Play, contentDescription = null, tint = InkOnLight, modifier = Modifier.size(if (small) 14.dp else 16.dp))
        Text(label, color = InkOnLight, fontSize = if (small) 14.sp else 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** A round glass button; [on] fills it, the way the desktop marks a title that is in the library. */
@Composable
private fun RoundGlassButton(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(if (on) Ink.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.12f))
            .border(1.dp, GlassEdge, CircleShape)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = if (on) InkOnLight else Ink, modifier = Modifier.size(20.dp))
    }
}

/** What the addons offer for what Play plays, per quality, with the share of sources each holds. */
@Composable
private fun WaysToWatchCard(
    availability: Availability?,
    loading: Boolean,
    glass: HazeState,
    onBrowse: () -> Unit,
    modifier: Modifier,
) {
    val groups = availability?.groups.orEmpty()
    val most = groups.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1
    Column(modifier.wideGlass(glass).padding(PanelPadding)) {
        Text("Ways to watch", color = ink(0.72f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 4.dp))
        if (loading) {
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(color = ink(0.55f), strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                Text("Asking your addons…", color = ink(0.55f), fontSize = 13.sp)
            }
        }
        groups.forEach { group ->
            Row(
                Modifier
                    .padding(top = 4.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(role = Role.Button, onClick = onBrowse)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                QualityMedal(group.medal)
                Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.1f))) {
                    val share = (group.count.toFloat() / most).coerceAtLeast(0.04f)
                    // Gold is spent on the top tier only, as on the medal.
                    val fill = if (group.rank == 0) Brush.horizontalGradient(listOf(GoldLow, GoldHigh)) else Brush.horizontalGradient(listOf(ink(0.7f), ink(0.7f)))
                    Box(Modifier.fillMaxWidth(share).fillMaxHeight().background(fill))
                }
                Text(
                    text = "${group.count} ${if (group.count == 1) "source" else "sources"}",
                    color = ink(0.72f),
                    fontSize = 13.sp,
                    textAlign = TextAlign.End,
                    modifier = Modifier.widthIn(min = 74.dp),
                )
            }
        }
        if (!loading && (availability == null || availability.total == 0)) {
            Text("No addon offers streams for this title.", color = ink(0.55f), fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
        }
        Box(
            Modifier
                .padding(top = 16.dp)
                .fillMaxWidth()
                .height(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White.copy(alpha = 0.1f))
                .border(1.dp, GlassEdge, RoundedCornerShape(10.dp))
                .clickable(role = Role.Button, onClick = onBrowse),
            contentAlignment = Alignment.Center,
        ) {
            Text("Browse all sources", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** A panel's heading, with its quiet count beside it. */
@Composable
private fun PanelTitle(title: String, note: String?, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.alignByBaseline())
        note?.let {
            Text(it, style = monoStyle(12.sp, FontWeight.Normal, ink(0.5f)), maxLines = 1, modifier = Modifier.alignByBaseline())
        }
    }
}

@Composable
private fun EpisodesPanel(
    graph: SessionGraph,
    meta: MetaDetail,
    seasons: List<Int>,
    activeSeason: Int?,
    episodes: List<MetaVideo>,
    episodeCount: Int,
    pageLabels: List<String>,
    page: Int,
    progress: Map<String, WatchState>,
    resumeVideoId: String?,
    glass: HazeState,
    onOpenSeasons: () -> Unit,
    onOpenPages: () -> Unit,
    onPlayEpisode: (MetaVideo) -> Unit,
    modifier: Modifier,
) {
    val menus = LocalTitleMenus.current
    val year = episodes.firstNotNullOfOrNull { it.released }?.take(4)
    val note = listOfNotNull("$episodeCount ${if (episodeCount == 1) "episode" else "episodes"}", year).joinToString(" · ")
    Column(modifier.fillMaxWidth().wideGlass(glass).padding(PanelPadding)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            PanelTitle("Episodes", note, Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (seasons.size > 1 && activeSeason != null) PickerPill(seasonLabel(activeSeason), onClick = onOpenSeasons)
                if (pageLabels.size > 1) PickerPill(pageLabels[page], active = true, onClick = onOpenPages)
            }
        }
        Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            episodes.forEach { video ->
                WideEpisodeRow(
                    video = video,
                    runtime = meta.runtime,
                    state = progress[video.id],
                    current = video.id == resumeVideoId,
                    saved = graph.downloads.entryFor(video.id)?.status == DownloadStatus.Done,
                    onClick = { onPlayEpisode(video) },
                    menu = menus?.let { m -> { m.openEpisode(meta, video) { onPlayEpisode(video) } } },
                )
            }
        }
    }
}

@Composable
private fun WideEpisodeRow(
    video: MetaVideo,
    runtime: String?,
    state: WatchState?,
    current: Boolean,
    saved: Boolean,
    onClick: () -> Unit,
    menu: (() -> Unit)?,
) {
    val fraction = state?.takeIf { it.durationSec > 0 }?.let { if (it.watched) 1.0 else it.positionSec / it.durationSec } ?: 0.0
    val inProgress = fraction > 0.0 && fraction < 1.0
    val left = state?.takeIf { it.durationSec > 0 && !it.watched }?.let { timeLeft(it.positionSec, it.durationSec).lowercase() + " left" }
    val aired = formatReleaseDate(video.released)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RowShape)
            .background(if (current) Color.White.copy(alpha = 0.05f) else Color.Transparent)
            .border(1.dp, if (current) GlassEdge else Color.Transparent, RowShape)
            .longPressClickable(onClick = onClick, onLongPress = menu)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.width(152.dp).height(86.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.05f))) {
            if (video.thumbnail != null) {
                HaloAsyncImage(url = video.thumbnail, contentDescription = null, modifier = Modifier.fillMaxSize())
            } else {
                Text("STILL", style = monoStyle(11.sp, color = ink(0.35f)), modifier = Modifier.align(Alignment.Center))
            }
            if (fraction > 0.0) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.25f))) {
                    Box(Modifier.fillMaxWidth(fraction.toFloat().coerceIn(0.02f, 1f)).fillMaxHeight().background(HaloColors.Accent))
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = episodeTag(video),
                    style = monoStyle(12.sp, FontWeight.Normal, if (current || inProgress) HaloColors.Accent else ink(0.5f)),
                    maxLines = 1,
                )
                if (state?.watched == true) {
                    Icon(HaloIcons.Check, contentDescription = "Watched", tint = HaloColors.Success, modifier = Modifier.size(14.dp))
                }
                Text(
                    text = video.displayTitle ?: episodeTag(video),
                    color = Ink,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (saved) {
                    Text(
                        text = "SAVED",
                        style = monoStyle(11.sp, FontWeight.Bold, HaloColors.Success),
                        modifier = Modifier.clip(RoundedCornerShape(3.dp)).background(HaloColors.Success.copy(alpha = 0.14f)).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            video.overview?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = ink(0.55f), fontSize = 13.sp, lineHeight = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            (left ?: formatRuntime(runtime))?.let { Text(it, style = monoStyle(12.sp, FontWeight.Normal, ink(0.5f)), maxLines = 1) }
            aired?.let { Text(it, style = monoStyle(12.sp, FontWeight.Normal, ink(0.5f)), maxLines = 1) }
        }
    }
}

/** The cast beside the facts, About taking the cast's height; one above the other where there is no room. */
@Composable
private fun PeoplePanels(
    credits: List<Credit>,
    about: List<Pair<String, String>>,
    besideEachOther: Boolean,
    glass: HazeState,
    modifier: Modifier,
) {
    val cast = @Composable { CastPanel(credits, glass) }
    when {
        credits.isEmpty() -> AboutPanel(about, glass, scrolls = false, modifier = modifier.fillMaxWidth())
        about.isEmpty() -> Box(modifier) { cast() }
        besideEachOther -> BesideLayout(
            modifier = modifier.fillMaxWidth(),
            main = cast,
            side = { AboutPanel(about, glass, scrolls = true, modifier = Modifier.fillMaxSize()) },
        )
        else -> Column(modifier, verticalArrangement = Arrangement.spacedBy(PanelGap)) {
            cast()
            AboutPanel(about, glass, scrolls = false, modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * [main] across what is left of the row and [side] at a fixed width beside
 * it, exactly as tall as [main]. Measured rather than matched by intrinsics,
 * which the cast's scrolling row cannot answer.
 */
@Composable
private fun BesideLayout(modifier: Modifier, main: @Composable () -> Unit, side: @Composable () -> Unit) {
    Layout(contents = listOf(main, side), modifier = modifier) { (mainItems, sideItems), constraints ->
        val gap = PanelGap.roundToPx()
        val sideWidth = AboutPanelWidth.roundToPx()
        val mainWidth = (constraints.maxWidth - sideWidth - gap).coerceAtLeast(0)
        val mainPlaced = mainItems.first().measure(Constraints(minWidth = mainWidth, maxWidth = mainWidth))
        val sidePlaced = sideItems.first().measure(Constraints.fixed(sideWidth, mainPlaced.height))
        layout(constraints.maxWidth, mainPlaced.height) {
            mainPlaced.place(0, 0)
            sidePlaced.place(constraints.maxWidth - sideWidth, 0)
        }
    }
}

private val AboutPanelWidth = 360.dp

/** The pictured cast, else names on initials; pressing a card looks the person up. */
@Composable
private fun CastPanel(credits: List<Credit>, glass: HazeState) {
    val uriHandler = LocalUriHandler.current
    Column(Modifier.fillMaxWidth().wideGlass(glass).padding(top = 20.dp, bottom = 22.dp)) {
        PanelTitle(
            "Cast and crew",
            "${credits.size} ${if (credits.size == 1) "person" else "people"}",
            Modifier.padding(horizontal = 22.dp),
        )
        LazyRow(
            Modifier.padding(top = 16.dp),
            contentPadding = PaddingValues(horizontal = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            items(items = credits, key = { it.name }) { credit ->
                Column(
                    Modifier
                        .width(128.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(role = Role.Button, onClickLabel = "Look up ${credit.name}") {
                            runCatching { uriHandler.openUri(personSearchUrl(credit.name)) }
                        },
                ) {
                    Box(
                        Modifier.width(128.dp).height(160.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.05f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(initialsOf(credit.name), color = ink(0.45f), fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                        credit.photo?.let { HaloAsyncImage(url = it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
                    }
                    Text(
                        credit.name,
                        color = Ink,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    credit.role?.let {
                        Text(it, color = ink(0.5f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                    }
                }
            }
        }
    }
}

/** Facts the addon sends, as a quiet two-column list; beside the cast a long one scrolls inside the panel. */
@Composable
private fun AboutPanel(rows: List<Pair<String, String>>, glass: HazeState, scrolls: Boolean, modifier: Modifier) {
    val scroll = if (scrolls) Modifier.verticalScroll(rememberScrollState()) else Modifier
    Column(modifier.wideGlass(glass).then(scroll).padding(PanelPadding)) {
        PanelTitle("About", null)
        Column(Modifier.padding(top = 8.dp)) {
            rows.forEachIndexed { index, (label, value) ->
                if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(GlassLine))
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp, bottom = if (index == rows.lastIndex) 0.dp else 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    Text(label, color = ink(0.5f), fontSize = 14.sp)
                    Text(value, color = Ink, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * The glass bar that takes over the top once the Play button has scrolled out
 * of sight, so playback is never a scroll away: the title, the time left and
 * a small Play.
 */
@Composable
internal fun BoxScope.WideCompactBar(
    visible: Boolean,
    title: String,
    playTarget: PlayTarget?,
    titleStart: Dp,
    endPadding: Dp,
    onPlay: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(tween(300)) { -it } + fadeIn(tween(300)),
        exit = slideOutVertically(tween(300)) { -it } + fadeOut(tween(300)),
        modifier = Modifier.align(Alignment.TopStart),
    ) {
        val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        Box(Modifier.fillMaxWidth().glassSurface(Color(0x99101014))) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = top)
                    .height(WideCompactBarHeight)
                    .padding(start = titleStart, end = endPadding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(title, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                playTarget?.let { target ->
                    target.left?.let { Text(it.lowercase(), color = ink(0.55f), fontSize = 13.sp, maxLines = 1) }
                    PlayPill(target.label, target.enabled, small = true, onClick = onPlay)
                }
            }
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(1.dp).background(GlassEdge))
        }
    }
}
