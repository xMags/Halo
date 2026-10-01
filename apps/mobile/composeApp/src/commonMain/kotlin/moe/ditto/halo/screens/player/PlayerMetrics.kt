package moe.ditto.halo.screens.player

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import moe.ditto.halo.ui.rememberResponsive

/**
 * Every measurement the player's chrome varies by device, resolved once per
 * composition instead of at each call site.
 *
 * It exists because the phone/tablet split here is not one or two values: the
 * screen scales its padding, its type, its buttons and its panels together, and
 * scattering `pick(...)` through the layout code is how one of them gets missed.
 */
internal data class PlayerMetrics(
    /** Horizontal padding for all chrome. */
    val hPad: Dp,
    val chromeTopPadding: Dp,
    val chromeBottomPadding: Dp,
    val titleSize: TextUnit,
    val chipValueSize: TextUnit,
    val transportTextSize: TextUnit,
    /** On-video subtitle size at 100 %; the rail's preview scales from this. */
    val captionSize: TextUnit,
    val playButtonSize: Dp,
    val playIconSize: Dp,
    val centreGap: Dp,
    val bottomBarGap: Dp,
    val railWidth: Dp,
    val episodeCardWidth: Dp,
    val episodeThumbHeight: Dp,
    /** The drawer sits lower than the chrome, so it has its own bottom padding. */
    val drawerBottomPadding: Dp,
    val upNextCardWidth: Dp,
)

/**
 * Resolves [PlayerMetrics] for the current window.
 *
 * `hPad` is one padding for both edges, so it takes the larger of the two safe
 * insets: in landscape a display cutout sits on one side only, and a padding
 * that cleared just that side would leave the chrome visibly off-centre. The
 * floor under it is what keeps a device with no cutout from looking cramped.
 */
@Composable
internal fun rememberPlayerMetrics(): PlayerMetrics {
    val responsive = rememberResponsive()
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val insets = WindowInsets.safeDrawing

    val leftInset = with(density) { insets.getLeft(this, layoutDirection).toDp() }
    val rightInset = with(density) { insets.getRight(this, layoutDirection).toDp() }
    val topInset = with(density) { insets.getTop(this).toDp() }
    val bottomInset = with(density) { insets.getBottom(this).toDp() }

    return remember(responsive, leftInset, rightInset, topInset, bottomInset) {
        val floor = responsive.pick(phone = 24.dp, tablet = 40.dp)
        PlayerMetrics(
            hPad = max(floor, max(leftInset, rightInset)),
            // The design's vertical padding is measured from the edge of a
            // screen with no system bars on it, which is what the player will
            // be once it goes immersive. Until then the inset is added rather
            // than assumed away, so the title does not sit under the clock.
            chromeTopPadding = topInset + responsive.pick(phone = 12.dp, tablet = 20.dp),
            chromeBottomPadding = bottomInset + responsive.pick(phone = 18.dp, tablet = 26.dp),
            titleSize = responsive.pick(phone = 16.5.sp, tablet = 19.sp),
            chipValueSize = responsive.pick(phone = 12.5.sp, tablet = 13.5.sp),
            transportTextSize = responsive.pick(phone = 12.sp, tablet = 13.sp),
            captionSize = responsive.pick(phone = 16.sp, tablet = 21.sp),
            playButtonSize = responsive.pick(phone = 72.dp, tablet = 88.dp),
            playIconSize = responsive.pick(phone = 34.dp, tablet = 40.dp),
            centreGap = responsive.pick(phone = 34.dp, tablet = 44.dp),
            bottomBarGap = responsive.pick(phone = 10.dp, tablet = 14.dp),
            railWidth = responsive.pick(phone = 372.dp, tablet = 440.dp),
            episodeCardWidth = responsive.pick(phone = 168.dp, tablet = 200.dp),
            episodeThumbHeight = responsive.pick(phone = 94.dp, tablet = 112.dp),
            drawerBottomPadding = bottomInset + responsive.pick(phone = 16.dp, tablet = 22.dp),
            upNextCardWidth = responsive.pick(phone = 320.dp, tablet = 360.dp),
        )
    }
}
