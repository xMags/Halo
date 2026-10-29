package moe.ditto.halo.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import moe.ditto.halo.SessionGraph
import moe.ditto.halo.ui.ConfirmSheet
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloIcons
import moe.ditto.halo.ui.HaloRadius
import moe.ditto.halo.ui.HaloSpacing
import moe.ditto.halo.ui.HaloDimensions
import moe.ditto.halo.ui.TitleMenu

/** The shell's title menus and what they put on screen: the menu, a question before a removal, a failure after one. */
@Stable
internal class TitleMenuHost {
    var menu by mutableStateOf<TitleMenu?>(null)
    var confirmation by mutableStateOf<MenuConfirmation?>(null)
    var message by mutableStateOf<String?>(null)
    lateinit var menus: TitleMenus
        internal set
}

@Composable
internal fun rememberTitleMenuHost(graph: SessionGraph, navigation: TitleNavigation): TitleMenuHost {
    val scope = rememberCoroutineScope()
    return remember(graph) {
        TitleMenuHost().also { host ->
            host.menus = TitleMenus(
                graph = graph,
                scope = scope,
                navigation = navigation,
                present = { host.menu = it },
                confirm = { host.confirmation = it },
                report = { host.message = it },
            )
        }
    }
}

/**
 * The menu sheet, the confirmation sheet and the failure message. Drawn as the
 * shell's last children, over the tab bar: the sheets are modal, and a message
 * has to be seen on whichever screen the menu was opened from. The
 * confirmation comes after the menu, so it draws over one still sliding away.
 */
@Composable
internal fun TitleMenuOverlays(host: TitleMenuHost, messageModifier: Modifier) {
    TitleMenuSheet(menu = host.menu, onDismiss = { host.menu = null })

    val pending = host.confirmation
    ConfirmSheet(
        visible = pending != null,
        title = pending?.title.orEmpty(),
        subject = pending?.subject,
        body = pending?.body.orEmpty(),
        confirmLabel = pending?.confirmLabel.orEmpty(),
        icon = HaloIcons.Trash,
        onConfirm = {
            pending?.onConfirm?.invoke()
            host.confirmation = null
        },
        onDismiss = { host.confirmation = null },
    )

    val message = host.message
    // Cleared after a while, and replaced at once by a newer failure.
    LaunchedEffect(message) {
        if (message == null) return@LaunchedEffect
        delay(MessageMs)
        host.message = null
    }
    AnimatedVisibility(visible = message != null, enter = fadeIn(), exit = fadeOut(), modifier = messageModifier) {
        // Kept while fading out, so the pill does not empty before it goes.
        var shown by remember { mutableStateOf("") }
        if (message != null) shown = message
        Text(
            text = shown,
            color = HaloColors.Text,
            fontSize = 14.sp,
            modifier = Modifier
                .padding(
                    start = HaloSpacing.Md,
                    end = HaloSpacing.Md,
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                        HaloDimensions.TabBarSpace,
                )
                .clip(RoundedCornerShape(HaloRadius.Md))
                .background(HaloColors.OverlayPill)
                .padding(horizontal = HaloSpacing.Md, vertical = 12.dp),
        )
    }
}

private const val MessageMs = 4_000L
