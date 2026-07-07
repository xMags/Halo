import { beforeEach, describe, expect, it } from 'vitest'
import type { WatchState } from '@halo/core'
import { createApp } from '../src/app'
import { createDb } from '../src/db'

const PASSWORD = 'test-password'
const SECRET = 'test-secret'

function makeApp() {
  return createApp({
    db: createDb(':memory:'),
    adminPassword: PASSWORD,
    jwtSecret: SECRET,
    corsOrigins: ['http://localhost:5173'],
  })
}

async function loginToken(app: ReturnType<typeof makeApp>): Promise<string> {
  const res = await app.request('/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ password: PASSWORD }),
  })
  expect(res.status).toBe(200)
  const { token } = (await res.json()) as { token: string }
  return token
}

function authedJson(token: string, body?: unknown): RequestInit {
  return {
    method: body === undefined ? 'GET' : 'PUT',
    headers: {
      Authorization: `Bearer ${token}`,
      ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  }
}

const state = (over: Partial<WatchState>): WatchState => ({
  videoId: 'tt0944947:1:2',
  itemId: 'series:tt0944947',
  positionSec: 100,
  durationSec: 3600,
  watched: false,
  updatedAt: 1000,
  ...over,
})

describe('auth', () => {
  let app: ReturnType<typeof makeApp>
  beforeEach(() => {
    app = makeApp()
  })

  it('rejects protected routes without a token', async () => {
    const res = await app.request('/watch-state')
    expect(res.status).toBe(401)
  })

  it('rejects a wrong password', async () => {
    const res = await app.request('/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ password: 'nope' }),
    })
    expect(res.status).toBe(401)
  })

  it('rejects a garbage token', async () => {
    const res = await app.request('/watch-state', authedJson('not-a-jwt'))
    expect(res.status).toBe(401)
  })

  it('grants access with a valid token', async () => {
    const token = await loginToken(app)
    const res = await app.request('/watch-state', authedJson(token))
    expect(res.status).toBe(200)
    expect(await res.json()).toEqual([])
  })
})

describe('watch-state LWW', () => {
  let app: ReturnType<typeof makeApp>
  let token: string
  beforeEach(async () => {
    app = makeApp()
    token = await loginToken(app)
  })

  it('applies a newer write over an older one', async () => {
    await app.request('/watch-state', authedJson(token, [state({ positionSec: 100, updatedAt: 1000 })]))
    const res = await app.request('/watch-state', authedJson(token, [state({ positionSec: 200, updatedAt: 2000 })]))
    const rows = (await res.json()) as WatchState[]
    expect(rows).toHaveLength(1)
    expect(rows[0]!.positionSec).toBe(200)
  })

  it('ignores a stale write arriving after a newer one', async () => {
    await app.request('/watch-state', authedJson(token, [state({ positionSec: 200, updatedAt: 2000 })]))
    const res = await app.request('/watch-state', authedJson(token, [state({ positionSec: 100, updatedAt: 1000 })]))
    const rows = (await res.json()) as WatchState[]
    expect(rows[0]!.positionSec).toBe(200)
    expect(rows[0]!.updatedAt).toBe(2000)
  })

  it('keeps the existing row on an updatedAt tie', async () => {
    await app.request('/watch-state', authedJson(token, [state({ positionSec: 100, updatedAt: 1000 })]))
    const res = await app.request('/watch-state', authedJson(token, [state({ positionSec: 999, updatedAt: 1000 })]))
    const rows = (await res.json()) as WatchState[]
    expect(rows[0]!.positionSec).toBe(100)
  })

  it('rejects invalid payloads', async () => {
    const res = await app.request('/watch-state', authedJson(token, [{ videoId: '' }]))
    expect(res.status).toBe(400)
  })
})

describe('library LWW + tombstones', () => {
  let app: ReturnType<typeof makeApp>
  let token: string
  const item = (over: Record<string, unknown>) => ({
    id: 'movie:tt0111161',
    type: 'movie',
    name: 'The Shawshank Redemption',
    addedAt: 1000,
    updatedAt: 1000,
    ...over,
  })

  beforeEach(async () => {
    app = makeApp()
    token = await loginToken(app)
  })

  it('a newer removal beats an older add and survives a stale re-add', async () => {
    await app.request('/library', authedJson(token, [item({})]))
    await app.request('/library', authedJson(token, [item({ removedAt: 2000, updatedAt: 2000 })]))
    // Stale device re-sends the pre-removal state.
    const res = await app.request('/library', authedJson(token, [item({})]))
    const rows = (await res.json()) as Array<{ removedAt?: number }>
    expect(rows).toHaveLength(1)
    expect(rows[0]!.removedAt).toBe(2000)
  })
})

describe('settings LWW', () => {
  let app: ReturnType<typeof makeApp>
  let token: string
  beforeEach(async () => {
    app = makeApp()
    token = await loginToken(app)
  })

  it('returns empty defaults before any write', async () => {
    const res = await app.request('/settings', authedJson(token))
    expect(await res.json()).toEqual({ value: {}, updatedAt: 0 })
  })

  it('round-trips and ignores stale writes', async () => {
    await app.request('/settings', authedJson(token, { value: { preferredSubtitleLang: 'eng' }, updatedAt: 2000 }))
    const res = await app.request('/settings', authedJson(token, { value: { preferredSubtitleLang: 'ger' }, updatedAt: 1000 }))
    const body = (await res.json()) as { value: { preferredSubtitleLang: string }; updatedAt: number }
    expect(body.value.preferredSubtitleLang).toBe('eng')
    expect(body.updatedAt).toBe(2000)
  })

  it('preserves unknown fields from newer clients', async () => {
    await app.request('/settings', authedJson(token, { value: { futureSetting: 42 }, updatedAt: 1000 }))
    const res = await app.request('/settings', authedJson(token))
    const body = (await res.json()) as { value: Record<string, unknown> }
    expect(body.value.futureSetting).toBe(42)
  })
})

describe('addons', () => {
  it('round-trips a collection replace', async () => {
    const app = makeApp()
    const token = await loginToken(app)
    const entry = {
      transportUrl: 'https://v3-cinemeta.strem.io/manifest.json',
      manifest: {
        id: 'com.linvo.cinemeta',
        version: '3.0.0',
        name: 'Cinemeta',
        resources: ['catalog', 'meta'],
        types: ['movie', 'series'],
        catalogs: [{ type: 'movie', id: 'top' }],
      },
      position: 0,
    }
    await app.request('/addons', authedJson(token, [entry]))
    const res = await app.request('/addons', authedJson(token))
    const rows = (await res.json()) as Array<{ transportUrl: string; manifest: { name: string } }>
    expect(rows).toHaveLength(1)
    expect(rows[0]!.manifest.name).toBe('Cinemeta')
  })
})
