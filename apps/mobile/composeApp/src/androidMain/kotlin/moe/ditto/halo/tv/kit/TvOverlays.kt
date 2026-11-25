package moe.ditto.halo.tv.kit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import moe.ditto.halo.ui.HaloColors

/**
 * Keeps remote focus inside an overlay: on open, focus moves into it; moving
 * past its edges does nothing; and when it closes, focus goes back to the
 * control that had it when the overlay opened, or to [returnFocusTo] when
 * that control is gone.
 */
@Composable
private fun Modifier.trapFocus(initial: FocusRequester, returnFocusTo: FocusRequester?): Modifier {
    val returnTo by rememberUpdatedState(returnFocusTo)
    val tracker = LocalTvFocusTracker.current
    // Read before focus moves in: afterwards the tracker names a control in here.
    val opener = remember { tracker.last }
    val view = LocalView.current
    LaunchedEffect(Unit) {
        // The first frame lays the overlay out; its focusables exist after it.
        runCatching { initial.requestFocus() }
    }
    DisposableEffect(Unit) {
        onDispose {
            // Posted: the overlay's focused node is still attached while this
            // runs, and removing it would clear a focus requested here.
            view.post {
                val restored = opener?.let { runCatching { it.requestFocus(FocusDirection.Enter) }.getOrDefault(false) } ?: false
                if (!restored) returnTo?.let { runCatching { it.requestFocus() } }
            }
        }
    }
    return this
        .focusRequester(initial)
        .focusProperties { onExit = { cancelFocusChange() } }
        .focusGroup()
}

/** Swallows taps so nothing behind an overlay can be touched through it. */
private fun Modifier.blockTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent()
    }
}

/**
 * A centred dialog over a dimmed screen. Back calls [onDismiss]. The first
 * focusable in [content] takes focus.
 */
@Composable
internal fun TvDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    returnFocusTo: FocusRequester? = null,
    width: Dp = 520.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val initial = remember { FocusRequester() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.62f))
            .blockTouches(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = modifier
                .widthIn(max = width)
                .shadow(24.dp, RoundedCornerShape(20.dp))
                .background(HaloColors.Surface, RoundedCornerShape(20.dp))
                .padding(32.dp)
                .trapFocus(initial, returnFocusTo),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

/**
 * Asks before doing something. A [destructive] confirmation is red. Focus
 * starts on Cancel when [cancelFirst] (always for destructive ones), so a
 * stray press of select does no harm.
 */
@Composable
internal fun TvConfirmDialog(
    title: String,
    message: String?,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    returnFocusTo: FocusRequester? = null,
    destructive: Boolean = false,
    cancelFirst: Boolean = destructive,
    cancelLabel: String = "Cancel",
) {
    TvDialog(onDismiss = onDismiss, returnFocusTo = returnFocusTo) {
        Text(title, style = TvType.Title)
        if (message != null) Text(message, style = TvType.Body)
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val confirmStyle = if (destructive) TvButtonStyle.Danger else TvButtonStyle.Primary
            if (cancelFirst) {
                TvButton(cancelLabel, onClick = onDismiss)
                TvButton(confirmLabel, onClick = onConfirm, style = confirmStyle)
            } else {
                TvButton(confirmLabel, onClick = onConfirm, style = confirmStyle)
                TvButton(cancelLabel, onClick = onDismiss)
            }
        }
    }
}

/**
 * A side panel of text to read: a synopsis, a biography, a title's facts.
 * Text takes no focus, so the panel holds it itself and turns Up and Down
 * into scrolling, a screenful at a time less a line of overlap.
 */
@Composable
internal fun TvReadingPanel(
    title: String,
    onDismiss: () -> Unit,
    width: Dp = 560.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    TvSidePanel(title = title, onDismiss = onDismiss, width = width) {
        val stepPx = with(LocalDensity.current) { ReadingStep.toPx() }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    val delta = when (event.key) {
                        Key.DirectionDown -> stepPx
                        Key.DirectionUp -> -stepPx
                        else -> return@onKeyEvent false
                    }
                    scope.launch { scroll.animateScrollBy(delta) }
                    true
                }
                .focusable()
                .verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

private val ReadingStep = 220.dp

/**
 * A panel sliding over the right side of the screen, for choices that keep
 * the screen behind them in view (audio, subtitles, source details). Back
 * calls [onDismiss].
 */
@Composable
internal fun TvSidePanel(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    returnFocusTo: FocusRequester? = null,
    width: Dp = 440.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val initial = remember { FocusRequester() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .blockTouches(),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Column(
            modifier = modifier
                .width(width)
                .fillMaxHeight()
                .shadow(32.dp)
                .background(HaloColors.Surface)
                .padding(start = 32.dp, end = TvMetrics.SideMargin, top = TvMetrics.VerticalMargin + 8.dp, bottom = TvMetrics.VerticalMargin)
                .trapFocus(initial, returnFocusTo),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = TvType.Title)
            if (subtitle != null) Text(subtitle, style = TvType.Meta)
            content()
        }
    }
}
