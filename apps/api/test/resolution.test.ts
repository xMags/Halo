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
    const aId = installUserAddon(db, 'admin', 'https://a.test/manifest.json', manifest('A', ['stream']), 0)
    installUserAddon(db, 'admin', 'https://b.test/manifest.json', manifest('B', ['stream']), 1)
    const cId = installUserAddon(db, 'admin', 'https://c.test/manifest.json', manifest('C', ['stream']), 2)

    const res = await app.request('/streams?type=movie&videoId=tt1', authed(token))
    const body = (await res.json()) as {
      results: Array<{ addon: { id: string; name: string; transportUrl?: string }; streams: unknown[] }>
      errors: Array<{ id: string }>
    }
    // A has one playable stream; B is omitted (only a torrent); C failed.
    expect(body.results).toHaveLength(1)
    expect(body.results[0]!.addon).toEqual({ id: aId, name: 'A' })
    expect(body.results[0]!.streams).toHaveLength(1)
    expect(body.errors.map((e) => e.id)).toEqual([cId])
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

describe('GET /next-episode', () => {
  const seriesManifest = (name: string, resources: string[]) => ({
    id: name.toLowerCase(),
    version: '1.0.0',
    name,
    resources,
    types: ['series'],
    catalogs: [],
  })

  const META = {
    'meta/series/tt1': {
      meta: {
        id: 'tt1',
        type: 'series',
        name: 'Show',
        videos: [
          { id: 'tt1:1:1', season: 1, episode: 1 },
          { id: 'tt1:1:2', season: 1, episode: 2, title: 'The Follow-up' },
        ],
      },
    },
  }

  const setup = (streamRoutes: Record<string, unknown>) => {
    const { app, db } = mount({
      'https://meta.test': META,
      'https://str.test': streamRoutes,
      'https://other.test': {
        // A rival addon whose stream would binge-match — it must never be
        // consulted, only the addon named in the request is.
        'stream/series/tt1%3A1%3A2': { streams: [{ url: 'https://other-cdn.test/ep2.mkv', behaviorHints: { bingeGroup: 'str-1080p' } }] },
      },
    })
    return { app, db }
  }

  it('returns the next episode and the same-addon stream matching the bingeGroup', async () => {
    const { app, db } = setup({
      'stream/series/tt1%3A1%3A2': {
        streams: [
          { url: 'https://cdn.test/ep2-720p.mkv', behaviorHints: { bingeGroup: 'str-720p' } },
          { url: 'https://cdn.test/ep2-1080p.mkv', behaviorHints: { bingeGroup: 'str-1080p' } },
        ],
      },
    })
    const token = await adminToken()
    installUserAddon(db, 'admin', 'https://meta.test/manifest.json', seriesManifest('Meta', ['meta']), 0)
    const strId = installUserAddon(db, 'admin', 'https://str.test/manifest.json', seriesManifest('Str', ['stream']), 1)
    installUserAddon(db, 'admin', 'https://other.test/manifest.json', seriesManifest('Other', ['stream']), 2)

    const res = await app.request(
      `/next-episode?type=series&metaId=tt1&videoId=${encodeURIComponent('tt1:1:1')}&addon=${strId}&bingeGroup=str-1080p`,
      authed(token),
    )
    expect(res.status).toBe(200)
    const body = (await res.json()) as { video: { id: string; title?: string }; stream: { url: string } | null }
    expect(body.video.id).toBe('tt1:1:2')
    expect(body.video.title).toBe('The Follow-up')
    expect(body.stream?.url).toBe('https://cdn.test/ep2-1080p.mkv')
  })

  it('returns stream null when nothing matches the bingeGroup or the match is not playable', async () => {
    const { app, db } = setup({
      'stream/series/tt1%3A1%3A2': {
        streams: [
          { url: 'https://cdn.test/ep2.mkv', behaviorHints: { bingeGroup: 'different-group' } },
          // Matching group but torrent-only — isPlayableStream must reject it.
          { infoHash: 'deadbeefdeadbeef', behaviorHints: { bingeGroup: 'str-1080p' } },
        ],
      },
    })
    const token = await adminToken()
    installUserAddon(db, 'admin', 'https://meta.test/manifest.json', seriesManifest('Meta', ['meta']), 0)
    const strId = installUserAddon(db, 'admin', 'https://str.test/manifest.json', seriesManifest('Str', ['stream']), 1)

    const res = await app.request(
      `/next-episode?type=series&metaId=tt1&videoId=${encodeURIComponent('tt1:1:1')}&addon=${strId}&bingeGroup=str-1080p`,
      authed(token),
    )
    const body = (await res.json()) as { video: { id: string }; stream: unknown }
    expect(body.video.id).toBe('tt1:1:2')
    expect(body.stream).toBeNull()
  })

  it('treats an unknown addon id and a failing addon as no-match, not an error', async () => {
    const { app, db } = mount({ 'https://meta.test': META, 'https://str.test': 'fail' })
    const token = await adminToken()
    installUserAddon(db, 'admin', 'https://meta.test/manifest.json', seriesManifest('Meta', ['meta']), 0)
    const strId = installUserAddon(db, 'admin', 'https://str.test/manifest.json', seriesManifest('Str', ['stream']), 1)

    const gone = await app.request(
      `/next-episode?type=series&metaId=tt1&videoId=${encodeURIComponent('tt1:1:1')}&addon=uninstalled-id&bingeGroup=g`,
      authed(token),
    )
    expect(((await gone.json()) as { video: { id: string }; stream: unknown }).stream).toBeNull()

    const down = await app.request(
      `/next-episode?type=series&metaId=tt1&videoId=${encodeURIComponent('tt1:1:1')}&addon=${strId}&bingeGroup=g`,
      authed(token),
    )
    expect(down.status).toBe(200)
    expect(((await down.json()) as { video: { id: string }; stream: unknown }).stream).toBeNull()
  })

  it('reports the end of a series as video null', async () => {
    const { app, db } = setup({})
    const token = await adminToken()
    installUserAddon(db, 'admin', 'https://meta.test/manifest.json', seriesManifest('Meta', ['meta']), 0)
    const res = await app.request(`/next-episode?type=series&metaId=tt1&videoId=${encodeURIComponent('tt1:1:2')}`, authed(token))
    expect(res.status).toBe(200)
    expect(await res.json()).toEqual({ video: null, stream: null })
  })

  it('404s when no addon can describe the meta and validates params', async () => {
    const { app, db } = mount({})
    const token = await adminToken()
    installUserAddon(db, 'admin', 'https://meta.test/manifest.json', seriesManifest('Meta', ['stream']), 0)
    expect((await app.request('/next-episode?type=series&metaId=tt1&videoId=tt1:1:1', authed(token))).status).toBe(404)
    expect((await app.request('/next-episode?type=series&metaId=tt1', authed(token))).status).toBe(400)
    const longGroup = 'g'.repeat(513)
    expect(
      (await app.request(`/next-episode?type=series&metaId=tt1&videoId=tt1:1:1&bingeGroup=${longGroup}`, authed(token))).status,
    ).toBe(400)
  })
})

