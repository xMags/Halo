package moe.ditto.halo.screens.player

import moe.ditto.halo.api.SubtitleOutline
import moe.ditto.halo.api.UserSettings
import moe.ditto.halo.player.SubtitleStyle
import kotlin.math.roundToInt

/**
 * The caption appearance is stored as synced preferences and applied to the
 * engine, which are two different vocabularies: the settings document speaks in
 * percentages and named outline weights, and the renderer draws in pixels.
 *
 * Every reading is treated as untrusted. The document is shared with the
 * desktop client and passed through the server with unknown fields intact, so a
 * value out of range is a value another client wrote, not an impossibility.
 */
internal const val MinSubtitleScalePercent = 50
internal const val MaxSubtitleScalePercent = 200

/** How far the shadow is offset when it is on. Off is no offset at all. */
private const val ShadowOffsetPixels = 2.0

internal fun subtitleStyleOf(settings: UserSettings): SubtitleStyle = SubtitleStyle(
    scale = settings.subtitleScalePercent
        ?.coerceIn(MinSubtitleScalePercent, MaxSubtitleScalePercent)
        ?.let { it / 100.0 }
        ?: 1.0,
    font = settings.subtitleFontFamily?.takeIf { it.isNotBlank() },
    outlineWidthPixels = outlineWidthPixels(settings.subtitleOutline),
    shadowOffsetPixels = if (settings.subtitleShadow == true) ShadowOffsetPixels else 0.0,
)

/**
 * A null outline is "not chosen", which is the renderer's own weight, and is
 * deliberately not the same as [SubtitleOutline.None].
 */
internal fun outlineWidthPixels(outline: SubtitleOutline?): Double = when (outline) {
    SubtitleOutline.None -> 0.0
    SubtitleOutline.Thin -> 1.0
    SubtitleOutline.Normal -> SubtitleStyle.DefaultOutlineWidthPixels
    SubtitleOutline.Thick -> 5.0
    null -> SubtitleStyle.DefaultOutlineWidthPixels
}

/**
 * What the player writes back after the viewer changes it. Only the two the
 * rail can edit: outline and shadow are set elsewhere and reading them back
 * from the engine would let the player rewrite settings it never touched.
 */
internal data class SubtitlePreference(val scale: Double, val font: String?)

internal fun UserSettings.withSubtitlePreference(preference: SubtitlePreference): UserSettings =
    withSubtitleScalePercent(
        (preference.scale * 100.0).roundToInt().coerceIn(MinSubtitleScalePercent, MaxSubtitleScalePercent),
    ).withSubtitleFontFamily(preference.font)
