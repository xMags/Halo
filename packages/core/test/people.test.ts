import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { lookupAnswer, peopleSearchAnswer, personAnswer } from '../src/people/answers'
import { allowedDistance, closestPeople, closestPeopleWords, editDistance } from '../src/people/closest'
import { readIdLookup, readPeopleSearch, readPerson, type PersonCredit, type PersonSummary } from '../src/people/readers'
import {
  bestTitleTier,
  choosePeopleRow,
  creditLine,
  creditRoleLabel,
  creditTitles,
  exactNamePerson,
  filmography,
  filmographyFilters,
  personRowLine,
  type FilmographyFilter,
} from '../src/people/rows'
import { createTmdbClient, endAtSentence, mapCreditsToImdb, parseTmdbCredential, selectCredits } from '../src/people/tmdb'

/* The shared vectors (test/vectors/people.json); see their `about` line. */
const vectors = JSON.parse(readFileSync(new URL('./vectors/people.json', import.meta.url), 'utf8')) as {
  peopleRow: Array<{ query: string; bestTitleTier: number | null; people: PersonSummary[]; ids: number[] }>
  bestTitleTier: Array<{ names: string[]; query: string; tier: number | null }>
  exactNamePerson: Array<{ people: PersonSummary[]; name: string; id: number | null }>
  creditTitles: { credits: PersonCredit[]; titles: unknown[] }
  personRowLine: Array<Pick<PersonSummary, 'knownFor' | 'knownForTitles'> & { line: string }>
  filmography: {
    credits: PersonCredit[]
    byFilter: Record<FilmographyFilter, string[]>
    filters: FilmographyFilter[]
    directorOnlyFilters: FilmographyFilter[]
    creditLines: Array<{ index: number; line: string }>
    roleLabels: Array<{ roles: PersonCredit['roles']; label: string }>
  }
  credentials: Array<{ pasted: string; kind: 'apiKey' | 'readToken' | null; value?: string }>
  closestWords: Array<{ query: string; words: string[] }>
  editDistance: Array<{ a: string; b: string; limit: number; distance: number }>
  allowedDistance: Array<{ length: number; allowed: number }>
  closestPeople: Array<{ query: string; answers: Record<string, PersonSummary[]>; corrected: string | null; ids: number[] }>
  endAtSentence: Array<{ text: string; max: number; cut: string }>
}

test('vectors: the people row', () => {
  for (const row of vectors.peopleRow) {
    assert.deepEqual(choosePeopleRow(row.people, row.query, row.bestTitleTier).map((p) => p.tmdbId), row.ids, `${row.query} / ${row.bestTitleTier}`)
  }
  for (const row of vectors.bestTitleTier) {
    assert.equal(bestTitleTier(row.names.map((name) => ({ name })), row.query, 2026), row.tier, row.query)
  }
  for (const row of vectors.exactNamePerson) assert.equal(exactNamePerson(row.people, row.name)?.tmdbId ?? null, row.id, row.name)
  assert.deepEqual(creditTitles(vectors.creditTitles.credits), vectors.creditTitles.titles)
  for (const row of vectors.personRowLine) assert.equal(personRowLine(row), row.line)
})

test('vectors: the filmography', () => {
  const { credits, byFilter, filters, directorOnlyFilters, creditLines, roleLabels } = vectors.filmography
  for (const [filter, ids] of Object.entries(byFilter)) {
    assert.deepEqual(filmography(credits, filter as FilmographyFilter).map((c) => c.imdbId), ids, filter)
  }
  assert.deepEqual(filmographyFilters(credits), filters)
  assert.deepEqual(filmographyFilters([credits[0]!]), directorOnlyFilters)
  assert.deepEqual(filmographyFilters([]), ['all'])
  for (const row of creditLines) assert.equal(creditLine(credits[row.index]!), row.line)
  for (const row of roleLabels) assert.equal(creditRoleLabel(row.roles), row.label)
})

test('vectors: TMDB credentials by shape', () => {
  for (const row of vectors.credentials) {
    const parsed = parseTmdbCredential(row.pasted)
    assert.equal(parsed?.kind ?? null, row.kind, row.pasted.slice(0, 40))
    if (row.value) assert.equal(parsed?.value, row.value)
  }
})

