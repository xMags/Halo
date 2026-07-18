import { invoke } from '@tauri-apps/api/core'
import { listen, type UnlistenFn } from '@tauri-apps/api/event'

/**
 * The generic mpv message channel (the Stremio-settled contract): raw mpv
 * commands/properties tunneled over four calls, no typed player API across
 * the boundary. Player UI builds whatever it needs on top of this.
 */

/** Playback lifecycle events forwarded from mpv's event loop. */
export type MpvLifecycleEvent = 'start-file' | 'end-file' | 'file-loaded' | 'seek' | 'playback-restart'

export type MpvPropValue = number | boolean | string | null

export interface MpvPropChange {
  name: string
  value: MpvPropValue
}

/** Runs an mpv command (argv array), e.g. `mpvCmd('loadfile', url)`. */
export function mpvCmd(...args: (string | number)[]): Promise<void> {
  return invoke('mpv_cmd', { args: args.map(String) })
}

/** Sets a property via mpv's string conversion, e.g. `mpvSet('pause', 'yes')`. */
export function mpvSet(name: string, value: string): Promise<void> {
  return invoke('mpv_set', { name, value })
}

/** One-off property read (mpv string formatting); null when unavailable. */
export function mpvGet(name: string): Promise<string | null> {
  return invoke('mpv_get', { name })
}

/**
 * Subscribes to property change events. `format` picks the wire type mpv
 * reports with ('double' | 'flag' | 'string').
 */
export function mpvObserve(name: string, format: 'double' | 'flag' | 'string'): Promise<void> {
  return invoke('mpv_observe', { name, format })
}

/** Clears every property observer (the player re-registers on mount). */
export function mpvUnobserveAll(): Promise<void> {
  return invoke('mpv_unobserve_all')
}

// Dev-only hook so scripts/cdp.mjs can read/set mpv properties and watch the
// log stream for hands-off verification (same precedent as __haloNav /
// __haloClient).
if (import.meta.env.DEV) {
  ;(window as Window & { __haloMpv?: unknown }).__haloMpv = { mpvCmd, mpvSet, mpvGet, onMpvLog }
}

export function onMpvProp(handler: (change: MpvPropChange) => void): Promise<UnlistenFn> {
  return listen<MpvPropChange>('mpv-prop', (event) => handler(event.payload))
}

export function onMpvEvent(handler: (kind: MpvLifecycleEvent) => void): Promise<UnlistenFn> {
  return listen<MpvLifecycleEvent>('mpv-event', (event) => handler(event.payload))
}

export function onMpvLog(handler: (line: string) => void): Promise<UnlistenFn> {
  return listen<string>('mpv-log', (event) => handler(event.payload))
}
