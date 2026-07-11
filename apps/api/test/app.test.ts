import { beforeEach, describe, expect, it } from 'vitest'
import { eq } from 'drizzle-orm'
import type { WatchState } from '@halo/core'
import { LoginRateLimiter } from '../src/auth'
import { libraryItems, users } from '../src/schema'
import type { Db } from '../src/db'
import { ADMIN_PASSWORD, authed, login, loginToken, makeApp, mockSafeFetch, seedUser, type App } from './helpers'

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
  beforeEach(() => {
    app = makeApp().app
  })

  it('rejects protected routes without a token', async () => {
    const res = await app.request('/watch-state')
    expect(res.status).toBe(401)
  })

  it('rejects a garbage token', async () => {
    const res = await app.request('/watch-state', authed('not-a-jwt'))
    expect(res.status).toBe(401)
  })

  it('grants access with a valid token', async () => {
    const token = await loginToken(app, 'admin', ADMIN_PASSWORD)
    const res = await app.request('/watch-state', authed(token))
    expect(res.status).toBe(200)
    expect(await res.json()).toEqual([])
  })

  it('rejects a wrong password with a generic error', async () => {
    const res = await login(app, 'admin', 'nope')
    expect(res.status).toBe(401)
    expect(await res.json()).toEqual({ error: 'invalid credentials' })
  })

  it('rejects an unknown username with the same error and status', async () => {
    const res = await login(app, 'ghost', 'whatever')
    expect(res.status).toBe(401)
    expect(await res.json()).toEqual({ error: 'invalid credentials' })
  })

  it('rate-limits after 10 failed attempts, blocking even a correct password', async () => {
    for (let i = 0; i < 10; i++) {
      const res = await login(app, 'admin', 'wrong')
      expect(res.status).toBe(401)
    }
    const blocked = await login(app, 'admin', ADMIN_PASSWORD)
    expect(blocked.status).toBe(429)
  })

  it('does not lock out a different username', async () => {
    const { app, db } = makeApp()
    seedUser(db, 'bob', 'bob-password')
    for (let i = 0; i < 10; i++) await login(app, 'admin', 'wrong')
    const res = await login(app, 'bob', 'bob-password')
    expect(res.status).toBe(200)
  })
})

describe('LoginRateLimiter', () => {
  it('blocks at the threshold and a successful reset clears the window', () => {
    const limiter = new LoginRateLimiter(3, 60_000)
    expect(limiter.isBlocked('u')).toBe(false)
    limiter.recordFailure('u')
    limiter.recordFailure('u')
    expect(limiter.isBlocked('u')).toBe(false)
    limiter.recordFailure('u')
    expect(limiter.isBlocked('u')).toBe(true)
    limiter.reset('u')
    expect(limiter.isBlocked('u')).toBe(false)
  })

  it('keys case-insensitively', () => {
    const limiter = new LoginRateLimiter(1, 60_000)
    limiter.recordFailure('Alice')
    expect(limiter.isBlocked('alice')).toBe(true)
  })
})

describe('watch-state LWW (per user)', () => {
  let app: App
  let token: string
  beforeEach(async () => {
    app = makeApp().app
    token = await loginToken(app, 'admin', ADMIN_PASSWORD)
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
    token = await loginToken(app, 'admin', ADMIN_PASSWORD)
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
    token = await loginToken(app, 'admin', ADMIN_PASSWORD)
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
    const token = await loginToken(app, 'admin', ADMIN_PASSWORD)
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
    const token = await loginToken(app, 'admin', ADMIN_PASSWORD)
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
    const token = await loginToken(app, 'admin', ADMIN_PASSWORD)
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
    const token = await loginToken(app, 'admin', ADMIN_PASSWORD)
    const res = await app.request(
      '/addons',
      authed(token, [
        { transportUrl: CINEMETA_URL, position: 0 },
        { transportUrl: CINEMETA_URL, position: 1 },
      ]),
    )
    expect(res.status).toBe(400)
  })

  it('gates PUT /addons/global to admins and shows globals to every user', async () => {
    const { app, db } = makeApp({ safeFetch: mockSafeFetch({ 'https://cinemeta.test': CINEMETA }) })
    const adminToken = await loginToken(app, 'admin', ADMIN_PASSWORD)
    seedUser(db, 'bob', 'bob-password')
    const bobToken = await loginToken(app, 'bob', 'bob-password')

    const forbidden = await app.request('/addons/global', authed(bobToken, [{ transportUrl: CINEMETA_URL, position: 0 }], 'PUT'))
    expect(forbidden.status).toBe(403)

    const ok = await app.request('/addons/global', authed(adminToken, [{ transportUrl: CINEMETA_URL, position: 0 }], 'PUT'))
    expect(ok.status).toBe(200)

    const body = (await (await app.request('/addons', authed(bobToken))).json()) as { global: Array<{ manifest: { name: string } }> }
    expect(body.global).toHaveLength(1)
    expect(body.global[0]!.manifest.name).toBe('Cinemeta')
  })
})