test('vectors: misspelled names', async () => {
  for (const row of vectors.closestWords) assert.deepEqual(closestPeopleWords(row.query), row.words, row.query)
  for (const row of vectors.editDistance) assert.equal(editDistance(row.a, row.b, row.limit), row.distance)
  for (const row of vectors.allowedDistance) assert.equal(allowedDistance(row.length), row.allowed)
  for (const row of vectors.closestPeople) {
    const closest = await closestPeople({ searchPeople: async (word) => row.answers[word] ?? [] }, row.query)
    assert.equal(closest?.corrected ?? null, row.corrected, row.query)
    assert.deepEqual(closest?.people.map((p) => p.tmdbId) ?? [], row.ids, row.query)
  }
  for (const row of vectors.endAtSentence) assert.equal(endAtSentence(row.text, row.max), row.cut)
})

test('credits keep films and series, drop appearances and talk shows, merge roles, most popular first', () => {
  const credits = selectCredits({
    cast: [
      { media_type: 'movie', id: 1, title: 'Tenet', release_date: '2020-08-22', popularity: 40, character: 'Cameo', poster_path: '/tenet.jpg' },
      { media_type: 'tv', id: 2, name: 'The Talk Show', first_air_date: '2010-01-01', popularity: 90, genre_ids: [10767] },
      { media_type: 'movie', id: 3, title: 'A Documentary', popularity: 80, character: 'Himself - Interviewee' },
      { media_type: 'person', id: 4, name: 'Not a title', popularity: 99 },
    ],
    crew: [
      { media_type: 'movie', id: 1, title: 'Tenet', job: 'Director', department: 'Directing', popularity: 40 },
      { media_type: 'movie', id: 1, title: 'Tenet', job: 'Screenplay', department: 'Writing', popularity: 40 },
      { media_type: 'movie', id: 5, title: 'Interstellar', job: 'Director', department: 'Directing', popularity: 60, release_date: '2014' },
      { media_type: 'tv', id: 6, name: 'Westworld', job: 'Writer', department: 'Writing', popularity: 50, genre_ids: [18] },
    ],
  })
  assert.deepEqual(credits.map((c) => c.name), ['Interstellar', 'Westworld', 'Tenet'])
  assert.deepEqual(credits[2]!.roles, ['cast', 'director', 'writer'])
  assert.equal(credits[2]!.character, 'Cameo')
  assert.equal(credits[2]!.year, '2020')
  assert.equal(credits[0]!.year, null)
  assert.equal(credits[1]!.type, 'series')
  assert.equal(credits[2]!.poster, 'https://image.tmdb.org/t/p/w342/tenet.jpg')
})

/** A fake TMDB answering by path, recording what was asked. */
function fakeTmdb(routes: Record<string, unknown>, status: Record<string, number> = {}) {
  const asked: Array<{ url: URL; headers: Record<string, string> }> = []
  const fetch = (async (input: string | URL | Request, init?: RequestInit) => {
    const url = new URL(String(input))
    asked.push({ url, headers: (init?.headers ?? {}) as Record<string, string> })
    const code = status[url.pathname]
    if (code) return new Response('{}', { status: code })
    const body = routes[url.pathname]
    if (body === undefined) return new Response('{}', { status: 404 })
    return new Response(JSON.stringify(body), { status: 200, headers: { 'content-type': 'application/json' } })
  }) as typeof globalThis.fetch
  return { fetch, asked }
}

const nolanSearch = {
  results: [
    {
      id: 525,
      name: ' Christopher Nolan ',
      known_for_department: 'Directing',
      profile_path: '/xuAIuYSmsUzKlUMBFGVZaWsY3DZ.jpg',
      popularity: 12,
      known_for: [{ title: 'Inception' }, { name: 'Westworld' }, { title: 'Interstellar' }, { title: 'Tenet' }],
    },
    { id: 8, name: 'Someone', known_for_department: 'Lighting', profile_path: '../evil.jpg' },
    ...Array.from({ length: 10 }, (_, i) => ({ id: 100 + i, name: `Person ${i}` })),
  ],
}

