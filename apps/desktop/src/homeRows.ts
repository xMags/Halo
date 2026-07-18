import type { LibraryItem, MetaPreview, WatchState } from '@halo/core'

/**
 * Pure builders for Home's activity rows — ported from mobile's Home screen so
 * both clients slice watch-state/library the same way.
 */

export interface ContinueWatchingItem {
  meta: MetaPreview
  progress: number
  itemId: string
}

/**
 * Display fields for a watch state: the state's own denormalized name/poster
 * first, the library entry as fallback for states written by older clients.
 * itemId is `${type}:${metaId}`; the type segment never contains a colon, the
 * meta id may (e.g. kitsu ids).
 */
function watchStateMeta(s: WatchState, libById: Map<string, LibraryItem>): MetaPreview | null {
  const lib = libById.get(s.itemId)
  const name = s.name ?? lib?.name
  if (!name) return null
  const type = s.itemId.slice(0, s.itemId.indexOf(':'))
  return { id: s.itemId.slice(type.length + 1), type, name, poster: s.poster ?? lib?.poster }
}

function activeLibraryById(library: LibraryItem[] | undefined): Map<string, LibraryItem> {
  return new Map((library ?? []).filter((i) => !i.removedAt).map((i) => [i.id, i]))
}

/** In-progress items, most recent first — does not require library membership. */
export function buildContinueWatching(
  watchStates: WatchState[] | undefined,
  library: LibraryItem[] | undefined,
): ContinueWatchingItem[] {
  const libById = activeLibraryById(library)
  // One card per show (most recent episode wins) — two in-progress episodes
  // of the same series must not produce duplicate keys.
  const seenItems = new Set<string>()
  return (watchStates ?? [])
    .filter((s) => s.durationSec > 0 && !s.watched)
    .map((s) => ({ s, fraction: s.positionSec / s.durationSec }))
    .filter(({ fraction }) => fraction > 0.02 && fraction < 0.95)
    .sort((a, b) => b.s.updatedAt - a.s.updatedAt)
    .flatMap(({ s, fraction }) => {
      if (seenItems.has(s.itemId)) return []
      seenItems.add(s.itemId)
      const meta = watchStateMeta(s, libById)
      return meta ? [{ meta, progress: fraction, itemId: s.itemId }] : []
    })
}

const RECENTLY_WATCHED_LIMIT = 15

/**
 * Playback history: last distinct items by recency, any progress — finished
 * shows included as a rewatch entry point. Items already in Continue Watching
 * are excluded so the two rows never duplicate.
 */
export function buildRecentlyWatched(
  watchStates: WatchState[] | undefined,
  library: LibraryItem[] | undefined,
  continueItems: { itemId: string }[],
  typeFilter: string | null,
): MetaPreview[] {
  const libById = activeLibraryById(library)
  const exclude = new Set(continueItems.map((c) => c.itemId))
  const seenItems = new Set<string>()
  return (watchStates ?? [])
    .filter((s) => !exclude.has(s.itemId))
    .sort((a, b) => b.updatedAt - a.updatedAt)
    .flatMap((s) => {
      if (seenItems.has(s.itemId)) return []
      seenItems.add(s.itemId)
      const meta = watchStateMeta(s, libById)
      if (!meta || (typeFilter && meta.type !== typeFilter)) return []
      return [meta]
    })
    .slice(0, RECENTLY_WATCHED_LIMIT)
}

/** Active library entries as poster previews, newest addition first. */
export function buildLibraryRow(
  library: LibraryItem[] | undefined,
  typeFilter: string | null,
): MetaPreview[] {
  return (library ?? [])
    .filter((item) => !item.removedAt)
    .filter((item) => !typeFilter || item.type === typeFilter)
    .sort((a, b) => b.addedAt - a.addedAt)
    .map((item) => ({
      id: item.id.slice(item.type.length + 1),
      type: item.type,
      name: item.name,
      poster: item.poster,
    }))
}
