import type { LibraryItem, MetaPreview, WatchState } from '@halo/core'

/**
 * Pure builders for Home's rows and the pane's shortcuts, ported from the
 * native WinUI Halo Desktop (`ContinueShelfPolicy` and `HomeStatePolicy`) so
 * both desktop clients pick the same titles from the same data.
 */

/** Below this the viewer opened something and left; the row does not count. */
const MINIMUM_STARTED_FRACTION = 0.02
/** At or above this nothing is left worth returning to, flag or no flag. */
const FINISHED_FRACTION = 0.95

/** The most cards the continue shelf shows, and the most lookups one pass asks for. */
export const CONTINUE_CARD_LIMIT = 8

/**
 * Watch rows key their title as `type:metaId`. A bare id with no prefix is a
 * film, which is how the oldest rows are shaped. The type never contains a
 * colon; the meta id may (kitsu ids), so only the first colon splits.
 */
export function itemType(itemId: string): string {
  const separator = itemId.indexOf(':')
  return separator < 0 ? 'movie' : itemId.slice(0, separator)
}

export function itemMetaId(itemId: string): string {
  const separator = itemId.indexOf(':')
  return separator < 0 ? itemId : itemId.slice(separator + 1)
}

/** Whether nothing is left of this row worth returning to. */
export function isFinishedWatchState(state: Pick<WatchState, 'watched' | 'positionSec' | 'durationSec'>): boolean {
  if (state.watched) return true
  return state.durationSec > 0 && state.positionSec / state.durationSec >= FINISHED_FRACTION
}

/** What a finished episode's successor is known to be. */
export type NextEpisodeLookup =
  | { state: 'unknown' }
  | { state: 'none' }
  | { state: 'resolved'; videoId: string }

/** A series whose newest row is finished and whose successor is not known yet. */
export interface ContinueNextRequest {
  itemId: string
  type: string
  metaId: string
  /** The finished episode to step past; also the lookup's key. */
  videoId: string
}

export interface ContinueCard {
  /** `resume` carries the stored position; `next` was never started. */
  kind: 'resume' | 'next'
  itemId: string
  type: string
  metaId: string
  videoId: string
  name: string
  poster: string | undefined
  positionSec: number
  durationSec: number
}

export interface ContinueShelf {
  cards: ContinueCard[]
  requests: ContinueNextRequest[]
}

/**
 * The continue shelf, newest activity first. A show is represented by whatever
 * it did most recently, and only once: left partway through, it resumes;
 * finished, a series advances to its next episode (a film simply leaves).
 *
 * A finished episode whose successor has not been looked up contributes a
 * request instead of a card, so the shelf paints from what is already known
 * and gains the promoted card once the lookup answers.
 */
export function buildContinueShelf(
  watchStates: WatchState[] | undefined,
  library: LibraryItem[] | undefined,
  lookup: (request: ContinueNextRequest) => NextEpisodeLookup,
  maximumCards = CONTINUE_CARD_LIMIT,
  maximumRequests = CONTINUE_CARD_LIMIT,
): ContinueShelf {
  const libById = activeLibraryById(library)
  const rows = [...(watchStates ?? [])].sort((a, b) => b.updatedAt - a.updatedAt)

  const shelf: ContinueShelf = { cards: [], requests: [] }
  const claimed = new Set<string>()
  for (const row of rows) {
    // Newest first, so nothing further could outrank what is already here.
    if (shelf.cards.length >= maximumCards) break

    // The library fills in a name and poster the row itself did not carry; a
    // row with no name at all cannot be drawn and does not speak for its show.
    const entry = libById.get(row.itemId)
    const name = row.name ?? entry?.name
    if (row.durationSec <= 0 || !name || claimed.has(row.itemId)) continue

    const finished = isFinishedWatchState(row)
    if (!finished && row.positionSec / row.durationSec <= MINIMUM_STARTED_FRACTION) continue

    // From here the row answers for its show, whether or not it draws a card.
    claimed.add(row.itemId)
    const type = itemType(row.itemId)
    const metaId = itemMetaId(row.itemId)
    const poster = row.poster ?? entry?.poster
    if (!finished) {
      shelf.cards.push({
        kind: 'resume',
        itemId: row.itemId,
        type,
        metaId,
        videoId: row.videoId,
        name,
        poster,
        positionSec: row.positionSec,
        durationSec: row.durationSec,
      })
      continue
    }

    if (type !== 'series') continue
    const request: ContinueNextRequest = { itemId: row.itemId, type, metaId, videoId: row.videoId }
    const next = lookup(request)
    if (next.state === 'unknown') {
      if (shelf.requests.length < maximumRequests) shelf.requests.push(request)
      continue
    }
    if (next.state === 'none' || !next.videoId) continue
    shelf.cards.push({
      kind: 'next',
      itemId: row.itemId,
      type,
      metaId,
      videoId: next.videoId,
      name,
      poster,
      positionSec: 0,
      durationSec: 0,
    })
  }
  return shelf
}

function activeLibraryById(library: LibraryItem[] | undefined): Map<string, LibraryItem> {
  return new Map((library ?? []).filter((i) => !i.removedAt).map((i) => [i.id, i]))
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

/**
 * Whether a catalog of this type belongs on Home. Only films and series do:
 * addons also publish account listings under other types (TorBox's torrent
 * list is `other`), whose entries are raw release names and hashes with no
 * artwork, not titles to browse.
 */
export function isHomeCatalogType(type: string): boolean {
  return type === 'movie' || type === 'series'
}

/** Home's kind filter. Anything that is not a series counts as a movie, as natively. */
export type HomeFilter = 'all' | 'movie' | 'series'

export function matchesHomeFilter(filter: HomeFilter, type: string): boolean {
  if (filter === 'all') return true
  return filter === (type === 'series' ? 'series' : 'movie')
}

/**
 * Picks up to `count` titles for the featured carousel, in catalog order,
 * never repeating a title two catalogs both list. Titles with a backdrop go
 * first, because the carousel draws them full width and a stretched poster
 * looks wrong; the rest only top the strip up when backdrops run short. Two
 * ordered passes rather than a sort keep each addon's own ranking.
 *
 * Returns indices into `candidates`.
 */
export function selectFeatured(
  filter: HomeFilter,
  candidates: ReadonlyArray<Pick<MetaPreview, 'id' | 'type' | 'background'>>,
  count: number,
): number[] {
  const selected: number[] = []
  if (count <= 0) return selected
  const seen = new Set<string>()
  for (const wantBackdrop of [true, false]) {
    for (let index = 0; index < candidates.length; index += 1) {
      if (selected.length === count) return selected
      const candidate = candidates[index]!
      const key = candidate.id ? `${candidate.type}:${candidate.id}` : ''
      if (Boolean(candidate.background) !== wantBackdrop) continue
      if (!key || !matchesHomeFilter(filter, candidate.type)) continue
      if (seen.has(key)) continue
      seen.add(key)
      selected.push(index)
    }
  }
  return selected
}
