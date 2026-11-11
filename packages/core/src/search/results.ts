import type { MetaPreview } from '../addon/types'

/*
 * How a search across several addons' catalogs becomes one list: which
 * catalog's answer leads, how two addons' copies of a title become one, what
 * order the titles take and which of them are not worth showing. Shared by
 * every client so they cannot rank or filter differently; the Kotlin app
 * carries the same rules in `browse/SearchResults.kt`.
 */

/** One searchable catalog: an installed addon's catalog that takes a `search` extra. */
export interface SearchTarget {
  key: string
  addonId: string
  addonName: string
  type: string
  id: string
  /** The catalog's own name, the row's title. */
  title: string
}

/** Where one catalog's answer stands. */
export type TargetState =
  | { status: 'pending' }
  | { status: 'failed' }
  | { status: 'done'; metas: MetaPreview[] }

/** One result row: a catalog that answered with titles. */
export interface SearchResultGroup {
  key: string
  title: string
  addonName: string
  type: string
  metas: MetaPreview[]
}

export interface SearchProgress {
  /** Rows so far, in addon order; a catalog's row appears as soon as it answers. */
  groups: SearchResultGroup[]
  /**
   * The best guess for the query. Each catalog's own first title is a
   * candidate, and the one whose name answers the query best wins, the
   * earlier catalog on a tie: a search for a series must not be topped by a
   * film only because films are asked first, nor an exact name by a longer
   * one only because its addon is listed first.
   *
   * It never swaps once shown. A candidate named exactly what was typed is
   * shown as soon as no earlier catalog is still out, since nothing later can
   * beat it; otherwise it stays undecided (null) until every catalog has
   * answered.
   *
   * Without a query it is the first title of the first catalog that answered
   * with any, undecided while an earlier catalog is still out.
   */
  topMatch: MetaPreview | null
  /** Catalogs still out. */
  pending: number
  /** Catalogs that failed, for naming the addons that did not answer. */
  failed: SearchTarget[]
  /** Catalogs asked. */
  total: number
}

/** A catalog's titles without repeats; addons sometimes list one title twice. */
export function uniqueMetas(metas: readonly MetaPreview[]): MetaPreview[] {
  const seen = new Set<string>()
  return metas.filter((meta) => {
    const key = `${meta.type}:${meta.id}`
    if (seen.has(key)) return false
    seen.add(key)
    return true
  })
}

/** What the search screen shows from each catalog's state, as answers arrive. */
export function searchProgress(
  targets: readonly SearchTarget[],
  states: readonly TargetState[],
  query?: string,
): SearchProgress {
  const groups: SearchResultGroup[] = []
  const failed: SearchTarget[] = []
  /** Each answering catalog's first title, with whether a catalog before it is still out. */
  const candidates: Array<{ meta: MetaPreview; afterPending: boolean }> = []
  let pending = 0
  targets.forEach((target, index) => {
    const state = states[index] ?? { status: 'pending' }
    if (state.status === 'pending') {
      pending += 1
      return
    }
    if (state.status === 'failed') {
      failed.push(target)
      return
    }
    if (state.metas.length === 0) return
    groups.push({ key: target.key, title: target.title, addonName: target.addonName, type: target.type, metas: state.metas })
    candidates.push({ meta: state.metas[0]!, afterPending: pending > 0 })
  })
  return { groups, topMatch: chooseTopMatch(candidates, pending, query), pending, failed, total: targets.length }
}

function chooseTopMatch(
  candidates: ReadonlyArray<{ meta: MetaPreview; afterPending: boolean }>,
  pending: number,
  query: string | undefined,
): MetaPreview | null {
  const first = candidates[0]
  if (!first) return null
  if (query === undefined) return first.afterPending ? null : first.meta
  const ranked = candidates.map((candidate) => ({ ...candidate, relevance: nameRelevance(candidate.meta.name, query) }))
  if (pending > 0) {
    const exact = ranked.find((candidate) => candidate.relevance === EXACT_NAME)
    return exact && !exact.afterPending ? exact.meta : null
  }
  // Array.sort is stable, so the earlier catalog keeps a tie.
  return [...ranked].sort((a, b) => a.relevance - b.relevance)[0]!.meta
}

