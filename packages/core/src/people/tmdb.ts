import { createTtlCache } from './ttlCache'

/**
 * A small TMDB (themoviedb.org) client for people search, person pages and
 * IMDb/TVDB id lookups. The API server runs it with its own read token; Halo
 * without an account runs it on the device with the viewer's own key. Every
 * answer is untrusted third-party JSON: it is read under a size cap and
 * reduced to bounded, validated fields before it is cached or used.
 *
 * The credential travels only in the Authorization header (a read token) or,
 * for a 32-hex API key, in the `api_key` query parameter TMDB requires for
 * that kind. It never appears in an error message.
 *
 * Answers are cached in memory per client, so a busy search box costs TMDB
 * one request per distinct query an hour.
 */

export const TMDB_BASE_URL = 'https://api.themoviedb.org'
const TMDB_IMAGE_BASE = 'https://image.tmdb.org/t/p'
const TMDB_TIMEOUT_MS = 8_000
const MAX_TMDB_RESPONSE_BYTES = 2 * 1024 * 1024

const HOUR = 3_600_000
const DAY = 24 * HOUR

const MAX_TEXT = 200
const MAX_BIOGRAPHY = 2_000
const MAX_SEARCH_PEOPLE = 8
const MAX_KNOWN_FOR_TITLES = 3
const MAX_TITLE_QUERY = 200
/** The most credits a person page lists, before mapping to IMDb ids. */
export const MAX_PERSON_CREDITS = 60
/** IMDb lookups in flight at once while mapping a person's credits. */
export const CREDIT_MAPPING_CONCURRENCY = 6
/** The whole time mapping a person's credits may take. */
export const CREDIT_MAPPING_BUDGET_MS = 8_000

