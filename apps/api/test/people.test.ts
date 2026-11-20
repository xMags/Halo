import { describe, expect, it, vi } from 'vitest'
import { loadTmdbReadToken } from '../src/env'
import { authed, makeApp, userToken } from './helpers'

// People search, person pages, IMDb/TVDB lookups and the capabilities
// answer, with the contract the MagnetoFX server answers them with. TMDB is
// never called: a stub fetch serves TMDB-shaped JSON.

const TOKEN = 'test-tmdb-read-token-0123456789.abc_DEF-ghi'

type FetchInput = Parameters<typeof globalThis.fetch>[0]

function hrefOf(input: FetchInput): string {
  return typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
}

function tmdbStub(routes: Record<string, unknown>, status: Record<string, number> = {}) {
  const calls: Array<{ url: string; authorization: string | null }> = []
  const fetch = (async (input: FetchInput, init?: RequestInit) => {
    const href = hrefOf(input)
    calls.push({ url: href, authorization: new Headers(init?.headers).get('authorization') })
    const path = new URL(href).pathname
    const code = status[path]
    if (code) return new Response('{}', { status: code })
    const body = routes[path]
    if (body === undefined) return new Response('{}', { status: 404 })
    return new Response(JSON.stringify(body), { status: 200, headers: { 'content-type': 'application/json' } })
  }) as typeof globalThis.fetch
  return { fetch, calls }
}

const searchBody = {
  results: [
    {
      id: 6193,
      name: ' Leonardo DiCaprio ',
      known_for_department: 'Acting',
      profile_path: '/leo.jpg',
      popularity: 55.5,
      known_for: [{ title: 'Inception' }, { title: 'Titanic' }, { name: 'A Show' }, { title: 'Fourth' }],
    },
    { id: 8, name: '', known_for_department: 'Acting' },
    ...Array.from({ length: 10 }, (_, i) => ({ id: 100 + i, name: `Extra ${i}`, known_for_department: 'Writing', popularity: 1 })),
  ],
}

type TestApp = ReturnType<typeof makeApp>['app']

function get(app: TestApp, token: string, path: string): Promise<Response> {
  return Promise.resolve(app.request(path, authed(token)))
}

describe('people without a TMDB token', () => {
  it('answers 404 not_available on every route, and capabilities says so', async () => {
    const { app } = makeApp()
    const token = await userToken('bob')
    for (const path of ['/people/search?q=nolan', '/people/525', '/lookup?source=imdb&id=tt1375666']) {
      const res = await get(app, token, path)
      expect(res.status, path).toBe(404)
      expect(await res.json()).toEqual({ error: 'not_available' })
    }
    expect(await (await get(app, token, '/capabilities')).json()).toEqual({
      people: false,
      lookup: false,
      magnets: { enabled: false, cachedOnly: true },
    })
  })
})

describe('capabilities', () => {
  it('names people and lookup when the token is set; magnets stay off', async () => {
    const { app } = makeApp({ tmdbReadToken: TOKEN })
    const res = await get(app, await userToken('bob'), '/capabilities')
    expect(res.status).toBe(200)
    expect(await res.json()).toEqual({ people: true, lookup: true, magnets: { enabled: false, cachedOnly: true } })
  })

  it('needs a session', async () => {
    const { app } = makeApp({ tmdbReadToken: TOKEN })
    for (const path of ['/capabilities', '/people/search?q=leo', '/people/1', '/lookup?source=imdb&id=tt1']) {
      expect((await app.request(path)).status, path).toBe(401)
    }
  })

  it('offers no magnet routes', async () => {
    const { app } = makeApp({ tmdbReadToken: TOKEN })
    const token = await userToken('bob')
    const res = await app.request('/magnets', authed(token, { infoHash: 'c12fe1c06bba254a9dc9f519b335aa7c1367a88a' }, 'POST'))
    expect(res.status).toBe(404)
  })
})

