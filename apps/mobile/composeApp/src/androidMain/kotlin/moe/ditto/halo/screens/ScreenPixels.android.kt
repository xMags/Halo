package moe.ditto.halo.screens

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

@Composable
internal actual fun rememberScreenShortSidePixels(): Int {
    val context = LocalContext.current
    // Read again when the configuration changes, which is when a display
    // mode change reaches the app.
    val configuration = LocalConfiguration.current
    return remember(context, configuration) { screenShortSidePixels(context) }
}

/**
 * The default display's current mode, in physical pixels. The mode rather
 * than the window metrics: a TV draws its interface at 1080p over a 2160p
 * panel, and the panel is what a 4K file would be shown on.
 */
internal fun screenShortSidePixels(context: Context): Int = runCatching {
    val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        ?: return@runCatching 0
    val mode = display.mode
    val shorter = minOf(mode.physicalWidth, mode.physicalHeight)
    if (shorter > 0) shorter else 0
}.getOrDefault(0)
