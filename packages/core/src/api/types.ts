import type { Manifest, Stream, Subtitle } from '../addon/types'

/** DTOs shared between the Halo API and both clients. Timestamps are Unix ms. */

export interface AddonEntry {
  /**
   * Opaque server-assigned id; resolution endpoints are addressed by it.
   * Stable for as long as the addon stays installed — list saves only touch
   * entries whose transportUrl was added or removed.
   */
  id: string
  /**
   * Absent on global entries for non-admin callers: transport URLs can embed
   * secrets (e.g. debrid API keys), so only their opaque id leaves the server.
   * Always present on the caller's own entries — the manage flow re-sends them.
   */
  transportUrl?: string
  manifest: Manifest
  position: number
}

/** Global addons (admin-managed, shown to everyone) plus the caller's own. */
export interface AddonsResponse {
  global: AddonEntry[]
  user: AddonEntry[]
}

/**
 * The authenticated user as returned by GET /auth/me. `isAdmin` is computed
 * server-side per request (OIDC groups claim / local is_admin column); the
 * client never derives it from the token itself.
 */
export interface Me {
  id: string
  username: string
  isAdmin: boolean
  createdAt: number
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

/** Subtitle outline weight; maps to VLC freetype-outline-thickness (0/2/4/6). */
export type SubtitleOutline = 'none' | 'thin' | 'normal' | 'thick'

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
  /** Subtitle outline weight; unset = 'normal'. */
  subtitleOutline?: SubtitleOutline
  /** Subtitle drop shadow on/off; unset = on. */
  subtitleShadow?: boolean
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
  id: string
  name: string
}

/** Per-addon failure surfaced by the fan-out resolution endpoints (no stack). */
export interface AddonError {
  id: string
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
