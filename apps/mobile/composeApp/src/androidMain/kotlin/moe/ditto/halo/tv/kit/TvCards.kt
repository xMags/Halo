package moe.ditto.halo.tv.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.ditto.halo.browse.isStandInPosterLink
import moe.ditto.halo.ui.HaloAsyncImage
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloIcons

private val CardColors = TvSurfaceColors(rest = HaloColors.Surface, focused = HaloColors.SurfaceHigh)

/** A thin watch-progress bar along the bottom of a card. */
@Composable
internal fun TvProgressBar(progress: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Color.White.copy(alpha = 0.2f)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(HaloColors.Accent),
        )
    }
}

/**
 * A 2:3 poster with its title under it. The title brightens with focus, so
 * the focused card reads even where posters look alike.
 */
@Composable
internal fun TvPosterCard(
    title: String,
    posterUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fallbackUrl: String? = null,
    subtitle: String? = null,
    progress: Float? = null,
    onMenu: (() -> Unit)? = null,
    width: Dp = TvMetrics.PosterWidth,
    onFocusChange: ((Boolean) -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier.width(width), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TvSurface(
            onClick = onClick,
            onMenu = onMenu,
            modifier = Modifier
                .width(width)
                .height(width * 1.5f)
                .semantics { contentDescription = title },
            colors = CardColors,
            onFocusChange = { isFocused ->
                focused = isFocused
                onFocusChange?.invoke(isFocused)
            },
        ) {
            HaloAsyncImage(
                // A card an addon draws in place of art is no poster: the
                // title's own stand-in reads better than its lettering.
                url = posterUrl?.takeUnless(::isStandInPosterLink),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                fallbackUrl = fallbackUrl,
                failure = { ArtlessPoster(title) },
            )
            if (progress != null && progress > 0f) {
                TvProgressBar(progress, Modifier.align(Alignment.BottomCenter).padding(10.dp))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                color = if (focused) HaloColors.Text else HaloColors.TextMeta,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) Text(subtitle, style = TvType.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * A poster for a title with no artwork, or whose artwork will not load: its
 * name set on a quiet gradient under a film glyph, so the card still reads as
 * that title rather than an empty slot.
 */
@Composable
private fun BoxScope.ArtlessPoster(title: String) {
    Column(
        Modifier
            .matchParentSize()
            .background(Brush.linearGradient(listOf(HaloColors.SurfaceHigh, HaloColors.Surface)))
            .padding(horizontal = 12.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Icon(HaloIcons.Movie, contentDescription = null, tint = HaloColors.TextMeta.copy(alpha = 0.45f), modifier = Modifier.size(26.dp))
        Text(
            title,
            color = HaloColors.TextMeta,
            fontSize = 15.sp,
            lineHeight = 19.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A 16:9 card for continue-watching and episodes: art with the title drawn
 * over a scrim, a progress bar, and one or two lines under it.
 */
@Composable
internal fun TvWideCard(
    title: String,
    imageUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    line: String? = null,
    detail: String? = null,
    progress: Float? = null,
    badge: (@Composable () -> Unit)? = null,
    onMenu: (() -> Unit)? = null,
    onFocusChange: ((Boolean) -> Unit)? = null,
) {
    Column(modifier.width(TvMetrics.WideCardWidth), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TvSurface(
            onClick = onClick,
            onMenu = onMenu,
            modifier = Modifier
                .size(TvMetrics.WideCardWidth, TvMetrics.WideCardHeight)
                .semantics { contentDescription = listOfNotNull(title, line).joinToString(", ") },
            colors = CardColors,
            onFocusChange = onFocusChange,
        ) {
            HaloAsyncImage(url = imageUrl, contentDescription = null, modifier = Modifier.fillMaxSize())
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color(0xD9050709))),
            )
            Text(
                title,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 14.dp, end = 14.dp, bottom = 20.dp),
                color = HaloColors.Text,
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (progress != null && progress > 0f) {
                TvProgressBar(
                    progress,
                    Modifier.align(Alignment.BottomCenter).padding(start = 14.dp, end = 14.dp, bottom = 9.dp),
                )
            }
            if (badge != null) Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) { badge() }
        }
        if (line != null || detail != null) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (line != null) {
                    Text(
                        line,
                        color = HaloColors.Text,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (detail != null) Text(detail, style = TvType.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
