package moe.ditto.halo.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min

enum class SheetPresentation {
    Bottom,
    Side,
}

data class SelectOption(
    val key: String,
    val label: String,
    val detail: String? = null,
    val selected: Boolean = false,
)

private val SheetCorner = HaloRadius.Xl + 4.dp
private val DividerColor = Color.White.copy(alpha = 0.08f)

/**
 * Frosted picker for audio tracks, subtitles, seasons, and settings choices.
 *
 * This is an in-tree overlay rather than a platform dialog on purpose. It has to
 * work inside the orientation-locked player, where it must inherit that screen's
 * own metrics and sit inside its layout — a separate window would not. Render it
 * as the LAST child of a screen's root Box, never nested inside scrolling
 * content, so it draws above everything else.
 */
@Composable
fun SelectSheet(
    visible: Boolean,
    title: String,
    options: List<SelectOption>,
    onSelect: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    presentation: SheetPresentation = SheetPresentation.Bottom,
    footer: (@Composable () -> Unit)? = null,
) {
    val responsive = rememberResponsive()
    val side = presentation == SheetPresentation.Side

    // The backdrop fades with the container while the card slides on top of it;
    // sliding the backdrop too would drag the dimming in from off-screen.
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(durationMillis = 220)),
        exit = fadeOut(tween(durationMillis = 180)),
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClose,
                    ),
            )

            val cardModifier = if (side) {
                Modifier
                    .align(Alignment.CenterEnd)
                    .width(min(responsive.width.value * 0.48f, 440f).dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topStart = SheetCorner, bottomStart = SheetCorner))
                    .animateEnterExit(
                        enter = slideInHorizontally(tween(220)) { it },
                        exit = slideOutHorizontally(tween(180)) { it },
                    )
            } else {
                Modifier
                    .align(Alignment.BottomCenter)
                    // A full-width sheet floats unanchored on a tablet; cap and
                    // centre it to roughly the iOS form-sheet width instead.
                    .then(
                        if (responsive.isTablet) {
                            Modifier.width(min(responsive.width.value * 0.72f, 640f).dp)
                        } else {
                            Modifier.fillMaxWidth()
                        },
                    )
                    .heightIn(max = responsive.height * 0.7f)
                    .clip(RoundedCornerShape(topStart = SheetCorner, topEnd = SheetCorner))
                    .animateEnterExit(
                        enter = slideInVertically(tween(220)) { it },
                        exit = slideOutVertically(tween(180)) { it },
                    )
            }

            Column(
                modifier = cardModifier
                    .glassSurface(HaloColors.SheetTint)
                    .border(1.dp, HaloColors.GlassBorder, sheetShape(side))
                    .padding(top = HaloSpacing.Sm, bottom = if (side) HaloSpacing.Md else HaloSpacing.Xl + HaloSpacing.Sm),
            ) {
                if (!side) {
                    Box(
                        Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(bottom = HaloSpacing.Sm)
                            .width(38.dp)
                            .height(5.dp)
                            .clip(RoundedCornerShape(HaloRadius.Pill))
                            .background(Color.White.copy(alpha = 0.25f)),
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = HaloSpacing.Lg, end = HaloSpacing.Lg, bottom = HaloSpacing.Sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(text = title, color = HaloColors.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        if (description != null) {
                            Text(
                                text = description,
                                color = HaloColors.TextDim,
                                fontSize = 11.5.sp,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(HaloRadius.Pill))
                            .background(Color.White.copy(alpha = 0.08f))
                            .clickable(role = Role.Button, onClick = onClose),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = HaloIcons.Close,
                            contentDescription = "Close",
                            tint = HaloColors.Text,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                LazyColumn(Modifier.weight(weight = 1f, fill = false)) {
                    items(items = options, key = { it.key }) { option ->
                        SheetOptionRow(
                            option = option,
                            onClick = {
                                onSelect(option.key)
                                onClose()
                            },
                        )
                    }
                }

                if (footer != null) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(DividerColor))
                    Box(
                        Modifier.padding(
                            start = HaloSpacing.Lg,
                            end = HaloSpacing.Lg,
                            top = HaloSpacing.Md,
                        ),
                    ) { footer() }
                }
            }
        }
    }
}

private fun sheetShape(side: Boolean) = if (side) {
    RoundedCornerShape(topStart = SheetCorner, bottomStart = SheetCorner)
} else {
    RoundedCornerShape(topStart = SheetCorner, topEnd = SheetCorner)
}

@Composable
private fun SheetOptionRow(option: SelectOption, onClick: () -> Unit) {
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(DividerColor))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = HaloSpacing.Lg, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Sm + 4.dp),
        ) {
            // The check column keeps its width whether or not it holds a mark,
            // so labels stay on one left edge down the whole list.
            Box(Modifier.width(22.dp)) {
                if (option.selected) {
                    Icon(
                        imageVector = HaloIcons.Check,
                        contentDescription = null,
                        tint = HaloColors.Accent,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = option.label,
                    color = if (option.selected) Color.White else Color.White.copy(alpha = 0.9f),
                    fontSize = 15.5.sp,
                    fontWeight = if (option.selected) FontWeight.Bold else FontWeight.Medium,
                )
                if (option.detail != null) {
                    Text(
                        text = option.detail,
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
            }
        }
    }
}
