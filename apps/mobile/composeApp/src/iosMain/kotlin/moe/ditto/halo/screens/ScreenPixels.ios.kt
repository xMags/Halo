package moe.ditto.halo.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.UIKit.UIScreen

@Composable
internal actual fun rememberScreenShortSidePixels(): Int = remember { screenShortSidePixels() }

/**
 * The main screen's native size, in physical pixels. `nativeBounds` is the
 * panel itself, unaffected by orientation or the point scale.
 */
@OptIn(ExperimentalForeignApi::class)
private fun screenShortSidePixels(): Int {
    val (width, height) = UIScreen.mainScreen.nativeBounds.useContents { size.width to size.height }
    val shorter = minOf(width, height)
    return if (shorter.isFinite() && shorter > 0) shorter.toInt() else 0
}