test('a read token goes as a Bearer header and an API key as api_key; a search is capped at eight', async () => {
  const token = fakeTmdb({ '/3/search/person': nolanSearch })
  const client = createTmdbClient({ credential: { kind: 'readToken', value: 'tok.en.value' }, fetch: token.fetch })
  const people = await client.searchPeople('nolan')
  assert.equal(people.length, 8)
  assert.equal(people[0]!.name, 'Christopher Nolan')
  assert.equal(people[0]!.photo, 'https://image.tmdb.org/t/p/w185/xuAIuYSmsUzKlUMBFGVZaWsY3DZ.jpg')
  assert.deepEqual(people[0]!.knownForTitles, ['Inception', 'Westworld', 'Interstellar'])
  assert.equal(people[1]!.photo, null)
  assert.equal(people[1]!.knownFor, null)
  assert.equal(token.asked[0]!.headers.authorization, 'Bearer tok.en.value')
  assert.equal(token.asked[0]!.url.searchParams.get('include_adult'), 'false')
  assert.equal(token.asked[0]!.url.searchParams.get('api_key'), null)
  // Cached: the same query again asks nothing.
  await client.searchPeople(' Nolan ')
  assert.equal(token.asked.length, 1)

  const key = fakeTmdb({ '/3/search/person': nolanSearch })
  await createTmdbClient({ credential: { kind: 'apiKey', value: '0123456789abcdef0123456789abcdef' }, fetch: key.fetch }).searchPeople('nolan')
  assert.equal(key.asked[0]!.url.searchParams.get('api_key'), '0123456789abcdef0123456789abcdef')
  assert.equal(key.asked[0]!.headers.authorization, undefined)
})

test('a refused credential is reported and the error never carries the key', async () => {
  const tmdb = fakeTmdb({}, { '/3/search/person': 401 })
  let refused = 0
  const client = createTmdbClient({
    credential: { kind: 'apiKey', value: '0123456789abcdef0123456789abcdef' },
    fetch: tmdb.fetch,
    onRefused: () => (refused += 1),
  })
  await assert.rejects(client.searchPeople('nolan'), (err: Error) => {
    assert.ok(!err.message.includes('0123456789abcdef'))
    return true
  })
  assert.equal(refused, 1)
})

test('person, lookup and search answers have the server contract', async () => {
  const tmdb = fakeTmdb({
    '/3/search/person': { results: [] },
    '/3/person/525': {
      id: 525,
      name: 'Christopher Nolan',
      biography: '  First one. Second one. ',
      known_for_department: 'Directing',
      combined_credits: { cast: [], crew: [{ media_type: 'movie', id: 157336, title: 'Interstellar', job: 'Director', release_date: '2014-11-05', popularity: 60 }] },
    },
    '/3/movie/157336/external_ids': { imdb_id: 'tt0816692' },
    '/3/find/tt0816692': { movie_results: [{ id: 157336, title: 'Interstellar', release_date: '2014-11-05' }], tv_results: [], person_results: [] },
    '/3/find/nm0634240': { movie_results: [], tv_results: [], person_results: [{ id: 525, name: 'Christopher Nolan' }] },
    '/3/find/121361': { movie_results: [], tv_results: [{ id: 1399, name: 'Game of Thrones', first_air_date: '2011-04-17' }], person_results: [] },
    '/3/tv/1399/external_ids': { imdb_id: 'tt0944947' },
  })
  const client = createTmdbClient({ credential: { kind: 'readToken', value: 't' }, fetch: tmdb.fetch })

  assert.deepEqual(await peopleSearchAnswer(client, 'x'), { status: 400, body: { error: 'q must be 2 to 120 characters' } })
  assert.deepEqual(await peopleSearchAnswer(client, 'nobody here'), { status: 200, body: { people: [] } })

  const person = await personAnswer(client, '525')
  assert.equal(person.status, 200)
  const read = readPerson(person.body)
  assert.equal(read.person.biography, 'First one. Second one.')
  assert.deepEqual(read.credits.map((c) => [c.imdbId, c.roles]), [['tt0816692', ['director']]])
  assert.equal(read.partial, false)
  assert.equal((await personAnswer(client, '0')).status, 400)
  assert.equal((await personAnswer(client, '12abc')).status, 400)
  assert.deepEqual(await personAnswer(client, '999'), { status: 404, body: { error: 'person not found' } })

  assert.deepEqual(readIdLookup((await lookupAnswer(client, 'imdb', 'tt0816692')).body), {
    kind: 'title',
    name: 'Interstellar',
    type: 'movie',
    id: 'tt0816692',
    year: '2014',
    poster: null,
  })
  assert.deepEqual(readIdLookup((await lookupAnswer(client, 'imdb', 'nm0634240')).body), {
    kind: 'person',
    name: 'Christopher Nolan',
    tmdbId: 525,
    photo: null,
  })
  const tvdb = readIdLookup((await lookupAnswer(client, 'tvdb', '121361')).body)
  assert.equal(tvdb.kind === 'title' && tvdb.id, 'tt0944947')
  assert.equal((await lookupAnswer(client, 'tvdb', 'abc')).status, 400)
  assert.equal((await lookupAnswer(client, 'imdb', 'xx1')).status, 400)
  assert.equal((await lookupAnswer(client, 'other', '1')).status, 400)
  assert.deepEqual(await lookupAnswer(client, 'imdb', 'tt0000001'), { status: 404, body: { error: 'not found' } })
})

