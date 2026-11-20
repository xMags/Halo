package moe.ditto.halo.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.ditto.halo.api.MetaPreview
import moe.ditto.halo.browse.hasNoPoster
import moe.ditto.halo.browse.ratedValue
import moe.ditto.halo.people.PersonSummary
import moe.ditto.halo.people.knownForLabel
import moe.ditto.halo.ui.HaloAsyncImage
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloDimensions
import moe.ditto.halo.ui.HaloIcons
import moe.ditto.halo.ui.HaloRadius
import moe.ditto.halo.ui.HaloSkeleton
import moe.ditto.halo.ui.HaloSpacing
import moe.ditto.halo.ui.longPressClickable
import moe.ditto.halo.ui.metahubPosterUrl
import moe.ditto.halo.ui.monoStyle

/*
 * The one slot above the results (the top match, or the action card while the
 * field holds a pasted link, or the slot's placeholder on a visit's first
 * search), the people row's cards, and Search's notes and empty states. Both
 * slot cards are the same frame with one primary action. The desktop draws
 * the same in `search/SearchSlots.tsx` and `search/Search.tsx`.
 */

private val CardShape = RoundedCornerShape(HaloRadius.Xl)
private val PosterWidth = 96.dp
private val ButtonShape = RoundedCornerShape(HaloRadius.Md)

/**
 * The page colour over the title's art, held nearly solid behind the text and
 * thinning toward the far edge, where the picture shows.
 */
private val ArtHaze = Brush.horizontalGradient(
    0f to HaloColors.Background.copy(alpha = 0.95f),
    0.55f to HaloColors.Background.copy(alpha = 0.84f),
    1f to HaloColors.Background.copy(alpha = 0.52f),
)

/** The card frame both slot cards share; [onClick] makes the whole card its primary action. */
@Composable
private fun SlotFrame(
    modifier: Modifier,
    onClick: (() -> Unit)?,
    onLongPress: (() -> Unit)? = null,
    backdrop: (@Composable BoxScope.() -> Unit)? = null,
    lead: @Composable () -> Unit,
    text: @Composable () -> Unit,
) {
    val tap = when {
        onClick != null -> Modifier.longPressClickable(onClick = onClick, role = Role.Button, onLongPress = onLongPress)
        else -> Modifier
    }
    Box(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(HaloColors.Glass)
            .border(1.dp, HaloColors.GlassBorder, CardShape)
            .then(tap),
    ) {
        backdrop?.invoke(this)
        Row(
            Modifier.fillMaxWidth().padding(HaloSpacing.Md),
            horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Md),
            verticalAlignment = Alignment.Top,
        ) {
            lead()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) { text() }
        }
    }
}

@Composable
private fun SlotKicker(text: String, color: Color = HaloColors.AccentLight) {
    Text(text, style = monoStyle(11.sp, FontWeight.Bold, color), letterSpacing = 1.76.sp, maxLines = 1)
}