/** TMDB TV genres that are not shows a person acted in: News, Reality, Talk. */
const EXCLUDED_TV_GENRES: ReadonlySet<number> = new Set([10763, 10764, 10767])
/** "Self", "Himself", "Herself", alone or followed by " - Host", " (archive footage)" and the like. */
const SELF_CHARACTER = /^(?:self|himself|herself)(?:\s*[-(:].*)?$/i
/** TMDB image paths look like "/abc123XYZ.jpg". */
const IMAGE_PATH = /^\/[A-Za-z0-9_-]{1,100}\.(?:jpg|jpeg|png|webp)$/
const IMDB_TITLE_ID = /^tt\d{1,10}$/
const YEAR_PREFIX = /^(\d{4})-\d{2}-\d{2}$/

export type TmdbDepartment = 'Acting' | 'Directing' | 'Writing'
export type TitleType = 'movie' | 'series'
export type CreditRole = 'cast' | 'director' | 'writer'
/** TMDB's own word for a title's kind, used in its paths. */
export type TmdbMediaKind = 'movie' | 'tv'
export type TmdbFindSource = 'tvdb' | 'imdb'

/**
 * The two kinds of TMDB credential: the "API Read Access Token" (a long
 * dotted token, sent as a Bearer header) and the "API Key" (32 hex
 * characters, sent as `api_key`). Both reach the same API.
 */
export type TmdbCredential = { kind: 'readToken'; value: string } | { kind: 'apiKey'; value: string }

export interface TmdbPersonSummary {
  tmdbId: number
  name: string
  knownFor: TmdbDepartment | null
  photo: string | null
  popularity: number
  knownForTitles: string[]
}

/** One title a person is credited on, roles merged, before its IMDb id is known. */
export interface CreditCandidate {
  type: TitleType
  tmdbId: number
  name: string
  year: string | null
  poster: string | null
  roles: CreditRole[]
  character: string | null
  popularity: number
}

export interface PersonCredit extends CreditCandidate {
  imdbId: string
}

export interface TmdbPersonDetails {
  tmdbId: number
  name: string
  photo: string | null
  knownFor: TmdbDepartment | null
  biography: string | null
  /** At most MAX_PERSON_CREDITS, most popular first. */
  credits: CreditCandidate[]
}

export interface TmdbTitleHit {
  type: TitleType
  tmdbId: number
  name: string
  year: string | null
  poster: string | null
  popularity: number
}

export interface TmdbPersonHit {
  tmdbId: number
  name: string
  photo: string | null
}

export interface TmdbFindResult {
  movies: TmdbTitleHit[]
  series: TmdbTitleHit[]
  people: TmdbPersonHit[]
}

export interface TmdbClient {
  searchPeople(query: string): Promise<TmdbPersonSummary[]>
  /** Null when TMDB has no person with this id. */
  person(tmdbId: number): Promise<TmdbPersonDetails | null>
  /** Null when TMDB knows no IMDb id for the title (that answer is cached too). */
  imdbIdFor(kind: TmdbMediaKind, tmdbId: number): Promise<string | null>
  find(source: TmdbFindSource, id: string): Promise<TmdbFindResult>
  searchTitle(kind: TmdbMediaKind, name: string, year?: number): Promise<TmdbTitleHit[]>
}

export interface TmdbClientOptions {
  credential: TmdbCredential
  /** The fetch to reach TMDB with (the server's SSRF-guarded one; tests stub it). */
  fetch: typeof fetch
  now?: () => number
  /** Told when TMDB refuses the credential (401), so the app can turn the features off. */
  onRefused?: () => void
}

/** TMDB answered with an error status. Carries the status only, never the URL (which may hold an API key). */
export class TmdbRequestError extends Error {
  constructor(readonly status: number) {
    super(`TMDB request failed with ${status}`)
    this.name = 'TmdbRequestError'
  }
}

/** TMDB's answer was larger than this client reads. */
export class TmdbResponseTooLargeError extends Error {
  constructor() {
    super('TMDB response too large')
    this.name = 'TmdbResponseTooLargeError'
  }
}

/** TMDB answered 404. Kept inside this module; callers see null instead. */
class TmdbNotFoundError extends Error {
  constructor() {
    super('TMDB has no such entry')
    this.name = 'TmdbNotFoundError'
  }
}

/**
 * What was pasted, trimmed, as a credential when it has either shape; null
 * otherwise. Only the shape is judged: whether TMDB accepts it is TMDB's to
 * say. A JSON Web Token is three non-empty base64url parts joined by dots;
 * TMDB's run to a couple of hundred characters, so the bounds only keep out
 * what cannot be one.
 */
export function parseTmdbCredential(pasted: string): TmdbCredential | null {
  const value = pasted.replace(/^[ \t\r\n]+|[ \t\r\n]+$/g, '')
  if (/^[0-9a-fA-F]{32}$/.test(value)) return { kind: 'apiKey', value }
  if (value.length < 100 || value.length > 2048) return null
  return /^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/.test(value) ? { kind: 'readToken', value } : null
}

/** A response's JSON, refusing one past `maxBytes` (declared or as it streams in). */
async function readJsonCapped(res: Response, maxBytes: number): Promise<unknown> {
  const declared = Number(res.headers.get('content-length'))
  if (Number.isFinite(declared) && declared > maxBytes) {
    await res.body?.cancel()
    throw new TmdbResponseTooLargeError()
  }
  if (!res.body) return JSON.parse('')
  const reader = res.body.getReader()
  const chunks: Uint8Array[] = []
  let total = 0
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    total += value.byteLength
    if (total > maxBytes) {
      await reader.cancel()
      throw new TmdbResponseTooLargeError()
    }
    chunks.push(value)
  }
  const bytes = new Uint8Array(total)
  let offset = 0
  for (const chunk of chunks) {
    bytes.set(chunk, offset)
    offset += chunk.byteLength
  }
  // TextDecoder drops a leading BOM, as Response.json() does.
  return JSON.parse(new TextDecoder().decode(bytes))
}

