import assert from 'node:assert/strict'
import test from 'node:test'
import { HaloApiError, type LibraryItem, type Manifest, type WatchState } from '../src/index'
import { DeviceBackend, DeviceDataError, type DeviceCollection, type DeviceStore } from '../src/device/deviceBackend'

/* ── Fixtures ──────────────────────────────────────────────────────────────── */

class MemoryStore implements DeviceStore {
  readonly documents = new Map<DeviceCollection, string>()
  /** Delay before each read resolves, to force overlapping operations to interleave. */
  readDelayMs = 0

  async read(collection: DeviceCollection): Promise<string | null> {
    const value = this.documents.get(collection) ?? null
    if (this.readDelayMs > 0) await new Promise((resolve) => setTimeout(resolve, this.readDelayMs))
    return value
  }

  async write(collection: DeviceCollection, contents: string): Promise<void> {
    this.documents.set(collection, contents)
  }
}

/** An addon that never answers: it settles only when the request is aborted, as a real fetch does. */
function hangUntilAborted(_url: URL, init?: RequestInit): Promise<Response> {
  return new Promise((_resolve, reject) => {
    const signal = init?.signal
    if (!signal) return
    if (signal.aborted) reject(signal.reason)
    else signal.addEventListener('abort', () => reject(signal.reason))
  })
}

type Route = unknown | ((url: URL, init?: RequestInit) => Response | Promise<Response>)

/** A fetch that answers from a table of exact URLs; anything else is a 404. Counts every request. */
function fakeFetch(routes: Record<string, Route>) {
  const calls: string[] = []
  const fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(typeof input === 'string' ? input : input instanceof URL ? input.href : input.url)
    calls.push(url.href)
    const route = routes[url.href]
    if (route === undefined) return new Response('not found', { status: 404 })
    if (typeof route === 'function') return route(url, init)
    return Response.json(route)
  }) as typeof globalThis.fetch
  return { fetch, calls }
}

function manifest(id: string, overrides: Partial<Manifest> = {}): Manifest {
  return { id, version: '1.0.0', name: id, resources: [], types: ['movie', 'series'], catalogs: [], ...overrides }
}

const CATALOG_ADDON = 'https://catalog.test/manifest.json'
const STREAM_A = 'https://stream-a.test/manifest.json'
const STREAM_B = 'https://stream-b.test/manifest.json'

const catalogManifest = manifest('catalog', {
  resources: ['catalog', 'meta'],
  catalogs: [{ type: 'movie', id: 'top', name: 'Top', extra: [{ name: 'search' }] }],
  idPrefixes: ['tt'],
})
const streamManifest = (id: string) => manifest(id, { resources: ['stream', 'subtitles'], idPrefixes: ['tt'] })

function makeBackend(routes: Record<string, Route>, opts: { timeoutMs?: number } = {}) {
  const store = new MemoryStore()
  const { fetch, calls } = fakeFetch({
    [CATALOG_ADDON]: catalogManifest,
    [STREAM_A]: streamManifest('stream-a'),
    [STREAM_B]: streamManifest('stream-b'),
    ...routes,
  })
  let clock = 1_000
  let ids = 0
  const backend = new DeviceBackend({
    fetch,
    store,
    now: () => ++clock,
    newId: () => `id-${++ids}`,
    ...(opts.timeoutMs !== undefined ? { timeoutMs: opts.timeoutMs } : {}),
  })
  return { backend, store, calls }
}

async function rejectsWithStatus(promise: Promise<unknown>, status: number): Promise<HaloApiError> {
  try {
    await promise
  } catch (err) {
    assert.ok(err instanceof HaloApiError, `expected HaloApiError, got ${String(err)}`)
    assert.equal(err.status, status)
    return err
  }
  assert.fail(`expected a ${status} rejection`)
}

function library(id: string, updatedAt: number, overrides: Partial<LibraryItem> = {}): LibraryItem {
  return { id, type: 'movie', name: id, addedAt: 1, updatedAt, ...overrides }
}

function watch(videoId: string, updatedAt: number, overrides: Partial<WatchState> = {}): WatchState {
  return { videoId, itemId: `movie:${videoId}`, positionSec: 10, durationSec: 100, watched: false, updatedAt, ...overrides }
}

/* ── Addons ────────────────────────────────────────────────────────────────── */

