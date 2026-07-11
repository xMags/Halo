import { randomUUID } from 'node:crypto'
import { eq } from 'drizzle-orm'
import { expect } from 'vitest'
import type { Manifest } from '@halo/core'
import { createApp } from '../src/app'
import { hashPassword } from '../src/auth'
import { ensureAdminUser } from '../src/bootstrap'
import { createDb, type Db } from '../src/db'
import { userAddons, users } from '../src/schema'

export const ADMIN_PASSWORD = 'admin-password'
export const SECRET = 'test-secret'

export type App = ReturnType<typeof createApp>

/** Fresh in-memory DB with the admin user provisioned and a wired-up app. */
export function makeApp(opts: { safeFetch?: typeof fetch } = {}): { app: App; db: Db } {
  const db = createDb(':memory:')
  ensureAdminUser(db, ADMIN_PASSWORD)
  const app = createApp({ db, jwtSecret: SECRET, corsOrigins: ['http://localhost:5173'], safeFetch: opts.safeFetch })
  return { app, db }
}

function urlOf(input: Parameters<typeof fetch>[0]): string {
  return typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
}

/**
 * A stand-in for the SSRF-guarded manifest fetch: serves `manifests` keyed by
 * transport base, 404s anything else. No network, no DNS.
 */
export function mockSafeFetch(manifests: Record<string, unknown>): typeof fetch {
  return (async (input) => {
    const base = urlOf(input).replace(/\/manifest\.json$/, '')
    const manifest = manifests[base]
    if (!manifest) return new Response('not found', { status: 404 })
    return new Response(JSON.stringify(manifest), {
      status: 200,
      headers: { 'content-type': 'application/json' },
    })
  }) as typeof fetch
}

/**
 * Serves Stremio addon resources (catalog/meta/stream/subtitles) for the given
 * transport bases. `routes[base]` maps a resource path (e.g. `stream/movie/tt1`)
 * to its JSON body, or throws for a base to simulate a failing addon.
 */
export function mockResolveFetch(routes: Record<string, Record<string, unknown> | 'fail'>): typeof fetch {
  return (async (input) => {
    const url = urlOf(input)
    for (const [base, table] of Object.entries(routes)) {
      if (!url.startsWith(`${base}/`)) continue
      if (table === 'fail') throw new Error(`addon ${base} is down`)
      // `${base}/${resource}/${type}/${id}.json` or `.../${extra}.json`
      const path = url.slice(base.length + 1).replace(/\.json$/, '')
      const body = table[path]
      if (body === undefined) return new Response('not found', { status: 404 })
      return new Response(JSON.stringify(body), { status: 200, headers: { 'content-type': 'application/json' } })
    }
    return new Response('not found', { status: 404 })
  }) as typeof fetch
}

/** Inserts a user directly (fast path for tests that don't exercise POST /users). */
export function seedUser(db: Db, username: string, password: string, isAdmin = false): void {
  db.insert(users)
    .values({
      id: randomUUID(),
      username: username.toLowerCase(),
      passwordHash: hashPassword(password),
      isAdmin,
      createdAt: Date.now(),
    })
    .run()
}

/** Installs an addon for a user directly, skipping the server manifest fetch. */
export function installUserAddon(db: Db, username: string, transportUrl: string, manifest: unknown, position = 0): void {
  const row = db.select({ id: users.id }).from(users).where(eq(users.username, username.toLowerCase())).get()!
  db.insert(userAddons)
    .values({ userId: row.id, transportUrl, manifest: manifest as Manifest, position, addedAt: Date.now() })
    .run()
}

export async function login(app: App, username: string, password: string): Promise<Response> {
  return app.request('/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  })
}

export async function loginToken(app: App, username: string, password: string): Promise<string> {
  const res = await login(app, username, password)
  expect(res.status).toBe(200)
  const { token } = (await res.json()) as { token: string }
  return token
}

export function authed(token: string, body?: unknown, method: 'GET' | 'PUT' | 'POST' | 'DELETE' = body === undefined ? 'GET' : 'PUT'): RequestInit {
  return {
    method,
    headers: {
      Authorization: `Bearer ${token}`,
      ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  }
}
