import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import { Icon } from './Icon'

interface Props {
  title: string
  /** Optional right-aligned header action ("See all"). */
  action?: ReactNode
  children: ReactNode
}

/**
 * Horizontal poster shelf, Netflix-style: hidden scrollbar, hover chevrons
 * paging by ~a viewport. The page's vertical wheel is deliberately left alone —
 * hijacking it to scroll rows sideways makes the whole page feel broken.
 */
export function PosterRow({ title, action, children }: Props) {
  const scroller = useRef<HTMLDivElement>(null)
  const [canLeft, setCanLeft] = useState(false)
  const [canRight, setCanRight] = useState(false)

  const recompute = useCallback(() => {
    const el = scroller.current
    if (!el) return
    setCanLeft(el.scrollLeft > 4)
    setCanRight(el.scrollLeft + el.clientWidth < el.scrollWidth - 4)
  }, [])

  // Content width settles after images/queries load; observe rather than guess.
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
    if (!el) return
    el.scrollBy({ left: dir * el.clientWidth * 0.8, behavior: 'smooth' })
  }

  return (
    <section className="poster-row">
      <div className="poster-row-head">
        <div className="t-heading">{title}</div>
        {action}
      </div>
      <div className="poster-row-body">
        <div className="row-scroll" ref={scroller} onScroll={recompute}>
          {children}
        </div>
        {canLeft && (
          <button type="button" className="row-chevron row-chevron-left" onClick={() => page(-1)}>
            <Icon name="chevronLeft" size={22} />
          </button>
        )}
        {canRight && (
          <button type="button" className="row-chevron row-chevron-right" onClick={() => page(1)}>
            <Icon name="chevronRight" size={22} />
          </button>
        )}
      </div>
    </section>
  )
}
