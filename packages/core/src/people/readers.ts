/*
 * The people, credits, lookups and capabilities a Halo server answers, read
 * on the client. Everything here is untrusted: text is bounded and stripped
 * of control characters, images are accepted only from TMDB's own image host
 * over https, ids only in their exact shapes, and unknown fields are ignored.
 * A bad entry is skipped rather than failing the whole answer; an answer
 * that names nothing throws `PeopleAnswerError`.
 */

export type PersonDepartment = 'Acting' | 'Directing' | 'Writing'
export type PersonCreditRole = 'cast' | 'director' | 'writer'

/** What a server offers beyond the shared contract. Anything it does not say reads as off. */
export interface Capabilities {
  people: boolean
  lookup: boolean
  magnets: boolean
  /** Only an explicit false says uncached torrents may be fetched. */
  magnetsCachedOnly: boolean
}

export const NO_CAPABILITIES: Capabilities = Object.freeze({ people: false, lookup: false, magnets: false, magnetsCachedOnly: true })

export interface PersonSummary {
  tmdbId: number
  name: string
  knownFor: PersonDepartment | null
  photo: string | null
  popularity: number
  knownForTitles: string[]
}

export interface PeopleSearch {
  people: PersonSummary[]
  /** The name a misspelling was read as, when the server corrected it to someone listed. */
  corrected: string | null
}

export interface PersonCredit {
  type: 'movie' | 'series'
  imdbId: string
  /** 0 when the server sent none. */
  tmdbId: number
  name: string
  year: string | null
  poster: string | null
  roles: PersonCreditRole[]
  character: string | null
  popularity: number
}

export interface PersonDetail {
  person: {
    tmdbId: number
    name: string
    photo: string | null
    knownFor: PersonDepartment | null
    biography: string | null
  }
  credits: PersonCredit[]
  /** Some credits could not be given an IMDb id in time; the page says so. */
  partial: boolean
}

export type IdLookup =
  | { kind: 'person'; name: string; tmdbId: number; photo: string | null }
  | { kind: 'title'; name: string; type: 'movie' | 'series'; id: string; year: string | null; poster: string | null }

export class PeopleAnswerError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'PeopleAnswerError'
  }
}

const MAX_NAME = 200
const MAX_BIOGRAPHY = 2000
const MAX_IMAGE_URL = 300
const MAX_PEOPLE = 8
const MAX_KNOWN_FOR_TITLES = 3
const MAX_CREDITS = 60
const TMDB_IMAGE_PREFIX = 'https://image.tmdb.org/t/p/'
// eslint-disable-next-line no-control-regex
const CONTROL = /[\u0000-\u001f\u007f-\u009f]/g

function record(value: unknown): Record<string, unknown> | null {
  return typeof value === 'object' && value !== null && !Array.isArray(value) ? (value as Record<string, unknown>) : null
}

function requireRecord(value: unknown, message: string): Record<string, unknown> {
  const object = record(value)
  if (!object) throw new PeopleAnswerError(message)
  return object
}

/** Trimmed, control characters as spaces, refused rather than cut when longer than the bound: a clipped name names someone else. */
function boundedText(value: unknown, maximum = MAX_NAME): string | null {
  if (typeof value !== 'string') return null
  const text = value.trim()
  if (!text || text.length > maximum) return null
  return text.replace(CONTROL, ' ')
}

/** Prose: line breaks kept, other control characters as spaces, cut (not refused) at the bound, since a shortened biography still reads. */
function prose(value: unknown, maximum: number): string | null {
  if (typeof value !== 'string') return null
  const raw = value.trim().replace(/\r\n?/g, '\n')
  let text = ''
  for (const unit of raw) {
    if (text.length >= maximum) break
    // eslint-disable-next-line no-control-regex
    text += unit === '\n' || !/^[\u0000-\u001f\u007f-\u009f]$/.test(unit) ? unit : ' '
  }
  if (text.length > maximum) text = text.slice(0, maximum)
  const last = text.charCodeAt(text.length - 1)
  if (last >= 0xd800 && last <= 0xdbff) text = text.slice(0, -1)
  text = text.trim()
  return text || null
}

function safePositiveInteger(value: unknown): number | null {
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0 ? value : null
}

function popularity(value: unknown): number {
  return typeof value === 'number' && Number.isFinite(value) && value > 0 ? value : 0
}

/** Whether a picture link is TMDB's own image host over https, with a plain path. */
export function isTmdbImageUrl(url: string): boolean {
  if (url.length > MAX_IMAGE_URL || !url.startsWith(TMDB_IMAGE_PREFIX)) return false
  const path = url.slice(TMDB_IMAGE_PREFIX.length)
  return path.length > 0 && !path.includes('..') && /^[A-Za-z0-9/_.-]+$/.test(path)
}

function tmdbImage(value: unknown): string | null {
  return typeof value === 'string' && isTmdbImageUrl(value) ? value : null
}

function imdbTitleId(value: unknown): string | null {
  return typeof value === 'string' && /^tt\d{1,10}$/.test(value) ? value : null
}

