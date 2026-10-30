package moe.ditto.halo.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.ditto.halo.api.MetaCard
import moe.ditto.halo.ui.HaloAsyncImage
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloIcons
import moe.ditto.halo.ui.HaloRadius
import moe.ditto.halo.ui.HaloSpacing
import moe.ditto.halo.ui.longPressClickable
import moe.ditto.halo.ui.monoStyle

/** The share of the hero the art takes. A wide backdrop would be cropped to a sliver at full height. */
private const val ArtShare = 0.66f

/** Where the art starts to fade into the page, as a share of its own height. */
private const val ArtFadeFrom = 0.5f

/**
 * Holds the title's place whether it is a logo, one line or two. The text is
 * laid out from the bottom, so a slot that changed size would move everything
 * above the buttons when a logo finished loading.
 */
private val TitleSlotHeight = 72.dp
private val LogoMaxWidth = 280.dp
private val ButtonHeight = 42.dp
private val ArtScrim = Brush.verticalGradient(
    0.4f to Color.Transparent,
    1f to HaloColors.Background.copy(alpha = 0.72f),
)

/**
 * The desktop's featured banner, stacked for a phone: the art fades into the
 * page, and the label, title, facts, synopsis and actions stand below it rather
 * than beside it, where a phone has no room for them.
 */
@Composable
internal fun PhoneFeaturedHero(
    card: MetaCard,
    height: Dp,
    gutter: Dp,
    inLibrary: Boolean,
    count: Int,
    index: Int,
    /** How long this title stays up, for the pip's fill; null once the viewer has picked one. */
    dwellMillis: Int?,
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
            .clip(RoundedCornerShape(HaloRadius.Xl))
            .longPressClickable(onClick = onOpen, role = null, onLongPress = menus?.let { { it.openPoster(card) } }),
    ) {
        Box(Modifier.fillMaxWidth().fillMaxHeight(ArtShare)) {
            FeaturedArt(card) { shown ->
                Box(Modifier.fillMaxSize().fadeOutBelow(ArtFadeFrom)) {
                    HaloAsyncImage(
                        url = shown.background ?: shown.poster,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        // The desktop keeps the art's upper part, where faces usually are.
                        alignment = BiasAlignment(0f, -0.6f),
                    )
                    Box(Modifier.fillMaxSize().background(ArtScrim))
                }
            }
        }
        FeaturedText(card, Modifier.fillMaxSize()) { shown ->
            Box(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(start = HaloSpacing.Md, end = HaloSpacing.Md, bottom = 30.dp),
                    verticalArrangement = Arrangement.spacedBy(HaloSpacing.Sm),
                ) {
                    Text(
                        text = "FEATURED",
                        style = monoStyle(11.sp, FontWeight.Bold, HaloColors.AccentLight),
                        letterSpacing = 1.76.sp,
                    )
                    Box(Modifier.fillMaxWidth().height(TitleSlotHeight), contentAlignment = Alignment.BottomStart) {
                        FeaturedTitle(shown.name, shown.logo, 28.sp, LogoMaxWidth, TitleSlotHeight)
                    }
                    // Addons send blank fields as often as missing ones.
                    FeaturedFacts(shown.imdbRating?.takeIf { it.isNotBlank() }, shown.releaseInfo?.takeIf { it.isNotBlank() })
                    Text(
                        text = shown.description.orEmpty(),
                        color = HaloColors.TextDim,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        // Two lines held even while the full record is on its way.
                        minLines = 2,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        Modifier.padding(top = HaloSpacing.Xs),
                        horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Sm),
                    ) {
                        HeroButton(
                            label = if (shown.type == "movie") "Play" else "Choose episode",
                            icon = HaloIcons.Play,
                            accent = true,
                            onClick = onPlay,
                            height = ButtonHeight,
                        )
                        HeroButton(label = "Details", icon = null, accent = false, onClick = onOpen, height = ButtonHeight)
                        HeroButton(
                            label = null,
                            icon = if (inLibrary) HaloIcons.Bookmark else HaloIcons.BookmarkOutline,
                            iconTint = if (inLibrary) HaloColors.AccentLight else HaloColors.Text,
                            iconDescription = if (inLibrary) "Remove from library" else "Add to library",
                            accent = false,
                            onClick = onToggleLibrary,
                            height = ButtonHeight,
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
                dwellMillis = dwellMillis,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}
