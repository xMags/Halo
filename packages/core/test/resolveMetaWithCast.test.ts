import assert from 'node:assert/strict'
import test from 'node:test'
import type { Manifest } from '../src/index'
import { resolveMetaWithCast, sanitizeCast, type ResolvableAddon, type ResolveContext } from '../src/addon/resolve'

const cinemeta: ResolvableAddon = {
  id: 'a1',
  transportUrl: 'https://cinemeta.test/manifest.json',
  manifest: {
    id: 'com.linvo.cinemeta',
    version: '1.0.0',
    name: 'Cinemeta',
    resources: ['catalog', 'meta'],
    types: ['movie', 'series'],
    idPrefixes: ['tt'],
    catalogs: [],
  } as Manifest,
}

const tmdb: ResolvableAddon = {
  id: 'a2',
  transportUrl: 'https://tmdb.test/manifest.json',
  manifest: {
    id: 'tmdb-addon',
    version: '3.1.7',
    name: 'The Movie Database Addon',
    resources: ['catalog', 'meta'],
    types: ['movie', 'series'],
    idPrefixes: ['tmdb:'],
    catalogs: [],
  } as Manifest,
}

const pictured = [
  { name: 'Ahaan Panday', character: 'Krish Kapoor', photo: 'https://image.tmdb.org/a.jpg' },
  { name: 'Aneet Padda', character: 'Vaani Batra', photo: 'https://image.tmdb.org/b.jpg' },
]

function context(routes: Record<string, unknown>): { ctx: ResolveContext; calls: string[] } {
  const calls: string[] = []
  const fetch = (async (input: RequestInfo | URL) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
    calls.push(url)
    if (!(url in routes)) return new Response('not found', { status: 404 })
    return new Response(JSON.stringify(routes[url]), { headers: { 'content-type': 'application/json' } })
  }) as typeof globalThis.fetch
  return { ctx: { fetch, timeoutMs: 2_000 }, calls }
}

test('a names-only meta gains the TMDB addon cast with photos', async () => {
  const { ctx } = context({
    'https://cinemeta.test/meta/movie/tt28037987.json': {
      meta: { id: 'tt28037987', type: 'movie', name: 'Saiyaara', cast: ['Ahaan Panday'], awards: '40 wins' },
    },
    'https://tmdb.test/meta/movie/tt28037987.json': {
      meta: { id: 'tmdb:1', type: 'movie', name: 'Saiyaara', app_extras: { cast: pictured } },
    },
  })
  const res = await resolveMetaWithCast([cinemeta, tmdb], 'movie', 'tt28037987', ctx)
  assert.equal(res?.meta.id, 'tt28037987', 'the primary addon still answers the title')
  assert.equal(res?.meta.awards, '40 wins')
  assert.deepEqual(res?.meta.app_extras?.cast, pictured)
})

test('a failing or missing TMDB addon leaves the meta as it was', async () => {
  const meta = { id: 'tt1', type: 'movie', name: 'X', cast: ['A'] }
  const { ctx } = context({ 'https://cinemeta.test/meta/movie/tt1.json': { meta } })
  assert.deepEqual((await resolveMetaWithCast([cinemeta, tmdb], 'movie', 'tt1', ctx))?.meta, meta)
  assert.deepEqual((await resolveMetaWithCast([cinemeta], 'movie', 'tt1', ctx))?.meta, meta)
})

test('only IMDb ids are sent to the TMDB addon for a cast', async () => {
  const { ctx, calls } = context({
    'https://tmdb.test/meta/movie/tmdb%3A5.json': { meta: { id: 'tmdb:5', type: 'movie', name: 'Y' } },
  })
  await resolveMetaWithCast([cinemeta, tmdb], 'movie', 'tmdb:5', ctx)
  assert.equal(calls.filter((url) => url.startsWith('https://tmdb.test/')).length, 1, 'just its own meta request')
})

test('cast from an addon is bounded and only keeps https photos', () => {
  assert.deepEqual(
    sanitizeCast([
      { name: '  Ahaan Panday ', character: 'Krish', photo: 'https://img.test/a.jpg' },
      { name: 'No Photo', photo: 'http://img.test/plain.jpg' },
      { name: '', photo: 'https://img.test/c.jpg' },
      { name: 'x'.repeat(201) },
      'not a person',
    ]),
    [
      { name: 'Ahaan Panday', character: 'Krish', photo: 'https://img.test/a.jpg' },
      { name: 'No Photo' },
    ],
  )
  assert.equal(sanitizeCast([{ name: 'Nobody pictured' }]), null)
  assert.equal(sanitizeCast('nope'), null)
})
