package moe.ditto.halo.tv

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import moe.ditto.halo.screens.TitleMenuHost
import moe.ditto.halo.tv.kit.TvConfirmDialog
import moe.ditto.halo.tv.kit.TvMetrics
import moe.ditto.halo.tv.kit.TvRowSurface
import moe.ditto.halo.tv.kit.TvSidePanel
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloIcons

/**
 * The title menus on a TV: the same menus the phone opens on a long press
 * ([moe.ditto.halo.screens.TitleMenus]), opened here by holding select or
 * pressing Menu, as a side panel. The library toggle comes first, then the
 * actions; removals ask first, in red.
 */
@Composable
internal fun TvTitleMenuOverlays(host: TitleMenuHost) {
    host.menu?.let { menu ->
        TvSidePanel(
            title = menu.title,
            subtitle = menu.subtitle,
            onDismiss = { host.menu = null },
        ) {
            menu.saved?.let { saved ->
                TvRowSurface(
                    title = if (saved) "Remove from library" else "Add to library",
                    onClick = {
                        host.menu = null
                        menu.onToggleSaved()
                    },
                    trailing = {
                        Icon(
                            if (saved) HaloIcons.Bookmark else HaloIcons.BookmarkOutline,
                            contentDescription = null,
                            tint = HaloColors.TextDim,
                        )
                    },
                )
            }
            menu.actions.forEach { action ->
                TvRowSurface(
                    title = action.label,
                    titleColor = if (action.danger) HaloColors.Danger else HaloColors.Text,
                    onClick = {
                        host.menu = null
                        action.onSelect()
                    },
                    trailing = {
                        Icon(
                            action.icon,
                            contentDescription = null,
                            tint = if (action.danger) HaloColors.Danger else HaloColors.TextDim,
                        )
                    },
                )
            }
        }
    }

    host.confirmation?.let { pending ->
        TvConfirmDialog(
            title = pending.title,
            message = listOfNotNull(pending.subject, pending.body).joinToString("\n\n"),
            confirmLabel = pending.confirmLabel,
            destructive = true,
            onConfirm = {
                host.confirmation = null
                pending.onConfirm()
            },
            onDismiss = { host.confirmation = null },
        )
    }

    TvToast(host.message, onShown = { host.message = null })
}

/** A short message at the bottom of the screen that goes away by itself. */
@Composable
internal fun TvToast(message: String?, onShown: () -> Unit) {
    LaunchedEffect(message) {
        if (message == null) return@LaunchedEffect
        delay(ToastMs)
        onShown()
    }
    var shown by remember { mutableStateOf("") }
    if (message != null) shown = message
    Box(Modifier.fillMaxSize().padding(bottom = TvMetrics.VerticalMargin + 8.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(visible = message != null, enter = fadeIn(), exit = fadeOut()) {
            Row(
                Modifier
                    .background(HaloColors.OverlayPill, RoundedCornerShape(14.dp))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(shown, color = HaloColors.Text, fontSize = 16.sp)
            }
        }
    }
}

private const val ToastMs = 4_000L
