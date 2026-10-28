import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import { FluentIcon } from './FluentIcon'

interface Props {
  title: string
  /** Quiet mono note beside the title, such as a count. */
  note?: string
  children: ReactNode
}

interface Edges {
  overflow: boolean
  atStart: boolean
  atEnd: boolean
}

const AT_REST: Edges = { overflow: false, atStart: true, atEnd: true }

/**
 * A titled strip that scrolls sideways, for the title page's glass panels.
 * Unlike Home's Shelf, its arrows appear only when the strip is wider than
 * the panel and dim at either end, and the edge with more to show fades out.
 * A page moves one panel width less one card, so the last card seen stays as
 * the anchor.
 */
export function ScrollRow({ title, note, children }: Props) {
  const strip = useRef<HTMLDivElement>(null)
  const [edges, setEdges] = useState<Edges>(AT_REST)

  const measure = useCallback(() => {
    const el = strip.current
    if (!el) return
    const max = el.scrollWidth - el.clientWidth
    const next = { overflow: max > 1, atStart: el.scrollLeft <= 1, atEnd: el.scrollLeft >= max - 1 }
    setEdges((current) =>
      current.overflow === next.overflow && current.atStart === next.atStart && current.atEnd === next.atEnd
        ? current
        : next,
    )
  }, [])

  useEffect(() => {
    const el = strip.current
    if (!el) return
    measure()
    // Cards arrive as art loads and the panel resizes with the window.
    const observer = new ResizeObserver(measure)
    observer.observe(el)
    for (const child of el.children) observer.observe(child)
    return () => observer.disconnect()
  }, [measure, children])

  const page = (direction: -1 | 1) => {
    const el = strip.current
    if (!el) return
    el.scrollBy({ left: direction * Math.max(el.clientWidth - 160, 160), behavior: 'smooth' })
  }

  const fades = `${edges.overflow && !edges.atStart ? 'srow-more-l' : ''} ${
    edges.overflow && !edges.atEnd ? 'srow-more-r' : ''
  }`

  return (
    <section className="srow">
      <div className="srow-head">
        <h2 className="srow-title">
          {title}
          {note && <small>{note}</small>}
        </h2>
        {edges.overflow && (
          <div className="srow-pager">
            <button type="button" title="Scroll left" disabled={edges.atStart} onClick={() => page(-1)}>
              <FluentIcon glyph="chevronLeft" size={14} />
            </button>
            <button type="button" title="Scroll right" disabled={edges.atEnd} onClick={() => page(1)}>
              <FluentIcon glyph="chevronRight" size={14} />
            </button>
          </div>
        )}
      </div>
      <div className={`srow-strip ${fades}`} ref={strip} onScroll={measure}>
        {children}
      </div>
    </section>
  )
}
