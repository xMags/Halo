package moe.ditto.halo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val ButtonShape = RoundedCornerShape(HaloRadius.Md + 2.dp)
private val BadgeShape = RoundedCornerShape(HaloRadius.Md + 2.dp)
private val ButtonHeight = 50.dp
private val BadgeSize = 42.dp

/**
 * Asks before something irreversible happens.
 *
 * Deliberately not a one-option [SelectSheet]: a picker's row is an alternative
 * among others, so a lone destructive row reads as a list that lost its list —
 * indented past an empty check column, with the consequence set as a row detail
 * in the size the app uses for throwaway metadata. A confirmation is the
 * opposite shape. It names one thing, spells out what happens to it, and offers
 * exactly two ways out, which is why the commit is a button and the way back is
 * its equal rather than a corner glyph.
 *
 * Styled destructive throughout, because every confirmation in the app is a
 * delete or a replace. Give it a gentler tone the day one isn't.
 *
 * See [SheetScaffold] for where this has to sit in a screen's tree, and for what
 * [bottomClearance] is for.
 *
 * @param subject what is being acted on, in the words the user just tapped on.
 * @param body what will happen, and what will not.
 */
@Composable
fun ConfirmSheet(
    visible: Boolean,
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    subject: String? = null,
    icon: ImageVector? = null,
    cancelLabel: String = "Cancel",
    bottomClearance: Dp = 0.dp,
) {
    SheetScaffold(
        visible = visible,
        onDismiss = onDismiss,
        bottomPadding = HaloSpacing.Lg,
        modifier = modifier,
        bottomClearance = bottomClearance,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HaloSpacing.Lg, vertical = HaloSpacing.Sm),
            horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Md - 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Box(
                    modifier = Modifier
                        .size(BadgeSize)
                        .clip(BadgeShape)
                        .background(HaloColors.Danger.copy(alpha = 0.14f))
                        .border(1.dp, HaloColors.Danger.copy(alpha = 0.34f), BadgeShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = HaloColors.Danger,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(text = title, style = HaloType.Heading)
                if (subject != null) {
                    Text(
                        text = subject,
                        color = HaloColors.TextMeta,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
        }

        Text(
            text = body,
            style = HaloType.Caption.copy(fontSize = 13.sp, lineHeight = 19.sp),
            modifier = Modifier.padding(
                start = HaloSpacing.Lg,
                end = HaloSpacing.Lg,
                top = HaloSpacing.Xs + 2.dp,
                bottom = HaloSpacing.Md + 2.dp,
            ),
        )

        Column(
            modifier = Modifier.padding(horizontal = HaloSpacing.Lg),
            verticalArrangement = Arrangement.spacedBy(HaloSpacing.Sm + 2.dp),
        ) {
            SheetButton(
                label = confirmLabel,
                labelColor = HaloColors.Danger,
                fill = HaloColors.Danger.copy(alpha = 0.14f),
                border = HaloColors.Danger.copy(alpha = 0.42f),
                onClick = onConfirm,
            )
            SheetButton(
                label = cancelLabel,
                labelColor = HaloColors.Text,
                fill = Color.White.copy(alpha = 0.06f),
                border = HaloColors.GlassBorder,
                onClick = onDismiss,
            )
        }
    }
}

@Composable
private fun SheetButton(
    label: String,
    labelColor: Color,
    fill: Color,
    border: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(ButtonHeight)
            .clip(ButtonShape)
            .background(fill)
            .border(1.dp, border, ButtonShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, color = labelColor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}
