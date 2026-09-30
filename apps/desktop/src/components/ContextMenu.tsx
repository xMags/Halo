import { useEffect, useLayoutEffect, useRef, useState, useSyncExternalStore, type MouseEvent } from 'react'
import { FluentIcon, type FluentGlyph } from './FluentIcon'
import { placeContextMenu, type Point } from './menuPlacement'

/**
 * Right-click menus, drawn as the WinUI MenuFlyout the other menus reproduce
 * and placed at the pointer the way Windows places a context menu.
 *
 * The webview's own menu (Save image as, Copy image link, Inspect) means
 * nothing in an app, so it is suppressed everywhere except text fields, where
 * it carries Cut, Copy and Paste. Surfaces with something to act on open this
 * menu instead through `openContextMenu` from their `onContextMenu`.
 */

export type ContextMenuEntry =
  | { kind: 'item'; label: string; glyph: FluentGlyph; onSelect: () => void }
  | { kind: 'separator' }

interface OpenMenu {
  id: number
  anchor: Point
  entries: ContextMenuEntry[]
  /** Opened with the menu key or Shift+F10: focus starts on the first item. */
  fromKeyboard: boolean
}

let active: OpenMenu | null = null
let nextId = 1
const listeners = new Set<() => void>()

function publish(next: OpenMenu | null): void {
  if (next === active) return
  active = next
  for (const listener of listeners) listener()
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

function current(): OpenMenu | null {
  return active
}

export function closeContextMenu(): void {
  publish(null)
}

/**
 * Opens the menu for the element whose `onContextMenu` received `event`.
 * Separators only ever sit between items, so a builder can emit them
 * unconditionally around entries it sometimes leaves out.
 */
export function openContextMenu(event: MouseEvent<HTMLElement>, entries: ContextMenuEntry[]): void {
  // Also what tells the document-level listener below that this press has a
  // menu of its own.
  event.preventDefault()
  const tidy = tidySeparators(entries)
  if (tidy.length === 0) {
    closeContextMenu()
    return
  }
  // A right click reports button 2. The menu key and Shift+F10 report no
  // button, and a position Chromium picks inside the element, so the menu
  // hangs from the element's corner instead, as a keyboard-invoked WinUI
  // context flyout does.
  const fromKeyboard = event.button !== 2
  const rect = event.currentTarget.getBoundingClientRect()
  const anchor = fromKeyboard ? { x: rect.left, y: rect.bottom } : { x: event.clientX, y: event.clientY }
  publish({ id: nextId++, anchor, entries: tidy, fromKeyboard })
}

function tidySeparators(entries: ContextMenuEntry[]): ContextMenuEntry[] {
  const result: ContextMenuEntry[] = []
  for (const entry of entries) {
    if (entry.kind === 'separator' && (result.length === 0 || result[result.length - 1]!.kind === 'separator')) {
      continue
    }
    result.push(entry)
  }
  if (result[result.length - 1]?.kind === 'separator') result.pop()
  return result
}

const NAVIGATION_KEYS = new Set(['ArrowDown', 'ArrowUp', 'Home', 'End'])

/** Arrow keys wrap around the ends, as a WinUI menu's do. `index` is -1 before any item has focus. */
function nextFocusIndex(key: string, index: number, last: number): number {
  switch (key) {
    case 'Home':
      return 0
    case 'End':
      return last
    case 'ArrowDown':
      return index >= last ? 0 : index + 1
    default:
      return index <= 0 ? last : index - 1
  }
}

const TEXT_INPUT_TYPES = new Set(['text', 'search', 'url', 'email', 'password', 'tel', 'number'])

/** Where the webview's own edit menu (Cut, Copy, Paste) is worth keeping. */
function isTextField(target: EventTarget | null): boolean {
  if (target instanceof HTMLTextAreaElement) return true
  if (target instanceof HTMLInputElement) return TEXT_INPUT_TYPES.has(target.type)
  return target instanceof HTMLElement && target.isContentEditable
}

/** Mounted once at the root: suppresses the webview's menu and draws ours. */
export function ContextMenuHost() {
  const menu = useSyncExternalStore(subscribe, current, current)

  useEffect(() => {
    // Bubble phase on the document, so it runs after React's root listener:
    // a surface that opened a menu has already called preventDefault.
    const onContextMenu = (event: globalThis.MouseEvent) => {
      if (event.defaultPrevented) return
      closeContextMenu()
      if (isTextField(event.target)) return
      event.preventDefault()
    }
    document.addEventListener('contextmenu', onContextMenu)
    return () => document.removeEventListener('contextmenu', onContextMenu)
  }, [])

  if (!menu) return null
  return <ContextMenuView key={menu.id} menu={menu} />
}

function ContextMenuView({ menu }: { menu: OpenMenu }) {
  const ref = useRef<HTMLDivElement>(null)
  const [position, setPosition] = useState<Point | null>(null)

  // Measured before the first paint, so the menu never shows at a spot it
  // then jumps away from.
  useLayoutEffect(() => {
    const element = ref.current
    if (!element) return
    setPosition(
      placeContextMenu(
        menu.anchor,
        { width: element.offsetWidth, height: element.offsetHeight },
        { width: window.innerWidth, height: window.innerHeight },
      ),
    )
  }, [menu])

  // Focus waits for placement: until then the menu is `visibility: hidden`,
  // and a hidden element cannot take focus.
  const placed = position !== null
  useEffect(() => {
    if (!placed) return
    const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null
    const element = ref.current
    if (menu.fromKeyboard) element?.querySelector<HTMLButtonElement>('.menu-item')?.focus()
    else element?.focus({ preventScroll: true })
    return () => {
      // Only when the menu took focus with it: an item that navigated or
      // opened something has already put focus where it belongs.
      const stranded = document.activeElement === null || document.activeElement === document.body
      if (stranded && previous?.isConnected) previous.focus({ preventScroll: true })
    }
  }, [placed, menu])

  // Light dismiss: a press anywhere else, scrolling, resizing, or the window
  // losing focus closes it, as a Windows context menu closes.
  useEffect(() => {
    const onPointerDown = (event: PointerEvent) => {
      if (event.target instanceof Node && ref.current?.contains(event.target)) return
      closeContextMenu()
    }
    const onKey = (event: KeyboardEvent) => {
      // The innermost layer: nothing underneath answers keys while it is open.
      event.stopImmediatePropagation()
      if (event.key === 'Escape' || event.key === 'Tab') {
        event.preventDefault()
        closeContextMenu()
        return
      }
      if (!NAVIGATION_KEYS.has(event.key)) return
      event.preventDefault()
      const items = [...(ref.current?.querySelectorAll<HTMLButtonElement>('.menu-item') ?? [])]
      if (items.length === 0) return
      const index = items.indexOf(document.activeElement as HTMLButtonElement)
      items[nextFocusIndex(event.key, index, items.length - 1)]?.focus()
    }
    const dismiss = () => closeContextMenu()
    window.addEventListener('pointerdown', onPointerDown, true)
    window.addEventListener('keydown', onKey, true)
    window.addEventListener('wheel', dismiss, { capture: true, passive: true })
    window.addEventListener('resize', dismiss)
    window.addEventListener('blur', dismiss)
    document.addEventListener('scroll', dismiss, true)
    return () => {
      window.removeEventListener('pointerdown', onPointerDown, true)
      window.removeEventListener('keydown', onKey, true)
      window.removeEventListener('wheel', dismiss, true)
      window.removeEventListener('resize', dismiss)
      window.removeEventListener('blur', dismiss)
      document.removeEventListener('scroll', dismiss, true)
    }
  }, [])

  return (
    <div
      ref={ref}
      className="menu context-menu"
      role="menu"
      tabIndex={-1}
      style={position ? { left: position.x, top: position.y } : { left: 0, top: 0, visibility: 'hidden' }}
      onContextMenu={(event) => event.preventDefault()}
    >
      {menu.entries.map((entry, index) =>
        entry.kind === 'separator' ? (
          <div key={`separator-${index}`} className="menu-separator" role="separator" />
        ) : (
          <button
            key={`${index}-${entry.label}`}
            type="button"
            className="menu-item"
            role="menuitem"
            onClick={() => {
              closeContextMenu()
              entry.onSelect()
            }}
          >
            <FluentIcon glyph={entry.glyph} />
            <span className="ellipsis">{entry.label}</span>
          </button>
        ),
      )}
    </div>
  )
}
