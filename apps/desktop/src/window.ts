import { invoke } from '@tauri-apps/api/core'
import { getCurrentWindow } from '@tauri-apps/api/window'
import { useCallback, useEffect, useRef, useState } from 'react'
import { flushSync } from 'react-dom'

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

/**
 * Tells the shell the page has rendered, which is when the window, created
 * hidden, is shown: before then it would be a black frame with no title bar.
 */
export function revealWindow(): void {
  void invoke('app_ready').catch(() => undefined)
}

export function closeWindow(): void {
  void getCurrentWindow().close()
}

/**
 * The Windows window menu (Restore, Move, Size, Minimize, Maximize, Close) at
 * the pointer: what a right-click on any Windows title bar opens.
 */
export function showWindowMenu(): void {
  void invoke('window_show_system_menu').catch(() => undefined)
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

/** Whether the webview covers the whole screen, as it does only in fullscreen. */
function coversScreen(): boolean {
  return window.innerWidth >= window.screen.width && window.innerHeight >= window.screen.height
}

/** The longest a switch waits for the window to show its new size. */
const SETTLE_TIMEOUT_MS = 1000

/**
 * Resolves once the webview no longer covers the screen. Leaving fullscreen
 * resolves on the API call before tao has restored the window, and anything
 * done to the window in between is undone by that restore.
 */
function windowedSettled(): Promise<void> {
  if (!coversScreen()) return Promise.resolve()
  return new Promise((resolve) => {
    const done = () => {
      window.removeEventListener('resize', check)
      clearTimeout(timer)
      resolve()
    }
    const check = () => {
      if (!coversScreen()) done()
    }
    const timer = setTimeout(done, SETTLE_TIMEOUT_MS)
    window.addEventListener('resize', check)
  })
}

/** Windows' min/max/restore animations for the main window, off for the length of a fullscreen switch. */
function setTransitions(enabled: boolean): Promise<unknown> {
  return invoke('window_set_transitions', { enabled }).catch(() => undefined)
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
  /** Fullscreen was entered from a maximized window, which leaving it re-maximizes. */
  const restoreMaximizedRef = useRef(false)
  /** The state a switch this hook started is heading for; null when none is under way. */
  const pendingRef = useRef<boolean | null>(null)

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
    // Asking the window whether it is fullscreen answers a few frames after it
    // has resized, and the player drew those frames in the old layout: the
    // picture hopped by the title bar's height. While a switch started here is
    // under way, the webview's own resize (which lands before its first frame
    // at the new size) settles the state as soon as the size matches the
    // target. readNative still has the last word once the switch completes.
    const onWebviewResize = () => {
      const target = pendingRef.current
      if (target === null || fullscreenRef.current === target) return
      if (coversScreen() !== target) return
      flushSync(() => store(target))
    }
    window.addEventListener('resize', onWebviewResize)
    return () => {
      mountedRef.current = false
      void unlisten.then((fn) => fn())
      window.removeEventListener('resize', onWebviewResize)
    }
  }, [applyFullscreenStyle, readNative, store])

  /** One switch, run inside setFullscreen's queue with transitions off. */
  const switchTo = useCallback(
    async (next: boolean): Promise<boolean> => {
      const win = getCurrentWindow()
      // Square the DWM clip before entering fullscreen so no desktop pixels
      // flash through its corners. Restore rounding only after leaving.
      if (next) await applyFullscreenStyle(true)
      // A maximized window keeps its maximized flag through tao's fullscreen
      // switch, and Windows holds a maximized window to the work area, so
      // fullscreen would stop short of the taskbar. Leave maximized first and
      // put it back on the way out, both in place so the window never shows
      // at its normal size in between (window_placement.rs).
      if (next && !fullscreenRef.current) {
        restoreMaximizedRef.current = await invoke<boolean>('window_unmaximize_in_place').catch(() => false)
      }
      pendingRef.current = next
      try {
        await win.setFullscreen(next)
        if (!next && restoreMaximizedRef.current) {
          restoreMaximizedRef.current = false
          await windowedSettled()
          await invoke('window_remaximize').catch(() => undefined)
        }
      } catch {
        const actual = await readNative().catch(() => fullscreenRef.current)
        await applyFullscreenStyle(actual)
        return actual
      } finally {
        pendingRef.current = null
      }
      const actual = await readNative().catch(() => store(next))
      await applyFullscreenStyle(actual)
      return actual
    },
    [applyFullscreenStyle, readNative, store],
  )

  const setFullscreen = useCallback(
    (next: boolean): Promise<boolean> => {
      // The restore and re-maximize around a switch from a maximized window
      // would each play Windows' shrinking, cross-fading animation; the
      // fullscreen switch itself is a single jump either way.
      const transition = transitionRef.current.then(async () => {
        await setTransitions(false)
        try {
          return await switchTo(next)
        } finally {
          await setTransitions(true)
        }
      })
      transitionRef.current = transition.then(
        () => undefined,
        () => undefined,
      )
      return transition
    },
    [switchTo],
  )

  const toggleFullscreen = useCallback(
    () => setFullscreen(!fullscreenRef.current),
    [setFullscreen],
  )

  return { fullscreen, setFullscreen, toggleFullscreen }
}
