package moe.ditto.halo.tv.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloIcons

internal enum class TvButtonStyle { Primary, Secondary, Danger }

private fun buttonColors(style: TvButtonStyle): Pair<TvSurfaceColors, Pair<Color, Color>> = when (style) {
    // White at rest and focused: the main action reads first.
    TvButtonStyle.Primary -> TvSurfaceColors(HaloColors.Primary, HaloColors.Primary) to
        (HaloColors.OnPrimary to HaloColors.OnPrimary)
    TvButtonStyle.Secondary -> TvSurfaceColors(HaloColors.FieldFill, HaloColors.Primary) to
        (HaloColors.Text to HaloColors.OnPrimary)
    TvButtonStyle.Danger -> TvSurfaceColors(HaloColors.DangerFill, HaloColors.Danger) to
        (HaloColors.Danger to HaloColors.OnAccent)
}

/** A pill button. A focused secondary button turns white, as the primary one is. */
@Composable
internal fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    style: TvButtonStyle = TvButtonStyle.Secondary,
    enabled: Boolean = true,
    onFocusChange: ((Boolean) -> Unit)? = null,
) {
    val (surface, ink) = buttonColors(style)
    TvSurface(
        onClick = onClick,
        modifier = modifier.height(TvMetrics.ButtonHeight),
        enabled = enabled,
        cornerRadius = TvMetrics.ButtonHeight / 2,
        colors = surface,
        contentAlignment = Alignment.Center,
        onFocusChange = onFocusChange,
    ) { focused ->
        val color = (if (focused) ink.second else ink.first).let { if (enabled) it else it.copy(alpha = 0.4f) }
        Row(
            modifier = Modifier.padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (icon != null) Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
            Text(text, color = color, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

/** A round icon-only button; [contentDescription] names it for accessibility and tests. */
@Composable
internal fun TvIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: TvButtonStyle = TvButtonStyle.Secondary,
) {
    val (surface, ink) = buttonColors(style)
    TvSurface(
        onClick = onClick,
        modifier = modifier
            .size(TvMetrics.ButtonHeight)
            .semantics { this.contentDescription = contentDescription },
        cornerRadius = TvMetrics.ButtonHeight / 2,
        colors = surface,
        contentAlignment = Alignment.Center,
    ) { focused ->
        Icon(icon, contentDescription = null, tint = if (focused) ink.second else ink.first, modifier = Modifier.size(20.dp))
    }
}

private val RowColors = TvSurfaceColors(rest = HaloColors.Surface, focused = HaloColors.SurfaceHigh)

/** A settings row: a title, an optional hint under it, and a trailing control. */
@Composable
internal fun TvRowSurface(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    titleColor: Color = HaloColors.Text,
    onMenu: (() -> Unit)? = null,
    colors: TvSurfaceColors = RowColors,
    /** An icon or picture before the title. */
    leading: (@Composable (focused: Boolean) -> Unit)? = null,
    trailing: @Composable (focused: Boolean) -> Unit = {},
) {
    TvSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(if (hint == null) 56.dp else 68.dp),
        onMenu = onMenu,
        cornerRadius = 14.dp,
        colors = colors,
        focusScale = TvMetrics.RowFocusScale,
    ) { focused ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).align(Alignment.CenterStart),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            leading?.invoke(focused)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, color = titleColor, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                if (hint != null) {
                    Text(hint, style = TvType.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            trailing(focused)
        }
    }
}

/** An on/off row; select flips it. */
@Composable
internal fun TvSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
) {
    TvRowSurface(
        title = title,
        hint = hint,
        onClick = { onCheckedChange(!checked) },
        modifier = modifier.semantics { stateDescription = if (checked) "On" else "Off" },
    ) {
        TvSwitch(checked)
    }
}

@Composable
private fun TvSwitch(checked: Boolean) {
    Box(
        modifier = Modifier
            .size(width = 44.dp, height = 24.dp)
            .background(if (checked) HaloColors.Accent else Color(0xFF424753), RoundedCornerShape(12.dp)),
    ) {
        Box(
            Modifier
                .padding(3.dp)
                .offset(x = if (checked) 20.dp else 0.dp)
                .size(18.dp)
                .background(Color.White, CircleShape),
        )
    }
}

/** A row that opens a choice; shows the current [value] and a chevron. */
@Composable
internal fun TvChoiceRow(
    title: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
) {
    TvRowSurface(title = title, hint = hint, onClick = onClick, modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(value, style = TvType.Meta, maxLines = 1)
            Icon(
                HaloIcons.ChevronLeft,
                contentDescription = null,
                tint = HaloColors.TextDim,
                // The icon set has a left chevron only.
                modifier = Modifier.size(16.dp).graphicsLayer { scaleX = -1f },
            )
        }
    }
}
