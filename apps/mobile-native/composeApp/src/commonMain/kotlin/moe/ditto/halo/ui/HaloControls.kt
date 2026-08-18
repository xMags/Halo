package moe.ditto.halo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val heroScrimStops = HaloHeroScrimLocations.zip(HaloHeroScrim).toTypedArray()

/**
 * Bottom-weighted scrim over hero art, so titles and controls stay legible on
 * top of arbitrary poster photography. Place it over the art and size it with
 * `Modifier.matchParentSize()`.
 */
@Composable
fun HeroScrim(modifier: Modifier = Modifier) {
    Box(modifier.background(Brush.verticalGradient(colorStops = heroScrimStops)))
}

private val SegmentedTrackShape = RoundedCornerShape(HaloRadius.Md - 3.dp)
private val SegmentedThumbShape = RoundedCornerShape(HaloRadius.Sm - 1.dp)

/** iOS-style segmented filter. */
@Composable
fun Segmented(
    options: List<String>,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(SegmentedTrackShape)
            .background(Color.White.copy(alpha = 0.06f))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Sm),
    ) {
        options.forEach { option ->
            val active = option == value
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(SegmentedThumbShape)
                    .background(if (active) Color.White.copy(alpha = 0.16f) else Color.Transparent)
                    .selectable(
                        selected = active,
                        role = Role.RadioButton,
                        onClick = { onChange(option) },
                    )
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = option,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (active) Color.White else HaloColors.TextDim,
                )
            }
        }
    }
}

private val SearchFieldShape = RoundedCornerShape(HaloRadius.Md - 1.dp)
private const val SearchPlaceholder = "Search movies, series…"

/**
 * The frosted field's chrome, shared by both search entry points so the static
 * one on Home and the live one on Search cannot drift apart visually.
 */
@Composable
private fun SearchChrome(
    populated: Boolean,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = modifier
            .clip(SearchFieldShape)
            .background(HaloColors.FieldFill)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HaloSpacing.Sm),
    ) {
        Icon(
            imageVector = HaloIcons.Search,
            contentDescription = null,
            tint = if (populated) HaloColors.Text else HaloColors.TextDim,
            modifier = Modifier.size(17.dp),
        )
        Box(Modifier.weight(1f)) { content() }
        trailing?.invoke()
    }
}

/**
 * Search as a button: the whole field navigates instead of accepting input.
 * Home uses this so tapping search pushes a dedicated screen rather than
 * raising a keyboard over a browse surface.
 */
@Composable
fun SearchFieldButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    value: String = "",
    placeholder: String = SearchPlaceholder,
) {
    SearchChrome(
        populated = value.isNotEmpty(),
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Text(
            text = value.ifEmpty { placeholder },
            fontSize = 15.sp,
            fontWeight = if (value.isEmpty()) FontWeight.Normal else FontWeight.Medium,
            color = if (value.isEmpty()) HaloColors.TextDim else HaloColors.Text,
        )
    }
}

/** Editable search field with a clear affordance once it holds text. */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = SearchPlaceholder,
    onClear: () -> Unit = { onValueChange("") },
    onSubmit: () -> Unit = {},
    autoFocus: Boolean = false,
) {
    val focusRequester = remember { FocusRequester() }
    if (autoFocus) {
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
    }

    SearchChrome(
        populated = value.isNotEmpty(),
        modifier = modifier.fillMaxWidth(),
        trailing = {
            if (value.isNotEmpty()) {
                Icon(
                    imageVector = HaloIcons.CloseCircle,
                    contentDescription = "Clear search",
                    tint = HaloColors.TextDim,
                    modifier = Modifier
                        .size(17.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                            onClick = onClear,
                        ),
                )
            }
        },
    ) {
        Box {
            if (value.isEmpty()) {
                Text(text = placeholder, fontSize = 15.sp, color = HaloColors.TextDim)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                singleLine = true,
                textStyle = TextStyle(color = HaloColors.Text, fontSize = 15.sp),
                cursorBrush = SolidColor(HaloColors.Accent),
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Search,
                ),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            )
        }
    }
}

private val MetaTextColor = HaloColors.TextMeta

/** Dot-separated metadata (year · runtime · genre), with an optional gold rating. */
@Composable
fun MetaLine(
    parts: List<String>,
    modifier: Modifier = Modifier,
    rating: String? = null,
) {
    val items = parts.filter { it.isNotBlank() }
    if (items.isEmpty() && rating == null) return

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items.forEachIndexed { index, part ->
            if (index > 0) Text(text = "·", color = MetaTextColor, fontSize = 13.sp)
            Text(text = part, color = MetaTextColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        if (rating != null) {
            if (items.isNotEmpty()) Text(text = "·", color = MetaTextColor, fontSize = 13.sp)
            Icon(
                imageVector = HaloIcons.Star,
                contentDescription = null,
                tint = HaloColors.Gold,
                modifier = Modifier.size(12.dp),
            )
            Text(text = rating, color = HaloColors.Gold, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Centred empty or error state filling whatever space it is given. */
@Composable
fun CenterMessage(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(HaloColors.Background)
            .padding(HaloSpacing.Lg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = HaloColors.TextDim,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
        )
    }
}
