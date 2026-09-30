import { useSyncExternalStore } from 'react'
import { MAX_LINE_MBPS } from './downloadsLogic'
import type { ThemeChoice } from './theme'

/**
 * Device-local preferences — the ones that describe *this machine* rather than
 * the account, so they deliberately do not go through the synced settings
 * blob:
 *
 *   theme                 which palette this install paints in
 *   hardwareDecoding      mpv `hwdec`; depends on the GPU in this box
 *   resumePlayback        whether a stream opens at its saved position
 *   subtitleTrackStyling  mpv `sub-ass-override`; a rendering choice libass
 *                         only makes on this client
 *   measuredLineMbps      the fastest sustained download this machine has
 *                         seen, in megabits; the sources sheet compares a
 *                         source's bitrate against it (0 = never measured)
 *   discordPresence       whether this machine's Discord shows what is
 *                         playing; on by default, as in the native app
 *
 * Everything account-shaped (languages, subtitle size/font/outline/shadow,
 * autoplay) stays in `settings.ts`, which syncs last-write-wins.
 *
 * One store with an explicit subscription rather than four `useState`s: the
 * settings pane and the player's rail both read and write these, and they must
 * never disagree about what is selected.
 */

const KEY = 'halo.localPrefs.v1'

export interface LocalPrefs {
  theme: ThemeChoice
  hardwareDecoding: boolean
  resumePlayback: boolean
  subtitleTrackStyling: boolean
  measuredLineMbps: number
  discordPresence: boolean
}

const DEFAULTS: LocalPrefs = {
  theme: 'dark',
  hardwareDecoding: true,
  resumePlayback: true,
  subtitleTrackStyling: true,
  measuredLineMbps: 0,
  discordPresence: true,
}

function isThemeChoice(value: unknown): value is ThemeChoice {
  return value === 'light' || value === 'dark' || value === 'system'
}

function read(): LocalPrefs {
  try {
    const raw = localStorage.getItem(KEY)
    if (!raw) return DEFAULTS
    const parsed: unknown = JSON.parse(raw)
    if (!parsed || typeof parsed !== 'object') return DEFAULTS
    const row = parsed as Partial<Record<keyof LocalPrefs, unknown>>
    return {
      theme: isThemeChoice(row.theme) ? row.theme : DEFAULTS.theme,
      hardwareDecoding:
        typeof row.hardwareDecoding === 'boolean' ? row.hardwareDecoding : DEFAULTS.hardwareDecoding,
      resumePlayback:
        typeof row.resumePlayback === 'boolean' ? row.resumePlayback : DEFAULTS.resumePlayback,
      subtitleTrackStyling:
        typeof row.subtitleTrackStyling === 'boolean'
          ? row.subtitleTrackStyling
          : DEFAULTS.subtitleTrackStyling,
      measuredLineMbps:
        typeof row.measuredLineMbps === 'number' &&
        Number.isFinite(row.measuredLineMbps) &&
        row.measuredLineMbps > 0
          ? Math.min(row.measuredLineMbps, MAX_LINE_MBPS)
          : DEFAULTS.measuredLineMbps,
      discordPresence:
        typeof row.discordPresence === 'boolean' ? row.discordPresence : DEFAULTS.discordPresence,
    }
  } catch {
    // A blocked or corrupt store must not stop the app booting.
    return DEFAULTS
  }
}

let current: LocalPrefs = read()
const listeners = new Set<() => void>()

function subscribe(listener: () => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

function snapshot(): LocalPrefs {
  return current
}

export function getLocalPrefs(): LocalPrefs {
  return current
}

export function setLocalPrefs(patch: Partial<LocalPrefs>): void {
  current = { ...current, ...patch }
  try {
    localStorage.setItem(KEY, JSON.stringify(current))
  } catch {
    // Losing the preference is not worth failing a click over.
  }
  if (patch.theme !== undefined) applyTheme(current.theme)
  for (const listener of listeners) listener()
}

/** Live view of the local preferences; re-renders on every write. */
export function useLocalPrefs(): LocalPrefs {
  return useSyncExternalStore(subscribe, snapshot, snapshot)
}

/* ── Theme application ───────────────────────────────────────────────────── */

const SYSTEM_LIGHT = '(prefers-color-scheme: light)'
let systemWatcher: MediaQueryList | null = null

function resolve(choice: ThemeChoice): 'light' | 'dark' {
  if (choice !== 'system') return choice
  try {
    return window.matchMedia(SYSTEM_LIGHT).matches ? 'light' : 'dark'
  } catch {
    return 'dark'
  }
}

/**
 * Stamps the resolved palette on the root element. `system` is resolved here
 * rather than left to a CSS media query so the attribute is always present and
 * the two palettes need exactly one selector each; index.css still carries a
 * `prefers-color-scheme` fallback for the frame before this runs.
 */
export function applyTheme(choice: ThemeChoice): void {
  document.documentElement.dataset.theme = resolve(choice)

  // Only a `system` choice cares about the OS flipping under us.
  if (choice === 'system' && !systemWatcher) {
    try {
      systemWatcher = window.matchMedia(SYSTEM_LIGHT)
      systemWatcher.addEventListener('change', () => {
        if (current.theme !== 'system') return
        document.documentElement.dataset.theme = resolve('system')
        for (const listener of listeners) listener()
      })
    } catch {
      systemWatcher = null
    }
  }
}

/** Called once before the first render so no frame paints the wrong palette. */
export function applyStoredTheme(): void {
  applyTheme(current.theme)
}

/** What the title bar's toggle flips to: the opposite of what is on screen. */
export function nextThemeChoice(choice: ThemeChoice): 'light' | 'dark' {
  return resolve(choice) === 'dark' ? 'light' : 'dark'
}
