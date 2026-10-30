package moe.ditto.halo.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import kotlinx.coroutines.delay

/** How long a logo may keep the title's place before the name is shown instead. */
private const val LogoPatienceMs = 2_000L

/**
 * A title's logo art when its addon has one, else its name.
 *
 * The place is held empty while the logo is on its way rather than filled with
 * the name, because the name turning into the logo a moment later reads as the
 * page changing its mind. A logo that fails gives way to the name at once, and
 * one that is slow does after [LogoPatienceMs], so the title is never a lasting
 * gap. The logo stays composed throughout: it is what asks for the picture.
 *
 * @param logoPending true while it is not known yet whether there is a logo.
 */
@Composable
internal fun TitleArt(
    name: String,
    logo: String?,
    logoMaxWidth: Dp,
    /** Also the height held for the logo, so what sits beside it does not move when it lands. */
    logoMaxHeight: Dp,
    logoPending: Boolean = false,
    nameText: @Composable () -> Unit,
) {
    val painter = logo?.takeIf { it.isNotBlank() }?.let {
        rememberAsyncImagePainter(model = it, contentScale = ContentScale.Fit)
    }
    val state = painter?.state?.collectAsState()?.value
    var outOfPatience by remember(name) { mutableStateOf(false) }
    LaunchedEffect(name) {
        delay(LogoPatienceMs)
        outOfPatience = true
    }

    val waiting = logoPending || (painter != null && state !is AsyncImagePainter.State.Error)
    if (!waiting) {
        nameText()
        return
    }
    Box(Modifier.height(logoMaxHeight), contentAlignment = Alignment.BottomStart) {
        if (outOfPatience && state !is AsyncImagePainter.State.Success) nameText()
        // Bounded both ways: addons ship anything from square marks to long wordmarks.
        painter?.let {
            Image(
                painter = it,
                contentDescription = name,
                contentScale = ContentScale.Fit,
                alignment = Alignment.BottomStart,
                modifier = Modifier.widthIn(max = logoMaxWidth).height(logoMaxHeight),
            )
        }
    }
}
