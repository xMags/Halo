/**
 * Halo "glassy-dark" design system — desktop port of apps/mobile/src/theme.ts.
 * Identity: near-black canvas, frosted translucent chrome, iOS-blue system
 * accent, white hero CTAs, gradient scrims over real poster art.
 *
 * Colour roles:
 *   accent   — system/interactive (tabs, links, small buttons, selection)
 *   primary  — the hero call-to-action (Play/Sources): white on black text,
 *              always the single most prominent action on a screen.
 *
 * These constants mirror the CSS variables in index.css; use the variables in
 * stylesheets and these exports where a value is needed in TS.
 */
export const colors = {
  background: '#0a0c11',
  surface: '#14161d',
  surfaceHigh: '#1c202a',
  border: '#252a35',
  text: '#f4f6fb',
  textDim: '#8b93a5',
  accent: '#0a84ff',
  danger: '#ff6b6b',
  success: '#5dd39e',

  // Hero call-to-action (white button, black label).
  primary: '#ffffff',
  onPrimary: '#000000',
  onAccent: '#ffffff',

  // Translucent surfaces — pair with backdrop-filter blur.
  glass: 'rgba(255,255,255,0.07)',
  glassBorder: 'rgba(255,255,255,0.11)',
  hairline: 'rgba(255,255,255,0.11)',
  fieldFill: 'rgba(255,255,255,0.09)',
  sheetTint: 'rgba(20,22,30,0.72)',
  /** Near-black fill for floating pills/HUDs over video (notices, gesture HUD). */
  overlayPill: 'rgba(5,7,12,0.82)',

  // Muted brights for metadata (ratings, etc.).
  gold: '#ffd479',
} as const

export const spacing = { xs: 4, sm: 8, md: 16, lg: 24, xl: 32 } as const

export const radius = { sm: 8, md: 12, lg: 16, xl: 20, pill: 999 } as const

export const POSTER_WIDTH = 112
export const POSTER_HEIGHT = 168
export const POSTER_RATIO = 1.5
