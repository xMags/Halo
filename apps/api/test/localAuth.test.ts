import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { eq } from 'drizzle-orm'
import { LoginRateLimiter } from '../src/auth'
import { ensureAdminUser } from '../src/bootstrap'
import { libraryItems, users } from '../src/schema'
import { createDb, type Db } from '../src/db'
import { ADMIN_PASSWORD, authed, login, loginToken, makeApp, makeLocalApp, seedUser, type App } from './helpers'

describe('auth config discovery', () => {
  it('local mode announces itself without OIDC details', async () => {
    const { app } = makeLocalApp()
    const res = await app.request('/auth/config')
    expect(res.status).toBe(200)
    expect(await res.json()).toEqual({ mode: 'local' })
  })

  it('an OIDC deployment exposes no local auth routes', async () => {
    const { app } = makeApp()
    const res = await login(app, 'admin', ADMIN_PASSWORD)
    // The route isn't mounted; the path falls through to the authed router's
    // middleware and dies there. Either way: no password surface.
    expect(res.status).toBe(401)
  })
})

describe('local login', () => {
  let app: App
  let db: Db
  beforeEach(() => {
    const made = makeLocalApp()
    app = made.app
    db = made.db
  })

  it('rejects protected routes without a token', async () => {
    const res = await app.request('/watch-state')
    expect(res.status).toBe(401)
  })

  it('rejects a garbage token', async () => {
    const res = await app.request('/watch-state', authed('not-a-jwt'))
    expect(res.status).toBe(401)
  })

  it('returns a token with its expiry and grants access', async () => {
    const res = await login(app, 'admin', ADMIN_PASSWORD)
    expect(res.status).toBe(200)
    const body = (await res.json()) as { token: string; expiresAt: number }
    expect(body.token).toBeTruthy()
    expect(body.expiresAt).toBeGreaterThan(Date.now())

    const protectedRes = await app.request('/watch-state', authed(body.token))
    expect(protectedRes.status).toBe(200)
    expect(await protectedRes.json()).toEqual([])
  })

  it('GET /auth/me reports the local admin flag', async () => {
    const token = await loginToken(app, 'admin', ADMIN_PASSWORD)
    const res = await app.request('/auth/me', authed(token))
    expect(res.status).toBe(200)
    expect(await res.json()).toMatchObject({ username: 'admin', isAdmin: true })
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

  it('ignores credential-less (OIDC leftover) rows sharing the username', async () => {
    // A row provisioned by a former OIDC deployment: same username, no password.
    db.insert(users).values({ id: 'idp-sub-123', username: 'bob', createdAt: Date.now() }).run()
    seedUser(db, 'bob', 'bob-password')
    const res = await login(app, 'bob', 'bob-password')
    expect(res.status).toBe(200)
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
    seedUser(db, 'bob', 'bob-password')
    for (let i = 0; i < 10; i++) await login(app, 'admin', 'wrong')
    const res = await login(app, 'bob', 'bob-password')
    expect(res.status).toBe(200)
  })
})

describe('sliding refresh', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  it('trades a valid token for a fresh one that outlives it', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-01-01T00:00:00Z'))
    const { app } = makeLocalApp()
    const token = await loginToken(app, 'admin', ADMIN_PASSWORD)

    vi.setSystemTime(new Date('2026-01-20T00:00:00Z'))
    const res = await app.request('/auth/refresh', authed(token, undefined, 'POST'))
    expect(res.status).toBe(200)
    const body = (await res.json()) as { token: string; expiresAt: number }
    // Fresh 30-day window from now, not from the original login.
    expect(body.expiresAt).toBe(new Date('2026-02-19T00:00:00Z').getTime())

    const protectedRes = await app.request('/watch-state', authed(body.token))
    expect(protectedRes.status).toBe(200)
  })

  it('refuses to refresh an expired token', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-01-01T00:00:00Z'))
    const { app } = makeLocalApp()
    const token = await loginToken(app, 'admin', ADMIN_PASSWORD)

    vi.setSystemTime(new Date('2026-02-15T00:00:00Z'))
    const res = await app.request('/auth/refresh', authed(token, undefined, 'POST'))
    expect(res.status).toBe(401)
  })

  it('enforces the 90-day absolute cap across refresh chains', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-01-01T00:00:00Z'))
    const { app } = makeLocalApp()
    let token = await loginToken(app, 'admin', ADMIN_PASSWORD)

    // Refresh every 25 days: each token is still valid, but auth_time pins the
    // original login, so the chain dies past day 90.
    let now = new Date('2026-01-01T00:00:00Z').getTime()
    for (let i = 0; i < 3; i++) {
      now += 25 * 86400_000
      vi.setSystemTime(now)
      const res = await app.request('/auth/refresh', authed(token, undefined, 'POST'))
      expect(res.status).toBe(200)
      token = ((await res.json()) as { token: string }).token
    }

    now += 25 * 86400_000 // day 100
    vi.setSystemTime(now)
    const res = await app.request('/auth/refresh', authed(token, undefined, 'POST'))
    expect(res.status).toBe(401)
    expect(await res.json()).toEqual({ error: 'session expired' })
  })
})