describe('cross-user isolation', () => {
  let app: App
  let db: Db
  let adminToken: string
  let bobToken: string
  beforeEach(async () => {
    const made = makeApp()
    app = made.app
    db = made.db
    adminToken = await loginToken(app, 'admin', ADMIN_PASSWORD)
    // Exercise the admin create-user route here.
    const created = await app.request('/users', authed(adminToken, { username: 'bob', password: 'bob-password' }, 'POST'))
    expect(created.status).toBe(201)
    bobToken = await loginToken(app, 'bob', 'bob-password')
  })

  it("keeps one user's watch-state, library and settings invisible to another", async () => {
    await app.request('/watch-state', authed(adminToken, [state({ positionSec: 500 })]))
    await app.request('/library', authed(adminToken, [{ id: 'movie:x', type: 'movie', name: 'X', addedAt: 1, updatedAt: 1 }]))
    await app.request('/settings', authed(adminToken, { value: { preferredSubtitleLang: 'eng' }, updatedAt: 1 }))

    expect(await (await app.request('/watch-state', authed(bobToken))).json()).toEqual([])
    expect(await (await app.request('/library', authed(bobToken))).json()).toEqual([])
    expect(await (await app.request('/settings', authed(bobToken))).json()).toEqual({ value: {}, updatedAt: 0 })
  })

  it('keeps LWW independent per user', async () => {
    await app.request('/watch-state', authed(adminToken, [state({ positionSec: 100, updatedAt: 5000 })]))
    // Bob writes an older timestamp for the same videoId — must not be shadowed
    // by admin's newer row; they are separate rows.
    await app.request('/watch-state', authed(bobToken, [state({ positionSec: 999, updatedAt: 1 })]))
    const bobRows = (await (await app.request('/watch-state', authed(bobToken))).json()) as WatchState[]
    expect(bobRows).toHaveLength(1)
    expect(bobRows[0]!.positionSec).toBe(999)
  })
})

describe('user management + cascade', () => {
  it('non-admin is forbidden from user management', async () => {
    const { app, db } = makeApp()
    seedUser(db, 'bob', 'bob-password')
    const bobToken = await loginToken(app, 'bob', 'bob-password')
    expect((await app.request('/users', authed(bobToken))).status).toBe(403)
    expect((await app.request('/users', authed(bobToken, { username: 'x', password: 'password1' }, 'POST'))).status).toBe(403)
  })

  it('rejects deleting your own account', async () => {
    const { app } = makeApp()
    const adminToken = await loginToken(app, 'admin', ADMIN_PASSWORD)
    const res = await app.request('/users/admin', authed(adminToken, undefined, 'DELETE'))
    expect(res.status).toBe(400)
  })

  it('rejects a duplicate username', async () => {
    const { app } = makeApp()
    const adminToken = await loginToken(app, 'admin', ADMIN_PASSWORD)
    await app.request('/users', authed(adminToken, { username: 'bob', password: 'bob-password' }, 'POST'))
    const dupe = await app.request('/users', authed(adminToken, { username: 'BOB', password: 'other-password' }, 'POST'))
    expect(dupe.status).toBe(409)
  })

  it('deleting a user kills their token and cascades their data', async () => {
    const { app, db } = makeApp()
    const adminToken = await loginToken(app, 'admin', ADMIN_PASSWORD)
    await app.request('/users', authed(adminToken, { username: 'bob', password: 'bob-password' }, 'POST'))
    const bobToken = await loginToken(app, 'bob', 'bob-password')
    await app.request('/library', authed(bobToken, [{ id: 'movie:x', type: 'movie', name: 'X', addedAt: 1, updatedAt: 1 }]))

    const bobId = db.select({ id: users.id }).from(users).where(eq(users.username, 'bob')).get()!.id
    expect(db.select().from(libraryItems).where(eq(libraryItems.userId, bobId)).all()).toHaveLength(1)

    const del = await app.request('/users/bob', authed(adminToken, undefined, 'DELETE'))
    expect(del.status).toBe(200)

    // Token now names a user that no longer exists.
    expect((await app.request('/library', authed(bobToken))).status).toBe(401)
    expect(db.select().from(libraryItems).where(eq(libraryItems.userId, bobId)).all()).toHaveLength(0)
  })
})

describe('password change', () => {
  it('changes the password after verifying the current one', async () => {
    const { app } = makeApp()
    const token = await loginToken(app, 'admin', ADMIN_PASSWORD)
    const wrong = await app.request('/auth/password', authed(token, { current: 'nope', next: 'new-password' }, 'POST'))
    expect(wrong.status).toBe(401)
    const ok = await app.request('/auth/password', authed(token, { current: ADMIN_PASSWORD, next: 'new-password' }, 'POST'))
    expect(ok.status).toBe(200)
    expect((await login(app, 'admin', ADMIN_PASSWORD)).status).toBe(401)
    expect((await login(app, 'admin', 'new-password')).status).toBe(200)
  })

  it('rejects a too-short new password', async () => {
    const { app } = makeApp()
    const token = await loginToken(app, 'admin', ADMIN_PASSWORD)
    const res = await app.request('/auth/password', authed(token, { current: ADMIN_PASSWORD, next: 'short' }, 'POST'))
    expect(res.status).toBe(400)
  })
})
