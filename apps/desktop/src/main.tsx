import { createRoot } from 'react-dom/client'
import { App } from './App'
import { applyStoredTheme } from './localPrefs'
import './index.css'

// Before the first render, so no frame paints the wrong palette.
applyStoredTheme()

// No StrictMode on purpose: its dev-mode double-mount would fire duplicated
// mpv side effects (loadfile, observers, watch-state reports) in the player.
createRoot(document.getElementById('root')!).render(<App />)

// Dev-only: expose the mpv channel for scripts/cdp.mjs driving.
if (import.meta.env.DEV) {
  void import('./player/mpv').then((m) => {
    ;(window as Window & { __haloMpv?: unknown }).__haloMpv = m
  })
}
