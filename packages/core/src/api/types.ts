import type { Manifest } from '../addon/types'

/** DTOs shared between the Halo API and both clients. Timestamps are Unix ms. */

export interface AddonEntry {
  transportUrl: string
  manifest: Manifest
  position: number
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
