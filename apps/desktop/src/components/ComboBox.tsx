import {
  useEffect,
  useId,
  useLayoutEffect,
  useRef,
  useState,
  type KeyboardEvent,
} from 'react'
import { createPortal } from 'react-dom'
import { FluentIcon } from './FluentIcon'

export interface ComboBoxOption<T extends string | number> {
  value: T
  label: string
}

/** Item pitch in the open list: 32px items with WinUI's 2px margins. */
const ITEM_PITCH = 36
/** Border plus the list's 4px inset plus the first item's top margin. */
const FIRST_ITEM_TOP = 7
/** The least gap the open list keeps from the window's edges. */
const WINDOW_MARGIN = 8

/** Where the open list sits, in window coordinates. */
interface ListPlacement {
  top: number
  left: number
  minWidth: number
  /** The title page is dark in both themes; the list must match the box. */
  dark: boolean
}

/**
 * WinUI's ComboBox. Closed, it is a control-fill box with the value and a
 * small chevron; open, a list drawn over the box so the chosen item sits where
 * the value was, as WinUI positions its drop-down.
 *
 * The open list is drawn at the document root in window coordinates, not
 * inside the box's own subtree, because the cards the box sits in clip their
 * overflow and would cut it off. Being detached, it closes when anything
 * scrolls or the window resizes, as it would otherwise drift off the box.
 *
 * Keyboard follows WinUI: while closed, Up/Down/Home/End change the value in
 * place and Enter, Space, F4 or Alt+Down open the list; while open, the arrows
 * move the highlight, Enter or Space commits it, and Escape or Tab closes
 * without changing anything.
 */
