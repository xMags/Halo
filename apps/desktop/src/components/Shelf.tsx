import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import { Icon } from './Icon'

interface Props {
  title: string
  /** Right-aligned header action ("See all"). */
  action?: ReactNode
  children: ReactNode
}

/**
 * Horizontal shelf: gutter on the left, bleeding off the right edge, hidden
 * scrollbar, hover chevrons paging by most of a viewport. The page's vertical
 * wheel is deliberately left alone — hijacking it to scroll rows sideways
 * makes the whole page feel broken.
 */
export function Shelf({ title, action, children }: Props) {
  const scroller = useRef<HTMLDivElement>(null)
  const [canLeft, setCanLeft] = useState(false)
  const [canRight, setCanRight] = useState(false)

  const recompute = useCallback(() => {
    const el = scroller.current
    if (!el) return
    setCanLeft(el.scrollLeft > 4)
    setCanRight(el.scrollLeft + el.clientWidth < el.scrollWidth - 4)
  }, [])

  // Content width settles after images and queries land; observe rather than
  // guess, and watch the children too (a shelf grows as its catalog resolves).
  useEffect(() => {
    const el = scroller.current
    if (!el) return
    recompute()
    const observer = new ResizeObserver(recompute)
    observer.observe(el)
    for (const child of el.children) observer.observe(child)
    return () => observer.disconnect()
  }, [recompute, children])

  const page = (dir: -1 | 1) => {
    const el = scroller.current
    if (el) el.scrollBy({ left: dir * el.clientWidth * 0.8 })
  }

  return (
    <section className="shelf">
      <div className="shelf-head">
        <div className="section-title ellipsis">{title}</div>
        <div className="spacer" />
        {action}
      </div>
      <div className="shelf-body">
        <div className="shelf-scroll no-bar" ref={scroller} onScroll={recompute}>
          {children}
        </div>
        {canLeft && (
          <button
            type="button"
            className="shelf-chevron shelf-chevron-left"
            aria-label="Scroll left"
            onClick={() => page(-1)}
          >
            <Icon name="chevronLeft" size={22} />
          </button>
        )}
        {canRight && (
          <button
            type="button"
            className="shelf-chevron shelf-chevron-right"
            aria-label="Scroll right"
            onClick={() => page(1)}
          >
            <Icon name="chevronRight" size={22} />
          </button>
        )}
      </div>
    </section>
  )
}
