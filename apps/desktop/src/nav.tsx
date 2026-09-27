import type { MetaPreview } from '@halo/core'
import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react'

/**
 * Minimal screen stack with rail sections — a handful of screens doesn't
 * justify a router dependency. Params mirror mobile's route params so flows
 * stay comparable; the section roots are the desktop-only part (mobile uses
 * bottom tabs instead).
 *
 * Source selection is not a screen: it is a sheet that slides over whatever
 * opened it (Detail, a failed download row, the hero) and leaves that screen
 * in place underneath. So it lives beside the stack, not on it — closing it
 * must not pop anything, and playing from it pushes the player onto the screen
 * that opened it, which is where Back then returns.
 */

/** Top-level rail destinations. Selecting one resets the stack to it. */
export type Section = 'home' | 'search' | 'library' | 'downloads' | 'settings'

export interface PlayerParams {
  url: string
  sourceKind?: 'remote' | 'download'
  downloadId?: string
  subtitleLang?: string
  videoId: string
  itemId: string
  type: string
  title: string
  /** Binge-continuation context; absent for movies. */
  metaId?: string
  showName?: string
  episodeLabel?: string
  poster?: string
  addonId?: string
  bingeGroup?: string
  filename?: string
  videoSize?: number
}

/** What the sources sheet needs to resolve and label a video's streams. */
export interface StreamsParams {
  type: string
  videoId: string
  itemId: string
  title: string
  metaId?: string
  showName?: string
  episodeLabel?: string
  poster?: string
  /** Runtime in minutes, when the meta knows it — drives the bitrate tile. */
  runtimeMinutes?: number
}

/** One poster in a catalog grid. `metaLine` replaces the release year under it. */
export interface CatalogItem {
  meta: MetaPreview
  metaLine?: string
}

/**
 * A shelf's "See all": the shelf's title, source label and items as they were
 * when it was pressed, laid out as a grid. A snapshot, as natively, so the
 * grid shows exactly what the shelf held.
 */
export interface CatalogParams {
  title: string
  source?: string
  items: CatalogItem[]
}

export type Screen =
  | { name: 'home' }
  | { name: 'search' }
  | { name: 'library' }
  | { name: 'downloads' }
  | { name: 'settings' }
  | { name: 'detail'; type: string; id: string }
  | ({ name: 'catalog' } & CatalogParams)
  | ({ name: 'player' } & PlayerParams)

interface NavContextValue {
  screen: Screen
  /** The stack's root — drives the rail's active highlight. */
  section: Section
  /** False on a section root, where there is nowhere to go back to. */
  canPop: boolean
  push: (screen: Screen) => void
  pop: () => void
  /** Swaps the current screen without growing the stack (autoplay handoff). */
  replace: (screen: Screen) => void
  /** Jumps to a rail section, clearing any pushed detail screens. */
  setRoot: (section: Section) => void
  reset: () => void
  /** The sources sheet's subject, or null when it is closed. */
  sheet: StreamsParams | null
  openSheet: (params: StreamsParams) => void
  closeSheet: () => void
}

const NavContext = createContext<NavContextValue | null>(null)

export function NavProvider({ children }: { children: ReactNode }) {
  const [stack, setStack] = useState<Screen[]>([{ name: 'home' }])
  const [sheet, setSheet] = useState<StreamsParams | null>(null)

  // Any navigation dismisses the sheet: it describes one video, and leaving
  // that video behind would strand it over an unrelated screen.
  const push = useCallback((screen: Screen) => {
    setSheet(null)
    setStack((s) => [...s, screen])
  }, [])
  const pop = useCallback(() => {
    setSheet(null)
    setStack((s) => (s.length > 1 ? s.slice(0, -1) : s))
  }, [])
  const replace = useCallback((screen: Screen) => setStack((s) => [...s.slice(0, -1), screen]), [])
  const setRoot = useCallback((section: Section) => {
    setSheet(null)
    setStack([{ name: section }])
  }, [])
  const reset = useCallback(() => {
    setSheet(null)
    setStack([{ name: 'home' }])
  }, [])

  const openSheet = useCallback((params: StreamsParams) => setSheet(params), [])
  const closeSheet = useCallback(() => setSheet(null), [])

  const value = useMemo(
    () => ({
      screen: stack[stack.length - 1]!,
      section: stack[0]!.name as Section,
      canPop: stack.length > 1,
      push,
      pop,
      replace,
      setRoot,
      reset,
      sheet,
      openSheet,
      closeSheet,
    }),
    [stack, push, pop, replace, setRoot, reset, sheet, openSheet, closeSheet],
  )

  // Dev-only hook so scripts/cdp.mjs can drive navigation without OS input.
  if (import.meta.env.DEV) {
    ;(window as Window & { __haloNav?: unknown }).__haloNav = value
  }

  return <NavContext.Provider value={value}>{children}</NavContext.Provider>
}

export function useNav(): NavContextValue {
  const ctx = useContext(NavContext)
  if (!ctx) throw new Error('useNav outside NavProvider')
  return ctx
}
