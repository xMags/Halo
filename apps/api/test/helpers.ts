import { randomUUID } from 'node:crypto'
import { expect } from 'vitest'
import { createApp } from '../src/app'
import { hashPassword } from '../src/auth'
import { ensureAdminUser } from '../src/bootstrap'
import { createDb, type Db } from '../src/db'
import { users } from '../src/schema'

export const ADMIN_PASSWORD = 'admin-password'
export const SECRET = 'test-secret'

export type App = ReturnType<typeof createApp>

/** Fresh in-memory DB with the admin user provisioned and a wired-up app. */
export function makeApp(opts: { safeFetch?: (url: string) => Promise<Response> } = {}): { app: App; db: Db } {
  const db = createDb(':memory:')
  ensureAdminUser(db, ADMIN_PASSWORD)
  const app = createApp({ db, jwtSecret: SECRET, corsOrigins: ['http://localhost:5173'], safeFetch: opts.safeFetch })
  return { app, db }
}

/**
 * A stand-in for the SSRF-guarded manifest fetch: serves `manifests` keyed by
 * transport base, 404s anything else. No network, no DNS.
 */
export function mockSafeFetch(manifests: Record<string, unknown>): (url: string) => Promise<Response> {
  return async (url: string) => {
    const base = url.replace(/\/manifest\.json$/, '')
    const manifest = manifests[base]
    if (!manifest) return new Response('not found', { status: 404 })
    return new Response(JSON.stringify(manifest), {
      status: 200,
      headers: { 'content-type': 'application/json' },
    })
  }
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
