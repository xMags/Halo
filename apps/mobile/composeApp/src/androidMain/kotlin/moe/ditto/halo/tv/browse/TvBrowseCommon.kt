package moe.ditto.halo.tv.browse

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.ditto.halo.api.MetaCard
import moe.ditto.halo.browse.ratedValue
import moe.ditto.halo.screens.MetaRef
import moe.ditto.halo.screens.ShelfSnapshot
import moe.ditto.halo.shell.PersonRoute
import moe.ditto.halo.shell.PlayerRoute
import moe.ditto.halo.shell.StreamsRoute
import moe.ditto.halo.tv.kit.TvButton
import moe.ditto.halo.tv.kit.TvMetrics
import moe.ditto.halo.tv.kit.TvPivotBringIntoViewSpec
import moe.ditto.halo.tv.kit.TvSurface
import moe.ditto.halo.tv.kit.TvSurfaceColors
import moe.ditto.halo.tv.kit.TvType
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloSkeleton
import moe.ditto.halo.ui.MetaLine

/** Where the TV browse screens lead; the shell supplies it. */
internal class TvBrowseNavigation(
    val openDetail: (MetaRef) -> Unit,
    val openSources: (StreamsRoute) -> Unit,
    /** A film's sources, straight from a card or the hero. */
    val playMovie: (MetaCard) -> Unit,
    val openShelf: (ShelfSnapshot) -> Unit,
    val openPerson: (PersonRoute) -> Unit,
    /** Playback of something that needs no source picker (a pasted link). */
    val play: (PlayerRoute) -> Unit,
    /** The Library destination, on its History view or its saved titles. */
    val openLibrary: (history: Boolean) -> Unit,
    val openSettings: () -> Unit,
    /** Back to the screen before, as the remote's Back does. */
    val back: () -> Unit,
)

/** A card's caption below its focused shape: two short lines. */
private val CaptionRoom = 48.dp

internal const val MovieType = "movie"
internal const val SeriesType = "series"

/**
 * Scrolls a vertical list so the focused element sits [pivot] below its top,
 * which keeps a row's heading in view above its focused card. An element
 * already wholly in view leaves the list where it is; "in view" stops at the
 * bottom safe margin, which a TV may crop, less room for the caption a card
 * carries under its focused shape (a title, a cast name).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ProvideVerticalPivot(pivot: Dp, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val pivotPx = with(density) { pivot.toPx() }
    val marginPx = with(density) { (TvMetrics.VerticalMargin + CaptionRoom).toPx() }
    val spec: BringIntoViewSpec = remember(pivotPx, marginPx) {
        TvPivotBringIntoViewSpec(pivotPx, stayWhenVisible = true, endMarginPx = marginPx)
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

/** "★ 8.4 · 2024 · Drama, Mystery": a title's facts on one line. */
@Composable
internal fun TvMetaLine(meta: MetaCard, modifier: Modifier = Modifier, extra: List<String> = emptyList()) {
    MetaLine(
        parts = listOfNotNull(meta.releaseInfo) + meta.genres.take(2).let { if (it.isEmpty()) emptyList() else listOf(it.joinToString(", ")) } + extra,
        rating = ratedValue(meta.imdbRating),
        modifier = modifier,
    )
}

/** The last card of a row: opens everything the row holds. */
@Composable
internal fun TvSeeAllCard(onClick: () -> Unit, width: Dp, height: Dp, modifier: Modifier = Modifier) {
    TvSurface(
        onClick = onClick,
        modifier = modifier.size(width, height).semantics { contentDescription = "See all" },
        colors = TvSurfaceColors(HaloColors.FieldFill, HaloColors.SurfaceHigh),
        contentAlignment = Alignment.Center,
    ) {
        Text("See all", color = HaloColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

/** A row of grey cards while a row's titles load. */
@Composable
internal fun TvSkeletonRow(count: Int, width: Dp, height: Dp, modifier: Modifier = Modifier) {
    Row(
        modifier.padding(start = TvMetrics.ContentStart, top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(TvMetrics.CardGap),
    ) {
        repeat(count) { HaloSkeleton(Modifier.size(width, height)) }
    }
}

/**
 * A screen's whole-body state: a message, with a focusable Retry when the
 * failure can be retried, so the remote is never left with nothing to do.
 */
@Composable
internal fun TvBodyMessage(
    message: String,
    modifier: Modifier = Modifier,
    retryModifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    /** What the button does; not every message's way on is a retry. */
    actionLabel: String = "Try again",
) {
    Box(modifier.fillMaxWidth().padding(start = TvMetrics.ContentStart, end = TvMetrics.SideMargin), contentAlignment = Alignment.CenterStart) {
        Column(Modifier.width(520.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(message, style = TvType.Body, textAlign = TextAlign.Start)
            if (onRetry != null) TvButton(actionLabel, onClick = onRetry, modifier = retryModifier)
        }
    }
}

/** A full-height loading state for a whole screen body. */
@Composable
internal fun TvBodyLoading(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        HaloSkeleton(Modifier.padding(start = TvMetrics.ContentStart).size(320.dp, 28.dp))
        TvSkeletonRow(6, TvMetrics.PosterWidth, TvMetrics.PosterHeight)
        HaloSkeleton(Modifier.padding(start = TvMetrics.ContentStart).height(18.dp).width(240.dp))
    }
}
