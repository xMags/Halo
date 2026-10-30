package moe.ditto.halo.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.ditto.halo.SessionGraph
import moe.ditto.halo.api.MetaCard
import moe.ditto.halo.browse.ContinueEntry
import moe.ditto.halo.browse.historyVideoTag
import moe.ditto.halo.cache.QueryState
import moe.ditto.halo.ui.HaloAsyncImage
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloIcons
import moe.ditto.halo.ui.ResponsiveInfo
import moe.ditto.halo.ui.fadeHiddenEdges
import moe.ditto.halo.ui.RowHeading
import moe.ditto.halo.ui.longPressClickable
import moe.ditto.halo.ui.monoStyle

/*
 * Home's featured banner and Continue watching as the desktop app draws them,
 * for a tablet held sideways, which has the desktop's room. Measurements
 * follow the desktop's stylesheets (`home.css`, `shelves.css`, the `.view`
 * metrics in `shell.css` at its widest tier).
 */

/** Whether Home gets the desktop's banner and Continue watching cards. */
internal fun usesWideHome(responsive: ResponsiveInfo): Boolean = responsive.isTablet && responsive.isLandscape

/** The banner's height: the desktop's widest, short of half the screen so the rows still start above the fold. */
internal fun wideFeaturedHeight(responsive: ResponsiveInfo): Dp = minOf(420.dp, responsive.height * 0.45f)

private val ContinueCardWidth = 336.dp
private val CardShape = RoundedCornerShape(8.dp)
private val ChipShape = RoundedCornerShape(3.dp)
private val ChipFill = Color.Black.copy(alpha = 0.69f)
private val HeroShape = RoundedCornerShape(8.dp)
private val ButtonShape = RoundedCornerShape(6.dp)

/** The desktop's dark hero scrim: solid behind the text, gone by the art's far side. */
private val ScrimSolid = Color(0xF518181A)
private val ScrimHalf = Color(0x9E18181A)

/** Fades the layer out over its lower part, as the desktop masks the banner art into the page. */
internal fun Modifier.fadeOutBelow(from: Float): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.verticalGradient(0f to Color.Black, from to Color.Black, 1f to Color.Transparent),
            blendMode = BlendMode.DstIn,
        )
    }

@Composable
internal fun WideFeaturedHero(
    card: MetaCard,
    height: Dp,
    gutter: Dp,
    inLibrary: Boolean,
    /** True until it is known whether this title has logo art. */
    logoPending: Boolean,
    count: Int,
    index: Int,
    onSelect: (Int) -> Unit,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onToggleLibrary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val menus = LocalTitleMenus.current
    Box(
        modifier
            .padding(horizontal = gutter)
            .fillMaxWidth()
            .height(height)
            .clip(HeroShape)
            .longPressClickable(onClick = onOpen, role = null, onLongPress = menus?.let { { it.openPoster(card) } }),
    ) {
        FeaturedArt(card) { shown ->
            Box(Modifier.fillMaxSize().fadeOutBelow(0.5f)) {
                HaloAsyncImage(url = shown.background ?: shown.poster, contentDescription = null, modifier = Modifier.fillMaxSize())
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.horizontalGradient(0f to ScrimSolid, 0.52f to ScrimHalf, 0.92f to Color.Transparent),
                    ),
                )
            }
        }
        FeaturedText(card, Modifier.fillMaxSize()) { shown ->
            Box(Modifier.fillMaxSize()) {
                Column(
                    Modifier.align(Alignment.CenterStart).padding(horizontal = 40.dp).widthIn(max = 580.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "FEATURED",
                        style = monoStyle(12.sp, FontWeight.Bold, HaloColors.Accent),
                        letterSpacing = 1.92.sp,
                    )
                    FeaturedTitle(shown.name, shown.logo, 46.sp, logoPending)
                    // Addons send blank fields as often as missing ones.
                    FeaturedFacts(shown.imdbRating?.takeIf { it.isNotBlank() }, shown.releaseInfo?.takeIf { it.isNotBlank() })
                    shown.description?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            color = HaloColors.Text,
                            fontSize = 16.sp,
                            lineHeight = 23.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 520.dp),
                        )
                    }
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        HeroButton(
                            label = if (shown.type == "movie") "Play" else "Choose episode",
                            icon = HaloIcons.Play,
                            accent = true,
                            onClick = onPlay,
                        )
                        HeroButton(label = "Details", icon = null, accent = false, onClick = onOpen)
                        HeroButton(
                            label = if (inLibrary) "In library" else "Add to library",
                            icon = if (inLibrary) HaloIcons.Bookmark else HaloIcons.BookmarkOutline,
                            iconTint = if (inLibrary) HaloColors.AccentLight else HaloColors.Text,
                            accent = false,
                            onClick = onToggleLibrary,
                        )
                    }
                }
            }
        }
        if (count > 1) {
            FeaturedDots(
                count = count,
                index = index,
                onSelect = onSelect,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp),
            )
        }
    }
}

