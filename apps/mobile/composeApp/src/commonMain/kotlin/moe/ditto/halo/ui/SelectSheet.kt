package moe.ditto.halo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class SelectOption(
    val key: String,
    val label: String,
    val detail: String? = null,
    val selected: Boolean = false,
    val destructive: Boolean = false,
)

private val DividerColor = Color.White.copy(alpha = 0.08f)

/**
 * Frosted picker for audio tracks, subtitles, seasons, and settings choices.
 *
 * One tap picks and closes: this is a list of alternatives, not a form. A choice
 * with consequences to spell out — deleting a file, replacing one — belongs in a
 * [ConfirmSheet] instead, which states what will happen and makes the commit an
 * explicit button.
 *
 * See [SheetScaffold] for where this has to sit in a screen's tree, and for what
 * [bottomClearance] is for.
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
    bottomClearance: Dp = 0.dp,
    footer: (@Composable () -> Unit)? = null,
) {
    val side = presentation == SheetPresentation.Side
    SheetScaffold(
        visible = visible,
        onDismiss = onClose,
        bottomPadding = if (side) HaloSpacing.Md else HaloSpacing.Xl + HaloSpacing.Sm,
        modifier = modifier,
        presentation = presentation,
        bottomClearance = bottomClearance,
    ) {
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
                    color = when {
                        option.destructive -> HaloColors.Danger
                        option.selected -> Color.White
                        else -> Color.White.copy(alpha = 0.9f)
                    },
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
