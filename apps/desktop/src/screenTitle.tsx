import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { createPortal } from 'react-dom'

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
  /** The command bar's controls slot, once it has mounted. */
  slot: HTMLElement | null
  setSlot: (element: HTMLElement | null) => void
}

const ScreenTitleContext = createContext<ScreenTitleContextValue | null>(null)

export function ScreenTitleProvider({ children }: { children: ReactNode }) {
  const [heading, setHeading] = useState<ScreenHeading>({ title: 'Halo', crumb: '' })
  const [slot, setSlot] = useState<HTMLElement | null>(null)
  const value = useMemo(
    () => ({ ...heading, set: setHeading, slot, setSlot }),
    [heading, slot],
  )
  return <ScreenTitleContext.Provider value={value}>{children}</ScreenTitleContext.Provider>
}

function useScreenTitleContext(): ScreenTitleContextValue {
  const ctx = useContext(ScreenTitleContext)
  if (!ctx) throw new Error('screen title hooks used outside ScreenTitleProvider')
  return ctx
}

export function useScreenTitle(): ScreenHeading {
  const ctx = useScreenTitleContext()
  return { title: ctx.title, crumb: ctx.crumb }
}

/**
 * Publishes the current screen's heading. Called unconditionally at the top of
 * a screen with whatever it knows so far — placeholders while loading are
 * fine and are what the bar shows.
 */
export function usePublishScreenTitle(title: string, crumb: string): void {
  const { set } = useScreenTitleContext()
  useEffect(() => {
    set({ title, crumb })
  }, [set, title, crumb])
}

/** The command bar hands its controls slot to the tree through this ref. */
export function useCommandBarSlotRef(): (element: HTMLElement | null) => void {
  return useScreenTitleContext().setSlot
}

/**
 * Renders a screen's own controls inside the command bar.
 *
 * A portal rather than a published node: an element handed through state would
 * be a new object on every render, so storing it would re-render and re-publish
 * without end. Portalling keeps the control in its screen's tree — its state
 * and handlers are ordinary local ones — while painting it in the bar.
 */
export function CommandBarActions({ children }: { children: ReactNode }) {
  const { slot } = useScreenTitleContext()
  return slot ? createPortal(children, slot) : null
}
