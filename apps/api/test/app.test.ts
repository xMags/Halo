import { beforeEach, describe, expect, it } from 'vitest'
import { eq } from 'drizzle-orm'
import type { WatchState } from '@halo/core'
import { users } from '../src/schema'
import type { Db } from '../src/db'
import { ADMIN_GROUP, adminToken, authed, CLIENT_ID, ISSUER, makeApp, mintToken, mockSafeFetch, userToken, type App } from './helpers'

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
  let app: App
  let db: Db
  beforeEach(() => {
    const made = makeApp()
    app = made.app
    db = made.db
  })

  it('serves the OIDC client config publicly', async () => {
    const res = await app.request('/auth/config')
    expect(res.status).toBe(200)
    expect(await res.json()).toEqual({
      mode: 'oidc',
      issuer: ISSUER,
      clientId: CLIENT_ID,
      scopes: ['openid', 'profile', 'email', 'offline_access', 'groups'],
    })
  })

  it('rejects protected routes without a token', async () => {
    const res = await app.request('/watch-state')
    expect(res.status).toBe(401)
  })

  it('rejects a garbage token', async () => {
    const res = await app.request('/watch-state', authed('not-a-jwt'))
    expect(res.status).toBe(401)
  })

  it('grants access with a valid token and JIT-provisions the user', async () => {
    const res = await app.request('/watch-state', authed(await adminToken()))
    expect(res.status).toBe(200)
    expect(await res.json()).toEqual([])
    const row = db.select().from(users).where(eq(users.id, 'admin-sub')).get()
    expect(row).toMatchObject({ id: 'admin-sub', username: 'admin' })
  })

  it('rejects a token from a different issuer', async () => {
    const token = await mintToken({ issuer: 'https://auth.test/application/o/other/' })
    expect((await app.request('/watch-state', authed(token))).status).toBe(401)
  })

  it('rejects a token minted for a different audience (another ditto app)', async () => {
    const token = await mintToken({ audience: 'some-other-client' })
    expect((await app.request('/watch-state', authed(token))).status).toBe(401)
  })

  it('rejects an expired token', async () => {
    const token = await mintToken({ expiresAt: Math.floor(Date.now() / 1000) - 3600 })
    expect((await app.request('/watch-state', authed(token))).status).toBe(401)
  })

  it('refreshes the stored username after an IdP-side rename', async () => {
    await app.request('/watch-state', authed(await mintToken({ sub: 'admin-sub', username: 'olduser' })))
    await app.request('/watch-state', authed(await mintToken({ sub: 'admin-sub', username: 'newuser' })))
    const row = db.select().from(users).where(eq(users.id, 'admin-sub')).get()
    expect(row?.username).toBe('newuser')
  })

  it('GET /auth/me needs a token', async () => {
    expect((await app.request('/auth/me')).status).toBe(401)
  })

  it('GET /auth/me reports admin status from the group claim (OIDC)', async () => {
    const asAdmin = await app.request('/auth/me', authed(await adminToken()))
    expect(asAdmin.status).toBe(200)
    expect(await asAdmin.json()).toMatchObject({ id: 'admin-sub', username: 'admin', isAdmin: true })

    const asUser = await app.request('/auth/me', authed(await userToken('bob')))
    expect(asUser.status).toBe(200)
    expect(await asUser.json()).toMatchObject({ id: 'bob-sub', username: 'bob', isAdmin: false })
  })
})

describe('watch-state LWW (per user)', () => {
  let app: App
  let token: string
  beforeEach(async () => {
    app = makeApp().app
    token = await adminToken()
  })

  it('applies a newer write over an older one', async () => {
    await app.request('/watch-state', authed(token, [state({ positionSec: 100, updatedAt: 1000 })]))
    const res = await app.request('/watch-state', authed(token, [state({ positionSec: 200, updatedAt: 2000 })]))
    const rows = (await res.json()) as WatchState[]
    expect(rows).toHaveLength(1)
    expect(rows[0]!.positionSec).toBe(200)
  })

  it('ignores a stale write arriving after a newer one', async () => {
    await app.request('/watch-state', authed(token, [state({ positionSec: 200, updatedAt: 2000 })]))
    const res = await app.request('/watch-state', authed(token, [state({ positionSec: 100, updatedAt: 1000 })]))
    const rows = (await res.json()) as WatchState[]
    expect(rows[0]!.positionSec).toBe(200)
    expect(rows[0]!.updatedAt).toBe(2000)
  })

  it('keeps the existing row on an updatedAt tie', async () => {
    await app.request('/watch-state', authed(token, [state({ positionSec: 100, updatedAt: 1000 })]))
    const res = await app.request('/watch-state', authed(token, [state({ positionSec: 999, updatedAt: 1000 })]))
    const rows = (await res.json()) as WatchState[]
    expect(rows[0]!.positionSec).toBe(100)
  })

  it('rejects invalid payloads', async () => {
    const res = await app.request('/watch-state', authed(token, [{ videoId: '' }]))
    expect(res.status).toBe(400)
  })
})