describe('people search', () => {
  it('sends the token only as a Bearer header and never in a URL or answer', async () => {
    const stub = tmdbStub({ '/3/search/person': searchBody })
    const { app } = makeApp({ safeFetch: stub.fetch, tmdbReadToken: TOKEN })
    const res = await get(app, await userToken('bob'), '/people/search?q=leo')
    const text = await res.text()
    expect(res.status).toBe(200)
    expect(stub.calls).toHaveLength(1)
    expect(stub.calls[0]!.authorization).toBe(`Bearer ${TOKEN}`)
    for (const call of stub.calls) expect(call.url).not.toContain(TOKEN)
    expect(text).not.toContain(TOKEN)
    const url = new URL(stub.calls[0]!.url)
    expect(url.origin).toBe('https://api.themoviedb.org')
    expect(url.searchParams.get('query')).toBe('leo')
    expect(url.searchParams.get('include_adult')).toBe('false')
  })

  it('answers at most 8 people in the documented shape', async () => {
    const stub = tmdbStub({ '/3/search/person': searchBody })
    const { app } = makeApp({ safeFetch: stub.fetch, tmdbReadToken: TOKEN })
    const body = (await (await get(app, await userToken('bob'), '/people/search?q=%20%20leo%20')).json()) as { people: unknown[] }
    expect(body.people).toHaveLength(8)
    expect(body.people[0]).toEqual({
      tmdbId: 6193,
      name: 'Leonardo DiCaprio',
      knownFor: 'Acting',
      photo: 'https://image.tmdb.org/t/p/w185/leo.jpg',
      popularity: 55.5,
      knownForTitles: ['Inception', 'Titanic', 'A Show'],
    })
  })

  it('needs 2 to 120 characters after trimming', async () => {
    const { app } = makeApp({ safeFetch: tmdbStub({}).fetch, tmdbReadToken: TOKEN })
    const token = await userToken('bob')
    for (const q of ['', '%20a%20', 'x'.repeat(121)]) {
      const res = await get(app, token, `/people/search?q=${q}`)
      expect(res.status, q).toBe(400)
      expect(await res.json()).toEqual({ error: 'q must be 2 to 120 characters' })
    }
  })

  it('answers a misspelled name with the person it most likely means', async () => {
    const nolan = { id: 525, name: 'Christopher Nolan', popularity: 10 }
    const jonathan = { id: 527, name: 'Jonathan Nolan', popularity: 20 }
    const fetch = (async (input: FetchInput) => {
      const query = new URL(hrefOf(input)).searchParams.get('query')
      return Response.json({ results: query === 'nolan' ? [nolan, jonathan] : [] })
    }) as typeof globalThis.fetch
    const { app } = makeApp({ safeFetch: fetch, tmdbReadToken: TOKEN })
    const res = await get(app, await userToken('bob'), '/people/search?q=crystophar%20nolan')
    const body = (await res.json()) as { people: Array<{ tmdbId: number }>; corrected: string }
    expect(body.corrected).toBe('Christopher Nolan')
    expect(body.people.map((person) => person.tmdbId)).toEqual([525])
  })

  it('answers an upstream failure with 502 lookup failed, logging no URL', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    const { app } = makeApp({ safeFetch: tmdbStub({}, { '/3/search/person': 500 }).fetch, tmdbReadToken: TOKEN })
    const res = await get(app, await userToken('bob'), '/people/search?q=leo')
    expect(res.status).toBe(502)
    expect(await res.json()).toEqual({ error: 'lookup failed' })
    expect(warn).toHaveBeenCalledWith('[halo-upstream] people search: TmdbRequestError status 500')
    warn.mockRestore()
  })
})

