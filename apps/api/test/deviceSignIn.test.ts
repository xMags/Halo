import { beforeEach, describe, expect, it } from 'vitest'
import { DeviceSignInStore, normalizeUserCode } from '../src/deviceSignIn'
import { ADMIN_PASSWORD, authed, makeApp, makeLocalApp, seedUser, type App } from './helpers'
import type { Db } from '../src/db'
import { users } from '../src/schema'
import { eq } from 'drizzle-orm'

type Started = {
  device_code: string
  user_code: string
  verification_uri: string
  verification_uri_complete: string
  expires_in: number
  interval: number
}

const json = (body: unknown) => ({
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(body),
})

const form = (fields: Record<string, string>) => ({
  method: 'POST',
  headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
  body: new URLSearchParams(fields).toString(),
})

async function start(app: App): Promise<Started> {
  const res = await app.request('http://halo.test/auth/device/start', { method: 'POST' })
  expect(res.status).toBe(200)
  return (await res.json()) as Started
}

const poll = (app: App, deviceCode: string) => app.request('/auth/device/token', json({ device_code: deviceCode }))

const decide = (app: App, code: string, decision: 'approve' | 'deny', password = ADMIN_PASSWORD, username = 'admin') =>
  app.request('/auth/device/approve', form({ code, username, password, decision }))

describe('device sign-in (local mode)', () => {
  let app: App
  let db: Db
  beforeEach(() => {
    const made = makeLocalApp()
    app = made.app
    db = made.db
  })

  it('hands out a code to show and the page to approve it at', async () => {
    const started = await start(app)
    expect(started.device_code.length).toBeGreaterThanOrEqual(43)
    expect(started.user_code).toMatch(/^[BCDFGHJKLMNPQRSTVWXZ]{4}-[BCDFGHJKLMNPQRSTVWXZ]{4}$/)
    expect(started.verification_uri).toBe('http://halo.test/auth/device')
    expect(started.verification_uri_complete).toBe(`http://halo.test/auth/device?code=${started.user_code.replace('-', '')}`)
    expect(started.expires_in).toBe(600)
    expect(started.interval).toBe(5)
  })

  it('signs the TV in once the phone approves, exactly once', async () => {
    const started = await start(app)
    const waiting = await poll(app, started.device_code)
    expect(waiting.status).toBe(400)
    expect(await waiting.json()).toEqual({ error: 'authorization_pending' })

    const approved = await decide(app, started.user_code.toLowerCase(), 'approve')
    expect(approved.status).toBe(200)
    expect(await approved.text()).toContain('Your TV is signed in')

    // Two polls together: one session, one refusal.
    const [a, b] = await Promise.all([poll(app, started.device_code), poll(app, started.device_code)])
    const statuses = [a.status, b.status].sort()
    expect(statuses).toEqual([200, 400])
    const winner = a.status === 200 ? a : b
    const session = (await winner.json()) as { token: string; expiresAt: number }
    expect(session.expiresAt).toBeGreaterThan(Date.now())
    const me = await app.request('/auth/me', authed(session.token))
    expect(me.status).toBe(200)
    expect(await (a.status === 200 ? b : a).json()).toEqual({ error: 'invalid_grant' })
  })

  it('a denial reaches the TV', async () => {
    const started = await start(app)
    expect((await decide(app, started.user_code, 'deny')).status).toBe(200)
    const res = await poll(app, started.device_code)
    expect(await res.json()).toEqual({ error: 'access_denied' })
  })

  it('a wrong password approves nothing', async () => {
    const started = await start(app)
    const res = await decide(app, started.user_code, 'approve', 'wrong-password')
    expect(res.status).toBe(401)
    expect(await res.text()).toContain('Wrong username or password')
    expect(await (await poll(app, started.device_code)).json()).toEqual({ error: 'authorization_pending' })
  })

  it('opening the page with a code fills it in and decides nothing', async () => {
    const started = await start(app)
    const page = await app.request(`/auth/device?code=${started.user_code.replace('-', '')}`)
    expect(page.status).toBe(200)
    expect(await page.text()).toContain(`value="${started.user_code}"`)
    expect(page.headers.get('Cache-Control')).toBe('no-store')
    expect(page.headers.get('Content-Security-Policy')).toContain("default-src 'none'")
    expect(await (await poll(app, started.device_code)).json()).toEqual({ error: 'authorization_pending' })
  })

  it('never echoes what it was given into the page', async () => {
    const page = await app.request('/auth/device?code=%22%3E%3Cscript%3Ealert(1)%3C/script%3E')
    const html = await page.text()
    expect(html).not.toContain('<script')
    expect(html).toContain('value=""')
  })

  it('an unknown or used code says so', async () => {
    const res = await decide(app, 'BCDFGHJK', 'approve')
    expect(res.status).toBe(404)
    expect(await res.text()).toContain('expired or was already used')
    expect(await (await poll(app, 'not-a-real-code')).json()).toEqual({ error: 'invalid_grant' })
  })

  it('a code approved by an account deleted since gives no session', async () => {
    seedUser(db, 'viewer', 'viewer-password-1')
    const started = await start(app)
    expect((await decide(app, started.user_code, 'approve', 'viewer-password-1', 'viewer')).status).toBe(200)
    db.delete(users).where(eq(users.username, 'viewer')).run()
    expect(await (await poll(app, started.device_code)).json()).toEqual({ error: 'access_denied' })
  })

  it('bodies are checked and every answer is no-store', async () => {
    const bad = await app.request('/auth/device/token', json({ nope: 1 }))
    expect(bad.status).toBe(400)
    expect(await bad.json()).toEqual({ error: 'invalid_request' })
    expect(bad.headers.get('Cache-Control')).toBe('no-store')
    const missing = await app.request('/auth/device/approve', form({ code: 'BCDFGHJK' }))
    expect(missing.status).toBe(400)
  })

  it('starting is limited per address', async () => {
    for (let i = 0; i < 10; i++) await start(app)
    const res = await app.request('/auth/device/start', { method: 'POST' })
    expect(res.status).toBe(429)
    expect(await res.json()).toEqual({ error: 'rate_limited' })
  })

  it('an OIDC deployment has no device sign-in', async () => {
    const { app: oidc } = makeApp()
    const res = await oidc.request('/auth/device/start', { method: 'POST' })
    expect(res.status).not.toBe(200)
    const config = await oidc.request('/auth/config')
    expect(((await config.json()) as { deviceSignIn?: boolean }).deviceSignIn).toBeUndefined()
  })
})

