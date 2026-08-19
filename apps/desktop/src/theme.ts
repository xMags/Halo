/**
 * Halo desktop design system. Near-black window, monochrome chrome, one iOS
 * blue for interaction and one white button per screen for the hero action.
 *
 * Colour roles:
 *   accent      — interaction (selection bar, progress, focused field)
 *   accentText  — the readable-on-dark tint of the accent: kickers, links,
 *                 active pills. Never use `accent` for text on the canvas.
 *   primary     — the single hero call-to-action (Play/Resume/Sign in):
 *                 white fill, black label, at most one per screen.
 *
 * These constants mirror the custom properties in index.css. Stylesheets use
 * the variables; these exports exist for the handful of places a value has to
 * reach JS (canvas-free gradients, inline widths, chart-like fills).
 */
export const colors = {
  /** App background and title bar. */
  window: '#07080b',
  /** Left navigation rail — half a step darker than the canvas. */
  rail: '#090a0f',
  /** List rows and flat cards. */
  surface: '#0c0e13',
  /** Hero cards, aside panels — one step above `surface`. */
  surfaceRaised: '#0e1015',
  /** Fill behind poster/still/backdrop art while it loads or is missing. */
  placeholder: '#14161d',
  /** Row hover fill. */
  surfaceHover: '#12151b',

  /** Structural dividers: title bar, rail, command bar. */
  hairline: 'rgba(255,255,255,0.055)',
  /** Card and row borders. */
  border: 'rgba(255,255,255,0.08)',
  /** Borders that need to read as interactive (hover, focus, hero buttons). */
  borderStrong: 'rgba(255,255,255,0.13)',

  text: '#f4f6fb',
  /** Button labels and values. */
  textSecondary: '#c7cdd9',
  /** Body copy and inactive navigation. */
  textMuted: '#8b93a5',
  /** Mono metadata. */
  textDim: '#6b7383',
  /** Kickers and the quietest labels. */
  textDimmer: '#4e5666',

  accent: '#0a84ff',
  accentText: '#7ec0ff',
  success: '#5dd39e',
  warning: '#ffd479',
  danger: '#ff6b6b',
  /** Window close button hover — the one red that is not `danger`. */
  closeHover: '#c6273a',

  primary: '#ffffff',
  onPrimary: '#000000',
  onAccent: '#ffffff',
} as const

export const radius = {
  /** Controls: window buttons, small icon squares, sort/ghost buttons. */
  control: 8,
  /** Rows and buttons. */
  row: 11,
  /** Cards. */
  card: 14,
  /** Hero blocks and floating overlays. */
  overlay: 17,
  pill: 999,
} as const

/**
 * The two window tiers. Everything responsive is driven by these, applied as
 * one size class (a `min-width` media query in index.css) rather than
 * per-screen breakpoints — a view must never disagree with its chrome about
 * which tier it is in.
 */
export const sizeClass = {
  /** Windowed, 1280 × 800 — also the minimum supported window. */
  compact: {
    gutter: 28,
    navRail: 212,
    commandBar: 54,
    heroHeight: 322,
    posterWidth: 132,
    wideCard: 268,
    libraryColumns: 7,
  },
  /** Maximised, 1920 × 1080. */
  wide: {
    gutter: 44,
    navRail: 248,
    commandBar: 62,
    heroHeight: 460,
    posterWidth: 168,
    wideCard: 336,
    libraryColumns: 9,
  },
} as const

/** Viewport width at which the wide tier takes over (see index.css). */
export const WIDE_TIER_MIN_WIDTH = 1600

/** Title bar height, fixed in both tiers. */
export const TITLE_BAR_HEIGHT = 36
