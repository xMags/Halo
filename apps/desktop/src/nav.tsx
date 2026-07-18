import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react'

/**
 * Minimal screen stack with sidebar sections — a handful of screens doesn't
 * justify a router dependency. Params mirror mobile's route params so flows
 * stay comparable; the section roots are the desktop-only part (mobile uses
 * bottom tabs instead).
 */

/** Top-level sidebar destinations. Selecting one resets the stack to it. */
export type Section = 'home' | 'search' | 'library' | 'settings'

export interface PlayerParams {
  url: string
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

export interface StreamsParams {
  type: string
  videoId: string
  itemId: string
  title: string
  metaId?: string
  showName?: string
  episodeLabel?: string
  poster?: string
}

export type Screen =
  | { name: 'home' }
  | { name: 'search' }
  | { name: 'library' }
  | { name: 'settings' }
  | { name: 'detail'; type: string; id: string }
  | ({ name: 'streams' } & StreamsParams)
  | ({ name: 'player' } & PlayerParams)

interface NavContextValue {
  screen: Screen
  /** The stack's root — drives the sidebar's active highlight. */
  section: Section
  push: (screen: Screen) => void
  pop: () => void
  /** Swaps the current screen without growing the stack (autoplay handoff). */
  replace: (screen: Screen) => void
  /** Jumps to a sidebar section, clearing any pushed detail/streams screens. */
  setRoot: (section: Section) => void
  reset: () => void
}

const NavContext = createContext<NavContextValue | null>(null)

export function NavProvider({ children }: { children: ReactNode }) {
  const [stack, setStack] = useState<Screen[]>([{ name: 'home' }])

  const push = useCallback((screen: Screen) => setStack((s) => [...s, screen]), [])
  const pop = useCallback(() => setStack((s) => (s.length > 1 ? s.slice(0, -1) : s)), [])
  const replace = useCallback(
    (screen: Screen) => setStack((s) => [...s.slice(0, -1), screen]),
    [],
  )
  const setRoot = useCallback((section: Section) => setStack([{ name: section }]), [])
  const reset = useCallback(() => setStack([{ name: 'home' }]), [])

  const value = useMemo(
    () => ({
      screen: stack[stack.length - 1]!,
      section: stack[0]!.name as Section,
      push,
      pop,
      replace,
      setRoot,
      reset,
    }),
    [stack, push, pop, replace, setRoot, reset],
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