describe('library LWW + tombstones', () => {
  let app: App
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
    app = makeApp().app
    token = await adminToken()
  })

  it('a newer removal beats an older add and survives a stale re-add', async () => {
    await app.request('/library', authed(token, [item({})]))
    await app.request('/library', authed(token, [item({ removedAt: 2000, updatedAt: 2000 })]))
    // Stale device re-sends the pre-removal state.
    const res = await app.request('/library', authed(token, [item({})]))
    const rows = (await res.json()) as Array<{ removedAt?: number }>
    expect(rows).toHaveLength(1)
    expect(rows[0]!.removedAt).toBe(2000)
  })
})

describe('settings LWW', () => {
  let app: App
  let token: string
  beforeEach(async () => {
    app = makeApp().app
    token = await adminToken()
  })

  it('returns empty defaults before any write', async () => {
    const res = await app.request('/settings', authed(token))
    expect(await res.json()).toEqual({ value: {}, updatedAt: 0 })
  })

  it('round-trips and ignores stale writes', async () => {
    await app.request('/settings', authed(token, { value: { preferredSubtitleLang: 'eng' }, updatedAt: 2000 }))
    const res = await app.request('/settings', authed(token, { value: { preferredSubtitleLang: 'ger' }, updatedAt: 1000 }))
    const body = (await res.json()) as { value: { preferredSubtitleLang: string }; updatedAt: number }
    expect(body.value.preferredSubtitleLang).toBe('eng')
    expect(body.updatedAt).toBe(2000)
  })

  it('round-trips validated player preferences', async () => {
    const value = {
      videoFitMode: 'cover',
      subtitleScalePercent: 125,
      subtitleFontFamily: 'Avenir Next',
      playbackRate: 1.5,
    }
    const write = await app.request('/settings', authed(token, { value, updatedAt: 3000 }))
    expect(write.status).toBe(200)

    const res = await app.request('/settings', authed(token))
    expect(await res.json()).toEqual({ value, updatedAt: 3000 })
  })

  it('rejects invalid player preferences', async () => {
    const invalidFit = await app.request(
      '/settings',
      authed(token, { value: { videoFitMode: 'stretch' }, updatedAt: 1000 }),
    )
    const invalidScale = await app.request(
      '/settings',
      authed(token, { value: { subtitleScalePercent: 250 }, updatedAt: 1001 }),
    )
    const invalidFont = await app.request(
      '/settings',
      authed(token, { value: { subtitleFontFamily: '' }, updatedAt: 1002 }),
    )
    const invalidRate = await app.request(
      '/settings',
      authed(token, { value: { playbackRate: 8 }, updatedAt: 1003 }),
    )
    expect(invalidFit.status).toBe(400)
    expect(invalidScale.status).toBe(400)
    expect(invalidFont.status).toBe(400)
    expect(invalidRate.status).toBe(400)
  })

  it('preserves unknown fields from newer clients', async () => {
    await app.request('/settings', authed(token, { value: { futureSetting: 42 }, updatedAt: 1000 }))
    const res = await app.request('/settings', authed(token))
    const body = (await res.json()) as { value: Record<string, unknown> }
    expect(body.value.futureSetting).toBe(42)
  })
})

const CINEMETA = {
  id: 'com.linvo.cinemeta',
  version: '3.0.0',
  name: 'Cinemeta',
  resources: ['catalog', 'meta'],
  types: ['movie', 'series'],
  catalogs: [{ type: 'movie', id: 'top' }],
}

