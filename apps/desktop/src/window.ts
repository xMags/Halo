import { getCurrentWindow } from '@tauri-apps/api/window'
import { useEffect, useState } from 'react'

/**
 * Window chrome is app-drawn (`decorations: false` in tauri.conf.json), so the
 * title bar's buttons have to do what the OS caption normally would. Every
 * call is fire-and-forget: a rejected window command must never take the UI
 * down with it.
 */

export function minimizeWindow(): void {
  void getCurrentWindow().minimize()
}

export function toggleMaximizeWindow(): void {
  void getCurrentWindow().toggleMaximize()
}

export function closeWindow(): void {
  void getCurrentWindow().close()
}

/**
 * Tracks the maximised state so the title bar can show restore vs. maximise.
 * The design draws one square glyph for both; distinguishing them is the
 * Windows convention and costs nothing.
 *
 * Resize is the only event that changes it — `toggleMaximize` always produces
 * one, including when the user drags the window off a snapped edge.
 */
export function useIsMaximized(): boolean {
  const [maximized, setMaximized] = useState(false)

  useEffect(() => {
    const win = getCurrentWindow()
    let disposed = false
    const sync = () => {
      void win.isMaximized().then((value) => {
        if (!disposed) setMaximized(value)
      })
    }
    sync()
    const unlisten = win.onResized(sync)
    return () => {
      disposed = true
      void unlisten.then((fn) => fn())
    }
  }, [])

  return maximized
}
