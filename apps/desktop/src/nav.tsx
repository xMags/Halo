import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react'

/**
 * Minimal screen stack — four screens don't justify a router dependency.
 * Params mirror mobile's route params so flows stay comparable.
 */

export interface PlayerParams {
  url: string
  videoId: string
  itemId: string
  type: string
  title: string
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
  | { name: 'detail'; type: string; id: string }
  | ({ name: 'streams' } & StreamsParams)
  | ({ name: 'player' } & PlayerParams)

interface NavContextValue {
  screen: Screen
  push: (screen: Screen) => void
  pop: () => void
  reset: () => void
}

const NavContext = createContext<NavContextValue | null>(null)

export function NavProvider({ children }: { children: ReactNode }) {
  const [stack, setStack] = useState<Screen[]>([{ name: 'home' }])

  const push = useCallback((screen: Screen) => setStack((s) => [...s, screen]), [])
  const pop = useCallback(() => setStack((s) => (s.length > 1 ? s.slice(0, -1) : s)), [])
  const reset = useCallback(() => setStack([{ name: 'home' }]), [])

  const value = useMemo(
    () => ({ screen: stack[stack.length - 1]!, push, pop, reset }),
    [stack, push, pop, reset],
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
