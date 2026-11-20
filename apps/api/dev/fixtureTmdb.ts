/**
 * A canned TMDB for the development server, answering the few API paths the
 * people and lookup routes ask, behind the same injectable `safeFetch` the
 * canned addons use. Enough to exercise each path a client meets:
 *
 * - `christopher nolan` finds him (with a photo) and a namesake without one;
 *   `nolan` finds him and Jonathan Nolan; `crystophar nolan` finds nobody by
 *   that spelling, so the server corrects it word by word.
 * - `keanu reeves` finds one person whose credits include The Matrix.
 * - person 525 has credits mapped to the fixture catalog's IMDb ids, one of
 *   them unmappable (it is left out); person 999999 makes TMDB fail (502).
 * - `/find` knows tt0133093 (a film), tt0944947 (a series, also as TVDB
 *   121361) and nm0634240 (a person); anything else is not found.
 *
 * Artwork paths are real TMDB ones where known, so cards show pictures; the
 * rest have none, which is a state the apps must show too.
 */

export const FIXTURE_TMDB_TOKEN = 'fixture-tmdb-read-token-not-a-real-one'

const NOLAN = {
  id: 525,
  name: 'Christopher Nolan',
  known_for_department: 'Directing',
  profile_path: '/xuAIuYSmsUzKlUMBFGVZaWsY3DZ.jpg',
  popularity: 12.4,
  known_for: [{ title: 'Inception' }, { title: 'The Dark Knight' }, { title: 'Interstellar' }],
}
const JONATHAN = {
  id: 527,
  name: 'Jonathan Nolan',
  known_for_department: 'Writing',
  profile_path: null,
  popularity: 6.1,
  known_for: [{ title: 'The Dark Knight' }, { name: 'Westworld' }],
}
const NAMESAKE = { id: 900001, name: 'Christopher Nolan', known_for_department: 'Acting', profile_path: null, popularity: 0.6, known_for: [] }
const KEANU = {
  id: 6384,
  name: 'Keanu Reeves',
  known_for_department: 'Acting',
  profile_path: null,
  popularity: 30.2,
  known_for: [{ title: 'The Matrix' }, { title: 'John Wick' }],
}
const DICAPRIO = {
  id: 6193,
  name: 'Leonardo DiCaprio',
  known_for_department: 'Acting',
  profile_path: null,
  popularity: 25,
  known_for: [{ title: 'Inception' }],
}

const SEARCHES: Record<string, unknown[]> = {
  'christopher nolan': [NOLAN, NAMESAKE],
  nolan: [NOLAN, JONATHAN],
  'keanu reeves': [KEANU],
  keanu: [KEANU],
  'leonardo dicaprio': [DICAPRIO],
}

const PEOPLE: Record<number, unknown> = {
  525: {
    ...NOLAN,
    biography:
      'Christopher Edward Nolan is a British and American filmmaker. Known for his Hollywood blockbusters with complex storytelling, he is considered a leading filmmaker of the 21st century.\n\nHis films have grossed over $6 billion worldwide.',
    combined_credits: {
      cast: [{ media_type: 'movie', id: 99001, title: 'A Cameo Nobody Mapped', release_date: '2001-01-01', popularity: 1, character: 'Man in Crowd' }],
      crew: [
        { media_type: 'movie', id: 27205, title: 'Inception', job: 'Director', department: 'Directing', release_date: '2010-07-15', popularity: 90 },
        { media_type: 'movie', id: 27205, title: 'Inception', job: 'Writer', department: 'Writing', release_date: '2010-07-15', popularity: 90 },
        { media_type: 'movie', id: 155, title: 'The Dark Knight', job: 'Director', department: 'Directing', release_date: '2008-07-16', popularity: 80 },
      ],
    },
  },
  6384: {
    ...KEANU,
    biography: 'Keanu Charles Reeves is a Canadian actor.',
    combined_credits: {
      cast: [{ media_type: 'movie', id: 603, title: 'The Matrix', release_date: '1999-03-31', popularity: 70, character: 'Neo' }],
      crew: [],
    },
  },
  6193: {
    ...DICAPRIO,
    biography: '',
    combined_credits: { cast: [{ media_type: 'movie', id: 27205, title: 'Inception', release_date: '2010-07-15', popularity: 90, character: 'Cobb' }], crew: [] },
  },
}

const IMDB_IDS: Record<string, string> = {
  'movie:27205': 'tt1375666',
  'movie:155': 'tt0468569',
  'movie:603': 'tt0133093',
  'tv:1399': 'tt0944947',
}

const FINDS: Record<string, unknown> = {
  'imdb_id:tt0133093': { movie_results: [{ id: 603, title: 'The Matrix', release_date: '1999-03-31', popularity: 70 }], tv_results: [], person_results: [] },
  'imdb_id:tt0944947': { movie_results: [], tv_results: [{ id: 1399, name: 'Game of Thrones', first_air_date: '2011-04-17' }], person_results: [] },
  'tvdb_id:121361': { movie_results: [], tv_results: [{ id: 1399, name: 'Game of Thrones', first_air_date: '2011-04-17' }], person_results: [] },
  'imdb_id:nm0634240': { movie_results: [], tv_results: [], person_results: [{ id: 525, name: 'Christopher Nolan', profile_path: NOLAN.profile_path }] },
}

const TMDB_ORIGIN = 'https://api.themoviedb.org'

/** Whether a URL is one this stands in for. */
export function isFixtureTmdbUrl(href: string): boolean {
  return href.startsWith(`${TMDB_ORIGIN}/`)
}

/** Answers a TMDB request as the canned data says; a wrong token is a 401, as TMDB answers one. */
export function fixtureTmdbFetch(href: string, init?: RequestInit): Response {
  const headers = new Headers(init?.headers)
  if (headers.get('authorization') !== `Bearer ${FIXTURE_TMDB_TOKEN}`) return json({ status_message: 'Invalid API key' }, 401)
  const url = new URL(href)
  const path = url.pathname
  if (path === '/3/search/person') {
    const query = (url.searchParams.get('query') ?? '').trim().toLowerCase()
    return json({ results: SEARCHES[query] ?? [] })
  }
  const person = /^\/3\/person\/(\d+)$/.exec(path)
  if (person) {
    if (person[1] === '999999') return json({ status_message: 'Internal error' }, 500)
    const body = PEOPLE[Number(person[1])]
    return body ? json(body) : json({ status_message: 'Not found' }, 404)
  }
  const external = /^\/3\/(movie|tv)\/(\d+)\/external_ids$/.exec(path)
  if (external) {
    const imdbId = IMDB_IDS[`${external[1]}:${external[2]}`]
    return json({ imdb_id: imdbId ?? null })
  }
  const find = /^\/3\/find\/([A-Za-z0-9]+)$/.exec(path)
  if (find) {
    const body = FINDS[`${url.searchParams.get('external_source')}:${find[1]}`]
    return json(body ?? { movie_results: [], tv_results: [], person_results: [] })
  }
  return json({ status_message: 'Not found' }, 404)
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })
}
