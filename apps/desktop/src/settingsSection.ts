import { useSyncExternalStore } from 'react'

/**
 * Which Settings section is showing.
 *
 * It lives outside the screen because other surfaces deep-link into it: the
 * sources sheet's "Change these in Playback" has to land on Playback, not on
 * whatever section Settings happened to be left on. Session state, so it is
 * deliberately not persisted.
 */
export type SettingsSection = 'appearance' | 'addons' | 'playback' | 'subtitles' | 'account'

let section: SettingsSection = 'appearance'
const listeners = new Set<() => void>()

function subscribe(listener: () => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

export function setSettingsSection(next: SettingsSection): void {
  section = next
  for (const listener of listeners) listener()
}

export function useSettingsSection(): SettingsSection {
  return useSyncExternalStore(
    subscribe,
    () => section,
    () => section,
  )
}
