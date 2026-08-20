import { invoke } from '@tauri-apps/api/core'
import { getCurrentWindow } from '@tauri-apps/api/window'
import { useCallback, useEffect, useRef, useState } from 'react'

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

export interface WindowFullscreenController {
  fullscreen: boolean
  setFullscreen: (next: boolean) => Promise<boolean>
  toggleFullscreen: () => Promise<boolean>
}

/**
 * Owns fullscreen above the keyed player so autoplay replacements cannot reset
 * React state while the native window remains fullscreen. Native state is the
 * source of truth after every transition and resize.
 */
export function useWindowFullscreen(): WindowFullscreenController {
  const [fullscreen, setFullscreenState] = useState(false)
  const fullscreenRef = useRef(false)
  const mountedRef = useRef(true)
  const transitionRef = useRef<Promise<unknown>>(Promise.resolve())

  const store = useCallback((value: boolean) => {
    fullscreenRef.current = value
    if (mountedRef.current) setFullscreenState(value)
    return value
  }, [])

  const readNative = useCallback(async () => {
    const value = await getCurrentWindow().isFullscreen()
    return store(value)
  }, [store])

  const applyFullscreenStyle = useCallback(
    (value: boolean) => invoke('window_set_fullscreen_style', { fullscreen: value }).catch(() => undefined),
    [],
  )

  useEffect(() => {
    mountedRef.current = true
    const win = getCurrentWindow()
    void readNative()
      .then(applyFullscreenStyle)
      .catch(() => undefined)
    const unlisten = win.onResized(() => {
      void readNative().catch(() => undefined)
    })
    return () => {
      mountedRef.current = false
      void unlisten.then((fn) => fn())
    }
  }, [applyFullscreenStyle, readNative])

  const setFullscreen = useCallback(
    (next: boolean): Promise<boolean> => {
      const transition = transitionRef.current.then(async () => {
        // Square the DWM clip before entering fullscreen so no desktop pixels
        // flash through its corners. Restore rounding only after leaving.
        if (next) await applyFullscreenStyle(true)
        try {
          await getCurrentWindow().setFullscreen(next)
        } catch {
          const actual = await readNative().catch(() => fullscreenRef.current)
          await applyFullscreenStyle(actual)
          return actual
        }
        const actual = await readNative().catch(() => store(next))
        await applyFullscreenStyle(actual)
        return actual
      })
      transitionRef.current = transition.then(
        () => undefined,
        () => undefined,
      )
      return transition
    },
    [applyFullscreenStyle, readNative, store],
  )

  const toggleFullscreen = useCallback(
    () => setFullscreen(!fullscreenRef.current),
    [setFullscreen],
  )

  return { fullscreen, setFullscreen, toggleFullscreen }
}