test('installing addons fetches each manifest once and lists them as the user’s own, in order', async () => {
  const { backend, calls } = makeBackend({})
  const installed = await backend.putAddons([CATALOG_ADDON, STREAM_A])
  assert.deepEqual(installed.map((a) => [a.id, a.transportUrl, a.position, a.manifest.id]), [
    ['id-1', CATALOG_ADDON, 0, 'catalog'],
    ['id-2', STREAM_A, 1, 'stream-a'],
  ])
  const listed = await backend.getAddons()
  assert.deepEqual(listed.global, [])
  assert.deepEqual(listed.user, installed)

  // Kept URLs keep their id and are not fetched again; dropped ones go; order follows the list.
  calls.length = 0
  const next = await backend.putAddons([STREAM_B, CATALOG_ADDON])
  assert.deepEqual(next.map((a) => [a.id, a.transportUrl, a.position]), [
    ['id-3', STREAM_B, 0],
    ['id-1', CATALOG_ADDON, 1],
  ])
  assert.deepEqual(calls, [STREAM_B])
})

test('one bad manifest fails the whole install and leaves the list as it was', async () => {
  const { backend } = makeBackend({ 'https://broken.test/manifest.json': { id: '', name: 'x' } })
  await backend.putAddons([CATALOG_ADDON])
  const err = await rejectsWithStatus(backend.putAddons([CATALOG_ADDON, STREAM_A, 'https://broken.test/manifest.json']), 400)
  assert.match(err.message, /could not fetch a valid manifest for https:\/\/broken\.test/)
  assert.deepEqual((await backend.getAddons()).user.map((a) => a.transportUrl), [CATALOG_ADDON])
})

test('addon lists are refused for duplicates, non-http URLs and more than fifty entries', async () => {
  const { backend } = makeBackend({})
  await rejectsWithStatus(backend.putAddons([STREAM_A, STREAM_A]), 400)
  await rejectsWithStatus(backend.putAddons(['ftp://addon.test/manifest.json']), 400)
  await rejectsWithStatus(backend.putAddons(['not a url']), 400)
  await rejectsWithStatus(backend.putAddons(Array.from({ length: 51 }, (_, i) => `https://a${i}.test/manifest.json`)), 400)
})

test('hiding an addon’s catalogs strips them from the list but keeps it answering by id', async () => {
  const { backend } = makeBackend({ 'https://catalog.test/catalog/movie/top.json': { metas: [{ id: 'tt1', type: 'movie', name: 'One' }] } })
  const [catalog] = await backend.putAddons([CATALOG_ADDON])
  await backend.patchAddon(catalog!.id, { hideCatalogs: true })
  const [listed] = (await backend.getAddons()).user
  assert.equal(listed!.hideCatalogs, true)
  assert.deepEqual(listed!.manifest.catalogs, [])
  assert.equal((await backend.getCatalog(catalog!.id, 'movie', 'top')).metas[0]!.id, 'tt1')

  await backend.patchAddon(catalog!.id, { hideCatalogs: false })
  assert.equal((await backend.getAddons()).user[0]!.manifest.catalogs.length, 1)
  await rejectsWithStatus(backend.patchAddon('missing', { hideCatalogs: true }), 404)
})

/* ── Resolution ────────────────────────────────────────────────────────────── */

test('catalogs: unknown addons are refused, extras are bounded, upstream failures are a 502', async () => {
  const { backend } = makeBackend({
    'https://catalog.test/catalog/movie/top/search=dune.json': { metas: [{ id: 'tt2', type: 'movie', name: 'Dune' }] },
  })
  const [catalog] = await backend.putAddons([CATALOG_ADDON])
  assert.equal((await backend.getCatalog(catalog!.id, 'movie', 'top', { search: 'dune' })).metas[0]!.name, 'Dune')
  await rejectsWithStatus(backend.getCatalog('nope', 'movie', 'top'), 403)
  await rejectsWithStatus(backend.getCatalog(catalog!.id, 'movie', 'top', { search: 'x'.repeat(257) }), 400)
  const tooMany = Object.fromEntries(Array.from({ length: 9 }, (_, i) => [`k${i}`, 'v']))
  await rejectsWithStatus(backend.getCatalog(catalog!.id, 'movie', 'top', tooMany), 400)
  await rejectsWithStatus(backend.getCatalog(catalog!.id, 'movie', 'missing'), 502)
})

test('meta: the first addon that can describe the id answers, skipping ones that fail', async () => {
  const second = 'https://meta-two.test/manifest.json'
  const { backend } = makeBackend({
    [second]: manifest('meta-two', { resources: ['meta'], idPrefixes: ['tt'] }),
    'https://catalog.test/meta/movie/tt1.json': () => new Response('boom', { status: 500 }),
    'https://meta-two.test/meta/movie/tt1.json': { meta: { id: 'tt1', type: 'movie', name: 'From two' } },
  })
  await backend.putAddons([STREAM_A, CATALOG_ADDON, second])
  assert.equal((await backend.getMeta('movie', 'tt1')).meta.name, 'From two')
  await rejectsWithStatus(backend.getMeta('movie', 'kitsu:1'), 404)
})