export function ComboBox<T extends string | number>({
  options,
  value,
  onChange,
  width = 148,
  minWidth,
  ariaLabel,
}: {
  options: ReadonlyArray<ComboBoxOption<T>>
  value: T
  onChange: (value: T) => void
  width?: number | string
  minWidth?: number | string
  ariaLabel?: string
}) {
  const [open, setOpen] = useState(false)
  const [highlight, setHighlight] = useState(0)
  const [placement, setPlacement] = useState<ListPlacement | null>(null)
  const anchor = useRef<HTMLDivElement>(null)
  const box = useRef<HTMLButtonElement>(null)
  const list = useRef<HTMLDivElement>(null)
  const listId = useId()

  const selectedIndex = Math.max(
    0,
    options.findIndex((option) => option.value === value),
  )
  const current = options[selectedIndex]

  const close = (refocus: boolean) => {
    setOpen(false)
    if (refocus) box.current?.focus()
  }

  const openList = () => {
    const rect = box.current?.getBoundingClientRect()
    if (!rect) return
    setHighlight(selectedIndex)
    setPlacement({
      top: rect.top - (FIRST_ITEM_TOP + selectedIndex * ITEM_PITCH),
      left: rect.left,
      minWidth: rect.width,
      dark: Boolean(box.current?.closest('.theme-dark')),
    })
    setOpen(true)
  }

  const commit = (index: number) => {
    const option = options[index]
    if (option && option.value !== value) onChange(option.value)
  }

  // Dismiss on a press anywhere outside this control, in the capture phase so
  // the list is gone before the press lands on whatever is underneath it.
  useEffect(() => {
    if (!open) return
    const onPointerDown = (event: PointerEvent) => {
      const target = event.target
      if (target instanceof Node && (anchor.current?.contains(target) || list.current?.contains(target))) return
      setOpen(false)
    }
    // Scrolling the list itself is fine; any other scroll moves the box away.
    const onScroll = (event: Event) => {
      if (event.target instanceof Node && list.current?.contains(event.target)) return
      setOpen(false)
    }
    const onResize = () => setOpen(false)
    window.addEventListener('pointerdown', onPointerDown, true)
    window.addEventListener('scroll', onScroll, true)
    window.addEventListener('resize', onResize)
    return () => {
      window.removeEventListener('pointerdown', onPointerDown, true)
      window.removeEventListener('scroll', onScroll, true)
      window.removeEventListener('resize', onResize)
    }
  }, [open])

  // Keep the open list inside the window. It first opens with the chosen item
  // over the box, which pushes it off-screen for an item far down a long list
  // or a box near the window's edge; then it slides just far enough back.
  useLayoutEffect(() => {
    if (!open || !list.current) return
    const rect = list.current.getBoundingClientRect()
    let dy = 0
    if (rect.top < WINDOW_MARGIN) dy = WINDOW_MARGIN - rect.top
    else if (rect.bottom > window.innerHeight - WINDOW_MARGIN) {
      dy = window.innerHeight - WINDOW_MARGIN - rect.bottom
    }
    // A wide list from a box near the right edge would run off the window.
    const dx = Math.min(0, window.innerWidth - WINDOW_MARGIN - rect.right)
    if (dy !== 0 || dx !== 0) {
      setPlacement((previous) =>
        previous ? { ...previous, top: previous.top + dy, left: previous.left + dx } : previous,
      )
    }
    const item = list.current.children[selectedIndex]
    if (item instanceof HTMLElement) item.scrollIntoView({ block: 'nearest' })
    // Placement is decided once per opening, not on every highlight move, so
    // this deliberately depends on `open` alone.
  }, [open])

  useEffect(() => {
    if (!open || !list.current) return
    const item = list.current.children[highlight]
    if (item instanceof HTMLElement) item.scrollIntoView({ block: 'nearest' })
  }, [open, highlight])

  const onKeyDown = (event: KeyboardEvent<HTMLButtonElement>) => {
    const last = options.length - 1
    if (!open) {
      if (event.key === 'F4' || (event.altKey && event.key === 'ArrowDown')) {
        event.preventDefault()
        openList()
      } else if (event.key === 'ArrowDown') {
        event.preventDefault()
        commit(Math.min(last, selectedIndex + 1))
      } else if (event.key === 'ArrowUp') {
        event.preventDefault()
        commit(Math.max(0, selectedIndex - 1))
      } else if (event.key === 'Home') {
        event.preventDefault()
        commit(0)
      } else if (event.key === 'End') {
        event.preventDefault()
        commit(last)
      }
      return
    }
    if (event.key === 'Escape') {
      // Innermost layer: a sheet behind this also answers Escape.
      event.preventDefault()
      event.stopPropagation()
      event.nativeEvent.stopImmediatePropagation()
      close(false)
    } else if (event.key === 'Tab') {
      close(false)
    } else if (event.key === 'ArrowDown') {
      event.preventDefault()
      setHighlight((index) => Math.min(last, index + 1))
    } else if (event.key === 'ArrowUp') {
      event.preventDefault()
      setHighlight((index) => Math.max(0, index - 1))
    } else if (event.key === 'Home') {
      event.preventDefault()
      setHighlight(0)
    } else if (event.key === 'End') {
      event.preventDefault()
      setHighlight(last)
    } else if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault()
      commit(highlight)
      close(false)
    }
  }

  return (
    <div className="menu-anchor" ref={anchor}>
      <button
        ref={box}
        type="button"
        role="combobox"
        className="combo-box"
        style={{ width, minWidth }}
        aria-label={ariaLabel}
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-controls={open ? listId : undefined}
        aria-activedescendant={open ? `${listId}-${highlight}` : undefined}
        onClick={() => (open ? close(false) : openList())}
        onKeyDown={onKeyDown}
      >
        <span className="combo-box-label ellipsis">{current?.label ?? ''}</span>
        <span className="combo-box-chevron">
          <FluentIcon glyph="chevronDownSmall" size={9} />
        </span>
      </button>
      {open &&
        placement &&
        createPortal(
          <div
            ref={list}
            id={listId}
            role="listbox"
            className={`combo-popup ${placement.dark ? 'theme-dark' : ''}`}
            style={{
              top: placement.top,
              left: placement.left,
              minWidth: placement.minWidth,
              maxHeight: 360,
              overflowY: 'auto',
            }}
          >
            {options.map((option, index) => (
              <button
                key={String(option.value)}
                id={`${listId}-${index}`}
                type="button"
                role="option"
                tabIndex={-1}
                aria-selected={index === selectedIndex}
                className={`combo-item ellipsis ${index === selectedIndex ? 'combo-item-selected' : ''} ${
                  index === highlight && index !== selectedIndex ? 'combo-item-active' : ''
                }`}
                onPointerEnter={() => setHighlight(index)}
                onClick={() => {
                  commit(index)
                  close(true)
                }}
              >
                {option.label}
              </button>
            ))}
          </div>,
          document.body,
        )}
    </div>
  )
}
