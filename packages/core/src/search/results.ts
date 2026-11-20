import type { MetaPreview } from '../addon/types'

/*
 * The search rules every client shares about one title: how well its name
 * answers a query, the identities two addons' copies of it share, how those
 * copies become one, and whether its poster is a poster at all. The ranking
 * and the board built on them are in `board.ts`. The Kotlin app carries the
 * same rules in `browse/SearchResults.kt`.
 */

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

/**
 * A name that opens with a studio's product code, `ABCD-123 …`: how adult
 * video is catalogued, and what indexes that carry it list it under. A general
 * search is never for one of these, and its poster is nothing to put on a
 * screen unasked, so they are left out whatever else they match.
 */
const PRODUCT_CODE_NAME = /^[A-Z]{2,6}-\d{3,5}\b/

const IMDB_ID = /^tt\d+$/

/** Whether an id is an IMDb title id, `tt` and digits. */
export function isImdbId(id: string): boolean {
  return IMDB_ID.test(id)
}

/** Whether a name opens with a studio's product code (see `PRODUCT_CODE_NAME`). */
export function isProductCodeName(name: string): boolean {
  return PRODUCT_CODE_NAME.test(name)
}

/** The first four-digit year of a release line: `2021`, `2016–2025` and `2024-` all give their start. */
export function releaseYear(releaseInfo: string | undefined): string | null {
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
 * A title whose name carries its year, as "Doctor Strange (2016)" does on
 * entries an addon made from file names (MediaFusion's `mf…` ones): the name
 * without it, and the year as the release line when there was none. That is
 * what lets such an entry be recognised as the copy of a real title it is.
 */
export function withYearOutOfName<T extends MetaPreview>(meta: T): T {
  const match = /^(.*\S)\s*\((\d{4})\)$/.exec(meta.name)
  if (!match) return meta
  return { ...meta, name: match[1]!, releaseInfo: releaseYear(meta.releaseInfo) ? meta.releaseInfo : match[2]! }
}

/**
 * Two copies of one title as one. The better-ranked copy leads, unless only
 * the other is named by its IMDb id: that is the id every addon can find
 * streams and details by, where an addon's own id works with that addon alone.
 */
export function mergeCopies<T extends MetaPreview & { posterMissing?: boolean }>(kept: T, other: T): T {
  return IMDB_ID.test(kept.id) || !IMDB_ID.test(other.id) ? fillMissingDetails(kept, other) : fillMissingDetails(other, kept)
}


/**
 * The better-ranked copy of a title, with what only the other copy knew.
 * Cinemeta ranks well but sends a name, a year and a poster link that is often
 * dead; the TMDB addon sends a rating and a poster that loads. The id stays
 * the kept copy's, since that is the one streams are found by.
 */
export function fillMissingDetails<T extends MetaPreview & { posterMissing?: boolean }>(kept: T, other: T): T {
  // A copy whose poster is known not to load has no poster at all.
  const otherHasNone = hasNoPoster(other) || other.posterMissing === true
  const poster =
    hasNoPoster(kept) || isMetahubPoster(kept.poster) || kept.posterMissing === true
      ? otherHasNone
        ? kept.poster
        : other.poster
      : kept.poster
  const merged: T = {
    ...kept,
    ...(poster ? { poster } : {}),
    ...(kept.imdbRating || !other.imdbRating ? {} : { imdbRating: other.imdbRating }),
    ...(kept.releaseInfo || !other.releaseInfo ? {} : { releaseInfo: other.releaseInfo }),
    ...(kept.description || !other.description ? {} : { description: other.description }),
  }
  if (!otherHasNone && merged.posterMissing) delete merged.posterMissing
  return merged
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
