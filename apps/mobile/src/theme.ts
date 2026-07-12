import type { TextStyle } from 'react-native'

/**
 * Halo "glassy-dark" design system.
 * Identity: near-black canvas, frosted translucent chrome, iOS-blue system
 * accent, white hero CTAs, gradient scrims over real poster art.
 *
 * Colour roles:
 *   accent   — system/interactive (tabs, links, small buttons, selection)
 *   primary  — the hero call-to-action (Play/Sources): white on black text,
 *              always the single most prominent action on a screen.
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

  // Translucent surfaces — pair with a BlurView underneath.
  glass: 'rgba(255,255,255,0.07)',
  glassBorder: 'rgba(255,255,255,0.11)',
  hairline: 'rgba(255,255,255,0.11)',
  fieldFill: 'rgba(255,255,255,0.09)',
  tabBarTint: 'rgba(15,17,23,0.72)',
  sheetTint: 'rgba(20,22,30,0.72)',
  /** Near-black fill for floating pills/HUDs over video (notices, unlock, gesture HUD). */
  overlayPill: 'rgba(5,7,12,0.82)',

  // Muted brights for metadata (ratings, etc.).
  gold: '#ffd479',
} as const

export const spacing = {
  xs: 4,
  sm: 8,
  md: 16,
  lg: 24,
  xl: 32,
} as const

export const radius = {
  sm: 8,
  md: 12,
  lg: 16,
  xl: 20,
  pill: 999,
} as const

/** Type ramp. Spread into a Text style; each entry is a valid RN TextStyle. */
export const type = {
  largeTitle: { fontSize: 30, fontWeight: '800', letterSpacing: -0.6, color: colors.text },
  title: { fontSize: 24, fontWeight: '800', letterSpacing: 0.2, color: colors.text },
  heading: { fontSize: 18, fontWeight: '700', letterSpacing: -0.3, color: colors.text },
  body: { fontSize: 14, lineHeight: 20, color: colors.text },
  callout: { fontSize: 15, fontWeight: '600', color: colors.text },
  caption: { fontSize: 12.5, color: colors.textDim },
  overline: {
    fontSize: 12,
    fontWeight: '600',
    letterSpacing: 0.5,
    textTransform: 'uppercase',
    color: colors.textDim,
  },
} satisfies Record<string, TextStyle>

/** Standard bottom-to-black scrim for hero art (title-over-image legibility). */
export const heroScrim = [
  'rgba(10,12,17,0.25)',
  'rgba(10,12,17,0)',
  'rgba(10,12,17,0.85)',
  colors.background,
] as const
export const heroScrimLocations = [0, 0.4, 0.82, 1] as const

export const POSTER_WIDTH = 112
export const POSTER_HEIGHT = 168
export const POSTER_RATIO = 1.5

/** Bottom padding so scroll content clears the floating (absolute) tab bar. */
export const TAB_BAR_SPACE = 96