test('an upstream failure is a 502 lookup failed', async () => {
  const tmdb = fakeTmdb({}, { '/3/search/person': 500 })
  const client = createTmdbClient({ credential: { kind: 'readToken', value: 't' }, fetch: tmdb.fetch })
  const logged: string[] = []
  assert.deepEqual(await peopleSearchAnswer(client, 'nolan', (where, failure) => logged.push(`${where}: ${failure}`)), {
    status: 502,
    body: { error: 'lookup failed' },
  })
  assert.deepEqual(logged, ['people search: TmdbRequestError status 500'])
})

test('credit mapping stops at its budget and says the list is partial', async () => {
  const candidates = Array.from({ length: 4 }, (_, i) => ({
    type: 'movie' as const,
    tmdbId: i + 1,
    name: `Film ${i}`,
    year: null,
    poster: null,
    roles: ['cast' as const],
    character: null,
    popularity: 0,
  }))
  const never = new Promise<string | null>(() => undefined)
  const { credits, partial } = await mapCreditsToImdb(
    { imdbIdFor: async (_kind, tmdbId) => (tmdbId === 1 ? 'tt1' : tmdbId === 2 ? null : never) },
    candidates,
    { budgetMs: 30, concurrency: 2 },
  )
  assert.deepEqual(credits.map((c) => c.imdbId), ['tt1'])
  assert.equal(partial, true)
})

test('the readers keep only safe fields', () => {
  const search = readPeopleSearch({
    people: [
      { tmdbId: 137427, name: ' Denis Villeneuve ', knownFor: 'Directing', photo: 'https://image.tmdb.org/t/p/w185/dv.jpg', popularity: 12.5 },
      { tmdbId: 137427, name: 'Again' },
      { tmdbId: 3, name: 'Elsewhere', photo: 'https://evil.example/a.jpg', knownFor: 'Lighting', popularity: -1 },
      { tmdbId: 0, name: 'Zero' },
      { tmdbId: 4, name: 'Line\u0007Break', knownForTitles: ['', 'Real', 7] },
    ],
    corrected: 'Somebody Else',
  })
  assert.deepEqual(search.people.map((p) => p.tmdbId), [137427, 3, 4])
  assert.equal(search.people[0]!.name, 'Denis Villeneuve')
  assert.equal(search.people[1]!.photo, null)
  assert.equal(search.people[1]!.knownFor, null)
  assert.equal(search.people[1]!.popularity, 0)
  assert.equal(search.people[2]!.name, 'Line Break')
  assert.deepEqual(search.people[2]!.knownForTitles, ['Real'])
  assert.equal(search.corrected, null)
  assert.equal(readPeopleSearch({ people: [{ tmdbId: 525, name: 'Christopher Nolan' }], corrected: 'Christopher Nolan' }).corrected, 'Christopher Nolan')
  assert.deepEqual(readPeopleSearch({ people: 'none' }).people, [])
  assert.throws(() => readPeopleSearch('people'))
  assert.throws(() => readPerson({ credits: [] }))
  assert.throws(() => readPerson({ person: { tmdbId: 0, name: 'Zero' } }))
  assert.equal(readPerson({ person: { tmdbId: 1, name: 'A', biography: 'Line one.\r\nLine two .' } }).person.biography, 'Line one.\nLine two .')
  assert.equal(readPerson({ person: { tmdbId: 1, name: 'A', biography: 'b'.repeat(3000) } }).person.biography?.length, 2000)
  for (const body of [{ kind: 'title', name: 'A', type: 'channel', id: 'tt1' }, { kind: 'person', name: 'A' }, { kind: 'other', name: 'A' }, { kind: 'title' }]) {
    assert.throws(() => readIdLookup(body))
  }
})