describe('person pages', () => {
  const person = {
    id: 525,
    name: 'Christopher Nolan',
    biography: 'A filmmaker.',
    known_for_department: 'Directing',
    profile_path: '/cn.jpg',
    combined_credits: {
      cast: [],
      crew: [
        { media_type: 'movie', id: 27205, title: 'Inception', job: 'Director', department: 'Directing', release_date: '2010-07-15', popularity: 90 },
        { media_type: 'movie', id: 99, title: 'Unmapped', job: 'Director', popularity: 1 },
      ],
    },
  }

  it('answers the person and their credits by IMDb id', async () => {
    const stub = tmdbStub({
      '/3/person/525': person,
      '/3/movie/27205/external_ids': { imdb_id: 'tt1375666' },
      '/3/movie/99/external_ids': { imdb_id: null },
    })
    const { app } = makeApp({ safeFetch: stub.fetch, tmdbReadToken: TOKEN })
    const res = await get(app, await userToken('bob'), '/people/525')
    expect(res.status).toBe(200)
    expect(await res.json()).toEqual({
      person: {
        tmdbId: 525,
        name: 'Christopher Nolan',
        photo: 'https://image.tmdb.org/t/p/w185/cn.jpg',
        knownFor: 'Directing',
        biography: 'A filmmaker.',
      },
      credits: [
        {
          type: 'movie',
          imdbId: 'tt1375666',
          tmdbId: 27205,
          name: 'Inception',
          year: '2010',
          poster: null,
          roles: ['director'],
          character: null,
          popularity: 90,
        },
      ],
      partial: false,
    })
  })

  it('is 404 for nobody, 400 for a bad id and 502 when TMDB fails', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    const { app } = makeApp({ safeFetch: tmdbStub({}, { '/3/person/777': 503 }).fetch, tmdbReadToken: TOKEN })
    const token = await userToken('bob')
    const missing = await get(app, token, '/people/1')
    expect(missing.status).toBe(404)
    expect(await missing.json()).toEqual({ error: 'person not found' })
    for (const id of ['0', '-1', '1.5', 'abc', '12345678901234567']) {
      const res = await get(app, token, `/people/${id}`)
      expect(res.status, id).toBe(400)
      expect(await res.json()).toEqual({ error: 'tmdbId must be a positive whole number' })
    }
    const failed = await get(app, token, '/people/777')
    expect(failed.status).toBe(502)
    expect(await failed.json()).toEqual({ error: 'lookup failed' })
    warn.mockRestore()
  })
})

