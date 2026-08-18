import { describe, expect, it } from 'vitest'
import type { AddonErrorCode } from '@halo/core'
import type { AddonFailureLog } from '../src/addonResolution'
import { ProxyTargetError } from '../src/proxyGuard'
import { adminToken, authed, installUserAddon, makeApp } from './helpers'

const movieManifest = (name: string, resources: string[] = ['stream']) => ({
  id: name.toLowerCase(),
  version: '1.0.0',
  name,
  resources,
  types: ['movie'],
  catalogs: [],
})

const seriesManifest = (name: string, resources: string[]) => ({
  ...movieManifest(name, resources),
  types: ['series'],
})

function urlOf(input: Parameters<typeof fetch>[0]): string {
  return typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  })
}

describe('safe addon failures', () => {
  const cases: Array<{
    label: string
    code: AddonErrorCode
    status?: number
    fetch: typeof fetch
    safeMessage: string
  }> = [
    {
      label: 'timeout',
      code: 'timeout',
      fetch: async () => {
        const error = new Error('raw timeout included a token')
        error.name = 'TimeoutError'
        throw error
      },
      safeMessage: 'Addon timed out.',
    },
    {
      label: 'upstream HTTP',
      code: 'upstream_http',
      status: 429,
      fetch: async () => new Response('raw upstream body with a token', { status: 429 }),
      safeMessage: 'Addon returned HTTP 429.',
    },
    {
      label: 'blocked target',
      code: 'blocked_target',
      fetch: async () => {
        throw new ProxyTargetError('raw blocked URL and address')
      },
      safeMessage: 'Addon target was blocked for safety.',
    },
    {
      label: 'invalid response',
      code: 'invalid_response',
      fetch: async () => json({ streams: 'not-an-array', raw: 'secret' }),
      safeMessage: 'Addon returned an invalid response.',
    },
    {
      label: 'unavailable',
      code: 'unavailable',
      fetch: async () => {
        throw new Error('raw network failure with credentials')
      },
      safeMessage: 'Addon is unavailable.',
    },
  ]

  it.each(cases)('classifies $label without exposing raw failure data', async ({ code, status, fetch, safeMessage }) => {
    const logs: AddonFailureLog[] = []
    const { app, db } = makeApp({ safeFetch: fetch, addonFailureLogger: (event) => logs.push(event) })
    const transportUrl = 'https://addon.test/credential-path/raw-addon-secret/manifest.json'
    const addonId = installUserAddon(db, 'admin', transportUrl, movieManifest(transportUrl), 0)
    const mediaId = 'customer-media-id-raw-secret'

    const response = await app.request(`/streams?type=movie&videoId=${mediaId}`, authed(await adminToken()))
    const body = (await response.json()) as {
      results: unknown[]
      errors: Array<{ id: string; name?: string; code?: string; status?: number; message: string }>
    }

    expect(body.results).toEqual([])
    expect(body.errors).toEqual([
      {
        id: addonId,
        name: 'Addon',
        code,
        ...(status !== undefined ? { status } : {}),
        message: safeMessage,
      },
    ])
    expect(logs).toHaveLength(1)
    expect(logs[0]).toMatchObject({
      route: '/streams',
      addonId,
      addonName: 'Addon',
      code,
      ...(status !== undefined ? { status } : {}),
    })
    expect(logs[0]!.durationMs).toBeGreaterThanOrEqual(0)

    const exposed = JSON.stringify({ body, logs })
    for (const forbidden of [
      transportUrl,
      'credential-path',
      mediaId,
      'admin-sub',
      'raw timeout',
      'raw upstream',
      'raw blocked',
      'raw network',
      'raw-addon-secret',
    ]) {
      expect(exposed).not.toContain(forbidden)
    }
  })

  it('retains successful addons when another addon fails', async () => {
    const safeFetch: typeof fetch = async (input) => {
      const url = urlOf(input)
      if (url.startsWith('https://good.test/')) return json({ streams: [{ url: 'https://cdn.test/video.mkv' }] })
      throw new Error('failure details must stay server-side')
    }
    const { app, db } = makeApp({ safeFetch })
    installUserAddon(db, 'admin', 'https://good.test/manifest.json', movieManifest('Good'), 0)
    installUserAddon(db, 'admin', 'https://bad.test/manifest.json', movieManifest('Bad'), 1)

    const body = (await (await app.request('/streams?type=movie&videoId=tt1', authed(await adminToken()))).json()) as {
      results: Array<{ addon: { name: string }; streams: unknown[] }>
      errors: Array<{ name?: string; code?: string }>
    }

    expect(body.results).toHaveLength(1)
    expect(body.results[0]!.addon.name).toBe('Good')
    expect(body.errors).toEqual([expect.objectContaining({ name: 'Bad', code: 'unavailable' })])
  })

  it('does not let an operational logger failure replace the safe API response', async () => {
    const { app, db } = makeApp({
      safeFetch: async () => {
        throw new Error('raw addon failure')
      },
      addonFailureLogger: () => {
        throw new Error('logger unavailable')
      },
    })
    installUserAddon(db, 'admin', 'https://bad.test/manifest.json', movieManifest('Bad'), 0)

    const response = await app.request('/streams?type=movie&videoId=tt1', authed(await adminToken()))
    const body = (await response.json()) as { errors: Array<{ code?: string }> }

    expect(response.status).toBe(200)
    expect(body.errors).toEqual([expect.objectContaining({ code: 'unavailable' })])
  })
})

