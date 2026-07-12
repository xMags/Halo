import type { Manifest, Stream, Subtitle } from '../addon/types'

/** DTOs shared between the Halo API and both clients. Timestamps are Unix ms. */

export interface AddonEntry {
  transportUrl: string
  manifest: Manifest
  position: number
}

/** What a client sends to set its addons; the server fetches the manifest. */
export interface AddonRef {
  transportUrl: string
  position: number
}

/** Global addons (admin-managed, shown to everyone) plus the caller's own. */
export interface AddonsResponse {
  global: AddonEntry[]
  user: AddonEntry[]
}

export interface LibraryItem {
  /** `${type}:${metaId}`, e.g. "movie:tt0111161". */
  id: string
  type: string
  name: string
  poster?: string
  addedAt: number
  /** Soft delete so removals sync across devices instead of resurrecting. */
  removedAt?: number
  /** Client-set; the server keeps whichever write is newest (LWW). */
  updatedAt: number
}

/** Synced user preferences. All fields optional; unknown fields round-trip. */
export interface UserSettings {
  /** ISO 639-2 code (e.g. "eng") the player auto-selects for audio tracks. */
  preferredAudioLang?: string
  /** ISO 639-2 code auto-applied for subtitles; unset = off by default. */
  preferredSubtitleLang?: string
  /** Player framing preference; cover crops proportionally, contain preserves the full frame. */
  videoFitMode?: 'cover' | 'contain'
  /** VLC subtitle text scale percentage. */
  subtitleScalePercent?: number
  /** VLC freetype font family for subtitles; unset = platform default. */
  subtitleFontFamily?: string
  /** Playback speed multiplier. */
  playbackRate?: number
}

export interface SettingsPayload {
  value: UserSettings
  /** Client-set; the server keeps whichever write is newest (LWW). */
  updatedAt: number
}

/** Identifies which effective addon a resolution result came from. */
export interface AddonSource {
  name: string
  transportUrl: string
}

/** Per-addon failure surfaced by the fan-out resolution endpoints (no stack). */
export interface AddonError {
  transportUrl: string
  message: string
}

/** Response of GET /streams: playable streams per addon plus per-addon errors. */
export interface StreamsResult {
  results: Array<{ addon: AddonSource; streams: Stream[] }>
  errors: AddonError[]
}

/** Response of GET /subtitles. `hashMatched` is true iff a videoHash was sent. */
export interface SubtitlesResult {
  results: Array<{ addon: AddonSource; subtitles: Subtitle[] }>
  errors: AddonError[]
  hashMatched: boolean
}

export interface WatchState {
  /** Meta id for movies, video id (e.g. "tt0944947:1:2") for episodes. */
  videoId: string
  /** Library item id this video belongs to. */
  itemId: string
  positionSec: number
  durationSec: number
  watched: boolean
  /** Client-set; the server keeps whichever write is newest (LWW). */
  updatedAt: number
}
