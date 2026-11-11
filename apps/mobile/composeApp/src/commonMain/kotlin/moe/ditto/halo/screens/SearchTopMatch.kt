package moe.ditto.halo.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.ditto.halo.api.MetaPreview
import moe.ditto.halo.browse.hasNoPoster
import moe.ditto.halo.browse.ratedValue
import moe.ditto.halo.ui.HaloAsyncImage
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloDimensions
import moe.ditto.halo.ui.HaloIcons
import moe.ditto.halo.ui.HaloRadius
import moe.ditto.halo.ui.HaloSpacing
import moe.ditto.halo.ui.longPressClickable
import moe.ditto.halo.ui.metahubPosterUrl
import moe.ditto.halo.ui.monoStyle

private val CardShape = RoundedCornerShape(HaloRadius.Xl)
private val PosterWidth = 108.dp
private val ButtonHeight = 40.dp

/**
 * The page colour over the title's art, held nearly solid behind the text and
 * thinning toward the far edge, where the picture shows. The desktop's top
 * match card hazes its backdrop the same way.
 */
private val ArtHaze = Brush.horizontalGradient(
    0f to HaloColors.Background.copy(alpha = 0.95f),
    0.55f to HaloColors.Background.copy(alpha = 0.84f),
    1f to HaloColors.Background.copy(alpha = 0.52f),
)

/**
 * The single best guess for a search, drawn as Home draws its featured title:
 * the title's own art across the card, its poster at a result card's scale,
 * what it is, and the two things a viewer does next. A tap anywhere else on it
 * opens the title page, as a poster would. The desktop's search has the same
 * card (`TopMatchCard` in `search/Search.tsx`).
 */
@Composable
internal fun SearchTopMatchCard(
    meta: MetaPreview,
    inLibrary: Boolean,
    onOpen: () -> Unit,
    /** The primary action: a film's sources, or the title page where an episode is chosen. */
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val menus = LocalTitleMenus.current
    val isSeries = meta.type == "series"
    val poster = meta.poster?.takeUnless { hasNoPoster(meta) }
    val facts = listOfNotNull(
        if (isSeries) "SERIES" else "MOVIE",
        meta.releaseInfo?.takeIf { it.isNotBlank() },
        if (inLibrary) "IN LIBRARY" else null,
    ).joinToString(" · ")

    Box(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(HaloColors.Glass)
            .border(1.dp, HaloColors.GlassBorder, CardShape)
            .longPressClickable(onClick = onOpen, role = null, onLongPress = menus?.let { { it.openPoster(meta) } }),
    ) {
        // Sized by the card, never sizing it: the text decides how tall this is.
        HaloAsyncImage(
            url = meta.background ?: poster,
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            // Faces sit in a backdrop's upper part.
            alignment = BiasAlignment(0f, -0.5f),
            fallbackUrl = metahubPosterUrl(meta.id),
        )
        Box(Modifier.matchParentSize().background(ArtHaze))

        Column(
            Modifier.fillMaxWidth().padding(HaloSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(HaloSpacing.Md),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Md), verticalAlignment = Alignment.Top) {
                HaloAsyncImage(
                    url = poster,
                    contentDescription = null,
                    modifier = Modifier
                        .width(PosterWidth)
                        .aspectRatio(1f / HaloDimensions.PosterRatio)
                        .clip(RoundedCornerShape(HaloRadius.Md))
                        .border(1.dp, HaloColors.GlassBorder, RoundedCornerShape(HaloRadius.Md)),
                    fallbackUrl = metahubPosterUrl(meta.id),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "TOP MATCH",
                        style = monoStyle(11.sp, FontWeight.Bold, HaloColors.AccentLight),
                        letterSpacing = 1.76.sp,
                    )
                    Text(
                        text = meta.name,
                        color = HaloColors.Text,
                        fontSize = 21.sp,
                        lineHeight = 25.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    ratedValue(meta.imdbRating)?.let { Text("★ $it", color = HaloColors.Gold, fontSize = 14.sp) }
                    Text(facts, style = monoStyle(11.5.sp, FontWeight.Normal, HaloColors.TextMeta), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    meta.description?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            color = HaloColors.TextMeta,
                            fontSize = 13.5.sp,
                            lineHeight = 18.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Sm)) {
                HeroButton(
                    label = if (isSeries) "Choose episode" else "Play",
                    icon = HaloIcons.Play,
                    accent = true,
                    onClick = onPlay,
                    height = ButtonHeight,
                )
                HeroButton(label = "Details", icon = null, accent = false, onClick = onOpen, height = ButtonHeight)
            }
        }
    }
}
