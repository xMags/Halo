import type { LibraryItem, MetaPreview } from '@halo/core'
import type { SearchHistoryEntry } from './searchHistory'

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
   * candidate. The first of them, in addon order, whose name is or begins
   * with what was typed wins: a search for a series must not be topped by a
   * film only because films are asked first. Failing any, the best-named
   * candidate wins, the earlier catalog on a tie.
   *
   * It never swaps once shown. A candidate that begins with the query is
   * shown as soon as no earlier catalog is still out, since no later answer
   * changes that choice; otherwise it stays undecided (null) until every
   * catalog has answered.
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
  const begins = ranked.find((candidate) => candidate.relevance <= BEGINS_WITH_QUERY)
  if (begins) return begins.afterPending ? null : begins.meta
  if (pending > 0) return null
  // Array.sort is stable, so the earlier catalog keeps a tie.
  return [...ranked].sort((a, b) => a.relevance - b.relevance)[0]!.meta
}

/** `type:id`, the identity two addons share for one title. */
export function metaKey(meta: Pick<MetaPreview, 'type' | 'id'>): string {
  return `${meta.type}:${meta.id}`
}

/** Lower case without accents, so "Amélie" is found by "amelie". */
function folded(text: string): string {
  return text.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase()
}

/** The query's words; a title matches when its name holds every one of them. */
function queryWords(query: string): string[] {
  return folded(query).split(/\s+/).filter(Boolean)
}

/** A name or query as plain words: folded, punctuation as spaces. */
function plainWords(text: string): string[] {
  return folded(text).split(/[^\p{L}\p{N}]+/u).filter(Boolean)
}

const EXACT_NAME = 0
/** The worst relevance that still means the name begins with what was typed. */
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

/**
 * The library entries the query names, newest first, as posters. They are
 * what the viewer most likely means, and no addon has to answer for them.
 */
export function libraryMatches(library: readonly LibraryItem[] | undefined, query: string): MetaPreview[] {
  const words = queryWords(query)
  if (words.length === 0) return []
  return (library ?? [])
    .filter((item) => !item.removedAt)
    .filter((item) => {
      const name = folded(item.name)
      return words.every((word) => name.includes(word))
    })
    .sort((a, b) => b.addedAt - a.addedAt)
    .map((item) => ({
      id: item.id.slice(item.type.length + 1),
      type: item.type,
      name: item.name,
      ...(item.poster ? { poster: item.poster } : {}),
    }))
}

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
  const year = releaseYear(meta.releaseInfo)
  const name = folded(meta.name).replace(/[^\p{L}\p{N}]+/gu, '')
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
  const seen = new Set(exclude.flatMap(titleIdentities))
  const merged: MetaPreview[] = []
  const longest = Math.max(0, ...lists.map((list) => list.length))
  for (let rank = 0; rank < longest; rank += 1) {
    for (const list of lists) {
      const meta = list[rank]
      if (!meta) continue
      const identities = titleIdentities(meta)
      if (identities.some((identity) => seen.has(identity))) continue
      identities.forEach((identity) => seen.add(identity))
      merged.push(meta)
    }
  }
  return merged
}

/**
 * Past searches that contain what is being typed, most recent first, for
 * picking one instead of typing it out. The term itself is not suggested back.
 */
export function suggestedSearches(history: readonly SearchHistoryEntry[], typed: string, limit = 5): string[] {
  const needle = folded(typed.trim())
  if (!needle) return []
  return history
    .map((entry) => entry.term)
    .filter((term) => {
      const candidate = folded(term)
      return candidate !== needle && candidate.includes(needle)
    })
    .slice(0, limit)
}
