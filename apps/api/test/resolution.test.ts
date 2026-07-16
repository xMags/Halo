import { describe, expect, it } from 'vitest'
import { adminToken, authed, installUserAddon, makeApp, mockResolveFetch } from './helpers'

const manifest = (name: string, resources: string[], catalogs: Array<{ type: string; id: string }> = []) => ({
  id: name.toLowerCase(),
  version: '1.0.0',
  name,
  resources,
  types: ['movie'],
  catalogs,
})

const mount = (routes: Record<string, Record<string, unknown> | 'fail'>) => makeApp({ safeFetch: mockResolveFetch(routes) })

describe('GET /streams', () => {
  it('aggregates playable streams, drops non-playable, and reports failures', async () => {
    const { app, db } = mount({
      'https://a.test': { 'stream/movie/tt1': { streams: [{ url: 'https://cdn.test/a.mp4' }, { infoHash: 'deadbeefdeadbeef' }] } },
      'https://b.test': { 'stream/movie/tt1': { streams: [{ infoHash: 'cafecafecafecafe' }] } },
      'https://c.test': 'fail',
    })
    const token = await adminToken()
    installUserAddon(db, 'admin', 'https://a.test/manifest.json', manifest('A', ['stream']), 0)
    installUserAddon(db, 'admin', 'https://b.test/manifest.json', manifest('B', ['stream']), 1)
    installUserAddon(db, 'admin', 'https://c.test/manifest.json', manifest('C', ['stream']), 2)

    const res = await app.request('/streams?type=movie&videoId=tt1', authed(token))
    const body = (await res.json()) as {
      results: Array<{ addon: { name: string }; streams: unknown[] }>
      errors: Array<{ transportUrl: string }>
    }
    // A has one playable stream; B is omitted (only a torrent); C failed.
    expect(body.results).toHaveLength(1)
    expect(body.results[0]!.addon.name).toBe('A')
    expect(body.results[0]!.streams).toHaveLength(1)
    expect(body.errors.map((e) => e.transportUrl)).toEqual(['https://c.test/manifest.json'])
  })
})

describe('GET /meta', () => {
  it('returns the first addon that succeeds, skipping ones that error', async () => {
    const { app, db } = mount({
      'https://a.test': {}, // supports meta but has no route -> 404 -> error
      'https://b.test': { 'meta/movie/tt1': { meta: { id: 'tt1', type: 'movie', name: 'Movie B' } } },
    })
    const token = await adminToken()
    installUserAddon(db, 'admin', 'https://a.test/manifest.json', manifest('A', ['meta']), 0)
    installUserAddon(db, 'admin', 'https://b.test/manifest.json', manifest('B', ['meta']), 1)

    const res = await app.request('/meta?type=movie&id=tt1', authed(token))
    expect(res.status).toBe(200)
    expect(((await res.json()) as { meta: { name: string } }).meta.name).toBe('Movie B')
  })

  it('404s when no effective addon can describe the id', async () => {
    const { app, db } = mount({ 'https://a.test': {} })
    const token = await adminToken()
    installUserAddon(db, 'admin', 'https://a.test/manifest.json', manifest('A', ['stream']), 0)
    const res = await app.request('/meta?type=movie&id=tt1', authed(token))
    expect(res.status).toBe(404)
  })
})

describe('GET /subtitles', () => {
  const HASH = 'abcdef0123456789'

  it('reflects hashMatched and forwards the hash extra', async () => {
    const { app, db } = mount({
      'https://s.test': {
        'subtitles/movie/tt1': { subtitles: [{ id: '1', url: 'https://s.test/1.srt', lang: 'eng' }] },
        [`subtitles/movie/tt1/videoHash=${HASH}`]: { subtitles: [{ id: '2', url: 'https://s.test/2.srt', lang: 'eng' }] },
      },
    })
    const token = await adminToken()
    installUserAddon(db, 'admin', 'https://s.test/manifest.json', manifest('S', ['subtitles']), 0)

    const plain = (await (await app.request('/subtitles?type=movie&videoId=tt1', authed(token))).json()) as {
      results: Array<{ subtitles: Array<{ id: string }> }>
      hashMatched: boolean
    }
    expect(plain.hashMatched).toBe(false)
    expect(plain.results[0]!.subtitles[0]!.id).toBe('1')

    const hashed = (await (await app.request(`/subtitles?type=movie&videoId=tt1&videoHash=${HASH}`, authed(token))).json()) as {
      results: Array<{ subtitles: Array<{ id: string }> }>
      hashMatched: boolean
    }
    expect(hashed.hashMatched).toBe(true)
    expect(hashed.results[0]!.subtitles[0]!.id).toBe('2')
  })

  it('rejects a malformed videoHash and a non-positive videoSize', async () => {
    const { app } = mount({})
    const token = await adminToken()
    expect((await app.request('/subtitles?type=movie&videoId=tt1&videoHash=xyz', authed(token))).status).toBe(400)
    expect((await app.request('/subtitles?type=movie&videoId=tt1&videoSize=0', authed(token))).status).toBe(400)
  })
})

describe('GET /catalog', () => {
  const CATALOG_URL = 'https://cat.test/manifest.json'
  const setup = async () => {
    const { app, db } = mount({ 'https://cat.test': { 'catalog/movie/top': { metas: [{ id: 'tt1', type: 'movie', name: 'Top Movie' }] } } })
    const token = await adminToken()
    installUserAddon(db, 'admin', CATALOG_URL, manifest('Cat', ['catalog'], [{ type: 'movie', id: 'top' }]), 0)
    return { app, token }
  }

  it('passes the catalog through for an installed addon', async () => {
    const { app, token } = await setup()
    const res = await app.request(`/catalog?addon=${encodeURIComponent(CATALOG_URL)}&type=movie&id=top`, authed(token))
    expect(res.status).toBe(200)
    expect(((await res.json()) as { metas: Array<{ name: string }> }).metas[0]!.name).toBe('Top Movie')
  })

  it('403s an addon outside the effective set', async () => {
    const { app, token } = await setup()
    const res = await app.request(`/catalog?addon=${encodeURIComponent('https://other.test/manifest.json')}&type=movie&id=top`, authed(token))
    expect(res.status).toBe(403)
  })

  it('enforces the extra-param count and length caps', async () => {
    const { app, token } = await setup()
    const many = Array.from({ length: 9 }, (_, i) => `k${i}=v`).join('&')
    expect((await app.request(`/catalog?addon=${encodeURIComponent(CATALOG_URL)}&type=movie&id=top&${many}`, authed(token))).status).toBe(400)
    const longValue = 'x'.repeat(257)
    expect(
      (await app.request(`/catalog?addon=${encodeURIComponent(CATALOG_URL)}&type=movie&id=top&genre=${longValue}`, authed(token))).status,
    ).toBe(400)
  })
})
