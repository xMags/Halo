import { invoke } from '@tauri-apps/api/core'

/**
 * Discord Rich Presence, owned by the Rust side (`src-tauri/src/discord.rs`,
 * ported from the native app's service). The webview only reports what is
 * playing and how playback changes; Rust decides what Discord shows, throttles
 * repeats and talks to Discord. Every call is fire-and-forget: presence must
 * never get in the way of playback.
 */

/** What is playing. Only display text and the poster ever reach Discord. */
export interface PresenceMedia {
  title: string
  showName: string
  episodeLabel: string
  mediaType: string
  posterUrl: string
}

/**
 * Playback at one moment. `fileSerial` is 0 until mpv has loaded the file;
 * `seekSerial` changes each time playback restarts at a new position, which is
 * what re-anchors Discord's timer.
 */
export interface PresenceSnapshot {
  fileSerial: number
  seekSerial: number
  ended: boolean
  buffering: boolean
  paused: boolean
  positionSeconds: number
  durationSeconds: number
  speed: number
}

export function presenceSetEnabled(enabled: boolean): void {
  void invoke('discord_presence_set_enabled', { enabled }).catch(() => undefined)
}

export function presenceSetMedia(media: PresenceMedia): void {
  void invoke('discord_presence_set_media', { media }).catch(() => undefined)
}

export function presenceUpdate(snapshot: PresenceSnapshot): void {
  void invoke('discord_presence_update', { snapshot }).catch(() => undefined)
}

export function presenceClear(): void {
  void invoke('discord_presence_clear').catch(() => undefined)
}
