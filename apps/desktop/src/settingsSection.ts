import { useSyncExternalStore } from 'react'

/**
 * A request to bring one Settings section into view.
 *
 * Settings is one scrolling form, as in the native app, so a section is a
 * place to scroll to rather than a page that is showing. Other surfaces
 * deep-link into it: the sources sheet's "Change these in Playback" has to
 * land on Playback. The request is held until Settings takes it, which works
 * whether Settings is already open or is opening because of it. Session
 * state, so it is deliberately not persisted.
 */
export type SettingsSection = 'appearance' | 'addons' | 'playback' | 'subtitles' | 'account'

/** Document order, which is also the rail's order. */
export const SETTINGS_SECTIONS: readonly SettingsSection[] = [
  'appearance',
  'addons',
  'playback',
  'subtitles',
  'account',
]

let pending: SettingsSection | null = null
const listeners = new Set<() => void>()

function subscribe(listener: () => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

function notify(): void {
  for (const listener of listeners) listener()
}

export function revealSettingsSection(next: SettingsSection): void {
  pending = next
  notify()
}

/** Called by Settings once it has scrolled to the requested section. */
export function clearSettingsSectionRequest(): void {
  if (pending === null) return
  pending = null
  notify()
}

export function useSettingsSectionRequest(): SettingsSection | null {
  return useSyncExternalStore(
    subscribe,
    () => pending,
    () => pending,
  )
}
