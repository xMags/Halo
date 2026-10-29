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

/** The most titles the recently watched shelf shows, as on the phone. */
export const RECENTLY_WATCHED_LIMIT = 15

/**
 * Playback history: the last distinct titles played, newest first, at any
 * progress, so a finished series is still one click from a rewatch. Titles
 * already on the continue shelf (`exclude`, by item id) are left out rather
 * than shown twice. The row's own name and poster come first, the library
 * entry's only fill in; a row nothing names is dropped. The phone app's
 * Recently Watched shelf, so both pick the same titles.
 */
export function buildRecentlyWatchedRow(
  watchStates: WatchState[] | undefined,
  library: LibraryItem[] | undefined,
  exclude: ReadonlySet<string>,
  filter: HomeFilter,
  limit = RECENTLY_WATCHED_LIMIT,
): MetaPreview[] {
  const libById = activeLibraryById(library)
  const rows = [...(watchStates ?? [])].sort((a, b) => b.updatedAt - a.updatedAt)
  const seen = new Set<string>()
  const shelf: MetaPreview[] = []
  for (const row of rows) {
    if (shelf.length >= limit) break
    if (exclude.has(row.itemId) || seen.has(row.itemId)) continue
    seen.add(row.itemId)
    const entry = libById.get(row.itemId)
    const name = row.name ?? entry?.name
    if (!name) continue
    const type = itemType(row.itemId)
    if (!matchesHomeFilter(filter, type)) continue
    shelf.push({ id: itemMetaId(row.itemId), type, name, poster: row.poster ?? entry?.poster })
  }
  return shelf
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

/** Words that already say what kind a catalog holds, so the kind is not added twice. */
const KIND_WORDS: Readonly<Record<string, RegExp>> = {
  movie: /\b(movies?|films?|cinema)\b/i,
  series: /\b(series|shows?|tv)\b/i,
}

/** The kind as a shelf title spells it, plural ("Popular Movies") and singular ("Latest Movie Releases"). */
function kindLabels(type: string): { plural: string; singular: string } {
  if (type === 'movie') return { plural: 'Movies', singular: 'Movie' }
  if (type === 'series') return { plural: 'Series', singular: 'Series' }
  const word = type.charAt(0).toUpperCase() + type.slice(1)
  return { plural: word, singular: word }
}

/**
 * Plural nouns a catalog name can end on, where the kind reads as a modifier
 * in front ("Latest Movie Releases") rather than tacked on behind ("Latest
 * Releases Movies").
 */
const TRAILING_NOUNS = new Set([
  'additions',
  'arrivals',
  'favorites',
  'favourites',
  'gems',
  'hits',
  'picks',
  'premieres',
  'releases',
  'titles',
])

/** "TMDB - Latest", "TMDB: Latest", "TMDB | Latest": a prefix and the rest. */
const NAME_PREFIX = /^(.+?)(?:\s+[-–—|]\s+|:\s+)(.+)$/
/** A short all-caps token such as TMDB or IMDB. */
const ACRONYM = /^[A-Z0-9]{2,6}$/
/** "Addon" or "Add-on" ending an addon's name, which says nothing on a shelf. */
const ADDON_SUFFIX = /\s*\badd-?on$/i

/**
 * Addons often prefix their catalogs with their own name ("TMDB - Latest
 * Releases"), which the source line beside the title already gives. The
 * prefix goes when it is an acronym or the addon's own name or first word;
 * anything else could be part of the title and stays.
 */
function splitAddonPrefix(name: string, addonName: string): { rest: string; acronym: string | null } {
  const match = NAME_PREFIX.exec(name)
  if (!match) return { rest: name, acronym: null }
  const prefix = match[1]!.trim()
  const rest = match[2]!.trim()
  if (ACRONYM.test(prefix)) return { rest, acronym: prefix }
  const addon = addonName.replace(ADDON_SUFFIX, '').trim().toLowerCase()
  const lower = prefix.toLowerCase()
  if (lower === addon || lower === addon.split(/\s+/)[0]) return { rest, acronym: null }
  return { rest: name, acronym: null }
}

/**
 * How a catalog shelf is labelled: the catalog's name with its kind
 * ("Popular Movies"), and the addon it comes from as the mono line beside it
 * ("CINEMETA"). A name that already says its kind ("Trending Movies", "Top
 * TV") is kept as it is, one ending on a plural noun takes the kind in front
 * ("Latest Movie Releases"), and a catalog with no name is titled by its
 * kind. The addon's own prefix leaves the title for the source line, as its
 * short form when it has one ("TMDB" over "The Movie Database Addon").
 */
export function catalogShelfLabels(
  catalogName: string | undefined,
  type: string,
  addonName: string,
): { title: string; source: string } {
  const kind = kindLabels(type)
  const { rest: name, acronym } = splitAddonPrefix(catalogName?.trim() ?? '', addonName)
  const source = (acronym ?? addonName.replace(ADDON_SUFFIX, '').trim()).toUpperCase()
  if (!name) return { title: kind.plural, source }
  const saysKind = KIND_WORDS[type]?.test(name) ?? name.toLowerCase().includes(type.toLowerCase())
  if (saysKind) return { title: name, source }
  const words = name.split(/\s+/)
  const last = words[words.length - 1]!
  if (words.length > 1 && TRAILING_NOUNS.has(last.toLowerCase())) {
    return { title: [...words.slice(0, -1), kind.singular, last].join(' '), source }
  }
  return { title: `${name} ${kind.plural}`, source }
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