export function createTmdbClient(options: TmdbClientOptions): TmdbClient {
  const { credential, fetch: doFetch, onRefused } = options
  const now = options.now ?? Date.now

  const searchPeopleCache = createTtlCache<TmdbPersonSummary[]>({ maxEntries: 1_000, ttlMs: HOUR, now })
  const personCache = createTtlCache<TmdbPersonDetails>({ maxEntries: 500, ttlMs: 6 * HOUR, now })
  const imdbIdCache = createTtlCache<string | null>({ maxEntries: 20_000, ttlMs: 7 * DAY, now })
  const findCache = createTtlCache<TmdbFindResult>({ maxEntries: 5_000, ttlMs: 7 * DAY, now })
  const searchTitleCache = createTtlCache<TmdbTitleHit[]>({ maxEntries: 1_000, ttlMs: HOUR, now })

  const getJson = async (pathAndQuery: string): Promise<unknown> => {
    const headers: Record<string, string> = { accept: 'application/json' }
    let url = `${TMDB_BASE_URL}${pathAndQuery}`
    if (credential.kind === 'readToken') headers.authorization = `Bearer ${credential.value}`
    else url += `${pathAndQuery.includes('?') ? '&' : '?'}api_key=${encodeURIComponent(credential.value)}`
    const res = await doFetch(url, { headers, signal: AbortSignal.timeout(TMDB_TIMEOUT_MS) })
    if (res.status === 404) {
      await res.body?.cancel()
      throw new TmdbNotFoundError()
    }
    if (!res.ok) {
      await res.body?.cancel()
      if (res.status === 401) onRefused?.()
      throw new TmdbRequestError(res.status)
    }
    return readJsonCapped(res, MAX_TMDB_RESPONSE_BYTES)
  }

  return {
    async searchPeople(query) {
      const q = query.trim()
      return searchPeopleCache.getOrLoad(q.toLowerCase(), async () => {
        const body = await getJson(`/3/search/person?query=${encodeURIComponent(q)}&include_adult=false`)
        return parsePeopleSearch(body)
      })
    },

    async person(tmdbId) {
      assertTmdbId(tmdbId)
      try {
        return await personCache.getOrLoad(String(tmdbId), async () => {
          const body = await getJson(`/3/person/${tmdbId}?append_to_response=combined_credits,external_ids`)
          const details = parsePersonDetails(body)
          // An answer without a usable name is treated as no such person.
          if (!details) throw new TmdbNotFoundError()
          return details
        })
      } catch (err) {
        if (err instanceof TmdbNotFoundError) return null
        throw err
      }
    },

    async imdbIdFor(kind, tmdbId) {
      assertTmdbId(tmdbId)
      return imdbIdCache.getOrLoad(`${kind}:${tmdbId}`, async () => {
        try {
          const body = await getJson(`/3/${kind}/${tmdbId}/external_ids`)
          return isRecord(body) && typeof body.imdb_id === 'string' && IMDB_TITLE_ID.test(body.imdb_id) ? body.imdb_id : null
        } catch (err) {
          // TMDB no longer knowing the title is an answer ("none"), not a failure.
          if (err instanceof TmdbNotFoundError) return null
          throw err
        }
      })
    },

    async find(source, id) {
      if (!/^[A-Za-z0-9]{1,32}$/.test(id)) throw new RangeError('find id must be 1 to 32 letters or digits')
      const externalSource = source === 'tvdb' ? 'tvdb_id' : 'imdb_id'
      return findCache.getOrLoad(`${source}:${id}`, async () => {
        try {
          const body = await getJson(`/3/find/${encodeURIComponent(id)}?external_source=${externalSource}`)
          return parseFind(body)
        } catch (err) {
          if (err instanceof TmdbNotFoundError) return { movies: [], series: [], people: [] }
          throw err
        }
      })
    },

    async searchTitle(kind, name, year) {
      const q = name.trim()
      if (!q || q.length > MAX_TITLE_QUERY) throw new RangeError(`title query must be 1 to ${MAX_TITLE_QUERY} characters`)
      if (year !== undefined && (!Number.isSafeInteger(year) || year < 1800 || year > 3000)) {
        throw new RangeError('year must be a whole year')
      }
      const yearParam = year === undefined ? '' : `&${kind === 'movie' ? 'year' : 'first_air_date_year'}=${year}`
      return searchTitleCache.getOrLoad(`${kind}:${year ?? ''}:${q.toLowerCase()}`, async () => {
        const body = await getJson(`/3/search/${kind}?query=${encodeURIComponent(q)}&include_adult=false${yearParam}`)
        const results = isRecord(body) && Array.isArray(body.results) ? body.results : []
        return results.flatMap((entry) => {
          const hit = titleHit(entry, kind === 'movie' ? 'movie' : 'series')
          return hit ? [hit] : []
        })
      })
    },
  }
}

export interface CreditMappingOptions {
  now?: () => number
  budgetMs?: number
  concurrency?: number
}

/**
 * Gives each credit its IMDb id, keeping the order. A credit TMDB has no IMDb
 * id for is left out: the apps could neither open nor play it. At most
 * `concurrency` lookups run at once, and no new one starts once `budgetMs`
 * has passed; a lookup still running then is abandoned (its answer still
 * fills the cache for next time). `partial` is true when any credit's id is
 * still unknown at the end, because the budget ran out or a lookup failed.
 */
