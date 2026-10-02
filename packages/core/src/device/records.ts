import type { LibraryItem, UserSettings, WatchState } from '../api/types'

/**
 * Validation and last-write-wins merging for the synced records, mirroring
 * the API server's request schemas and upserts exactly. The on-device backend
 * applies these so that what it stores is always something the server would
 * have accepted: when someone using Halo without an account signs in, their
 * records move into the account, and a row the server rejects would block
 * that move.
 */

const MAX_ADDONS = 50
const MAX_WATCH_STATE_NAME_LENGTH = 512

/** Why an addon URL list is refused, or null. Same rules as the server's PUT /addons, http(s) only. */
export function addonUrlsProblem(urls: unknown): string | null {
  if (!Array.isArray(urls)) return 'expected a list of addon URLs'
  if (urls.length > MAX_ADDONS) return `at most ${MAX_ADDONS} addons can be installed`
  const seen = new Set<string>()
  for (const url of urls) {
    if (typeof url !== 'string' || !isHttpUrl(url)) return `not an addon URL: ${String(url)}`
    if (seen.has(url)) return `duplicate transportUrl: ${url}`
    seen.add(url)
  }
  return null
}

export function libraryItemProblem(value: unknown): string | null {
  if (!isRecord(value)) return 'library item must be an object'
  if (!nonEmptyString(value.id) || !nonEmptyString(value.type) || !nonEmptyString(value.name)) {
    return 'library item needs an id, type and name'
  }
  if (value.poster !== undefined && !isUrl(value.poster)) return 'library item poster must be a URL'
  if (!positiveInteger(value.addedAt) || !positiveInteger(value.updatedAt)) return 'library item timestamps must be positive integers'
  if (value.removedAt !== undefined && !positiveInteger(value.removedAt)) return 'library item removedAt must be a positive integer'
  return null
}

export function watchStateProblem(value: unknown): string | null {
  if (!isRecord(value)) return 'watch state must be an object'
  if (!nonEmptyString(value.videoId) || !nonEmptyString(value.itemId)) return 'watch state needs a videoId and itemId'
  if (!nonNegativeNumber(value.positionSec) || !nonNegativeNumber(value.durationSec)) {
    return 'watch state position and duration must be non-negative numbers'
  }
  if (typeof value.watched !== 'boolean') return 'watch state watched must be a boolean'
  if (value.name !== undefined && !(nonEmptyString(value.name) && value.name.length <= MAX_WATCH_STATE_NAME_LENGTH)) {
    return 'watch state name must be 1 to 512 characters'
  }
  if (value.poster !== undefined && !isUrl(value.poster)) return 'watch state poster must be a URL'
  if (!positiveInteger(value.updatedAt)) return 'watch state updatedAt must be a positive integer'
  return null
}

/** Known settings are checked; unknown ones pass through, as on the server, so newer clients keep theirs. */
export function settingsProblem(value: unknown, updatedAt: unknown): string | null {
  if (!positiveInteger(updatedAt)) return 'settings updatedAt must be a positive integer'
  if (!isRecord(value)) return 'settings must be an object'
  const s = value as Record<keyof UserSettings, unknown>
  const checks: Array<[keyof UserSettings, boolean]> = [
    ['preferredAudioLang', optional(s.preferredAudioLang, (v) => typeof v === 'string' && v.length <= 8)],
    ['preferredSubtitleLang', optional(s.preferredSubtitleLang, (v) => typeof v === 'string' && v.length <= 8)],
    ['videoFitMode', optional(s.videoFitMode, (v) => v === 'cover' || v === 'contain')],
    ['subtitleScalePercent', optional(s.subtitleScalePercent, (v) => Number.isInteger(v) && (v as number) >= 50 && (v as number) <= 200)],
    ['subtitleFontFamily', optional(s.subtitleFontFamily, (v) => typeof v === 'string' && v.length >= 1 && v.length <= 64)],
    ['subtitleOutline', optional(s.subtitleOutline, (v) => v === 'none' || v === 'thin' || v === 'normal' || v === 'thick')],
    ['subtitleShadow', optional(s.subtitleShadow, (v) => typeof v === 'boolean')],
    ['playbackRate', optional(s.playbackRate, (v) => typeof v === 'number' && Number.isFinite(v) && v >= 0.25 && v <= 4)],
    ['autoplayNextEpisode', optional(s.autoplayNextEpisode, (v) => typeof v === 'boolean')],
  ]
  const invalid = checks.find(([, ok]) => !ok)
  return invalid ? `invalid setting: ${invalid[0]}` : null
}

/**
 * Applies incoming library rows over stored ones, keyed by id: a strictly
 * newer `updatedAt` wins and a tie keeps the stored row. A row's type is fixed
 * by its first write, as on the server. Removals are tombstones (`removedAt`),
 * never deletions, so they survive stale re-adds.
 */
export function mergeLibrary(stored: readonly LibraryItem[], incoming: readonly LibraryItem[]): LibraryItem[] {
  const byId = new Map(stored.map((item) => [item.id, item]))
  for (const item of incoming) {
    const existing = byId.get(item.id)
    if (!existing) byId.set(item.id, libraryRow(item))
    else if (item.updatedAt > existing.updatedAt) byId.set(item.id, { ...libraryRow(item), type: existing.type })
  }
  return [...byId.values()]
}

/** Same rule as `mergeLibrary`, keyed by videoId; a winning row replaces the stored one whole. */
export function mergeWatchStates(stored: readonly WatchState[], incoming: readonly WatchState[]): WatchState[] {
  const byVideo = new Map(stored.map((state) => [state.videoId, state]))
  for (const state of incoming) {
    const existing = byVideo.get(state.videoId)
    if (!existing || state.updatedAt > existing.updatedAt) byVideo.set(state.videoId, watchStateRow(state))
  }
  return [...byVideo.values()]
}

/** Only the columns the server stores: anything else a caller attached is dropped, not kept. */
function libraryRow(item: LibraryItem): LibraryItem {
  return {
    id: item.id,
    type: item.type,
    name: item.name,
    ...(item.poster !== undefined ? { poster: item.poster } : {}),
    addedAt: item.addedAt,
    ...(item.removedAt !== undefined ? { removedAt: item.removedAt } : {}),
    updatedAt: item.updatedAt,
  }
}

function watchStateRow(state: WatchState): WatchState {
  return {
    videoId: state.videoId,
    itemId: state.itemId,
    positionSec: state.positionSec,
    durationSec: state.durationSec,
    watched: state.watched,
    ...(state.name !== undefined ? { name: state.name } : {}),
    ...(state.poster !== undefined ? { poster: state.poster } : {}),
    updatedAt: state.updatedAt,
  }
}

function optional(value: unknown, valid: (value: unknown) => boolean): boolean {
  return value === undefined || valid(value)
}

function positiveInteger(value: unknown): boolean {
  return Number.isInteger(value) && (value as number) > 0
}

/** Finite as well: JSON cannot carry Infinity, so the server would refuse it on the way into an account. */
function nonNegativeNumber(value: unknown): boolean {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0
}

function nonEmptyString(value: unknown): value is string {
  return typeof value === 'string' && value.length > 0
}

function isUrl(value: unknown): boolean {
  if (typeof value !== 'string') return false
  try {
    new URL(value)
    return true
  } catch {
    return false
  }
}

function isHttpUrl(value: string): boolean {
  try {
    const url = new URL(value)
    return url.protocol === 'http:' || url.protocol === 'https:'
  } catch {
    return false
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}