describe('addons: server-fetched manifests', () => {
  const CINEMETA_URL = 'https://cinemeta.test/manifest.json'

  it('fetches and stores the manifest server-side, returned under user', async () => {
    const { app } = makeApp({ safeFetch: mockSafeFetch({ 'https://cinemeta.test': CINEMETA }) })
    const token = await adminToken()
    const put = await app.request('/addons', authed(token, [{ transportUrl: CINEMETA_URL, position: 0 }]))
    expect(put.status).toBe(200)
    const res = await app.request('/addons', authed(token))
    const body = (await res.json()) as { global: unknown[]; user: Array<{ manifest: { name: string } }> }
    expect(body.user).toHaveLength(1)
    expect(body.user[0]!.manifest.name).toBe('Cinemeta')
    expect(body.global).toEqual([])
  })

  it('ignores a client-supplied manifest field, trusting only the fetched one', async () => {
    const { app } = makeApp({ safeFetch: mockSafeFetch({ 'https://cinemeta.test': CINEMETA }) })
    const token = await adminToken()
    // Attempt to inject a forged manifest alongside the ref.
    await app.request(
      '/addons',
      authed(token, [{ transportUrl: CINEMETA_URL, position: 0, manifest: { id: 'evil', name: 'Evil', version: '9', resources: [], types: [], catalogs: [] } }]),
    )
    const body = (await (await app.request('/addons', authed(token))).json()) as { user: Array<{ manifest: { name: string } }> }
    expect(body.user[0]!.manifest.name).toBe('Cinemeta')
  })

  it('fails the whole request and keeps the old list when a manifest is unreachable', async () => {
    const { app } = makeApp({ safeFetch: mockSafeFetch({ 'https://cinemeta.test': CINEMETA }) })
    const token = await adminToken()
    await app.request('/addons', authed(token, [{ transportUrl: CINEMETA_URL, position: 0 }]))
    const res = await app.request(
      '/addons',
      authed(token, [
        { transportUrl: CINEMETA_URL, position: 0 },
        { transportUrl: 'https://down.test/manifest.json', position: 1 },
      ]),
    )
    expect(res.status).toBe(400)
    expect((await res.json()) as { error: string }).toMatchObject({ error: expect.stringContaining('down.test') })
    // Old single-addon list is intact.
    const body = (await (await app.request('/addons', authed(token))).json()) as { user: unknown[] }
    expect(body.user).toHaveLength(1)
  })

  it('rejects a duplicate transportUrl', async () => {
    const { app } = makeApp({ safeFetch: mockSafeFetch({ 'https://cinemeta.test': CINEMETA }) })
    const token = await adminToken()
    const res = await app.request(
      '/addons',
      authed(token, [
        { transportUrl: CINEMETA_URL, position: 0 },
        { transportUrl: CINEMETA_URL, position: 1 },
      ]),
    )
    expect(res.status).toBe(400)
  })

  it('gates PUT /addons/global on the admin group claim and shows globals to every user', async () => {
    const { app } = makeApp({ safeFetch: mockSafeFetch({ 'https://cinemeta.test': CINEMETA }) })
    const bobToken = await userToken('bob')

    const forbidden = await app.request('/addons/global', authed(bobToken, [{ transportUrl: CINEMETA_URL, position: 0 }], 'PUT'))
    expect(forbidden.status).toBe(403)

    const ok = await app.request('/addons/global', authed(await adminToken(), [{ transportUrl: CINEMETA_URL, position: 0 }], 'PUT'))
    expect(ok.status).toBe(200)

    const body = (await (await app.request('/addons', authed(bobToken))).json()) as { global: Array<{ manifest: { name: string } }> }
    expect(body.global).toHaveLength(1)
    expect(body.global[0]!.manifest.name).toBe('Cinemeta')
  })

  it('admin status follows the groups claim, not a stored flag', async () => {
    const { app } = makeApp({ safeFetch: mockSafeFetch({ 'https://cinemeta.test': CINEMETA }) })
    // Same subject: admin while the group is present, demoted once it is gone.
    const withGroup = await mintToken({ sub: 'bob-sub', username: 'bob', groups: [ADMIN_GROUP] })
    const withoutGroup = await mintToken({ sub: 'bob-sub', username: 'bob', groups: [] })

    const ok = await app.request('/addons/global', authed(withGroup, [{ transportUrl: CINEMETA_URL, position: 0 }], 'PUT'))
    expect(ok.status).toBe(200)
    const forbidden = await app.request('/addons/global', authed(withoutGroup, [], 'PUT'))
    expect(forbidden.status).toBe(403)
  })
})

describe('cross-user isolation', () => {
  let app: App
  let adminTok: string
  let bobTok: string
  beforeEach(async () => {
    app = makeApp().app
    adminTok = await adminToken()
    bobTok = await userToken('bob')
  })

  it("keeps one user's watch-state, library and settings invisible to another", async () => {
    await app.request('/watch-state', authed(adminTok, [state({ positionSec: 500 })]))
    await app.request('/library', authed(adminTok, [{ id: 'movie:x', type: 'movie', name: 'X', addedAt: 1, updatedAt: 1 }]))
    await app.request('/settings', authed(adminTok, { value: { preferredSubtitleLang: 'eng' }, updatedAt: 1 }))

    expect(await (await app.request('/watch-state', authed(bobTok))).json()).toEqual([])
    expect(await (await app.request('/library', authed(bobTok))).json()).toEqual([])
    expect(await (await app.request('/settings', authed(bobTok))).json()).toEqual({ value: {}, updatedAt: 0 })
  })

  it('keeps LWW independent per user', async () => {
    await app.request('/watch-state', authed(adminTok, [state({ positionSec: 100, updatedAt: 5000 })]))
    // Bob writes an older timestamp for the same videoId — must not be shadowed
    // by admin's newer row; they are separate rows.
    await app.request('/watch-state', authed(bobTok, [state({ positionSec: 999, updatedAt: 1 })]))
    const bobRows = (await (await app.request('/watch-state', authed(bobTok))).json()) as WatchState[]
    expect(bobRows).toHaveLength(1)
    expect(bobRows[0]!.positionSec).toBe(999)
  })
})