export async function mapCreditsToImdb(
  client: Pick<TmdbClient, 'imdbIdFor'>,
  candidates: readonly CreditCandidate[],
  options: CreditMappingOptions = {},
): Promise<{ credits: PersonCredit[]; partial: boolean }> {
  const now = options.now ?? Date.now
  const budgetMs = options.budgetMs ?? CREDIT_MAPPING_BUDGET_MS
  const concurrency = Math.max(1, options.concurrency ?? CREDIT_MAPPING_CONCURRENCY)
  const deadline = now() + budgetMs
  const ids: Array<string | null | undefined> = new Array<string | null | undefined>(candidates.length).fill(undefined)
  let partial = false
  let next = 0

  type Outcome = { kind: 'mapped'; id: string | null } | { kind: 'failed' } | { kind: 'expired' }
  let timer: ReturnType<typeof setTimeout> | undefined
  // Real time, so a hung lookup cannot hold the page past the budget.
  const expired = new Promise<Outcome>((resolve) => {
    timer = setTimeout(() => resolve({ kind: 'expired' }), Math.max(0, deadline - now()))
    ;(timer as { unref?: () => void }).unref?.()
  })

  const worker = async (): Promise<void> => {
    while (next < candidates.length) {
      if (now() >= deadline) {
        partial = true
        return
      }
      const index = next++
      const credit = candidates[index]!
      const lookup = client.imdbIdFor(credit.type === 'movie' ? 'movie' : 'tv', credit.tmdbId).then(
        (id): Outcome => ({ kind: 'mapped', id }),
        (): Outcome => ({ kind: 'failed' }),
      )
      const result = await Promise.race([lookup, expired])
      if (result.kind === 'expired') {
        partial = true
        return
      }
      if (result.kind === 'failed') {
        partial = true
        continue
      }
      ids[index] = result.id
    }
  }

  try {
    await Promise.all(Array.from({ length: Math.min(concurrency, candidates.length) }, () => worker()))
  } finally {
    clearTimeout(timer)
  }

  const credits: PersonCredit[] = []
  candidates.forEach((credit, index) => {
    const imdbId = ids[index]
    if (typeof imdbId === 'string') credits.push({ ...credit, imdbId })
  })
  return { credits, partial }
}

/**
 * A person's titles from TMDB's `combined_credits`: cast, directing (crew job
 * "Director") and writing (crew department "Writing"), one entry per title
 * with its roles merged. Only films and series count; news, reality and talk
 * shows and appearances as oneself are dropped. Most popular first, at most
 * MAX_PERSON_CREDITS.
 */
export function selectCredits(combinedCredits: unknown): CreditCandidate[] {
  if (!isRecord(combinedCredits)) return []
  const byTitle = new Map<string, CreditCandidate>()

  const add = (entry: unknown, role: CreditRole): void => {
    const hit = creditTitle(entry)
    if (!hit) return
    const character = role === 'cast' && isRecord(entry) ? (boundedText(entry.character) ?? null) : null
    if (character && SELF_CHARACTER.test(character)) return
    const key = `${hit.type}:${hit.tmdbId}`
    const existing = byTitle.get(key)
    if (!existing) {
      byTitle.set(key, { ...hit, roles: [role], character })
      return
    }
    if (!existing.roles.includes(role)) existing.roles.push(role)
    existing.character ??= character
    existing.popularity = Math.max(existing.popularity, hit.popularity)
  }

  for (const entry of arrayOf(combinedCredits.cast)) add(entry, 'cast')
  for (const entry of arrayOf(combinedCredits.crew)) {
    if (!isRecord(entry)) continue
    if (entry.job === 'Director') add(entry, 'director')
    if (entry.department === 'Writing') add(entry, 'writer')
  }

  const roleOrder: Record<CreditRole, number> = { cast: 0, director: 1, writer: 2 }
  return [...byTitle.values()]
    .map((credit) => ({ ...credit, roles: [...credit.roles].sort((a, b) => roleOrder[a] - roleOrder[b]) }))
    .sort((a, b) => b.popularity - a.popularity || a.tmdbId - b.tmdbId)
    .slice(0, MAX_PERSON_CREDITS)
}

/** A credited title without its role fields; null for anything not a film or a series, or a dropped TV genre. */
function creditTitle(entry: unknown): Omit<CreditCandidate, 'roles' | 'character'> | null {
  if (!isRecord(entry)) return null
  if (entry.media_type === 'tv') {
    const genres = arrayOf(entry.genre_ids)
    if (genres.some((genre) => typeof genre === 'number' && EXCLUDED_TV_GENRES.has(genre))) return null
    return titleHit(entry, 'series')
  }
  if (entry.media_type === 'movie') return titleHit(entry, 'movie')
  return null
}