/** `type:id`, the identity two addons share for one title. */
export function metaKey(meta: Pick<MetaPreview, 'type' | 'id'>): string {
  return `${meta.type}:${meta.id}`
}

/** Lower case without accents, so "Amélie" is found by "amelie". */
export function foldedText(text: string): string {
  return text.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase()
}

/** Short forms titles and people use for the same word; "Dr. Strange" is "Doctor Strange". */
const SHORT_FORMS: Readonly<Record<string, string>> = { dr: 'doctor', mr: 'mister', vs: 'versus' }

/** A name or query as plain words: folded, punctuation as spaces, short forms spelled out. */
function plainWords(text: string): string[] {
  return foldedText(text)
    .split(/[^\p{L}\p{N}]+/u)
    .filter(Boolean)
    .map((word) => SHORT_FORMS[word] ?? word)
}

const EXACT_NAME = 0
/** The relevance of a name that begins with what was typed, mid-word. */
const BEGINS_WITH_QUERY = 2

/**
 * How well a title's name answers the query, lower is better: the name is the
 * query (0), starts with its words (1), starts with it mid-word, as "Doctor
 * Stranger" does for "doctor strange" and any title does while its name is
 * still being typed (2), holds every word of it (3), holds every word inside
 * longer words (4), holds some of them (5), or none (6). Addons answer from
 * descriptions and alternative titles too, which is how a search for one film
 * returns titles that share no word with it; those belong last, not among
 * the first results.
 */
export function nameRelevance(name: string, query: string): number {
  const wanted = plainWords(query)
  if (wanted.length === 0) return EXACT_NAME
  const words = plainWords(name)
  const joined = words.join(' ')
  const phrase = wanted.join(' ')
  if (joined === phrase) return EXACT_NAME
  if (joined.startsWith(`${phrase} `)) return 1
  if (joined.startsWith(phrase)) return BEGINS_WITH_QUERY
  if (wanted.every((word) => words.includes(word))) return 3
  if (wanted.every((word) => joined.includes(word))) return 4
  if (wanted.some((word) => joined.includes(word))) return 5
  return 6
}

/**
 * The same titles, best-named for the query first. Within one level of
 * relevance the order given stays, which is the addons' own ranking.
 */
export function byRelevance<T extends Pick<MetaPreview, 'name'>>(metas: readonly T[], query: string): T[] {
  return metas
    .map((meta) => ({ meta, relevance: nameRelevance(meta.name, query) }))
    .sort((a, b) => a.relevance - b.relevance)
    .map((entry) => entry.meta)
}

/** The relevance of a name that shares no word with the query. */
const UNRELATED_NAME = 6
/** Results with a year and artwork that make the ones without artwork not worth showing. */
const ENOUGH_COMPLETE = 8
/** The relevance of a name that holds only some of the query's words. */
const SOME_WORDS = 5
/** Results holding every word of the query that make the ones holding only some not worth showing. */
const ENOUGH_NAMED = 8

/**
 * The merged results as the screen shows them: what was asked for first, and
 * without what nobody asked for.
 *
 * Addons pad a search with everything their index brushes against: titles
 * that share no word with the query (matched on a description or a foreign
 * alternative title), announced films with no release year, and entries with
 * no artwork at all. So:
 * - a title sharing no word with the query is left out, as long as some
 *   result does name it (when none does, as with an addon answering in
 *   another language, everything stays);
 * - a title with neither a release year nor artwork is left out: there is
 *   nothing to show for it and nothing to play;
 * - the rest are ordered by name relevance, with the ones missing a year or
 *   artwork after all that have both;
 * - once a search has a screenful of results with both (`ENOUGH_COMPLETE`),
 *   the ones without artwork are left out too. With fewer, they stay: a
 *   rare title's only listing may be one nobody uploaded a poster for;
 * - once a screenful holds every word of the query (`ENOUGH_NAMED`), the ones
 *   holding only some are left out: for "doctor strange", every title with
 *   "doctor" or "strange" somewhere in it is not what was asked.
 *
 * `missingArt` holds the keys (`type:id`) of titles whose poster is known not
 * to load, beyond what the addon's answer already admits (`hasNoPoster`).
 */