/** The featured title as [TitleArt], with the name in the hero's display style. */
@Composable
internal fun FeaturedTitle(
    name: String,
    logo: String?,
    size: TextUnit,
    logoPending: Boolean,
    logoMaxWidth: Dp = 380.dp,
    logoMaxHeight: Dp = 110.dp,
) {
    TitleArt(name, logo, logoMaxWidth, logoMaxHeight, logoPending) {
        Text(
            text = name,
            color = HaloColors.Text,
            fontSize = size,
            lineHeight = size * 1.1f,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.36).sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun FeaturedFacts(rating: String?, year: String?) {
    if (rating == null && year == null) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        rating?.let { Text("★ $it", color = HaloColors.Gold, fontSize = 15.sp) }
        if (rating != null && year != null) Text("·", color = HaloColors.TextDim, fontSize = 15.sp)
        year?.let { Text(it, color = HaloColors.TextMeta, fontSize = 15.sp, maxLines = 1) }
    }
}

@Composable
internal fun HeroButton(
    /** Null for an icon-only button, which then names itself with [iconDescription]. */
    label: String?,
    icon: ImageVector?,
    accent: Boolean,
    onClick: () -> Unit,
    iconTint: Color = if (accent) Color.White else HaloColors.Text,
    iconDescription: String? = null,
    height: Dp = 36.dp,
) {
    Row(
        Modifier
            .height(height)
            .clip(ButtonShape)
            .background(if (accent) HaloColors.Accent else HaloColors.Glass)
            .border(1.dp, if (accent) Color.Transparent else HaloColors.GlassBorder, ButtonShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        icon?.let { Icon(it, contentDescription = iconDescription, tint = iconTint, modifier = Modifier.size(16.dp)) }
        label?.let { Text(it, color = if (accent) Color.White else HaloColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
    }
}

/**
 * Continue watching as the desktop draws it: a wide card per title showing the
 * episode left partway, tagged with which one it is and how long is left,
 * rather than the show's poster.
 */
@Composable
internal fun WideContinueRow(
    graph: SessionGraph,
    entries: List<ContinueEntry>,
    onOpen: (ContinueEntry) -> Unit,
    onSeeAll: () -> Unit,
    gutter: Dp,
    gap: Dp,
    bottomPadding: Dp,
) {
    val menus = LocalTitleMenus.current
    val stripState = rememberLazyListState()
    Column(Modifier.padding(bottom = bottomPadding)) {
        RowHeading(title = "Continue watching", source = "${entries.size} IN PROGRESS", gutter = gutter, onSeeAll = onSeeAll)
        // Stops at the gutter like every row on this page, fading the end that hides cards.
        LazyRow(
            state = stripState,
            modifier = Modifier.padding(horizontal = gutter).fadeHiddenEdges(stripState),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            items(items = entries, key = { it.itemId }) { entry ->
                ContinueCard(
                    graph = graph,
                    entry = entry,
                    onClick = { onOpen(entry) },
                    onLongPress = menus?.let { m -> { m.openContinue(entry) } },
                )
            }
        }
    }
}

@Composable
private fun ContinueCard(graph: SessionGraph, entry: ContinueEntry, onClick: () -> Unit, onLongPress: (() -> Unit)?) {
    // The full record names the episode's still; the card shows the show's
    // own wide art until it arrives, and its poster when there is neither.
    val metaState by remember(graph, entry.meta.type, entry.meta.id) {
        graph.browse.meta(type = entry.meta.type, metaId = entry.meta.id)
    }.collectAsState(QueryState())
    val meta = metaState.value
    val still = meta?.videos?.firstOrNull { it.id == entry.videoId }?.thumbnail
        ?: meta?.background ?: entry.meta.background ?: meta?.poster ?: entry.meta.poster
    val tag = historyVideoTag(entry.videoId, entry.meta.id)
    val episode = tag.takeIf { it != "MOVIE" && it != "SERIES" }

    Column(
        Modifier
            .width(ContinueCardWidth)
            .clip(CardShape)
            .longPressClickable(onClick = onClick, onLongPress = onLongPress),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(CardShape)
                .border(1.dp, HaloColors.GlassBorder, CardShape),
        ) {
            HaloAsyncImage(url = still, contentDescription = null, modifier = Modifier.fillMaxSize())
            episode?.let { Chip(it, Modifier.align(Alignment.TopStart).padding(8.dp)) }
            Chip("${timeLeft(0.0, entry.remainingSec)} LEFT", Modifier.align(Alignment.BottomEnd).padding(8.dp))
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.Black.copy(alpha = 0.45f))) {
                Box(Modifier.fillMaxWidth(entry.progress.coerceIn(0.02f, 1f)).fillMaxHeight().background(HaloColors.Accent))
            }
        }
        Text(
            text = entry.meta.name,
            color = HaloColors.Text,
            fontSize = 15.sp,
            lineHeight = 20.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 1.dp, end = 1.dp, top = 8.dp),
        )
        Text(
            text = tag,
            style = monoStyle(12.sp, FontWeight.Normal, HaloColors.TextDim),
            maxLines = 1,
            modifier = Modifier.padding(start = 1.dp, end = 1.dp, top = 3.dp),
        )
    }
}

@Composable
private fun Chip(text: String, modifier: Modifier) {
    Text(
        text = text,
        style = monoStyle(12.sp, FontWeight.Bold, Color.White),
        maxLines = 1,
        modifier = modifier.clip(ChipShape).background(ChipFill).padding(horizontal = 6.dp, vertical = 3.dp),
    )
}