@Composable
private fun SlotTitle(text: String) {
    Text(
        text = text,
        color = HaloColors.Text,
        fontSize = 19.sp,
        lineHeight = 23.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

/** The meta row: a rating, a mono value, a plain value and a second mono value, whichever are given. */
@Composable
private fun SlotMeta(rating: String, mono: String, plain: String, tail: String) {
    if (rating.isEmpty() && mono.isEmpty() && plain.isEmpty() && tail.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Sm), verticalAlignment = Alignment.CenterVertically) {
        if (rating.isNotEmpty()) Text(rating, color = HaloColors.Gold, fontSize = 13.sp, maxLines = 1)
        if (mono.isNotEmpty()) {
            Text(mono, style = monoStyle(11.5.sp, FontWeight.Normal, HaloColors.TextMeta), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        }
        if (plain.isNotEmpty()) Text(plain, color = HaloColors.TextMeta, fontSize = 12.5.sp, maxLines = 1)
        if (tail.isNotEmpty()) Text(tail, style = monoStyle(11.5.sp, FontWeight.Normal, HaloColors.TextMeta), maxLines = 1)
    }
}

@Composable
private fun SlotButton(button: ActionButton, accent: Boolean) {
    val fill = when {
        button.destructive -> HaloColors.DangerFill
        accent -> HaloColors.Accent
        else -> HaloColors.Glass
    }
    val edge = when {
        button.destructive -> HaloColors.DangerBorder
        accent -> HaloColors.Accent
        else -> HaloColors.GlassBorder
    }
    val content = when {
        button.destructive -> HaloColors.Danger
        accent -> HaloColors.OnAccent
        else -> HaloColors.Text
    }
    Row(
        Modifier
            .padding(top = 4.dp)
            .height(38.dp)
            .clip(ButtonShape)
            .background(fill)
            .border(1.dp, edge, ButtonShape)
            .clickable(role = Role.Button, onClick = button.run)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (button.playGlyph) Icon(HaloIcons.Play, contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
        Text(button.label, color = content, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun SlotPoster(poster: String?, metaId: String, person: Boolean) {
    HaloAsyncImage(
        url = poster,
        contentDescription = null,
        modifier = Modifier
            .width(PosterWidth)
            .aspectRatio(1f / HaloDimensions.PosterRatio)
            .clip(RoundedCornerShape(HaloRadius.Md))
            .border(1.dp, HaloColors.GlassBorder, RoundedCornerShape(HaloRadius.Md)),
        fallbackUrl = if (person) null else metahubPosterUrl(metaId),
        failure = { GlyphPlaceholder(if (person) HaloIcons.Person else HaloIcons.Movie) },
    )
}

@Composable
private fun BoxScope.GlyphPlaceholder(glyph: ImageVector) {
    Icon(glyph, contentDescription = null, tint = HaloColors.TextFaint, modifier = Modifier.align(Alignment.Center).size(30.dp))
}

/**
 * The single best guess for a search: the title's own art behind the card,
 * its poster, what it is, three lines of its synopsis, and Open title. A tap
 * anywhere on it opens the title page too; a long press opens its menu.
 */
@Composable
internal fun TopMatchCard(meta: MetaPreview, inLibrary: Boolean, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val menus = LocalTitleMenus.current
    val poster = meta.poster?.takeUnless { hasNoPoster(meta) || meta.posterMissing }
    SlotFrame(
        modifier = modifier,
        onClick = onOpen,
        onLongPress = menus?.let { { it.openPoster(meta) } },
        backdrop = {
            // Sized by the card, never sizing it: the text decides how tall this is.
            HaloAsyncImage(
                url = meta.background ?: poster,
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                // Faces sit in a backdrop's upper part.
                alignment = BiasAlignment(0f, -0.5f),
                fallbackUrl = metahubPosterUrl(meta.id),
                failure = {},
            )
            Box(Modifier.matchParentSize().background(ArtHaze))
        },
        lead = { SlotPoster(poster, meta.id, person = false) },
        text = {
            SlotKicker("TOP MATCH")
            SlotTitle(meta.name)
            SlotMeta(
                rating = ratedValue(meta.imdbRating)?.let { "★ $it" }.orEmpty(),
                mono = if (meta.type == "series") "SERIES" else "MOVIE",
                plain = listOfNotNull(meta.releaseInfo?.takeIf { it.isNotBlank() }, if (inLibrary) "In library" else null).joinToString(" · "),
                tail = "",
            )
            meta.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = HaloColors.TextMeta, fontSize = 13.sp, lineHeight = 17.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            SlotButton(ActionButton("Open title", run = onOpen), accent = true)
        },
    )
}

private fun toneColor(tone: ActionTone): Color = when (tone) {
    ActionTone.Accent -> HaloColors.AccentLight
    ActionTone.Warn -> HaloColors.Gold
    ActionTone.Bad -> HaloColors.Danger
    ActionTone.Mute -> HaloColors.TextDim
}

/** What a pasted link or id is, and the one thing it does. */
@Composable
internal fun ActionCard(model: ActionCardModel, modifier: Modifier = Modifier) {
    val primary = model.primary
    SlotFrame(
        modifier = modifier,
        onClick = primary?.run,
        lead = {
            if (model.glyph == ActionGlyph.Poster) {
                SlotPoster(model.poster, model.metaId, model.person)
            } else {
                val tone = toneColor(model.tone)
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(HaloRadius.Md))
                        .background(tone.copy(alpha = 0.14f))
                        .border(1.dp, tone.copy(alpha = 0.32f), RoundedCornerShape(HaloRadius.Md)),
                    contentAlignment = Alignment.Center,
                ) {
                    val glyph = when (model.glyph) {
                        ActionGlyph.Magnet -> HaloIcons.Magnet
                        ActionGlyph.Link -> HaloIcons.Link
                        else -> HaloIcons.IdCard
                    }
                    Icon(glyph, contentDescription = null, tint = tone, modifier = Modifier.size(26.dp))
                }
            }
        },
        text = {
            SlotKicker(model.kicker, if (model.tone == ActionTone.Accent) HaloColors.AccentLight else toneColor(model.tone))
            SlotTitle(model.title)
            SlotMeta(model.rating, model.mono, model.year, model.tail)
            model.progress?.let { fraction ->
                Box(
                    Modifier
                        .padding(top = 2.dp)
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(HaloColors.Glass),
                ) {
                    Box(Modifier.fillMaxWidth(fraction.toFloat().coerceIn(0f, 1f)).fillMaxSize().background(HaloColors.Accent))
                }
            }
            if (model.secondary.isNotEmpty()) {
                Text(model.secondary, color = HaloColors.TextDim, fontSize = 13.sp, lineHeight = 17.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            primary?.let { SlotButton(it, accent = true) }
            model.plain?.let { SlotButton(it, accent = false) }
        },
    )
}

/** The slot's placeholder on a visit's first search: its size, nothing inside. */
@Composable
internal fun SlotSkeleton(modifier: Modifier = Modifier) {
    HaloSkeleton(modifier.fillMaxWidth().height(170.dp).clip(CardShape))
}

/** One person of the people row: their portrait in a poster card, the 2:3 shape TMDB's photos come in. */
@Composable
internal fun PersonCard(person: PersonSummary, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.clickable(role = Role.Button, onClick = onOpen)) {
        Box {
            HaloAsyncImage(
                url = person.photo,
                contentDescription = person.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f / HaloDimensions.PosterRatio)
                    .clip(RoundedCornerShape(HaloRadius.Md)),
                failure = { GlyphPlaceholder(HaloIcons.Person) },
            )
            Text(
                text = "PERSON",
                style = monoStyle(9.sp, FontWeight.Bold, HaloColors.Text),
                letterSpacing = 1.sp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(HaloColors.OverlayPill)
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            )
        }
        Text(person.name, color = HaloColors.TextDim, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        knownForLabel(person.knownFor).takeIf { it.isNotEmpty() }?.let {
            Text(it, color = HaloColors.TextFaint, fontSize = 10.5.sp, maxLines = 1, modifier = Modifier.padding(top = 1.dp))
        }
    }
}

/** What the field accepts, as a legend; the items do nothing when tapped. */
@Composable
internal fun SearchHints(people: Boolean, lookup: Boolean, magnets: Boolean, needsTmdbKey: Boolean, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("You can search anything…", color = HaloColors.TextDim, fontSize = 14.sp, modifier = Modifier.padding(bottom = HaloSpacing.Md))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Lg, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(HaloSpacing.Md),
            modifier = Modifier.widthIn(max = 520.dp),
        ) {
            Hint(HaloIcons.Movie, "Movies & Series")
            if (people) Hint(HaloIcons.Person, "Actors, Directors & Writers")
            Hint(HaloIcons.Link, if (magnets) "HTTP & Magnet Links" else "HTTP Links")
            // TVDB links need the lookup; without it only IMDb title links open.
            Hint(HaloIcons.IdCard, if (lookup) "IMDb / TVDB Links" else "IMDb Links")
        }
        if (needsTmdbKey) {
            Row(Modifier.padding(top = HaloSpacing.Lg), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Add a free TMDB key in Settings to also find actors, directors and writers, and open TVDB links.",
                    color = HaloColors.TextDim,
                    fontSize = 12.5.sp,
                    lineHeight = 17.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 420.dp),
                )
            }
            Text(
                text = "Open Settings",
                color = HaloColors.Accent,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable(role = Role.Button, onClick = onOpenSettings).padding(HaloSpacing.Sm),
            )
        }
    }
}

@Composable
private fun Hint(glyph: ImageVector, label: String) {
    Column(Modifier.width(96.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(glyph, contentDescription = null, tint = HaloColors.TextDim, modifier = Modifier.size(28.dp))
        Text(label, color = HaloColors.TextDim, fontSize = 11.5.sp, lineHeight = 14.sp, textAlign = TextAlign.Center)
    }
}

/** A glyph, a title, a line and at most one action, centred. */
@Composable
internal fun SearchEmptyState(
    glyph: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    action: Pair<String, () -> Unit>? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(vertical = HaloSpacing.Xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(glyph, contentDescription = null, tint = HaloColors.TextFaint, modifier = Modifier.size(36.dp))
        title?.let { Text(it, color = HaloColors.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center) }
        Text(text, color = HaloColors.TextDim, fontSize = 13.5.sp, textAlign = TextAlign.Center)
        action?.let { (label, run) ->
            Text(
                text = label,
                color = HaloColors.Accent,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable(role = Role.Button, onClick = run).padding(HaloSpacing.Sm),
            )
        }
    }
}