describe('third-party payload normalization', () => {
  it('preserves supported stream metadata and drops malformed entries and fields', async () => {
    const overlong = 'x'.repeat(4_097)
    const safeFetch: typeof fetch = async () => json({
      streams: [
        null,
        { url: 'ftp://cdn.test/video.mkv' },
        { url: '/relative/video.mkv' },
        { infoHash: 'deadbeef' },
        {
          url: 'https://cdn.test/video.mkv',
          name: '[TB+] Torrentio\n4k DV | HDR',
          title: 'Game of Thrones S01E01',
          description: 'Cached source',
          externalUrl: 'https://ignored.test',
          subtitles: [
            { id: 'embedded-en', url: 'https://subs.test/en.srt', lang: 'eng' },
            { id: 'bad', url: 'file:///private.srt', lang: 'eng' },
            { id: 17, url: 'https://subs.test/bad.srt', lang: 'eng' },
          ],
          behaviorHints: {
            notWebReady: false,
            bingeGroup: 'torrentio|f33ce65b218f7c51d65a69d90b09e46c2381c8ef',
            filename: 'Game.of.Thrones.S01E01.2160p.mkv',
            videoSize: 34_249_807_367,
            videoHash: '8330adfa7c2e68e8',
            proxyHeaders: {
              request: { Authorization: 'Bearer stream-token', Bad: 'line\r\nbreak', Numeric: 17 },
              response: { 'X-Playback': 'allowed' },
            },
          },
        },
        {
          url: 'http://cdn.test/minimal.mp4',
          name: 17,
          title: overlong,
          subtitles: 'not-an-array',
          behaviorHints: { videoSize: -1, bingeGroup: 17, proxyHeaders: [] },
        },
      ],
    })
    const { app, db } = makeApp({ safeFetch })
    installUserAddon(db, 'admin', 'https://torrentio.test/manifest.json', movieManifest('Torrentio TB'), 0)

    const body = (await (await app.request('/streams?type=movie&videoId=tt1', authed(await adminToken()))).json()) as {
      results: Array<{ streams: Array<Record<string, unknown>> }>
      errors: unknown[]
    }

    expect(body.errors).toEqual([])
    expect(body.results[0]!.streams).toEqual([
      {
        url: 'https://cdn.test/video.mkv',
        name: '[TB+] Torrentio\n4k DV | HDR',
        title: 'Game of Thrones S01E01',
        description: 'Cached source',
        subtitles: [{ id: 'embedded-en', url: 'https://subs.test/en.srt', lang: 'eng' }],
        behaviorHints: {
          notWebReady: false,
          bingeGroup: 'torrentio|f33ce65b218f7c51d65a69d90b09e46c2381c8ef',
          filename: 'Game.of.Thrones.S01E01.2160p.mkv',
          videoSize: 34_249_807_367,
          videoHash: '8330adfa7c2e68e8',
          proxyHeaders: {
            request: { Authorization: 'Bearer stream-token' },
            response: { 'X-Playback': 'allowed' },
          },
        },
      },
      { url: 'http://cdn.test/minimal.mp4' },
    ])
  })

  it('bounds the number of streams without rejecting a valid response', async () => {
    const safeFetch: typeof fetch = async () => json({
      streams: Array.from({ length: 501 }, (_, index) => ({ url: `https://cdn.test/${index}.mkv` })),
    })
    const { app, db } = makeApp({ safeFetch })
    installUserAddon(db, 'admin', 'https://many.test/manifest.json', movieManifest('Many'), 0)

    const body = (await (await app.request('/streams?type=movie&videoId=tt1', authed(await adminToken()))).json()) as {
      results: Array<{ streams: unknown[] }>
    }

    expect(body.results[0]!.streams).toHaveLength(500)
  })

  it('normalizes subtitle entries and classifies a missing container', async () => {
    let validContainer = true
    const safeFetch: typeof fetch = async () => validContainer
      ? json({
          subtitles: [
            { id: 'en-1', url: 'https://subs.test/en.srt', lang: 'eng' },
            { id: 'bad-scheme', url: 'file:///tmp/private.srt', lang: 'eng' },
            { id: 'missing-language', url: 'https://subs.test/unknown.srt' },
          ],
        })
      : json({ result: [] })
    const { app, db } = makeApp({ safeFetch })
    installUserAddon(db, 'admin', 'https://subs.test/manifest.json', movieManifest('Subs', ['subtitles']), 0)
    const token = await adminToken()

    const valid = (await (await app.request('/subtitles?type=movie&videoId=tt1', authed(token))).json()) as {
      results: Array<{ subtitles: unknown[] }>
      errors: unknown[]
    }
    expect(valid.results[0]!.subtitles).toEqual([{ id: 'en-1', url: 'https://subs.test/en.srt', lang: 'eng' }])
    expect(valid.errors).toEqual([])

    validContainer = false
    const invalid = (await (await app.request('/subtitles?type=movie&videoId=tt1', authed(token))).json()) as {
      results: unknown[]
      errors: Array<{ code?: string; message: string }>
    }
    expect(invalid.results).toEqual([])
    expect(invalid.errors).toEqual([expect.objectContaining({
      code: 'invalid_response',
      message: 'Addon returned an invalid response.',
    })])
  })

  it('uses normalized streams for same-addon next-episode matching', async () => {
    const logs: AddonFailureLog[] = []
    let malformed = false
    const safeFetch: typeof fetch = async (input) => {
      const url = urlOf(input)
      if (url.startsWith('https://meta.test/')) {
        return json({
          meta: {
            id: 'tt1',
            type: 'series',
            name: 'Show',
            videos: [
              { id: 'tt1:1:1', season: 1, episode: 1 },
              { id: 'tt1:1:2', season: 1, episode: 2 },
            ],
          },
        })
      }
      if (malformed) return json({ streams: {} })
      return json({
        streams: [
          { url: 'javascript:alert(1)', behaviorHints: { bingeGroup: 'same' } },
          {
            url: 'https://cdn.test/ep2.mkv',
            behaviorHints: { bingeGroup: 'same', videoSize: 5_128_215_802 },
          },
        ],
      })
    }
    const { app, db } = makeApp({ safeFetch, addonFailureLogger: (event) => logs.push(event) })
    installUserAddon(db, 'admin', 'https://meta.test/manifest.json', seriesManifest('Meta', ['meta']), 0)
    const streamId = installUserAddon(db, 'admin', 'https://stream.test/manifest.json', seriesManifest('Stream', ['stream']), 1)
    const token = await adminToken()
    const route = `/next-episode?type=series&metaId=tt1&videoId=tt1%3A1%3A1&addon=${streamId}&bingeGroup=same`

    const matched = (await (await app.request(route, authed(token))).json()) as { stream: { url: string; behaviorHints: { videoSize: number } } }
    expect(matched.stream).toEqual({
      url: 'https://cdn.test/ep2.mkv',
      behaviorHints: { bingeGroup: 'same', videoSize: 5_128_215_802 },
    })

    malformed = true
    const degraded = (await (await app.request(route, authed(token))).json()) as { stream: unknown }
    expect(degraded.stream).toBeNull()
    expect(logs).toEqual([expect.objectContaining({
      route: '/next-episode',
      addonId: streamId,
      code: 'invalid_response',
    })])
  })
})