describe('device sign-in store', () => {
  it('asks a TV that polls early to slow down', () => {
    let now = 0
    const store = new DeviceSignInStore(10, () => now)
    const { deviceCode } = store.start()!
    expect(store.poll(deviceCode).status).toBe('pending')
    now += 2_000
    expect(store.poll(deviceCode).status).toBe('slow_down')
    // The interval is now ten seconds, and fifteen after another early poll.
    now += 6_000
    expect(store.poll(deviceCode).status).toBe('slow_down')
    now += 15_000
    expect(store.poll(deviceCode).status).toBe('pending')
  })

  it('codes run out after ten minutes', () => {
    let now = 0
    const store = new DeviceSignInStore(10, () => now)
    const { deviceCode, userCode } = store.start()!
    now += 10 * 60_000
    expect(store.isPending(userCode)).toBe(false)
    expect(store.decide(userCode, 'someone')).toBe(false)
    expect(store.poll(deviceCode).status).toBe('expired')
  })

  it('keeps no more than its cap waiting, and sweeps expired ones to make room', () => {
    let now = 0
    const store = new DeviceSignInStore(2, () => now)
    expect(store.start()).not.toBeNull()
    expect(store.start()).not.toBeNull()
    expect(store.start()).toBeNull()
    now += 10 * 60_000
    expect(store.start()).not.toBeNull()
    expect(store.size).toBe(1)
  })

  it('reads codes the way people type them', () => {
    expect(normalizeUserCode('bcdf-ghjk')).toBe('BCDFGHJK')
    expect(normalizeUserCode(' BCDF GHJK ')).toBe('BCDFGHJK')
    expect(normalizeUserCode('BCDF-GHJA')).toBeNull()
    expect(normalizeUserCode('BCDF')).toBeNull()
  })
})
