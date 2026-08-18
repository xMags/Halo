package moe.ditto.halo.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.ditto.halo.SignedInGraph
import moe.ditto.halo.api.AddonSource
import moe.ditto.halo.api.Stream
import moe.ditto.halo.downloads.DownloadMedia
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloIcons
import moe.ditto.halo.ui.HaloRadius
import moe.ditto.halo.ui.glassSurface
import moe.ditto.halo.ui.rememberResponsive

/**
 * What the sources rail needs to show and act on one video's sources.
 *
 * Built by whoever owns navigation, because choosing a source from the rail
 * enters playback and building a download of it needs the same context the
 * pushed picker is handed. The screen showing the rail knows only which video
 * the viewer tapped.
 */
internal class SourcesRequest(
    val type: String,
    val videoId: String,
    /** One line naming the video: "Show · S01E02". */
    val title: String,
    val onPlay: (AddonSource, Stream) -> Unit,
    val downloadMedia: (AddonSource, Stream, String) -> DownloadMedia,
)

/**
 * The sources picker as a rail over the title it belongs to.
 *
 * On a phone the picker is a screen, because there is no room to show it beside
 * anything. On a tablet there is, and pushing a screen for it would throw away
 * the title the viewer is choosing a source *for* — including which episode is
 * being picked, which the rail keeps in view in its own header.
 *
 * Render it as the last child of a screen's root box, beside that screen's blur
 * source rather than inside it: the fill is frosted, and a frosted surface
 * cannot sample content it is part of.
 */
// BackHandler is still marked experimental in Compose 1.11; the opt-in is
// scoped to this file rather than turned on for the whole module, so a future
// signature change surfaces here and nowhere else.
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun SourcesRail(
    graph: SignedInGraph,
    /** The video whose sources are shown; null closes the rail. */
    request: SourcesRequest?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Back closes the rail rather than leaving the title behind it, which is
    // what the scrim and the close button do and what a viewer means by it.
    BackHandler(enabled = request != null, onBack = onClose)

    // Held across the exit animation so the rail does not blank out as it
    // slides away, the same way the player's rail keeps its last tab.
    var shown by remember { mutableStateOf<SourcesRequest?>(null) }
    if (request != null) shown = request
    val current = shown ?: return

    val responsive = rememberResponsive()
    val translate = with(LocalDensity.current) { RailTranslate.roundToPx() }

    AnimatedVisibility(
        visible = request != null,
        modifier = modifier,
        // The scrim fades with the container while the panel travels on top of
        // it; sliding the scrim too would drag the dimming in from off-screen.
        enter = fadeIn(tween(RailEnterMillis, easing = RailEasing)),
        exit = fadeOut(tween(RailEnterMillis, easing = RailEasing)),
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(RailScrim)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClickLabel = "Close sources",
                        onClick = onClose,
                    ),
            )

            val shape = RoundedCornerShape(topStart = RailCorner, bottomStart = RailCorner)
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .width(responsive.sourcesRailWidth)
                    .fillMaxHeight()
                    .animateEnterExit(
                        enter = slideInHorizontally(tween(RailEnterMillis, easing = RailEasing)) { translate },
                        exit = slideOutHorizontally(tween(RailEnterMillis, easing = RailEasing)) { translate },
                    ),
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .clip(shape)
                        .glassSurface(RailFill, blurRadius = RailBlur)
                        // Swallows taps so they do not reach the dismiss scrim.
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        )
                        // The fill runs to the edges; the contents clear the
                        // status bar and the home indicator. Not the leading
                        // side: this panel is pinned to the end edge, and a
                        // landscape cutout inset there belongs to screen it
                        // never covers.
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Vertical),
                        ),
                ) {
                    SourcesRailHeader(title = current.title, onClose = onClose)
                    SourcesPicker(
                        graph = graph,
                        type = current.type,
                        videoId = current.videoId,
                        onPlay = current.onPlay,
                        downloadMedia = current.downloadMedia,
                        contentPadding = PaddingValues(
                            start = RailBodyPadding,
                            end = RailBodyPadding,
                            bottom = RailBodyBottomPadding,
                        ),
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                // The visible edge, drawn over the panel rather than as part of
                // it so the rounded corners carry it too. Non-interactive.
                Box(Modifier.fillMaxSize().border(1.dp, RailEdge, shape))
            }
        }
    }
}

@Composable
private fun SourcesRailHeader(title: String, onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = RailBodyPadding, end = RailBodyPadding, top = 18.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "Sources",
                color = HaloColors.Text,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.2).sp,
            )
            Text(
                text = title,
                color = HaloColors.TextDim,
                fontSize = 12.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(HaloRadius.Pill))
                .background(CloseButtonFill)
                .clickable(role = Role.Button, onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = HaloIcons.Close,
                contentDescription = "Close sources",
                tint = HaloColors.Text,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * The player's rail values exactly, so the two panels read as the same surface
 * arriving for two different reasons.
 */
private const val RailEnterMillis = 220
private val RailEasing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
private val RailTranslate = 28.dp
private val RailCorner = 22.dp
private val RailBlur = 30.dp
private val RailBodyPadding = 20.dp
private val RailBodyBottomPadding = 22.dp

private val RailScrim = Color(red = 4f / 255f, green = 5f / 255f, blue = 8f / 255f, alpha = 0.5f)
private val RailFill = Color(red = 18f / 255f, green = 20f / 255f, blue = 27f / 255f, alpha = 0.94f)
private val RailEdge = Color.White.copy(alpha = 0.11f)
private val CloseButtonFill = Color.White.copy(alpha = 0.08f)
