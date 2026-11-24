package moe.ditto.halo.tv.kit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.ditto.halo.ui.HaloColors

private val FieldColors = TvSurfaceColors(rest = HaloColors.FieldFill, focused = HaloColors.SurfaceHigh)

/**
 * A text field for the remote. Moving focus onto it does not open the
 * keyboard (that would trap the remote's arrows inside it); pressing select
 * does. The keyboard's action key calls [onSubmit] and hands focus back to
 * the field, and so does Back.
 */
@Composable
internal fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    onSubmit: () -> Unit = {},
    outerFocus: FocusRequester = remember { FocusRequester() },
    /** Where Down goes, when the nearest control below is not the next step. */
    down: FocusRequester? = null,
) {
    var editing by remember { mutableStateOf(false) }
    val inner = remember { FocusRequester() }
    // Only a field that had focus can lose it; the first report is not a loss.
    var innerFocused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current

    fun stopEditing() {
        if (!editing) return
        editing = false
        keyboard?.hide()
        runCatching { outerFocus.requestFocus() }
    }

    LaunchedEffect(editing) {
        if (!editing) return@LaunchedEffect
        runCatching { inner.requestFocus() }
        keyboard?.show()
    }
    BackHandler(enabled = editing) { stopEditing() }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = TvType.Caption)
        TvSurface(
            onClick = { editing = true },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .focusProperties { if (down != null) this.down = down }
                .focusRequester(outerFocus)
                .semantics { contentDescription = label },
            cornerRadius = 12.dp,
            colors = FieldColors,
            focusScale = TvMetrics.RowFocusScale,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.CenterStart)
                    .then(
                        if (editing) Modifier.border(2.dp, HaloColors.Accent, RoundedCornerShape(12.dp)) else Modifier,
                    )
                    .padding(horizontal = 18.dp, vertical = 14.dp),
            ) {
                if (value.isEmpty() && !editing) Text(placeholder, color = HaloColors.TextFaint, fontSize = 17.sp, maxLines = 1)
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = TextStyle(color = HaloColors.Text, fontSize = 17.sp),
                    cursorBrush = SolidColor(HaloColors.Accent),
                    visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (password) KeyboardType.Password else keyboardType,
                        imeAction = imeAction,
                        autoCorrectEnabled = false,
                    ),
                    keyboardActions = KeyboardActions(onAny = {
                        stopEditing()
                        onSubmit()
                    }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(inner)
                        .focusProperties { canFocus = editing }
                        .onFocusChanged { state ->
                            if (state.isFocused) {
                                innerFocused = true
                            } else if (innerFocused) {
                                innerFocused = false
                                editing = false
                            }
                        },
                )
            }
        }
    }
}