test('streams: every capable addon is asked; unplayable streams are dropped and failures reported by code', async () => {
  const hanging = 'https://hanging.test/manifest.json'
  const invalid = 'https://invalid.test/manifest.json'
  const { backend } = makeBackend(
    {
      [hanging]: streamManifest('hanging'),
      [invalid]: streamManifest('invalid'),
      'https://stream-a.test/stream/movie/tt1.json': {
        streams: [{ url: 'https://cdn.test/a.mkv', name: 'A', behaviorHints: { bingeGroup: 'a' } }, { infoHash: 'abc' }, { url: 'magnet:?xt=1' }],
      },
      'https://stream-b.test/stream/movie/tt1.json': () => new Response('down', { status: 503 }),
      'https://hanging.test/stream/movie/tt1.json': hangUntilAborted,
      'https://invalid.test/stream/movie/tt1.json': { nope: true },
    },
    { timeoutMs: 50 },
  )
  await backend.putAddons([STREAM_A, STREAM_B, hanging, invalid, CATALOG_ADDON])
  const result = await backend.getStreams('movie', 'tt1')
  assert.deepEqual(result.results, [
    { addon: { id: 'id-1', name: 'stream-a' }, streams: [{ url: 'https://cdn.test/a.mkv', name: 'A', behaviorHints: { bingeGroup: 'a' } }] },
  ])
  assert.deepEqual(
    result.errors.map((e) => [e.name, e.code, e.status]),
    [
      ['stream-b', 'upstream_http', 503],
      ['hanging', 'timeout', undefined],
      ['invalid', 'invalid_response', undefined],
    ],
  )
})

test('subtitles: the hash is validated, empty values are left out, and hash matching is reported', async () => {
  const { backend, calls } = makeBackend({
    'https://stream-a.test/subtitles/movie/tt1/videoHash=0123456789abcdef&videoSize=42.json': {
      subtitles: [{ id: 's1', url: 'https://subs.test/1.srt', lang: 'eng' }, { id: 's2', url: 'javascript:alert(1)', lang: 'eng' }],
    },
    'https://stream-a.test/subtitles/movie/tt1.json': { subtitles: [] },
  })
  await backend.putAddons([STREAM_A])
  await rejectsWithStatus(backend.getSubtitles('movie', 'tt1', { videoHash: 'xyz' }), 400)
  await rejectsWithStatus(backend.getSubtitles('movie', 'tt1', { videoSize: 0 }), 400)

  const matched = await backend.getSubtitles('movie', 'tt1', { videoHash: '0123456789abcdef', videoSize: 42 })
  assert.equal(matched.hashMatched, true)
  assert.deepEqual(matched.results[0]!.subtitles, [{ id: 's1', url: 'https://subs.test/1.srt', lang: 'eng' }])

  calls.length = 0
  const bare = await backend.getSubtitles('movie', 'tt1', { videoHash: '', filename: '' })
  assert.equal(bare.hashMatched, false)
  assert.deepEqual(calls, ['https://stream-a.test/subtitles/movie/tt1.json'])
})

test('next episode: same addon and exact bingeGroup, otherwise no stream; unknown titles are a 404', async () => {
  const { backend } = makeBackend({
    'https://catalog.test/meta/series/tt9.json': {
      meta: {
        id: 'tt9',
        type: 'series',
        name: 'Show',
        videos: [
          { id: 'tt9:1:1', title: 'One', season: 1, episode: 1 },
          { id: 'tt9:1:2', title: 'Two', season: 1, episode: 2 },
        ],
      },
    },
    // Ids are URL-encoded in the path.
    'https://stream-a.test/stream/series/tt9%3A1%3A2.json': {
      streams: [{ url: 'https://cdn.test/other.mkv', behaviorHints: { bingeGroup: 'other' } }, { url: 'https://cdn.test/two.mkv', behaviorHints: { bingeGroup: 'grp' } }],
    },
  })
  const [, streamA] = await backend.putAddons([CATALOG_ADDON, STREAM_A])
  const matched = await backend.getNextEpisode({ type: 'series', metaId: 'tt9', videoId: 'tt9:1:1', addonId: streamA!.id, bingeGroup: 'grp' })
  assert.equal(matched.video?.id, 'tt9:1:2')
  assert.equal(matched.stream?.url, 'https://cdn.test/two.mkv')

  const unmatched = await backend.getNextEpisode({ type: 'series', metaId: 'tt9', videoId: 'tt9:1:1', addonId: streamA!.id, bingeGroup: 'none' })
  assert.equal(unmatched.video?.id, 'tt9:1:2')
  assert.equal(unmatched.stream, null)

  assert.deepEqual(await backend.getNextEpisode({ type: 'series', metaId: 'tt9', videoId: 'tt9:1:2' }), { video: null, stream: null })
  await rejectsWithStatus(backend.getNextEpisode({ type: 'series', metaId: 'tt404', videoId: 'tt404:1:1' }), 404)
})