export function refineResults(
  metas: readonly MetaPreview[],
  query: string,
  missingArt: ReadonlySet<string> = new Set(),
): MetaPreview[] {
  const entries = metas.map((meta) => ({
    meta,
    relevance: nameRelevance(meta.name, query),
    undated: releaseYear(meta.releaseInfo) === null,
    artless: hasNoPoster(meta) ? !IMDB_ID.test(meta.id) || missingArt.has(metaKey(meta)) : missingArt.has(metaKey(meta)),
  }))
  const someNamed = entries.some((entry) => entry.relevance < UNRELATED_NAME)
  const wellNamed = entries.filter((entry) => entry.relevance < SOME_WORDS).length >= ENOUGH_NAMED
  const named = entries
    .filter((entry) => !(someNamed && entry.relevance === UNRELATED_NAME))
    .filter((entry) => !(wellNamed && entry.relevance === SOME_WORDS))
  const plenty = named.filter((entry) => !entry.undated && !entry.artless).length >= ENOUGH_COMPLETE
  return named
    .filter((entry) => !(entry.artless && (entry.undated || plenty)))
    .sort((a, b) => Number(a.undated || a.artless) - Number(b.undated || b.artless) || a.relevance - b.relevance)
    .map((entry) => entry.meta)
}

const IMDB_ID = /^tt\d+$/

/** The first four-digit year of a release line: `2021`, `2016–2025` and `2024-` all give their start. */
function releaseYear(releaseInfo: string | undefined): string | null {
  return releaseInfo?.match(/\d{4}/)?.[0] ?? null
}

/**
 * Every identity a title can be recognised by. Addons name the same title by
 * different ids (Cinemeta's IMDb `tt…`, TMDB's own numbers), so besides its id
 * a title is known by its kind, name and year together: "Dune" from 2021 is
 * one film whichever addon lists it. Without a year a name alone is not
 * trusted, since remakes share names.
 */
export function titleIdentities(meta: Pick<MetaPreview, 'type' | 'id' | 'name' | 'releaseInfo'>): string[] {
  const identities = [metaKey(meta)]
  // The surest one when it is there: Cinemeta's id is the IMDb id, and the
  // TMDB addon sends it beside its own.
  const imdbId = IMDB_ID.test(meta.id) ? meta.id : (meta as { imdb_id?: unknown }).imdb_id
  if (typeof imdbId === 'string' && IMDB_ID.test(imdbId)) identities.push(`${meta.type}|imdb|${imdbId}`)
  const year = releaseYear(meta.releaseInfo)
  const name = foldedText(meta.name).replace(/[^\p{L}\p{N}]+/gu, '')
  if (year && name) identities.push(`${meta.type}|${name}|${year}`)
  return identities
}

/**
 * Several ranked lists as one: every list's first title, then every list's
 * second, and so on, each title once even when two addons name it by different
 * ids (see `titleIdentities`). Taking turns keeps each addon's own ranking
 * without letting the first addon's long tail bury the others' best; of two
 * copies of a title, the better-ranked one stays. Titles shown elsewhere on
 * the screen are passed in `exclude` and left out.
 */
export function interleave(
  lists: readonly (readonly MetaPreview[])[],
  exclude: readonly MetaPreview[] = [],
): MetaPreview[] {
  const excluded = new Set(exclude.flatMap(titleIdentities))
  /** Where in `merged` each identity's title sits. */
  const placed = new Map<string, number>()
  const merged: MetaPreview[] = []
  const longest = Math.max(0, ...lists.map((list) => list.length))
  for (let rank = 0; rank < longest; rank += 1) {
    for (const list of lists) {
      const listed = list[rank]
      if (!listed) continue
      const meta = withYearOutOfName(listed)
      const identities = titleIdentities(meta)
      if (identities.some((identity) => excluded.has(identity))) continue
      const kept = identities.map((identity) => placed.get(identity)).find((index) => index !== undefined)
      if (kept !== undefined) {
        merged[kept] = oneTitleFrom(merged[kept]!, meta)
        // Whatever else the second copy is known by now finds the same card.
        identities.forEach((identity) => placed.set(identity, kept))
        continue
      }
      identities.forEach((identity) => placed.set(identity, merged.length))
      merged.push(meta)
    }
  }
  return merged
}

