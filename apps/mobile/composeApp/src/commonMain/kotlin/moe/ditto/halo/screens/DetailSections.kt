package moe.ditto.halo.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.ditto.halo.api.MetaVideo
import moe.ditto.halo.api.WatchState
import moe.ditto.halo.browse.Availability
import moe.ditto.halo.browse.Credit
import moe.ditto.halo.browse.episodeTag
import moe.ditto.halo.ui.HaloAsyncImage
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloIcons
import moe.ditto.halo.ui.HaloRadius
import moe.ditto.halo.ui.HaloSpacing
import moe.ditto.halo.ui.longPressClickable
import moe.ditto.halo.ui.monoStyle

/*
 * The title page's sections, top to bottom: the hero, the play block, the
 * action tiles, the synopsis, the episodes, what the addons offer, the cast,
 * the facts and similar titles. Each is drawn only from what it is handed;
 * the screen decides what that is.
 */

private val TileShape = RoundedCornerShape(HaloRadius.Lg - 2.dp)
private val CardShape = RoundedCornerShape(HaloRadius.Lg)
private val PillShape = RoundedCornerShape(HaloRadius.Pill)
private val SectionTitleSize = 21.sp
private val DividerColor = Color.White.copy(alpha = 0.06f)
private val ProgressTrack = Color.White.copy(alpha = 0.25f)

/** A picker that narrows the list (a long season's episode range) is marked in the accent. */
private val PillActiveFill = HaloColors.Accent.copy(alpha = 0.16f)
private val PillActiveBorder = HaloColors.Accent.copy(alpha = 0.55f)
private val PillActiveLabel = Color(0xFF7EC0FF)

/** An episode's length and date: quieter than its synopsis. */
private val EpisodeMetaColor = Color(0xFF6F7789)

/** The title over its art: name, then years, length or seasons, genres and rating. */
@Composable
internal fun DetailHero(
    art: String?,
    title: String,
    facts: List<String>,
    rating: String?,
    height: Dp,
    gutter: Dp,
) {
    Box(Modifier.fillMaxWidth().height(height).background(HaloColors.Surface)) {
        // fillMaxSize, not matchParentSize: matching the parent leaves async
        // art unloaded (see the same note on Home's hero).
        HaloAsyncImage(url = art, contentDescription = null, modifier = Modifier.fillMaxSize())
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    0f to HaloColors.Background.copy(alpha = 0.45f),
                    0.22f to Color.Transparent,
                    0.45f to Color.Transparent,
                    0.86f to HaloColors.Background.copy(alpha = 0.92f),
                    1f to HaloColors.Background,
                ),
            ),
        )
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = gutter, end = gutter, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                color = HaloColors.Text,
                fontSize = 36.sp,
                lineHeight = 40.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.4).sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = facts.joinToString("  ·  "),
                    color = HaloColors.TextMeta,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                rating?.let { ImdbRating(it) }
            }
        }
    }
}

@Composable
private fun ImdbRating(rating: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(
            text = "IMDb",
            color = Color.Black,
            fontSize = 10.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.clip(RoundedCornerShape(3.dp)).background(HaloColors.Gold).padding(horizontal = 4.dp, vertical = 1.dp),
        )
        Text(text = rating, color = HaloColors.TextMeta, fontSize = 14.sp)
    }
}

/** What the page plays: Resume or Play, the episode, time left and how far in. */
internal data class PlayTarget(
    val label: String,
    /** "34 MIN LEFT", when partway through. */
    val left: String?,
    /** 0..1 when partway through. */
    val progress: Float?,
    val enabled: Boolean,
)

/** The white play button, with the quality line under it. */
@Composable
internal fun PlayBlock(target: PlayTarget, availability: Availability?, loading: Boolean, onPlay: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (target.enabled) HaloColors.Primary else HaloColors.Primary.copy(alpha = 0.4f))
                .clickable(enabled = target.enabled, role = Role.Button, onClick = onPlay),
        ) {
            Row(
                Modifier.align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(HaloIcons.Play, contentDescription = null, tint = HaloColors.OnPrimary, modifier = Modifier.size(20.dp))
                Text(text = target.label, color = HaloColors.OnPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                target.left?.let { Text(text = it, style = monoStyle(12.sp, color = Color(0xFF555555))) }
            }
            target.progress?.let { fraction ->
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                        .height(4.dp)
                        .background(HaloColors.Accent),
                )
            }
        }
        QualityLine(availability, loading)
    }
}

