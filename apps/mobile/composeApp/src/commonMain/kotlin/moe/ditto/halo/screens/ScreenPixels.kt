package moe.ditto.halo.screens

import androidx.compose.runtime.Composable

/**
 * Physical pixels of the shorter side of the display this app is on: what the
 * source ranking compares a picture's height with. On a TV it is the panel's
 * own mode (2160 on a 4K set), not the lower size the interface is drawn at.
 * 0 (unknown, so no cap) wherever the platform cannot say; never throws.
 */
@Composable
internal expect fun rememberScreenShortSidePixels(): Int