test('a cancelled request rejects with the caller’s own abort reason', async () => {
  const { backend } = makeBackend({
    'https://stream-a.test/stream/movie/tt1.json': hangUntilAborted,
  })
  await backend.putAddons([STREAM_A])
  const controller = new AbortController()
  const pending = backend.getStreams('movie', 'tt1', { signal: controller.signal })
  const reason = new Error('left the screen')
  controller.abort(reason)
  await assert.rejects(pending, (err) => err === reason)
})

/* ── Library, watch history, settings ──────────────────────────────────────── */

test('library: strictly newer wins, a tie keeps the stored row, the type is fixed, and removals survive stale re-adds', async () => {
  const { backend } = makeBackend({})
  await backend.putLibrary([library('movie:tt1', 10, { name: 'Original' })])

  let rows = await backend.putLibrary([library('movie:tt1', 10, { name: 'Tie' })])
  assert.equal(rows[0]!.name, 'Original')

  rows = await backend.putLibrary([library('movie:tt1', 11, { name: 'Renamed', type: 'series' })])
  assert.equal(rows[0]!.name, 'Renamed')
  assert.equal(rows[0]!.type, 'movie')

  await backend.putLibrary([library('movie:tt1', 20, { removedAt: 20 })])
  rows = await backend.putLibrary([library('movie:tt1', 15)])
  assert.equal(rows[0]!.removedAt, 20)
  assert.deepEqual(await backend.getLibrary(), rows)
})

test('library and watch history refuse rows the server would refuse, and store nothing from that batch', async () => {
  const { backend, store } = makeBackend({})
  await rejectsWithStatus(backend.putLibrary([library('movie:tt1', 1), library('', 1)]), 400)
  await rejectsWithStatus(backend.putLibrary([library('movie:tt1', 1, { poster: 'not a url' })]), 400)
  await rejectsWithStatus(backend.putLibrary([library('movie:tt1', 1.5)]), 400)
  await rejectsWithStatus(backend.putWatchStates([watch('tt1', 1, { positionSec: -1 })]), 400)
  await rejectsWithStatus(backend.putWatchStates([watch('tt1', 1, { positionSec: Number.POSITIVE_INFINITY })]), 400)
  await rejectsWithStatus(backend.putWatchStates([watch('tt1', 1, { name: 'x'.repeat(513) })]), 400)
  assert.equal(store.documents.size, 0)
})

test('overlapping progress reports both land', async () => {
  const { backend, store } = makeBackend({})
  store.readDelayMs = 5
  await Promise.all([backend.putWatchStates([watch('tt1', 5)]), backend.putWatchStates([watch('tt2', 6)])])
  assert.deepEqual((await backend.getWatchStates()).map((s) => s.videoId).sort(), ['tt1', 'tt2'])
})

test('watch history: a newer report replaces the row whole, an older one is ignored', async () => {
  const { backend } = makeBackend({})
  await backend.putWatchStates([watch('tt1', 10, { name: 'Named', positionSec: 30 })])
  let rows = await backend.putWatchStates([watch('tt1', 9, { positionSec: 99 })])
  assert.equal(rows[0]!.positionSec, 30)
  rows = await backend.putWatchStates([watch('tt1', 11, { positionSec: 40 })])
  assert.equal(rows[0]!.positionSec, 40)
  assert.equal(rows[0]!.name, undefined)
})

test('settings: newer replaces, older is ignored, unknown keys pass through, invalid values are refused', async () => {
  const { backend } = makeBackend({})
  assert.deepEqual(await backend.getSettings(), { value: {}, updatedAt: 0 })
  const value = { preferredSubtitleLang: 'eng', futureSetting: { nested: true } }
  assert.deepEqual(await backend.putSettings(value as never, 10), { value, updatedAt: 10 })
  assert.deepEqual(await backend.putSettings({ preferredSubtitleLang: 'fre' }, 9), { value, updatedAt: 10 })
  assert.deepEqual(await backend.getSettings(), { value, updatedAt: 10 })
  await rejectsWithStatus(backend.putSettings({ playbackRate: 9 }, 11), 400)
  await rejectsWithStatus(backend.putSettings({ videoFitMode: 'stretch' as never }, 11), 400)
  await rejectsWithStatus(backend.putSettings({}, 0), 400)
})

test('an unreadable stored document is reported, not replaced', async () => {
  const { backend, store } = makeBackend({})
  store.documents.set('library', '{ not json')
  await assert.rejects(backend.getLibrary(), DeviceDataError)
  await assert.rejects(backend.putLibrary([library('movie:tt1', 1)]), DeviceDataError)
  assert.equal(store.documents.get('library'), '{ not json')

  store.documents.set('watchStates', JSON.stringify({ version: 2, states: [] }))
  await assert.rejects(backend.getWatchStates(), DeviceDataError)
})