/** "FULL HD  11 SOURCES": the best quality on offer and how many sources there are. */
@Composable
private fun QualityLine(availability: Availability?, loading: Boolean) {
    val best = availability?.best
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val text = when {
            availability != null && availability.total > 0 -> "${availability.total} ${if (availability.total == 1) "SOURCE" else "SOURCES"}"
            loading -> "FINDING SOURCES…"
            else -> "NO SOURCES FOUND YET"
        }
        if (best != null && availability.total > 0) {
            Text(
                text = best.label,
                style = monoStyle(11.5.sp, FontWeight.Bold, HaloColors.Text),
                modifier = Modifier.border(1.dp, Color(0xFF3A4152), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
        Text(text = text, style = monoStyle(11.5.sp, color = HaloColors.TextDim))
    }
}

/** One of the row of square actions under the play button. */
internal data class ActionTile(
    val label: String,
    val icon: ImageVector,
    val tint: Color,
    val onClick: () -> Unit,
)

@Composable
internal fun ActionTiles(tiles: List<ActionTile>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.forEach { tile ->
            Column(
                Modifier
                    .weight(1f)
                    .height(64.dp)
                    .clip(TileShape)
                    .background(HaloColors.Glass)
                    .border(1.dp, HaloColors.GlassBorder, TileShape)
                    .clickable(role = Role.Button, onClick = tile.onClick),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
            ) {
                Icon(tile.icon, contentDescription = null, tint = tile.tint, modifier = Modifier.size(21.dp))
                Text(text = tile.label, color = tile.tint, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}

/** The synopsis, three lines until opened. */
@Composable
internal fun Synopsis(text: String, expanded: Boolean, onToggle: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(onClickLabel = if (expanded) "Show less" else "Show more", onClick = onToggle),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = text,
            color = Color(0xFFC3C9D6),
            fontSize = 15.sp,
            lineHeight = 23.sp,
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
        )
        Text(text = if (expanded) "Less" else "More", color = HaloColors.AccentLight, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** A section's heading, with a mono line under it and an optional control at the end. */
@Composable
internal fun SectionHeading(
    title: String,
    note: String?,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = title, color = HaloColors.Text, fontSize = SectionTitleSize, fontWeight = FontWeight.Bold)
            note?.let { Text(text = it, style = monoStyle(11.5.sp, color = HaloColors.TextDim), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        trailing?.invoke()
    }
}

/** A rounded picker chip (the season, a long season's episode range); [active] marks a narrowing choice. */
@Composable
internal fun PickerPill(label: String, active: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier
            .height(40.dp)
            .clip(PillShape)
            .background(if (active) PillActiveFill else HaloColors.Glass)
            .border(1.dp, if (active) PillActiveBorder else HaloColors.GlassBorder, PillShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val tint = if (active) PillActiveLabel else HaloColors.Text
        Text(text = label, color = tint, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Icon(HaloIcons.ChevronDown, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
    }
}

/** Below this the bar is a sliver that reads as a rendering artifact. */
private const val MinVisibleProgress = 0.02

/** One episode: its still with the tag and progress, the title and a watched mark, two lines of synopsis, then length or time left and the air date. */
@Composable
internal fun EpisodeRow(
    video: MetaVideo,
    state: WatchState?,
    current: Boolean,
    meta: String,
    onClick: () -> Unit,
    menu: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val fraction = state?.takeIf { it.durationSec > 0 && !it.watched }?.let { it.positionSec / it.durationSec } ?: 0.0
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (current) HaloColors.Accent.copy(alpha = 0.10f) else Color.Transparent)
            .longPressClickable(onClick = onClick, onLongPress = menu)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(132.dp).height(74.dp).clip(RoundedCornerShape(9.dp)).background(HaloColors.Surface)) {
            if (video.thumbnail != null) {
                HaloAsyncImage(url = video.thumbnail, contentDescription = null, modifier = Modifier.fillMaxSize())
            } else {
                Icon(HaloIcons.Play, contentDescription = null, tint = HaloColors.TextDim, modifier = Modifier.align(Alignment.Center).size(18.dp))
            }
            Text(
                text = episodeTag(video),
                style = monoStyle(10.sp, FontWeight.Bold, Color.White),
                maxLines = 1,
                modifier = Modifier
                    .padding(8.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            )
            if (fraction > MinVisibleProgress) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(ProgressTrack)) {
                    Box(Modifier.fillMaxWidth(fraction.toFloat().coerceAtMost(1f)).fillMaxHeight().background(HaloColors.Accent))
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = video.displayTitle ?: episodeTag(video),
                    color = HaloColors.Text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (state?.watched == true) {
                    Icon(HaloIcons.CheckCircle, contentDescription = "Watched", tint = HaloColors.Success, modifier = Modifier.size(16.dp))
                }
            }
            video.overview?.let {
                Text(text = it, color = HaloColors.TextDim, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(text = meta, style = monoStyle(11.sp, color = EpisodeMetaColor), maxLines = 1)
        }
    }
}

/** A long season's previous and next pages, under its list. */
@Composable
internal fun PageButtons(previous: String?, next: String?, onPrevious: () -> Unit, onNext: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(previous?.let { "‹  $it" } to onPrevious, next?.let { "$it  ›" } to onNext).forEach { (label, action) ->
            Box(
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (label != null) HaloColors.Glass else Color.Transparent)
                    .border(1.dp, if (label != null) HaloColors.GlassBorder else Color.Transparent, RoundedCornerShape(12.dp))
                    .clickable(enabled = label != null, role = Role.Button, onClick = action),
                contentAlignment = Alignment.Center,
            ) {
                label?.let { Text(text = it, color = HaloColors.TextMeta, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
            }
        }
    }
}

/** What the addons offer for a film, by quality, with the way into all of it. */
@Composable
internal fun WaysToWatch(availability: Availability, onBrowse: () -> Unit) {
    val most = availability.groups.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1
    Column(
        Modifier.fillMaxWidth().clip(CardShape).background(HaloColors.Surface).border(1.dp, Color.White.copy(alpha = 0.08f), CardShape)
            .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 16.dp),
    ) {
        availability.groups.forEach { group ->
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = group.label,
                    style = monoStyle(11.sp, FontWeight.Bold, HaloColors.Text),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(72.dp).border(1.dp, Color(0xFF3A4152), RoundedCornerShape(4.dp)).padding(vertical = 4.dp),
                )
                Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.10f))) {
                    Box(Modifier.fillMaxWidth(group.count.toFloat() / most).fillMaxHeight().background(HaloColors.TextMeta))
                }
                Text(
                    text = "${group.count} ${if (group.count == 1) "source" else "sources"}",
                    color = HaloColors.TextDim,
                    fontSize = 13.sp,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(72.dp),
                )
            }
        }
        Box(
            Modifier.padding(top = 8.dp).fillMaxWidth().height(46.dp).clip(RoundedCornerShape(12.dp)).background(HaloColors.Glass)
                .border(1.dp, HaloColors.GlassBorder, RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onBrowse),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "Browse all sources", color = HaloColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** The cast and crew as a scrolling row of round portraits; pressing one looks them up. */
@Composable
internal fun CastRow(credits: List<Credit>, gutter: Dp, onOpen: (Credit) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = gutter), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        items(items = credits, key = { it.name }) { credit ->
            Column(
                Modifier.width(78.dp).clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClickLabel = "Look up ${credit.name}") { onOpen(credit) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Box(Modifier.size(72.dp).clip(PillShape).background(HaloColors.SurfaceHigh), contentAlignment = Alignment.Center) {
                    Text(text = initialsOf(credit.name), color = HaloColors.TextMeta, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    credit.photo?.let { HaloAsyncImage(url = it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
                }
                Text(text = credit.name, color = HaloColors.Text, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
                credit.role?.let {
                    Text(text = it, color = HaloColors.TextDim, fontSize = 11.5.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 14.sp)
                }
            }
        }
    }
}

/** Up to two letters for a portrait with no picture. */
internal fun initialsOf(name: String): String =
    name.split(' ', '.', '-', '_').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "?" }

/** Facts the addon sends, as label and value rows on one card. */
@Composable
internal fun AboutCard(rows: List<Pair<String, String>>) {
    Column(Modifier.fillMaxWidth().clip(CardShape).background(HaloColors.Surface).border(1.dp, Color.White.copy(alpha = 0.08f), CardShape)) {
        rows.forEachIndexed { index, (label, value) ->
            if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(DividerColor))
            Row(
                Modifier.fillMaxWidth().heightIn(min = 46.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(text = label.uppercase(), style = monoStyle(11.5.sp, color = HaloColors.TextDim), modifier = Modifier.padding(top = 2.dp))
                Text(text = value, color = HaloColors.Text, fontSize = 14.sp, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            }
        }
    }
}
