import { closestPeople } from './closest'
import { mapCreditsToImdb, type TmdbClient } from './tmdb'

/*
 * The answers of the people and lookup routes (`GET /people/search`,
 * `GET /people/:tmdbId`, `GET /lookup`): validation, status codes and bodies,
 * as the MagnetoFX server answers them. The API server sends these as they
 * are; Halo without an account builds the same bodies on the device, so the
 * apps read both through one set of readers (`people/readers.ts`).
 */

export interface RouteAnswer {
  status: 200 | 400 | 404 | 502
  body: unknown
}

/** Says what failed upstream without naming a URL or a credential: the error's name, a status, a network code. */
export function describeUpstreamFailure(err: unknown): string {
  if (!(err instanceof Error)) return 'unknown'
  const status = (err as { status?: unknown }).status
  const cause = (err as { cause?: unknown }).cause
  const code = (cause as { code?: unknown } | undefined)?.code ?? (err as { code?: unknown }).code
  return [err.name, typeof status === 'number' ? `status ${status}` : '', typeof code === 'string' ? code : '']
    .filter((part) => part !== '')
    .join(' ')
}

export type UpstreamLog = (where: string, failure: string) => void

const QUERY_MIN = 2
const QUERY_MAX = 120

/** `GET /people/search?q=`: the people named so, or the ones a misspelling most likely means with the name they were found under. */
export async function peopleSearchAnswer(client: TmdbClient, rawQuery: string | undefined, log?: UpstreamLog): Promise<RouteAnswer> {
  const q = (rawQuery ?? '').trim()
  if (q.length < QUERY_MIN || q.length > QUERY_MAX) return { status: 400, body: { error: 'q must be 2 to 120 characters' } }
  try {
    const people = await client.searchPeople(q)
    if (people.length > 0) return { status: 200, body: { people } }
    // Nobody by that spelling. A failure here is still "nobody".
    const closest = await closestPeople(client, q).catch((err: unknown) => {
      log?.('closest people', describeUpstreamFailure(err))
      return null
    })
    return { status: 200, body: closest ? { people: closest.people, corrected: closest.corrected } : { people } }
  } catch (err) {
    log?.('people search', describeUpstreamFailure(err))
    return { status: 502, body: { error: 'lookup failed' } }
  }
}

/** `GET /people/:tmdbId`: the person, their credits with IMDb ids, and whether some could not be mapped in time. */
export async function personAnswer(client: TmdbClient, raw: string, log?: UpstreamLog): Promise<RouteAnswer> {
  const tmdbId = /^\d{1,16}$/.test(raw) ? Number(raw) : Number.NaN
  if (!Number.isSafeInteger(tmdbId) || tmdbId <= 0) return { status: 400, body: { error: 'tmdbId must be a positive whole number' } }
  try {
    const details = await client.person(tmdbId)
    if (!details) return { status: 404, body: { error: 'person not found' } }
    const { credits, partial } = await mapCreditsToImdb(client, details.credits)
    return {
      status: 200,
      body: {
        person: {
          tmdbId: details.tmdbId,
          name: details.name,
          photo: details.photo,
          knownFor: details.knownFor,
          biography: details.biography,
        },
        credits: credits.map((credit) => ({
          type: credit.type,
          imdbId: credit.imdbId,
          tmdbId: credit.tmdbId,
          name: credit.name,
          year: credit.year,
          poster: credit.poster,
          roles: credit.roles,
          character: credit.character,
          popularity: credit.popularity,
        })),
        partial,
      },
    }
  } catch (err) {
    log?.('person', describeUpstreamFailure(err))
    return { status: 502, body: { error: 'lookup failed' } }
  }
}

/**
 * `GET /lookup?source=&id=`: a pasted IMDb or TVDB link, as the title or
 * person it names. A title is answered with its IMDb id, the id every addon
 * understands; one without an IMDb id is not found, since the apps could
 * neither open nor play it.
 */
export async function lookupAnswer(
  client: TmdbClient,
  source: string | undefined,
  rawId: string | undefined,
  log?: UpstreamLog,
): Promise<RouteAnswer> {
  const id = rawId ?? ''
  if (source !== 'tvdb' && source !== 'imdb') return { status: 400, body: { error: 'source must be tvdb or imdb' } }
  if (source === 'tvdb' && !/^\d{1,10}$/.test(id)) return { status: 400, body: { error: 'a tvdb id is digits only' } }
  if (source === 'imdb' && !/^(?:tt|nm)\d{1,10}$/.test(id)) {
    return { status: 400, body: { error: 'an imdb id is tt or nm followed by digits' } }
  }
  try {
    const found = await client.find(source, id)
    if (source === 'imdb' && id.startsWith('nm')) {
      const person = found.people[0]
      if (!person) return { status: 404, body: { error: 'not found' } }
      return { status: 200, body: { kind: 'person', tmdbId: person.tmdbId, name: person.name, photo: person.photo } }
    }
    // TVDB numbers series first; an IMDb title id names one or the other.
    const title = source === 'tvdb' ? (found.series[0] ?? found.movies[0]) : (found.movies[0] ?? found.series[0])
    if (!title) return { status: 404, body: { error: 'not found' } }
    const imdbId = source === 'imdb' ? id : await client.imdbIdFor(title.type === 'movie' ? 'movie' : 'tv', title.tmdbId)
    if (!imdbId) return { status: 404, body: { error: 'not found' } }
    return { status: 200, body: { kind: 'title', type: title.type, id: imdbId, name: title.name, year: title.year, poster: title.poster } }
  } catch (err) {
    log?.('id lookup', describeUpstreamFailure(err))
    return { status: 502, body: { error: 'lookup failed' } }
  }
}