function titleHit(entry: unknown, type: TitleType): TmdbTitleHit | null {
  if (!isRecord(entry)) return null
  const tmdbId = positiveSafeInteger(entry.id)
  const name = boundedText(type === 'movie' ? entry.title : entry.name)
  if (!tmdbId || !name) return null
  return {
    type,
    tmdbId,
    name,
    year: yearOf(type === 'movie' ? entry.release_date : entry.first_air_date),
    poster: imageUrl('w342', entry.poster_path),
    popularity: popularityOf(entry.popularity),
  }
}

function parsePeopleSearch(body: unknown): TmdbPersonSummary[] {
  const results = isRecord(body) ? arrayOf(body.results) : []
  const people: TmdbPersonSummary[] = []
  for (const entry of results) {
    if (people.length >= MAX_SEARCH_PEOPLE) break
    if (!isRecord(entry)) continue
    const tmdbId = positiveSafeInteger(entry.id)
    const name = boundedText(entry.name)
    if (!tmdbId || !name) continue
    const knownForTitles = arrayOf(entry.known_for)
      .flatMap((title) => {
        if (!isRecord(title)) return []
        const text = boundedText(title.title) ?? boundedText(title.name)
        return text ? [text] : []
      })
      .slice(0, MAX_KNOWN_FOR_TITLES)
    people.push({
      tmdbId,
      name,
      knownFor: departmentOf(entry.known_for_department),
      photo: imageUrl('w185', entry.profile_path),
      popularity: popularityOf(entry.popularity),
      knownForTitles,
    })
  }
  return people
}

/**
 * A long text cut to at most `max` characters, ending on its last full
 * sentence when one ends in the second half of what fits; otherwise cut at
 * `max` as it is (a text with no sentence to end on).
 */
export function endAtSentence(text: string, max: number): string {
  if (text.length <= max) return text
  const cut = text.slice(0, max)
  let end = -1
  for (const match of cut.matchAll(/[.!?]["')\]]?(?=\s)/g)) end = match.index + match[0].length
  return end >= max / 2 ? cut.slice(0, end) : cut
}

function parsePersonDetails(body: unknown): TmdbPersonDetails | null {
  if (!isRecord(body)) return null
  const tmdbId = positiveSafeInteger(body.id)
  const name = boundedText(body.name)
  if (!tmdbId || !name) return null
  const biography = typeof body.biography === 'string' ? endAtSentence(body.biography.trim(), MAX_BIOGRAPHY) : ''
  return {
    tmdbId,
    name,
    photo: imageUrl('w185', body.profile_path),
    knownFor: departmentOf(body.known_for_department),
    biography: biography || null,
    credits: selectCredits(body.combined_credits),
  }
}

function parseFind(body: unknown): TmdbFindResult {
  if (!isRecord(body)) return { movies: [], series: [], people: [] }
  const titles = (value: unknown, type: TitleType): TmdbTitleHit[] =>
    arrayOf(value).flatMap((entry) => {
      const hit = titleHit(entry, type)
      return hit ? [hit] : []
    })
  const people = arrayOf(body.person_results).flatMap((entry): TmdbPersonHit[] => {
    if (!isRecord(entry)) return []
    const tmdbId = positiveSafeInteger(entry.id)
    const name = boundedText(entry.name)
    return tmdbId && name ? [{ tmdbId, name, photo: imageUrl('w185', entry.profile_path) }] : []
  })
  return { movies: titles(body.movie_results, 'movie'), series: titles(body.tv_results, 'series'), people }
}

function assertTmdbId(tmdbId: number): void {
  if (!positiveSafeInteger(tmdbId)) throw new RangeError('TMDB ids are positive whole numbers')
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function arrayOf(value: unknown): unknown[] {
  return Array.isArray(value) ? value : []
}

function boundedText(value: unknown): string | undefined {
  if (typeof value !== 'string') return undefined
  const text = value.trim()
  return text && text.length <= MAX_TEXT ? text : undefined
}

function positiveSafeInteger(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0 ? value : undefined
}

function popularityOf(value: unknown): number {
  return typeof value === 'number' && Number.isFinite(value) && value > 0 ? value : 0
}

function departmentOf(value: unknown): TmdbDepartment | null {
  return value === 'Acting' || value === 'Directing' || value === 'Writing' ? value : null
}

function yearOf(value: unknown): string | null {
  if (typeof value !== 'string') return null
  return YEAR_PREFIX.exec(value)?.[1] ?? null
}

function imageUrl(size: 'w185' | 'w342', path: unknown): string | null {
  return typeof path === 'string' && IMAGE_PATH.test(path) ? `${TMDB_IMAGE_BASE}/${size}${path}` : null
}