describe('password change', () => {
  let app: App
  let token: string
  beforeEach(async () => {
    app = makeLocalApp().app
    token = await loginToken(app, 'admin', ADMIN_PASSWORD)
  })

  it('changes the password after verifying the current one', async () => {
    const res = await app.request(
      '/auth/password',
      authed(token, { current: ADMIN_PASSWORD, next: 'a-new-password' }, 'POST'),
    )
    expect(res.status).toBe(200)
    expect((await login(app, 'admin', ADMIN_PASSWORD)).status).toBe(401)
    expect((await login(app, 'admin', 'a-new-password')).status).toBe(200)
  })

  it('rejects a wrong current password', async () => {
    const res = await app.request('/auth/password', authed(token, { current: 'wrong', next: 'a-new-password' }, 'POST'))
    expect(res.status).toBe(401)
  })

  it('rejects a too-short new password', async () => {
    const res = await app.request('/auth/password', authed(token, { current: ADMIN_PASSWORD, next: 'short' }, 'POST'))
    expect(res.status).toBe(400)
  })
})

describe('user management + cascade', () => {
  let app: App
  let db: Db
  let adminTok: string
  beforeEach(async () => {
    const made = makeLocalApp()
    app = made.app
    db = made.db
    adminTok = await loginToken(app, 'admin', ADMIN_PASSWORD)
  })

  it('non-admin is forbidden from user management', async () => {
    seedUser(db, 'bob', 'bob-password')
    const bobTok = await loginToken(app, 'bob', 'bob-password')
    expect((await app.request('/users', authed(bobTok))).status).toBe(403)
    expect((await app.request('/users', authed(bobTok, { username: 'eve', password: 'password123' }, 'POST'))).status).toBe(403)
  })

  it('creates users via the admin route and lists local accounts only', async () => {
    // OIDC leftover row must not appear in the local account listing.
    db.insert(users).values({ id: 'idp-sub-999', username: 'ghost', createdAt: Date.now() }).run()
    const created = await app.request('/users', authed(adminTok, { username: 'Bob', password: 'bob-password' }, 'POST'))
    expect(created.status).toBe(201)
    const res = await app.request('/users', authed(adminTok))
    const list = (await res.json()) as Array<{ username: string }>
    expect(list.map((u) => u.username).sort()).toEqual(['admin', 'bob'])
  })

  it('rejects a duplicate username', async () => {
    await app.request('/users', authed(adminTok, { username: 'bob', password: 'bob-password' }, 'POST'))
    const res = await app.request('/users', authed(adminTok, { username: 'bob', password: 'other-password' }, 'POST'))
    expect(res.status).toBe(409)
  })

  it('rejects deleting your own account', async () => {
    const res = await app.request('/users/admin', authed(adminTok, undefined, 'DELETE'))
    expect(res.status).toBe(400)
  })

  it('deleting a user kills their token and cascades their data', async () => {
    seedUser(db, 'bob', 'bob-password')
    const bobTok = await loginToken(app, 'bob', 'bob-password')
    await app.request(
      '/library',
      authed(bobTok, [{ id: 'movie:tt1', type: 'movie', name: 'M', addedAt: 1, updatedAt: 1 }]),
    )

    const res = await app.request('/users/bob', authed(adminTok, undefined, 'DELETE'))
    expect(res.status).toBe(200)
    expect((await app.request('/watch-state', authed(bobTok))).status).toBe(401)
    expect(db.select().from(libraryItems).where(eq(libraryItems.userId, 'bob-local-id')).all()).toEqual([])
  })
})

describe('admin bootstrap', () => {
  it('seeds the admin even when OIDC-provisioned rows exist (mode switch)', () => {
    const db = createDb(':memory:')
    db.insert(users).values({ id: 'idp-sub-1', username: 'olduser', createdAt: Date.now() }).run()
    ensureAdminUser(db, 'first-admin-password')
    const admin = db.select().from(users).where(eq(users.username, 'admin')).get()
    expect(admin?.passwordHash).toBeTruthy()
    expect(admin?.isAdmin).toBe(true)
  })

  it('does not reseed when a local account already exists', () => {
    const db = createDb(':memory:')
    ensureAdminUser(db, 'first-admin-password')
    ensureAdminUser(db, 'different-password')
    const rows = db.select().from(users).all()
    expect(rows).toHaveLength(1)
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
