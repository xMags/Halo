import type { SubtitleOutline } from '@halo/core'

/**
 * Subtitle appearance — shared by the settings pane and the player's live
 * rail, which must offer the same values or the two disagree about what is
 * selected.
 *
 * Everything here maps onto an mpv runtime property, so a change applies to
 * the playing file immediately; nothing reloads the stream. These style plain
 * text subtitles only — embedded ASS keeps its authored look, because we never
 * force `sub-ass-override`.
 */

/**
 * Bundled families (apps/desktop/fonts, handed to libass via `sub-fonts-dir`).
 * These names resolve on every install, independent of OS fonts, and are the
 * standard values for the synced setting. Mobile bundling the same set is a
 * planned follow-up; until then a family set on one platform may not resolve
 * on the other, and an unknown family falls back to the default.
 */
export const SUBTITLE_FONTS: ReadonlyArray<{ label: string; family?: string }> = [
  { label: 'Default' },
  { label: 'Inter', family: 'Inter' },
  { label: 'Serif', family: 'Source Serif 4' },
  { label: 'Mono', family: 'JetBrains Mono' },
]

/** mpv's own `sub-font` default — used to clear a cleared preference. */
export const MPV_DEFAULT_SUB_FONT = 'sans-serif'

export const SUBTITLE_OUTLINES: ReadonlyArray<{ key: SubtitleOutline; label: string }> = [
  { key: 'none', label: 'None' },
  { key: 'thin', label: 'Thin' },
  { key: 'normal', label: 'Normal' },
  { key: 'thick', label: 'Thick' },
]

/** mpv `sub-border-size` per outline step (mpv's own default border is 3). */
export const OUTLINE_BORDER: Record<SubtitleOutline, number> = {
  none: 0,
  thin: 1.5,
  normal: 3,
  thick: 4.5,
}

export const SUBTITLE_SCALE_MIN = 50
export const SUBTITLE_SCALE_MAX = 200
export const SUBTITLE_SCALE_STEP = 5
export const SUBTITLE_SCALE_DEFAULT = 100