function titleType(value: unknown): 'movie' | 'series' | null {
  return value === 'movie' || value === 'series' ? value : null
}

function year(value: unknown): string | null {
  return typeof value === 'string' && /^\d{4}$/.test(value) ? value : null
}

function department(value: unknown): PersonDepartment | null {
  return value === 'Acting' || value === 'Directing' || value === 'Writing' ? value : null
}

function roles(value: unknown): PersonCreditRole[] {
  const found: PersonCreditRole[] = []
  if (!Array.isArray(value)) return found
  for (const item of value) {
    if (found.length === 3) break
    if ((item === 'cast' || item === 'director' || item === 'writer') && !found.includes(item)) found.push(item)
  }
  return found
}

function summary(value: unknown): PersonSummary | null {
  const object = record(value)
  if (!object) return null
  const tmdbId = safePositiveInteger(object.tmdbId)
  const name = boundedText(object.name)
  if (!tmdbId || !name) return null
  const knownForTitles: string[] = []
  if (Array.isArray(object.knownForTitles)) {
    for (const title of object.knownForTitles) {
      if (knownForTitles.length === MAX_KNOWN_FOR_TITLES) break
      const text = boundedText(title)
      if (text) knownForTitles.push(text)
    }
  }
  return { tmdbId, name, knownFor: department(object.knownFor), photo: tmdbImage(object.photo), popularity: popularity(object.popularity), knownForTitles }
}

function credit(value: unknown): PersonCredit | null {
  const object = record(value)
  if (!object) return null
  const type = titleType(object.type)
  const imdbId = imdbTitleId(object.imdbId)
  const name = boundedText(object.name)
  const held = roles(object.roles)
  // Without an IMDb id it could be neither opened nor played; without a role it would not belong on the page.
  if (!type || !imdbId || !name || held.length === 0) return null
  return {
    type,
    imdbId,
    tmdbId: safePositiveInteger(object.tmdbId) ?? 0,
    name,
    year: year(object.year),
    poster: tmdbImage(object.poster),
    roles: held,
    character: boundedText(object.character),
    popularity: popularity(object.popularity),
  }
}

/** `GET /capabilities`. An older server has no `magnets` at all, which reads as off. */
export function readCapabilities(value: unknown): Capabilities {
  const object = requireRecord(value, 'The capabilities response must be an object.')
  const magnets = record(object.magnets)
  return {
    people: object.people === true,
    lookup: object.lookup === true,
    magnets: magnets?.enabled === true,
    magnetsCachedOnly: magnets?.cachedOnly !== false,
  }
}

/** `GET /people/search`: up to eight people, each once, and the corrected name when it names one of them. */
export function readPeopleSearch(value: unknown): PeopleSearch {
  const object = requireRecord(value, 'The people search response must be an object.')
  const people: PersonSummary[] = []
  if (Array.isArray(object.people)) {
    for (const item of object.people) {
      if (people.length === MAX_PEOPLE) break
      const person = summary(item)
      if (person && !people.some((other) => other.tmdbId === person.tmdbId)) people.push(person)
    }
  }
  const corrected = boundedText(object.corrected)
  return { people, corrected: corrected && people.some((person) => person.name === corrected) ? corrected : null }
}

/** `GET /people/:tmdbId`. */
export function readPerson(value: unknown): PersonDetail {
  const root = requireRecord(value, 'The person response must be an object.')
  const person = requireRecord(root.person, 'The person response has no person.')
  const tmdbId = safePositiveInteger(person.tmdbId)
  const name = boundedText(person.name)
  if (!tmdbId || !name) throw new PeopleAnswerError('The person response names nobody.')
  const credits: PersonCredit[] = []
  if (Array.isArray(root.credits)) {
    for (const item of root.credits) {
      if (credits.length === MAX_CREDITS) break
      const entry = credit(item)
      if (entry && !credits.some((other) => other.type === entry.type && other.imdbId === entry.imdbId)) credits.push(entry)
    }
  }
  return {
    person: { tmdbId, name, photo: tmdbImage(person.photo), knownFor: department(person.knownFor), biography: prose(person.biography, MAX_BIOGRAPHY) },
    credits,
    partial: root.partial === true,
  }
}

/** `GET /lookup`. */
export function readIdLookup(value: unknown): IdLookup {
  const object = requireRecord(value, 'The lookup response must be an object.')
  const name = boundedText(object.name)
  if (typeof object.kind !== 'string' || !name) throw new PeopleAnswerError('The lookup response names nothing.')
  if (object.kind === 'person') {
    const tmdbId = safePositiveInteger(object.tmdbId)
    if (!tmdbId) throw new PeopleAnswerError('The lookup response names nobody.')
    return { kind: 'person', name, tmdbId, photo: tmdbImage(object.photo) }
  }
  if (object.kind !== 'title') throw new PeopleAnswerError('The lookup response is of an unknown kind.')
  const type = titleType(object.type)
  const id = imdbTitleId(object.id)
  if (!type || !id) throw new PeopleAnswerError('The lookup response names no title.')
  return { kind: 'title', name, type, id, year: year(object.year), poster: tmdbImage(object.poster) }
}
