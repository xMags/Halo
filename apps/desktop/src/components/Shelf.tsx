import { useRef, type ReactNode } from 'react'
import { Icon } from './Icon'

/** How far a chevron pages the strip, per the design. */
const PAGE_PX = 560

interface Props {
  title: string
  /** Mono line beside the title: the owning addon, or a count. */
  source?: string
  /** Right-aligned header action, before the chevrons ("See all"). */
  action?: ReactNode
  children: ReactNode
}

/**
 * Horizontal shelf: a header row, then a strip that scrolls sideways with a
 * hidden scrollbar. The chevrons are always drawn rather than appearing on
 * demand — the design's header is a fixed lockup, and a control that comes and
 * goes as a catalog resolves makes the row jitter. The page's vertical wheel
 * is deliberately left alone.
 */
export function Shelf({ title, source, action, children }: Props) {
  const scroller = useRef<HTMLDivElement>(null)

  const page = (dir: -1 | 1) => {
    scroller.current?.scrollBy({ left: dir * PAGE_PX, behavior: 'smooth' })
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
          className="icon-btn icon-btn-28"
          style={{ marginLeft: 8 }}
          title="Scroll left"
          onClick={() => page(-1)}
        >
          <Icon name="chevronLeft" size={14} />
        </button>
        <button
          type="button"
          className="icon-btn icon-btn-28"
          style={{ marginLeft: 6 }}
          title="Scroll right"
          onClick={() => page(1)}
        >
          <Icon name="chevronRight" size={14} />
        </button>
      </div>
      <div className="shelf-scroll" ref={scroller}>
        {children}
      </div>
    </section>
  )
}
