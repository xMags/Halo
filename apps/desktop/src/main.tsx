import { createRoot } from 'react-dom/client'
import { App } from './App'
import './index.css'

// No StrictMode on purpose: its dev-mode double-mount would fire duplicated
// mpv side effects (loadfile, observers, watch-state reports) in the player.
createRoot(document.getElementById('root')!).render(<App />)

// Dev-only: expose the mpv channel for scripts/cdp.mjs driving.
if (import.meta.env.DEV) {
  void import('./mpv').then((m) => {
    ;(window as Window & { __haloMpv?: unknown }).__haloMpv = m
  })
}
