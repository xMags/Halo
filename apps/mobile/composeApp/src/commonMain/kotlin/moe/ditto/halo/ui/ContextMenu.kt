package moe.ditto.halo.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role

/**
 * A title's long-press menu: who it is about (the header, with its library
 * toggle) and a list of what can be done with it, the main actions first.
 * Destructive actions (remove, delete) come last and are drawn red, like every
 * destructive control in the app. Drawn by the shell's sheet; see
 * `screens/TitleMenuSheet.kt`.
 */
data class TitleMenu(
    val title: String,
    /** A mono line under the title: what it is and how far along ("S01E02 · 42 MIN LEFT"). */
    val subtitle: String?,
    val poster: String?,
    /** Null while the library has not loaded, which leaves the toggle out rather than guessed. */
    val saved: Boolean?,
    val onToggleSaved: () -> Unit,
    val actions: List<MenuAction>,
)

data class MenuAction(
    val label: String,
    val icon: ImageVector,
    val danger: Boolean = false,
    val onSelect: () -> Unit,
)

/**
 * A tap runs [onClick]; a long press runs [onLongPress], with the platform's
 * long-press vibration. A null [onLongPress] leaves the long press doing
 * nothing, as a plain `clickable` would.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.longPressClickable(
    onClick: () -> Unit,
    role: Role? = Role.Button,
    onLongPress: (() -> Unit)?,
): Modifier = combinedClickable(
    role = role,
    onClick = onClick,
    onLongClickLabel = if (onLongPress != null) "Show options" else null,
    onLongClick = onLongPress,
)