describe('id lookups', () => {
  const stub = () =>
    tmdbStub({
      '/3/find/121361': { tv_results: [{ id: 1399, name: 'Game of Thrones', first_air_date: '2011-04-17', poster_path: '/got.jpg' }] },
      '/3/tv/1399/external_ids': { imdb_id: 'tt0944947' },
      '/3/find/tt1375666': { movie_results: [{ id: 27205, title: 'Inception', release_date: '2010-07-15' }] },
      '/3/find/nm0634240': { person_results: [{ id: 525, name: 'Christopher Nolan', profile_path: '/cn.jpg' }] },
      '/3/find/tt0000001': { movie_results: [], tv_results: [], person_results: [] },
    })

  it('answers a TVDB id as the series by IMDb id, an IMDb title as itself and a person as the person', async () => {
    const { app } = makeApp({ safeFetch: stub().fetch, tmdbReadToken: TOKEN })
    const token = await userToken('bob')
    expect(await (await get(app, token, '/lookup?source=tvdb&id=121361')).json()).toEqual({
      kind: 'title',
      type: 'series',
      id: 'tt0944947',
      name: 'Game of Thrones',
      year: '2011',
      poster: 'https://image.tmdb.org/t/p/w342/got.jpg',
    })
    expect(await (await get(app, token, '/lookup?source=imdb&id=tt1375666')).json()).toEqual({
      kind: 'title',
      type: 'movie',
      id: 'tt1375666',
      name: 'Inception',
      year: '2010',
      poster: null,
    })
    expect(await (await get(app, token, '/lookup?source=imdb&id=nm0634240')).json()).toEqual({
      kind: 'person',
      tmdbId: 525,
      name: 'Christopher Nolan',
      photo: 'https://image.tmdb.org/t/p/w185/cn.jpg',
    })
    const missing = await get(app, token, '/lookup?source=imdb&id=tt0000001')
    expect(missing.status).toBe(404)
    expect(await missing.json()).toEqual({ error: 'not found' })
  })

  it('refuses a source or id of the wrong shape', async () => {
    const { app } = makeApp({ safeFetch: stub().fetch, tmdbReadToken: TOKEN })
    const token = await userToken('bob')
    const cases: Array<[string, string]> = [
      ['/lookup?source=tmdb&id=1', 'source must be tvdb or imdb'],
      ['/lookup?id=1', 'source must be tvdb or imdb'],
      ['/lookup?source=tvdb&id=12a', 'a tvdb id is digits only'],
      ['/lookup?source=tvdb&id=12345678901', 'a tvdb id is digits only'],
      ['/lookup?source=imdb&id=xx123', 'an imdb id is tt or nm followed by digits'],
      ['/lookup?source=imdb&id=tt', 'an imdb id is tt or nm followed by digits'],
    ]
    for (const [path, error] of cases) {
      const res = await get(app, token, path)
      expect(res.status, path).toBe(400)
      expect(await res.json()).toEqual({ error })
    }
  })

  it('answers an upstream failure with 502 lookup failed', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    const { app } = makeApp({ safeFetch: tmdbStub({}, { '/3/find/tt1375666': 500 }).fetch, tmdbReadToken: TOKEN })
    const res = await get(app, await userToken('bob'), '/lookup?source=imdb&id=tt1375666')
    expect(res.status).toBe(502)
    expect(await res.json()).toEqual({ error: 'lookup failed' })
    warn.mockRestore()
  })
})

describe('per-account rate limit', () => {
  it('counts each account on its own across people and lookup, and leaves capabilities alone', async () => {
    const stub = tmdbStub({ '/3/search/person': searchBody, '/3/find/tt1375666': { movie_results: [{ id: 1, title: 'A' }] } })
    const { app } = makeApp({ safeFetch: stub.fetch, tmdbReadToken: TOKEN })
    const bob = await userToken('bob')
    const amy = await userToken('amy')
    for (let i = 0; i < 59; i += 1) expect((await get(app, bob, '/people/search?q=leo')).status, `request ${i + 1}`).toBe(200)
    expect((await get(app, bob, '/lookup?source=imdb&id=tt1375666')).status).toBe(200)
    const limited = await get(app, bob, '/people/search?q=leo')
    expect(limited.status).toBe(429)
    const body = (await limited.json()) as { error: string; retryAfter: number }
    expect(body.error).toBe('rate_limited')
    expect(body.retryAfter).toBeGreaterThan(0)
    expect(limited.headers.get('retry-after')).toBe(String(body.retryAfter))
    expect((await get(app, amy, '/people/search?q=leo')).status).toBe(200)
    expect((await get(app, bob, '/capabilities')).status).toBe(200)
  })
})

describe('configuration', () => {
  it('TMDB_READ_TOKEN is optional and refused with a message that does not repeat it', () => {
    expect(loadTmdbReadToken(undefined)).toBeUndefined()
    expect(loadTmdbReadToken('  ')).toBeUndefined()
    expect(loadTmdbReadToken(` ${TOKEN} `)).toBe(TOKEN)
    for (const bad of ['short-token-123', 'has spaces in the token value', 'x'.repeat(1025), 'secret-with/slash-0123456789']) {
      let message = ''
      try {
        loadTmdbReadToken(bad)
      } catch (err) {
        message = (err as Error).message
      }
      expect(message).toMatch(/TMDB_READ_TOKEN/)
      expect(message).not.toContain(bad)
    }
  })
})
