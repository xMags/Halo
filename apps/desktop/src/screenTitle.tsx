import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'

/**
 * The command bar names the screen below it, but only the screen knows its own
 * title once data has loaded (a meta's name, a live result count, the query
 * being typed). Rather than have the bar re-run every screen's queries, each
 * screen publishes its heading here and the bar renders whatever is current.
 *
 * The crumb is the mono line beside the title: short, uppercase, metadata.
 */
interface ScreenHeading {
  title: string
  crumb: string
}

interface ScreenTitleContextValue extends ScreenHeading {
  set: (heading: ScreenHeading) => void
}

const ScreenTitleContext = createContext<ScreenTitleContextValue | null>(null)

export function ScreenTitleProvider({ children }: { children: ReactNode }) {
  const [heading, setHeading] = useState<ScreenHeading>({ title: 'Halo', crumb: '' })
  const value = useMemo(() => ({ ...heading, set: setHeading }), [heading])
  return <ScreenTitleContext.Provider value={value}>{children}</ScreenTitleContext.Provider>
}

export function useScreenTitle(): ScreenHeading {
  const ctx = useContext(ScreenTitleContext)
  if (!ctx) throw new Error('useScreenTitle outside ScreenTitleProvider')
  return { title: ctx.title, crumb: ctx.crumb }
}

/**
 * Publishes the current screen's heading. Called unconditionally at the top of
 * a screen with whatever it knows so far — placeholders while loading are
 * fine and are what the bar shows.
 */
export function usePublishScreenTitle(title: string, crumb: string): void {
  const ctx = useContext(ScreenTitleContext)
  if (!ctx) throw new Error('usePublishScreenTitle outside ScreenTitleProvider')
  const { set } = ctx
  useEffect(() => {
    set({ title, crumb })
  }, [set, title, crumb])
}
