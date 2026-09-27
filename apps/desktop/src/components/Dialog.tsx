import { useCallback, useEffect, useId, useRef, useState, useSyncExternalStore } from 'react'

/**
 * The confirmation dialog, reproducing the native client's WinUI
 * ContentDialog: a fixed 440px box on the flyout surface, a 20px title, the
 * body at 14px, and a command band of equal-width buttons under a hairline.
 *
 * Raised with `showDialog`, which resolves once the dialog is dismissed, the
 * way the native pages await `ShowAsync`. `window.confirm` is not an option:
 * the WebView2 host answers it by itself without drawing anything.
 */

export interface DialogOptions {
  title: string
  body: string
  /** The action being confirmed; leave out for a message with only a close button. */
  primary?: string
  close: string
  /** Drawn in the accent and focused first. Without one, the first button takes focus. */
  defaultButton?: 'primary' | 'close'
}

interface PendingDialog {
  id: number
  options: DialogOptions
  resolve: (confirmed: boolean) => void
}

/** How long the close animation runs before the dialog leaves the tree. */
const CLOSE_MS = 83

let queue: PendingDialog[] = []
let nextId = 1
const listeners = new Set<() => void>()

function publish(next: PendingDialog[]): void {
  queue = next
  for (const listener of listeners) listener()
}

/**
 * Shows a dialog and resolves true when its primary button was chosen, false
 * for the close button or Escape. Like the native dialog it is not light
 * dismissed: a press on the smoke does nothing. Dialogs raised while one is
 * open wait their turn.
 */
export function showDialog(options: DialogOptions): Promise<boolean> {
  return new Promise((resolve) => {
    publish([...queue, { id: nextId++, options, resolve }])
  })
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

function current(): PendingDialog | undefined {
  return queue[0]
}

/** Mounted once at the root; draws whichever dialog is at the front of the queue. */
export function DialogHost() {
  const active = useSyncExternalStore(subscribe, current, current)
  if (!active) return null
  return <DialogView key={active.id} dialog={active} />
}

function DialogView({ dialog }: { dialog: PendingDialog }) {
  const { options } = dialog
  const [closing, setClosing] = useState(false)
  const boxRef = useRef<HTMLDivElement>(null)
  const primaryRef = useRef<HTMLButtonElement>(null)
  const closeRef = useRef<HTMLButtonElement>(null)
  const settled = useRef(false)
  const titleId = useId()
  const bodyId = useId()

  const finish = useCallback(
    (confirmed: boolean) => {
      if (settled.current) return
      settled.current = true
      setClosing(true)
      window.setTimeout(() => {
        publish(queue.filter((entry) => entry.id !== dialog.id))
        dialog.resolve(confirmed)
      }, CLOSE_MS)
    },
    [dialog],
  )

  useEffect(() => {
    const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null
    const first = options.defaultButton === 'close' || !options.primary ? closeRef : primaryRef
    first.current?.focus()
    return () => previous?.focus()
  }, [options.defaultButton, options.primary])

  // Modal: while it is open no key reaches the page underneath, Escape
  // dismisses, and Tab cycles between the dialog's own buttons.
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      const box = boxRef.current
      if (!box) return
      event.stopImmediatePropagation()
      if (event.key === 'Escape') {
        event.preventDefault()
        finish(false)
        return
      }
      const buttons = [...box.querySelectorAll<HTMLButtonElement>('button')]
      if (event.key === 'Tab' && buttons.length > 0) {
        event.preventDefault()
        const index = buttons.indexOf(document.activeElement as HTMLButtonElement)
        const step = event.shiftKey ? -1 : 1
        buttons[(index + step + buttons.length) % buttons.length]?.focus()
        return
      }
      if (!(event.target instanceof Node) || !box.contains(event.target)) event.preventDefault()
    }
    window.addEventListener('keydown', onKey, true)
    return () => window.removeEventListener('keydown', onKey, true)
  }, [finish])

  const primaryIsDefault = options.primary !== undefined && options.defaultButton === 'primary'
  const closeIsDefault = options.defaultButton === 'close'

  return (
    <div className={`dlg-layer ${closing ? 'dlg-closing' : ''}`}>
      <div className="dlg-smoke" />
      <div
        ref={boxRef}
        className="dlg"
        role="alertdialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={bodyId}
      >
        <div className="dlg-content">
          <h2 id={titleId} className="dlg-title">
            {options.title}
          </h2>
          <p id={bodyId} className="dlg-body">
            {options.body}
          </p>
        </div>
        <div className={`dlg-commands ${options.primary ? '' : 'dlg-commands-single'}`}>
          {options.primary && (
            <button
              ref={primaryRef}
              type="button"
              className={primaryIsDefault ? 'btn-accent' : 'btn'}
              onClick={() => finish(true)}
            >
              {options.primary}
            </button>
          )}
          <button
            ref={closeRef}
            type="button"
            className={closeIsDefault ? 'btn-accent' : 'btn'}
            onClick={() => finish(false)}
          >
            {options.close}
          </button>
        </div>
      </div>
    </div>
  )
}