describe('GET /catalog', () => {
  const CATALOG_URL = 'https://cat.test/manifest.json'
  const setup = async () => {
    const { app, db } = mount({ 'https://cat.test': { 'catalog/movie/top': { metas: [{ id: 'tt1', type: 'movie', name: 'Top Movie' }] } } })
    const token = await adminToken()
    const addonId = installUserAddon(db, 'admin', CATALOG_URL, manifest('Cat', ['catalog'], [{ type: 'movie', id: 'top' }]), 0)
    return { app, token, addonId }
  }

  it('passes the catalog through for an installed addon, addressed by opaque id', async () => {
    const { app, token, addonId } = await setup()
    const res = await app.request(`/catalog?addon=${addonId}&type=movie&id=top`, authed(token))
    expect(res.status).toBe(200)
    expect(((await res.json()) as { metas: Array<{ name: string }> }).metas[0]!.name).toBe('Top Movie')
  })

  it('403s an unknown addon id and rejects addressing by transport URL', async () => {
    const { app, token } = await setup()
    expect((await app.request('/catalog?addon=not-an-installed-id&type=movie&id=top', authed(token))).status).toBe(403)
    // The URL is no longer a valid address — only the opaque id is.
    expect((await app.request(`/catalog?addon=${encodeURIComponent(CATALOG_URL)}&type=movie&id=top`, authed(token))).status).toBe(403)
  })

  it('enforces the extra-param count and length caps', async () => {
    const { app, token, addonId } = await setup()
    const many = Array.from({ length: 9 }, (_, i) => `k${i}=v`).join('&')
    expect((await app.request(`/catalog?addon=${addonId}&type=movie&id=top&${many}`, authed(token))).status).toBe(400)
    const longValue = 'x'.repeat(257)
    expect(
      (await app.request(`/catalog?addon=${addonId}&type=movie&id=top&genre=${longValue}`, authed(token))).status,
    ).toBe(400)
  })
})
