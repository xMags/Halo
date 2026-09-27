/**
 * Halo desktop design system — the token reference.
 *
 * The applied values live in `index.css`: both palettes are declared there so
 * the first paint is already correct (a JS-applied palette would flash the
 * wrong theme). This file documents what the names mean and exports the
 * handful of numbers that have to reach JS.
 *
 * Token names mirror the brushes in the native WinUI Halo Desktop's
 * `Styles/Tokens.xaml`, so a value here can be checked against that file:
 *
 *   --m    window (Mica)          --t1..--t4  text, brightest to faintest
 *   --ly   content layer          --ac        accent (text-safe)
 *   --cd   card fill              --aa        accent fill (buttons)
 *   --cs   card stroke            --oa        on-accent label
 *   --ct   control fill           --at        accent tint
 *   --cts  control stroke         --ns        nav selection
 *   --cth  control hover          --su/--ca/--cr  success / caution / critical
 *   --sb   subtle hover           --df/--dh   danger fill / hover
 *   --sbp  subtle pressed (WinUI's SubtleFillColorTertiary)
 *   --tt   tile, --tb secondary   --dv        divider
 *   --p1/--p2  placeholder art    --pt        placeholder caption
 *   --ds   sheet + menu surface   --ib/--is   info fill / stroke
 *   --fl   flyout (dialog surface)
 *   --chd  chart dim              --hs1/--hs2 hero scrim stops
 *
 * The player overlay is pinned to dark in both themes and uses literal
 * colours, not these tokens — see the Player section at the end of index.css.
 */

/** Theme the user picked. `system` follows the OS setting live. */
export type ThemeChoice = 'light' | 'dark' | 'system'

/**
 * Title bar height. Load-bearing beyond layout: windowed playback reserves it
 * with mpv's `video-margin-ratio-top`, so the player must use this same value
 * or the picture sits under the bar.
 */
export const TITLE_BAR_HEIGHT = 32

/** Width reserved for the caption buttons at the end of the title bar. */
export const CAPTION_SLOT_WIDTH = 138

/** Navigation pane width when open; the compact strip is 48px. */
export const NAV_PANE_WIDTH = 224

/**
 * Content-column widths at which the poster/card/gutter/hero metrics step up.
 * Applied as container queries on the content surface (`index.css`), listed
 * here because the values are part of the design contract.
 */
export const CONTENT_BREAKPOINTS = {
  /** Poster 148, continue card 300, gutter 30, hero 360. */
  medium: 1100,
  /** The downloads detail pane appears. */
  downloadsPane: 1200,
  /** Poster 168, continue card 336, gutter 36, hero 420. */
  wide: 1500,
} as const

/** Hero rotation dwell, paused on hover. */
export const HERO_DWELL_MS = 6_000
