import { useCallback, useLayoutEffect, useRef, useState, type ReactNode } from 'react'
import { FluentIcon } from './FluentIcon'

interface Props {
  title: string
  /** Mono line beside the title: the owning addon, or a count. */
  source?: string
  /** Right-aligned header action, before the chevrons ("See all"). */
  action?: ReactNode
  children: ReactNode
}

/** Which ends of the strip are in view. */
interface Edges {
  start: boolean
  end: boolean
}

/** Sub-pixel slack: a strip scrolled to its end can stop a fraction short. */
const EDGE_SLACK_PX = 1

function measureEdges(strip: HTMLElement): Edges {
  return {
    start: strip.scrollLeft <= EDGE_SLACK_PX,
    end: strip.scrollLeft + strip.clientWidth >= strip.scrollWidth - EDGE_SLACK_PX,
  }
}

/** The mask that fades whichever edges still hide cards. */
function fadeClass({ start, end }: Edges): string {
  if (start && end) return ''
  if (start) return 'shelf-fade-end'
  if (end) return 'shelf-fade-start'
  return 'shelf-fade-both'
}

/**
 * Horizontal shelf: a header row, then a strip that scrolls sideways with a
 * hidden scrollbar. The chevrons are always drawn rather than appearing on
 * demand (the design's header is a fixed lockup, and a control that comes and
 * goes as a catalog resolves makes the row jitter); the one pointing at an end
 * already in view is disabled instead. The edges that still hide cards fade
 * out. The page's vertical wheel is deliberately left alone.
 */
export function Shelf({ title, source, action, children }: Props) {
  const scroller = useRef<HTMLDivElement>(null)
  const [edges, setEdges] = useState<Edges>({ start: true, end: true })

  const updateEdges = useCallback(() => {
    const strip = scroller.current
    if (!strip) return
    const next = measureEdges(strip)
    setEdges((prev) => (prev.start === next.start && prev.end === next.end ? prev : next))
  }, [])

  // The strip's overflow changes with its own width (the window) and with its
  // cards (a catalog resolving), neither of which fires a scroll event.
  useLayoutEffect(() => {
    const strip = scroller.current
    if (!strip) return
    updateEdges()
    const resize = new ResizeObserver(updateEdges)
    resize.observe(strip)
    const cards = new MutationObserver(updateEdges)
    cards.observe(strip, { childList: true })
    return () => {
      resize.disconnect()
      cards.disconnect()
    }
  }, [updateEdges])

  // Pages by the whole cards that fit, landing on a card boundary, so a click
  // never leaves a half card at either edge.
  const page = (dir: -1 | 1) => {
    const strip = scroller.current
    const first = strip?.firstElementChild
    if (!strip || !(first instanceof HTMLElement)) return
    const gap = Number.parseFloat(getComputedStyle(strip).columnGap) || 0
    const step = first.offsetWidth + gap
    if (step <= 0) return
    const perPage = Math.max(1, Math.floor((strip.clientWidth + gap) / step))
    const target = (Math.round(strip.scrollLeft / step) + dir * perPage) * step
    strip.scrollTo({ left: target, behavior: 'smooth' })
  }

  return (
    <section className="shelf">
      <div className="shelf-head">
        <div className="shelf-title ellipsis">{title}</div>
        {source && <div className="shelf-source">{source}</div>}
        <div className="spacer" />
        {action}
        <button
          type="button"
          className="icon-btn icon-btn-28 shelf-chevron"
          style={{ marginLeft: 8 }}
          title="Scroll left"
          aria-label="Scroll left"
          disabled={edges.start}
          onClick={() => page(-1)}
        >
          <FluentIcon glyph="chevronLeft" size={14} />
        </button>
        <button
          type="button"
          className="icon-btn icon-btn-28 shelf-chevron"
          style={{ marginLeft: 6 }}
          title="Scroll right"
          aria-label="Scroll right"
          disabled={edges.end}
          onClick={() => page(1)}
        >
          <FluentIcon glyph="chevronRight" size={14} />
        </button>
      </div>
      <div className={`shelf-scroll ${fadeClass(edges)}`} ref={scroller} onScroll={updateEdges}>
        {children}
      </div>
    </section>
  )
}
