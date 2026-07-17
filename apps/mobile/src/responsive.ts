import { useWindowDimensions } from 'react-native'

/**
 * Responsive layout primitive — the single source of truth for phone/tablet
 * branching. Everything derives from `useWindowDimensions()` (reactive: it
 * re-renders on rotation, split-screen, and foldable unfold — `Dimensions.get`
 * is a stale snapshot and must never drive layout).
 *
 * Device class ("is this a tablet") is decided by the *smallest* dimension, the
 * rotation-invariant analog of Android's `sw600dp` resource qualifier. Using
 * bare width would misfire on a phone held in landscape (844×390 → reads as a
 * tablet mid-rotation). Column counts, in contrast, key off the *current* width
 * on purpose, so portrait and landscape pack a different number of posters.
 */

export type Breakpoint = 'phone' | 'tablet' | 'largeTablet'

/** Android sw600dp — catches every 7"+ tablet. */
const TABLET_MIN = 600
/** iPad-mini portrait / large-tablet tier for optional extra density. */
const LARGE_TABLET_MIN = 768

/** Comfortable reading width for single-column, form-like content on tablets. */
const CONTENT_MAX_WIDTH = 700

export interface Responsive {
  width: number
  height: number
  bp: Breakpoint
  isTablet: boolean
  isLandscape: boolean
  /** Poster-grid columns for the *current* window width (portrait ≠ landscape). */
  posterColumns: number
  /**
   * Max width for single-column reading content (settings forms, stream lists,
   * synopsis). `null` on phone → full-bleed. Screens opt in; poster rows and
   * grids deliberately stay full-width to use the extra space.
   */
  contentMaxWidth: number | null
  /** Pick a value by breakpoint without re-deriving `bp` at the call site. */
  pick: <T>(phone: T, tablet: T, large?: T) => T
}

function breakpointFor(smallestWidth: number): Breakpoint {
  if (smallestWidth >= LARGE_TABLET_MIN) return 'largeTablet'
  if (smallestWidth >= TABLET_MIN) return 'tablet'
  return 'phone'
}

/** Poster columns scale with available width, not device class. */
function posterColumnsFor(width: number): number {
  if (width >= 1400) return 7
  if (width >= 1100) return 6
  if (width >= 820) return 5
  if (width >= 600) return 4
  return 3
}

/**
 * Exact pixel width for one poster cell in a `numColumns` grid, so cells keep a
 * fixed size instead of flex-filling. This is what makes a partial final row
 * left-align at its natural width rather than stretching to fill (FlatList's
 * default with flex:1 children — egregious at high column counts).
 */
export function gridItemWidth(
  windowWidth: number,
  columns: number,
  opts: { horizontalPadding: number; gap: number },
): number {
  const inner = windowWidth - opts.horizontalPadding * 2 - opts.gap * (columns - 1)
  return Math.floor(inner / columns)
}

export function useResponsive(): Responsive {
  const { width, height } = useWindowDimensions()
  const bp = breakpointFor(Math.min(width, height))
  const isTablet = bp !== 'phone'

  return {
    width,
    height,
    bp,
    isTablet,
    isLandscape: width > height,
    posterColumns: posterColumnsFor(width),
    contentMaxWidth: isTablet ? CONTENT_MAX_WIDTH : null,
    pick: (phone, tablet, large) =>
      bp === 'largeTablet' ? (large ?? tablet) : bp === 'tablet' ? tablet : phone,
  }
}
