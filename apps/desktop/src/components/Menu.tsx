import { useEffect, type ReactNode } from 'react'
import { Icon } from './Icon'

/**
 * The flyout the library sort, the downloads overflow and the sheet's sort
 * button all open. Closes on Escape and on any pointer press outside its
 * anchor — the anchor is identified by `data-menu` rather than by a ref so a
 * press on the *trigger* is the trigger's own toggle, not a dismiss followed
 * by a re-open.
 *
 * `pointerdown` in the capture phase, so the menu is gone before the press
 * lands on whatever is underneath it.
 */
export function MenuAnchor({ children }: { children: ReactNode }) {
  return (
    <div className="menu-anchor" data-menu>
      {children}
    </div>
  )
}

export function Menu({
  open,
  onClose,
  minWidth = 196,
  children,
}: {
  open: boolean
  onClose: () => void
  minWidth?: number
  children: ReactNode
}) {
  useEffect(() => {
    if (!open) return
    const onPointerDown = (event: PointerEvent) => {
      const target = event.target
      if (target instanceof Element && target.closest('[data-menu]')) return
      onClose()
    }
    const onKey = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return
      // The sheet also answers Escape; a menu is the innermost layer, so it
      // consumes the key. `stopImmediatePropagation` and not just
      // `stopPropagation`: the sheet's listener is on `window` too, and a key
      // event dispatched *at* window would otherwise reach both.
      event.stopImmediatePropagation()
      event.stopPropagation()
      onClose()
    }
    window.addEventListener('pointerdown', onPointerDown, true)
    window.addEventListener('keydown', onKey, true)
    return () => {
      window.removeEventListener('pointerdown', onPointerDown, true)
      window.removeEventListener('keydown', onKey, true)
    }
  }, [open, onClose])

  if (!open) return null
  return (
    <div className="menu" style={{ minWidth }} role="menu">
      {children}
    </div>
  )
}

export function MenuItem({
  label,
  checked,
  onClick,
}: {
  label: string
  /** Renders the accent tick that marks the active choice in a sort menu. */
  checked?: boolean
  onClick: () => void
}) {
  return (
    <button type="button" className="menu-item" role="menuitem" onClick={onClick}>
      {checked !== undefined && (
        <span className="menu-check">{checked && <Icon name="check" size={14} />}</span>
      )}
      <span>{label}</span>
    </button>
  )
}
