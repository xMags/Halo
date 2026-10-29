package moe.ditto.halo.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.ditto.halo.ui.HaloAsyncImage
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloIcons
import moe.ditto.halo.ui.HaloRadius
import moe.ditto.halo.ui.HaloSpacing
import moe.ditto.halo.ui.MenuAction
import moe.ditto.halo.ui.SheetScaffold
import moe.ditto.halo.ui.TitleMenu
import moe.ditto.halo.ui.monoStyle
import moe.ditto.halo.ui.rememberResponsive

/**
 * A title's long-press menu as a bottom sheet: the title it is about, with its
 * library toggle and a close button, then a list of actions. Every action closes the sheet before it runs, so one that opens a
 * screen or a confirmation never lands underneath it.
 *
 * Sits where every sheet sits (see [SheetScaffold]): the shell's last child.
 */
@Composable
internal fun TitleMenuSheet(menu: TitleMenu?, onDismiss: () -> Unit) {
    // Kept while the sheet slides away, so it does not empty on the way out.
    var shown by remember { mutableStateOf(menu) }
    if (menu != null) shown = menu
    val content = shown
    val responsive = rememberResponsive()

    SheetScaffold(visible = menu != null, onDismiss = onDismiss, bottomPadding = HaloSpacing.Lg) {
        if (content == null) return@SheetScaffold
        val run = { action: MenuAction ->
            onDismiss()
            action.onSelect()
        }
        // A floor on the height: a film with three tiles and one row would
        // otherwise be a strip along the bottom that reads as cut short. The
        // scaffold caps it at 70% of the screen, so this stays under that.
        Column(Modifier.heightIn(min = responsive.height * MinHeightFraction)) {
            Header(content, onDismiss)
            HorizontalDivider(
                color = HaloColors.Hairline,
                modifier = Modifier.padding(horizontal = HaloSpacing.Lg),
            )
            Column(Modifier.verticalScroll(rememberScrollState()).padding(top = HaloSpacing.Sm)) {
                content.actions.forEachIndexed { index, action ->
                    // What destroys something stands apart from the rest.
                    if (action.danger && index > 0 && !content.actions[index - 1].danger) {
                        HorizontalDivider(
                            color = HaloColors.Hairline,
                            modifier = Modifier.padding(horizontal = HaloSpacing.Lg, vertical = HaloSpacing.Xs),
                        )
                    }
                    ActionRow(action, onClick = { run(action) })
                }
            }
        }
    }
}

private const val MinHeightFraction = 0.55f

private val PosterShape = RoundedCornerShape(HaloRadius.Sm)

@Composable
private fun Header(menu: TitleMenu, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = HaloSpacing.Lg, end = HaloSpacing.Sm, top = HaloSpacing.Xs, bottom = HaloSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Md),
    ) {
        HaloAsyncImage(
            url = menu.poster,
            contentDescription = null,
            modifier = Modifier
                .size(width = 52.dp, height = 78.dp)
                .clip(PosterShape),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = menu.title,
                color = HaloColors.Text,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            menu.subtitle?.let {
                Text(
                    text = it,
                    style = monoStyle(11.5.sp, color = HaloColors.TextDim),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = HaloSpacing.Xs),
                )
            }
        }
        menu.saved?.let { saved ->
            HeaderButton(
                icon = if (saved) HaloIcons.Bookmark else HaloIcons.BookmarkOutline,
                description = if (saved) "Remove from library" else "Add to library",
                tint = if (saved) HaloColors.Accent else HaloColors.Text,
                onClick = menu.onToggleSaved,
            )
        }
        HeaderButton(icon = HaloIcons.Close, description = "Close", tint = HaloColors.Text, onClick = onDismiss)
    }
}

@Composable
private fun HeaderButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(HaloRadius.Pill))
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = description, tint = tint, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun ActionRow(action: MenuAction, onClick: () -> Unit) {
    val tint = if (action.danger) HaloColors.Danger else HaloColors.Text
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = HaloSpacing.Lg + HaloSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Lg),
    ) {
        Icon(imageVector = action.icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Text(text = action.label, color = tint, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