/**
 * A title whose name carries its year, as "Doctor Strange (2016)" does on
 * entries an addon made from file names (MediaFusion's `mf…` ones): the name
 * without it, and the year as the release line when there was none. That is
 * what lets such an entry be recognised as the copy of a real title it is.
 */
function withYearOutOfName(meta: MetaPreview): MetaPreview {
  const match = /^(.*\S)\s*\((\d{4})\)$/.exec(meta.name)
  if (!match) return meta
  return { ...meta, name: match[1]!, releaseInfo: releaseYear(meta.releaseInfo) ? meta.releaseInfo : match[2]! }
}

/**
 * Two copies of one title as one. The better-ranked copy leads, unless only
 * the other is named by its IMDb id: that is the id every addon can find
 * streams and details by, where an addon's own id works with that addon alone.
 */
function oneTitleFrom(kept: MetaPreview, other: MetaPreview): MetaPreview {
  return IMDB_ID.test(kept.id) || !IMDB_ID.test(other.id) ? withDetailsFrom(kept, other) : withDetailsFrom(other, kept)
}

/**
 * The better-ranked copy of a title, with what only the other copy knew.
 * Cinemeta ranks well but sends a name, a year and a poster link that is often
 * dead; the TMDB addon sends a rating and a poster that loads. The id stays
 * the kept copy's, since that is the one streams are found by.
 */
function withDetailsFrom(kept: MetaPreview, other: MetaPreview): MetaPreview {
  const poster = hasNoPoster(kept) || isMetahubPoster(kept.poster) ? (hasNoPoster(other) ? kept.poster : other.poster) : kept.poster
  return {
    ...kept,
    ...(poster ? { poster } : {}),
    ...(kept.imdbRating || !other.imdbRating ? {} : { imdbRating: other.imdbRating }),
    ...(kept.releaseInfo || !other.releaseInfo ? {} : { releaseInfo: other.releaseInfo }),
    ...(kept.description || !other.description ? {} : { description: other.description }),
  }
}

/** Stremio's own poster hosts, which a card tries by IMDb id anyway. */
function isMetahubPoster(poster: string | undefined): boolean {
  return poster !== undefined && /^https:\/\/[a-z0-9-]+\.metahub\.space\//.test(poster)
}

/** The TMDB addon's link built from a missing image: `…/t/p/w500null`. */
const TMDB_MISSING_IMAGE = /\/t\/p\/[a-z0-9]+null$/i
/**
 * MediaFusion's poster for one of its own `mf…` entries: not artwork but a
 * card it draws with the entry's name on it, since it has no picture for a
 * title it only knows from a file name.
 */
const GENERATED_POSTER = /\/poster\/[a-z]+\/mf\d+\.[a-z]+(\?.*)?$/i
/** MediaFusion's id for an entry it made from a file name, which is all it has to draw that card from. */
const FILE_NAME_ENTRY = /^mf\d+$/

/**
 * Whether an addon's answer already says the title has no poster: none sent,
 * a link built from a missing image, or a card the addon draws in its place.
 * An entry made from a file name is known by its id as well as by the link,
 * since the link's shape is the addon's to change.
 */
export function hasNoPoster(meta: Pick<MetaPreview, 'poster'> & { id?: string }): boolean {
  if (meta.id !== undefined && FILE_NAME_ENTRY.test(meta.id)) return true
  return !meta.poster || TMDB_MISSING_IMAGE.test(meta.poster) || GENERATED_POSTER.test(meta.poster)
}
